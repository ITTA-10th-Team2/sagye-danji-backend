package com.gyejoldanji.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.global.config.properties.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 만료 이력 정리의 cutoff 계산·후보 조회 → PK 단건 잠금 → 재확인 → 삭제 순서·배치 반복·배치마다의 commit/rollback을 DB 없이 확인한다.
 *
 * <p>Repository·트랜잭션 관리자·Clock을 대체하고 서비스가 만드는 실제 TransactionTemplate(READ COMMITTED)을 쓴다. 실제 MySQL의 시각
 * 경계·실제 잠금 범위·배치 commit·rollback·경합은 {@code ExpiredSessionCleanupMySqlIntegrationTest}에서 검증한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpiredAuthSessionCleanupServiceTest {

    /** 실행 시작 시각(나노초). 마이크로초로 잘라 쓴다. */
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00.123456789Z");
    private static final Instant CUTOFF_7_DAYS = Instant.parse("2026-09-27T03:00:00.123456Z");
    private static final LocalDateTime CUTOFF_UTC = LocalDateTime.parse("2026-09-27T03:00:00.123456");
    /** DB 예외 메시지에 섞일 수 있는 값. 서비스는 메시지를 로그로 남기지 않는다. */
    private static final String SECRET = "Duplicate entry 'SECRET-VALUE'";

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private AuthRefreshTokenRepository refreshTokenRepository;
    @Mock
    private Clock clock;

    private final AuthProperties properties = new AuthProperties();
    private ExpiredAuthSessionCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ExpiredAuthSessionCleanupService(transactionManager, sessionRepository, refreshTokenRepository,
                properties, clock);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60), NOW.plusSeconds(120));
        when(sessionRepository.findExpiredIds(any(), anyInt())).thenReturn(List.of());
        when(sessionRepository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(session(CUTOFF_UTC.minusNanos(1_000))));
        when(refreshTokenRepository.deleteBySessionId(anyLong())).thenReturn(0);
        when(sessionRepository.deleteRowById(anyLong())).thenReturn(1);
    }

    /** 후보가 없으면 후보 조회 한 번(한 트랜잭션 commit)으로 끝나고 아무것도 잠그거나 지우지 않는다. */
    @Test
    void endsAfterOneEmptyBatch(CapturedOutput output) {
        service.cleanUp();

        verify(sessionRepository, times(1)).findExpiredIds(CUTOFF_7_DAYS, 200);
        verify(sessionRepository, never()).findByIdForUpdate(anyLong());
        verify(refreshTokenRepository, never()).deleteBySessionId(anyLong());
        verify(sessionRepository, never()).deleteRowById(anyLong());
        verify(transactionManager, times(1)).commit(any());
        verify(transactionManager, never()).rollback(any());
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=0, refreshTokens=0");
    }

    /**
     * cutoff = 실행 시작의 Clock 값을 UTC 마이크로초로 자른 뒤 보관 기간(일)을 뺀 값이다. Clock은 한 번만 읽고 이번 실행의 모든 배치가 같은
     * cutoff를 쓴다(배치 사이에 시간이 지나도 대상이 늘지 않는다).
     */
    @ParameterizedTest
    @CsvSource({
            "7, 2026-09-27T03:00:00.123456Z",
            "1, 2026-10-03T03:00:00.123456Z",
            "30, 2026-09-04T03:00:00.123456Z"})
    void usesOneMicrosecondCutoffPerRun(int retentionDays, Instant cutoff) {
        properties.setExpiredSessionRetentionDays(retentionDays);
        when(sessionRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(
                session(LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC).minusNanos(1_000))));
        batches(ids(1, 200), ids(201, 1), List.of());

        service.cleanUp();

        verify(clock, times(1)).instant();
        verify(sessionRepository, times(3)).findExpiredIds(cutoff, 200);
    }

    /**
     * T28: 배치마다 트랜잭션 시작 → 잠금 없는 후보 조회(최대 200) → 후보를 PK 순서대로 하나씩 잠금 → 그 세션들의 Refresh 이력(세션별) →
     * 세션(PK별) 삭제 → commit이고, 후보가 없을 때까지 반복한다(200·200·1·0개). 삭제 범위는 그 배치에서 잠근 ID뿐이다. 완료 로그는 commit한
     * 건수다.
     */
    @Test
    void locksCandidatesInOrderThenDeletesAndCommitsEachBatch(CapturedOutput output) {
        List<Long> first = ids(1, 200);
        List<Long> second = ids(201, 200);
        List<Long> third = ids(401, 1);
        batches(first, second, third, List.of());
        when(refreshTokenRepository.deleteBySessionId(anyLong()))
                .thenAnswer(invocation -> invocation.<Long>getArgument(0) <= 175 || invocation.<Long>getArgument(0) == 401 ? 2 : 0);

        service.cleanUp();

        InOrder order = inOrder(transactionManager, sessionRepository, refreshTokenRepository);
        for (List<Long> batch : List.of(first, second, third)) {
            order.verify(transactionManager).getTransaction(any());
            order.verify(sessionRepository).findExpiredIds(CUTOFF_7_DAYS, 200);
            for (Long id : batch) {
                order.verify(sessionRepository).findByIdForUpdate(id);
            }
            for (Long id : batch) {
                order.verify(refreshTokenRepository).deleteBySessionId(id);
            }
            for (Long id : batch) {
                order.verify(sessionRepository).deleteRowById(id);
            }
            order.verify(transactionManager).commit(any());
        }
        order.verify(transactionManager).getTransaction(any());
        order.verify(sessionRepository).findExpiredIds(CUTOFF_7_DAYS, 200);
        order.verify(transactionManager).commit(any());
        order.verifyNoMoreInteractions();
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, times(4)).getTransaction(argThat(definition ->
                definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=401, refreshTokens=352");
    }

    /** 후보 조회 뒤 잠그기 전에 사라진 세션(다른 정리 실행이 삭제)은 건너뛰고, 잠근 세션만 지운다. */
    @Test
    void skipsCandidatesDeletedBeforeLock() {
        batches(List.of(1L, 2L, 3L), List.of());
        when(sessionRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        service.cleanUp();

        verify(refreshTokenRepository).deleteBySessionId(1L);
        verify(refreshTokenRepository).deleteBySessionId(3L);
        verify(refreshTokenRepository, never()).deleteBySessionId(2L);
        verify(sessionRepository).deleteRowById(1L);
        verify(sessionRepository).deleteRowById(3L);
        verify(sessionRepository, never()).deleteRowById(2L);
    }

    /**
     * 후보가 잠그기 전에 모두 사라져 그 배치가 아무것도 지우지 않아도 실행을 끝내지 않는다. 다음 배치가 새 트랜잭션에서 후보를 다시 골라
     * 남은 대상을 지우고, 후보 조회가 비어야 끝난다.
     */
    @Test
    void continuesWhenAllCandidatesVanishedBeforeLock(CapturedOutput output) {
        List<Long> vanished = ids(1, 200);
        batches(vanished, ids(201, 3), List.of());
        vanished.forEach(id -> when(sessionRepository.findByIdForUpdate(id)).thenReturn(Optional.empty()));

        service.cleanUp();

        verify(sessionRepository, times(3)).findExpiredIds(CUTOFF_7_DAYS, 200);
        verify(transactionManager, times(3)).getTransaction(any());
        verify(sessionRepository, times(3)).deleteRowById(anyLong());
        ids(201, 3).forEach(id -> verify(sessionRepository).deleteRowById(id));
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=3, refreshTokens=0");
    }

    /** Refresh 이력이 하나도 없는 세션도 지우며, 지운 이력 수가 0이라고 반복을 멈추지 않는다. */
    @Test
    void continuesWhenBatchHasNoRefreshTokens() {
        batches(ids(1, 200), ids(201, 3), List.of());

        service.cleanUp();

        verify(sessionRepository, times(203)).deleteRowById(anyLong());
        verify(sessionRepository, times(3)).findExpiredIds(CUTOFF_7_DAYS, 200);
        verify(transactionManager, times(3)).commit(any());
    }

    /**
     * 잠근 세션이 후보 조건(expires_at &lt; cutoff)과 다르면(만료 시각은 바뀌지 않으므로 일어나면 안 됨) 지우지 않고 그 배치를 rollback한 뒤
     * 실패로 끝낸다. 같은 후보를 끝없이 다시 고르지 않는다. cutoff와 같은 값도 조건 밖이다.
     */
    @Test
    void lockedSessionNotMatchingCutoffFailsInsteadOfLooping() {
        batches(List.of(1L, 2L), List.of(1L, 2L), List.of());
        when(sessionRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(session(CUTOFF_UTC)));

        assertThatThrownBy(service::cleanUp).isInstanceOf(IllegalStateException.class);

        verify(sessionRepository, times(1)).findExpiredIds(any(), anyInt());
        verify(refreshTokenRepository, never()).deleteBySessionId(anyLong());
        verify(sessionRepository, never()).deleteRowById(anyLong());
        verify(transactionManager, never()).commit(any());
        verify(transactionManager, times(1)).rollback(any());
    }

    /** 두 번째 배치가 실패하는 지점. */
    enum Failure {
        CANDIDATE_QUERY, ROW_LOCK, TOKEN_DELETE, SESSION_DELETE, COMMIT
    }

    /**
     * 두 번째 배치가 실패하면 첫 배치 commit은 남고, 실패 배치는 rollback(commit 실패는 관리자가 정리)된다. 예외를 그대로 던져 실행을
     * 실패로 끝내며 다음 배치를 조회하지 않고 재시도하지 않는다. 완료 로그를 남기지 않고, 서비스는 예외 메시지를 로그에 쓰지 않는다.
     */
    @ParameterizedTest
    @EnumSource(Failure.class)
    void failedBatchStopsTheRunAndKeepsEarlierCommits(Failure failure, CapturedOutput output) {
        RuntimeException exception = switch (failure) {
            case CANDIDATE_QUERY -> new QueryTimeoutException(SECRET);
            case ROW_LOCK -> new CannotAcquireLockException(SECRET);
            case TOKEN_DELETE, SESSION_DELETE -> new DataIntegrityViolationException(SECRET);
            case COMMIT -> new TransactionSystemException(SECRET);
        };
        List<Long> second = ids(201, 200);
        switch (failure) {
            case CANDIDATE_QUERY -> when(sessionRepository.findExpiredIds(any(), anyInt()))
                    .thenReturn(ids(1, 200)).thenThrow(exception);
            case ROW_LOCK -> {
                batches(ids(1, 200), second, ids(401, 1), List.of());
                when(sessionRepository.findByIdForUpdate(250L)).thenThrow(exception);
            }
            case TOKEN_DELETE -> {
                batches(ids(1, 200), second, ids(401, 1), List.of());
                when(refreshTokenRepository.deleteBySessionId(250L)).thenThrow(exception);
            }
            case SESSION_DELETE -> {
                batches(ids(1, 200), second, ids(401, 1), List.of());
                when(sessionRepository.deleteRowById(250L)).thenThrow(exception);
            }
            case COMMIT -> {
                batches(ids(1, 200), second, ids(401, 1), List.of());
                doNothing().doThrow(exception).when(transactionManager).commit(any());
            }
        }

        assertThatThrownBy(service::cleanUp).isSameAs(exception);

        verify(sessionRepository, times(2)).findExpiredIds(CUTOFF_7_DAYS, 200);
        verify(transactionManager, times(2)).getTransaction(any());
        // 첫 배치 commit 1회. 실패 배치는 rollback하며, commit 실패면 그 commit 호출이 한 번 더 있다.
        verify(transactionManager, times(failure == Failure.COMMIT ? 2 : 1)).commit(any());
        verify(transactionManager, times(failure == Failure.COMMIT ? 0 : 1)).rollback(any());
        verify(sessionRepository, never()).findByIdForUpdate(401L);
        assertThat(output.getAll()).doesNotContain("정리 완료", "SECRET-VALUE");
    }

    /** 잠근 수와 지운 세션 수가 다르면(일어나면 안 됨) 그 배치를 rollback하고 실패로 끝낸다. 같은 대상을 끝없이 다시 고르지 않는다. */
    @Test
    void mismatchedDeleteCountFailsInsteadOfLooping() {
        batches(ids(1, 200), ids(1, 200), List.of());
        when(sessionRepository.deleteRowById(200L)).thenReturn(0);

        assertThatThrownBy(service::cleanUp).isInstanceOf(IllegalStateException.class);

        verify(sessionRepository, times(1)).findExpiredIds(any(), anyInt());
        verify(transactionManager, never()).commit(any());
        verify(transactionManager, times(1)).rollback(any());
    }

    @SafeVarargs
    private void batches(List<Long> first, List<Long>... rest) {
        when(sessionRepository.findExpiredIds(any(), anyInt())).thenReturn(first, rest);
    }

    private static List<Long> ids(long from, int count) {
        return LongStream.range(from, from + count).boxed().toList();
    }

    /** 만료 시각만 의미 있는 세션(14일 세션을 그만큼 앞에서 시작). */
    private static AuthSession session(LocalDateTime expiresAt) {
        Duration ttl = Duration.ofDays(14);
        return AuthSession.start(Member.create("TOSS_ANON", "cleanup-test"), UUID.randomUUID(), expiresAt.minus(ttl), ttl);
    }
}
