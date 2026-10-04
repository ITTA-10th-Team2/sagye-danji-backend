package com.gyejoldanji.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.gyejoldanji.domain.auth.dto.RefreshResponse;
import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository.OwnerIds;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.ServiceTokenService.InsufficientTtlException;
import com.gyejoldanji.domain.auth.service.ServiceTokenService.IssuedTokens;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh 회전: 원문 해시로 소유자 ID hint → 트랜잭션(회원 → 세션 → 토큰 행 잠금, 잠금 뒤 시각으로 재검사, 회전 또는 재사용 폐기)
 * → commit 성공 뒤 결과.
 *
 * <p>재사용 탐지는 세션 폐기를 commit해야 하므로 트랜잭션 안에서 예외를 던지지 않고 결과로 돌려준 뒤, commit이 끝나면 밖에서
 * AUTH_006으로 바꾼다. 아무것도 바꾸지 않는 거부(AUTH_003·AUTH_005)는 트랜잭션 안에서 던진다. commit을 포함한 트랜잭션 실패는
 * 503 COMMON_008 또는 500 COMMON_003이며 성공이나 AUTH_006으로 응답하지 않는다. 자동 재시도하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final TransactionTemplate transactionTemplate;
    private final MemberRepository memberRepository;
    private final AuthSessionRepository sessionRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final ServiceTokenService tokenService;
    private final Clock clock;

    /** 트랜잭션 결과. 재사용이면 응답 없이 세션 폐기만 commit한다. */
    private record Rotation(RefreshResponse response) {

        static final Rotation REUSED = new Rotation(null);
    }

    /** 이전 Refresh를 소비하고 같은 세션의 새 토큰 쌍을 발급한다. 반환·AUTH_006 시점에는 commit이 끝나 있다. */
    public RefreshResponse refresh(String refreshToken) {
        byte[] hash = ServiceTokenService.sha256(refreshToken);
        Rotation rotation;
        try {
            rotation = transactionTemplate.execute(status -> rotate(hash));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GlobalExceptionHandler.translateTransactionFailure("Refresh", e);
        }
        if (rotation == Rotation.REUSED) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_REUSED);
        }
        return rotation.response();
    }

    /**
     * 트랜잭션 안. 판정 순서(04 의사코드): 세션 무효 AUTH_003 → 토큰 만료 AUTH_005 → 재사용 폐기 → 응답 TTL 부족 AUTH_003 → 회전.
     * 이미 폐기된 세션은 재사용보다, 유효 세션의 재사용은 TTL 부족보다 먼저 판정한다.
     */
    private Rotation rotate(byte[] hash) {
        // 해시만 조건인 스칼라 ID라 엔티티를 올리지 않는다. 권한 확정이 아니므로 잠근 뒤 소유 관계를 다시 확인한다.
        OwnerIds owner = refreshTokenRepository.findOwnerIdsByHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_INVALID));
        Member member = memberRepository.findByIdForUpdate(owner.getMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        AuthSession session = sessionRepository.findByIdForUpdate(owner.getSessionId())
                .filter(s -> s.getMember().getId().equals(member.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        AuthRefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .filter(t -> t.getSession().getId().equals(session.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_INVALID));
        // 마지막 잠금 뒤의 시각. 요청 진입·hint·앞 잠금 시점의 값을 쓰지 않는다. DB TIMESTAMP(6)에 맞춰 마이크로초로 자른다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

        if (member.getStatus() != MemberStatus.ACTIVE
                || session.getRevokedAt() != null
                || !utcNow.isBefore(session.getExpiresAt())) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_INVALID);
        }
        if (!utcNow.isBefore(token.getExpiresAt())) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        if (token.getConsumedAt() != null || token.getGeneration() != session.getCurrentRefreshGeneration()) {
            session.revoke(SessionRevokeReason.REFRESH_REUSE, utcNow);
            return Rotation.REUSED;
        }

        IssuedTokens tokens;
        try {
            // 응답 TTL 확인과 서명을 상태 변경 전에 끝낸다. 서명 등 다른 실패는 그대로 올라가 rollback 뒤 500이다.
            tokens = tokenService.issue(member.getId(), session.getSessionKey(), now,
                    session.getExpiresAt().toInstant(ZoneOffset.UTC));
        } catch (InsufficientTtlException e) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_INVALID);
        }
        token.consume(utcNow);
        session.advanceGeneration();
        // AuthRefreshToken.issue는 세션의 현재 세대·만료를 복사하므로 세대를 올린 뒤에 만든다.
        refreshTokenRepository.save(AuthRefreshToken.issue(session, tokens.refreshTokenHash()));
        return new Rotation(new RefreshResponse("Bearer", tokens.accessToken(), tokens.expiresIn(),
                tokens.refreshToken(), tokens.refreshExpiresIn()));
    }
}
