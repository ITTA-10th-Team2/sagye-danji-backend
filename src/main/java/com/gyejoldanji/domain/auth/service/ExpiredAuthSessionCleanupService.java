package com.gyejoldanji.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.global.config.properties.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 이력 정리: 만료 뒤 보관 기간이 지난 세션({@code expires_at < cutoff})과 그 Refresh 이력 전부를 배치로 삭제한다.
 *
 * <p>cutoff는 실행 시작 때 한 번 정한다(UTC 마이크로초 현재 시각 - 보관 기간). 배치마다 별도 트랜잭션에서 후보 ID를 잠금 없이 PK 순으로
 * 최대 {@value #BATCH_SIZE}개 고르고 → 그 순서대로 PK 단건 행 잠금 → 다시 확인한 세션들의 Refresh 이력(세션별) → 세션(PK별) 순으로 지운 뒤
 * commit한다. 범위·IN 목록 문장을 쓰지 않아 실행 계획과 관계없이 고른 행만 잠그며, 후보가 없으면 끝낸다.
 *
 * <p>배치 트랜잭션은 READ COMMITTED다. REPEATABLE READ에서는 PK 단건 조회·세션별 이력 삭제·FK 검사가 페이지 경계·이력 인덱스에 gap
 * 잠금을 남겨, 대상 바로 앞 세션의 새 Refresh 이력 INSERT를 배치 commit까지 막을 수 있다(실제 MySQL로 확인). 공유 TransactionTemplate
 * 설정은 바꾸지 않고 이 서비스 전용 템플릿에만 적용한다. 회원은 잠그지도 지우지도 않는다(기록·사진의 삭제 cascade를 타지 않는다).
 *
 * <p>배치가 실패하면 그 배치만 rollback되고 앞 배치 commit은 남는다. 예외를 그대로 던져 이번 실행을 실패로 끝내며 다음 배치·재시도는 하지
 * 않는다. 트랜잭션 밖(스케줄러)에서 호출한다.
 */
@Slf4j
@Service
public class ExpiredAuthSessionCleanupService {

    /** 한 트랜잭션에서 잠그고 지우는 최대 세션 수. */
    static final int BATCH_SIZE = 200;

    private final TransactionTemplate transactionTemplate;
    private final AuthSessionRepository sessionRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final AuthProperties authProperties;
    private final Clock clock;

    public ExpiredAuthSessionCleanupService(PlatformTransactionManager transactionManager,
                                            AuthSessionRepository sessionRepository,
                                            AuthRefreshTokenRepository refreshTokenRepository,
                                            AuthProperties authProperties, Clock clock) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.sessionRepository = sessionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** 대상이 없어질 때까지 배치별로 commit하며 정리한다. 실패하면 예외를 던지고 그때까지 commit한 배치는 남는다. */
    public void cleanUp() {
        // DB TIMESTAMP(6)에 맞춰 마이크로초로 자른 시각. 이번 실행의 모든 배치가 같은 cutoff를 쓴다.
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS)
                .minus(Duration.ofDays(authProperties.getExpiredSessionRetentionDays()));
        int sessions = 0;
        int refreshTokens = 0;
        while (true) {
            Batch batch = transactionTemplate.execute(status -> deleteBatch(cutoff));
            if (batch.candidates() == 0) {
                break;
            }
            sessions += batch.sessions();
            refreshTokens += batch.refreshTokens();
        }
        log.info("만료 인증 세션 정리 완료: sessions={}, refreshTokens={}", sessions, refreshTokens);
    }

    /** 트랜잭션 안. 후보를 PK 순으로 하나씩 잠그고 다시 확인한 세션만 그 Refresh 이력 → 세션 순으로 지운다. */
    private Batch deleteBatch(Instant cutoff) {
        List<Long> candidates = sessionRepository.findExpiredIds(cutoff, BATCH_SIZE);
        LocalDateTime cutoffUtc = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        List<Long> ids = new ArrayList<>();
        for (Long id : candidates) {
            // PK 단건 잠금이라 그 행만 잠긴다. 그사이 다른 정리 실행이 지운 행은 건너뛴다.
            sessionRepository.findByIdForUpdate(id).ifPresent(session -> {
                if (!session.getExpiresAt().isBefore(cutoffUtc)) {
                    // 만료 시각은 바뀌지 않는 값이다. 후보 조건과 어긋나면 다음 배치도 같은 후보를 고르므로 반복하지 않고 실패로 끝낸다.
                    throw new IllegalStateException("잠근 세션이 만료 이력 정리 조건과 다릅니다.");
                }
                ids.add(id);
            });
        }
        if (ids.isEmpty()) {
            // 후보가 잠그기 전에 모두 사라졌어도 끝내지 않는다. 다음 배치가 새 트랜잭션에서 후보를 다시 고른다.
            return new Batch(candidates.size(), 0, 0);
        }
        // FK RESTRICT: 자식(Refresh 이력)을 먼저 지운다. 이력이 없는 세션도 그대로 세션 삭제로 넘어간다.
        int refreshTokens = 0;
        for (Long id : ids) {
            refreshTokens += refreshTokenRepository.deleteBySessionId(id);
        }
        int sessions = 0;
        for (Long id : ids) {
            sessions += sessionRepository.deleteRowById(id);
        }
        if (sessions != ids.size()) {
            // 잠근 행이라 일어나면 안 된다. 같은 대상을 끝없이 다시 고르지 않도록 이 배치를 rollback하고 실행을 끝낸다.
            throw new IllegalStateException("잠근 만료 세션 수와 삭제한 세션 수가 다릅니다.");
        }
        return new Batch(candidates.size(), sessions, refreshTokens);
    }

    /** 한 배치의 후보 수와 지운 세션 수(= 잠그고 확인한 세션 수)·Refresh 이력 수. */
    private record Batch(int candidates, int sessions, int refreshTokens) {
    }
}
