package com.gyejoldanji.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.ServiceTokenService.IssuedTokens;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import com.gyejoldanji.global.config.properties.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 익명 인증: 토스 교환(트랜잭션 밖, 1회) → 인증 트랜잭션 → commit 성공 뒤 결과 반환.
 *
 * <p>DB 작업은 {@link TransactionTemplate}으로 묶어 commit까지 이 경계 안에서 끝낸다. commit을 포함한 트랜잭션 실패는 여기서
 * 원인 타입으로 분류해 503 COMMON_008 또는 500 COMMON_003으로 바꾼다. 다른 도메인의 무결성 409 처리로 넘기지 않으며, 이미 분류된
 * {@link BusinessException}은 그대로 둔다. 트랜잭션과 code 교환은 자동 재시도하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AnonymousAuthService {

    private final TossAnonymousAuthClient tossClient;
    private final TransactionTemplate transactionTemplate;
    private final MemberRepository memberRepository;
    private final AuthSessionRepository sessionRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final ServiceTokenService tokenService;
    private final AuthProperties properties;
    private final Clock clock;

    /** code를 교환하고 회원 세션과 토큰을 발급한다. 반환 시점에는 commit이 끝나 있다. */
    public AnonymousAuthResponse authenticate(String code) {
        String anonKey = tossClient.exchangeAnonymousCode(code);
        try {
            return transactionTemplate.execute(status -> issue(anonKey));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GlobalExceptionHandler.translateTransactionFailure("익명 인증", e);
        }
    }

    /** 트랜잭션 안: 회원 upsert → 잠금 → ACTIVE 확인 → 새 시각 → 세션·토큰·Refresh 해시 저장 → 로그인 시각. */
    private AnonymousAuthResponse issue(String anonKey) {
        memberRepository.ensureIdentity(anonKey, now());
        Member member = memberRepository.findIdentityForUpdate(anonKey)
                .orElseThrow(() -> new IllegalStateException("upsert한 회원을 잠그지 못했습니다."));
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MEMBER_INACTIVE);
        }
        Instant now = now(); // 잠금 대기 뒤의 시각으로 발급한다. upsert 전 시각을 재사용하지 않는다.
        boolean newMember = member.getLastLoginAt() == null;

        Duration sessionTtl = Duration.ofSeconds(properties.getSessionTtlSeconds());
        AuthSession session = sessionRepository.save(AuthSession.start(member, UUID.randomUUID(), utc(now), sessionTtl));
        IssuedTokens tokens = tokenService.issue(member.getId(), session.getSessionKey(), now, now.plus(sessionTtl));
        refreshTokenRepository.save(AuthRefreshToken.issue(session, tokens.refreshTokenHash()));
        member.recordAuthentication(utc(now));

        return new AnonymousAuthResponse("Bearer", tokens.accessToken(), tokens.expiresIn(), tokens.refreshToken(),
                tokens.refreshExpiresIn(), newMember, new AnonymousAuthResponse.MemberInfo(
                        String.valueOf(member.getId()),
                        member.isOnboardingCompleted() ? "COMPLETED" : "NOT_COMPLETED"));
    }

    /** DB TIMESTAMP(6)에 맞춘 UTC 마이크로초 현재 시각. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
