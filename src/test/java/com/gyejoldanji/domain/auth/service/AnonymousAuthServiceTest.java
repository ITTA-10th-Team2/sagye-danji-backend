package com.gyejoldanji.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.TestJwtKeys;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 익명 인증 서비스의 순서·시각·회원 판정·실패 분류를 DB 없이 확인한다.
 *
 * <p>Repository와 트랜잭션 관리자는 대체하고 토큰은 실제 RSA 키로 만든다. 실제 MySQL의 잠금·rollback·commit 장애는
 * {@code AnonymousAuthMySqlIntegrationTest}에서 검증한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class AnonymousAuthServiceTest {

    private static final String CODE = "one-time-code";
    private static final String ANON_KEY = "SECRET-ANON-KEY";
    /** upsert 전 Clock 값. 발급 기준으로 다시 쓰면 안 된다. */
    private static final Instant BEFORE_LOCK = Instant.parse("2026-10-03T12:00:00.123456789Z");
    /** 잠금 획득 뒤 Clock 값. 마이크로초로 잘라 발급 기준으로 쓴다. */
    private static final Instant AFTER_LOCK = Instant.parse("2026-10-03T12:00:05.987654321Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-03T12:00:05.987654Z");

    private static ServiceTokenService tokenService;
    private static AuthProperties properties;

    @Mock
    private TossAnonymousAuthClient tossClient;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private AuthRefreshTokenRepository refreshTokenRepository;
    @Mock
    private Clock clock;

    private AnonymousAuthService service;
    private Member member;

    @BeforeAll
    static void createTokenService() throws Exception {
        properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setAudience("test-audience");
        properties.getJwt().setKeyId("test-key");
        tokenService = new ServiceTokenService(properties, TestJwtKeys.generate("RSA", 2048));
    }

    @BeforeEach
    void setUp() {
        properties.setAccessTtlSeconds(900);
        properties.setSessionTtlSeconds(1_209_600);
        member = Member.create("TOSS_ANON", ANON_KEY);
        ReflectionTestUtils.setField(member, "id", 7L);
        service = new AnonymousAuthService(tossClient, new TransactionTemplate(transactionManager), memberRepository,
                sessionRepository, refreshTokenRepository, tokenService, properties, clock);

        when(tossClient.exchangeAnonymousCode(CODE)).thenReturn(ANON_KEY);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(clock.instant()).thenReturn(BEFORE_LOCK, AFTER_LOCK);
        when(memberRepository.findIdentityForUpdate(ANON_KEY)).thenReturn(Optional.of(member));
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 신규 회원: 교환 → TX 시작 → upsert(전 시각) → 잠금 → 잠금 뒤 시각으로 세션·토큰·로그인 시각 → commit 순서다. */
    @Test
    void issuesForNewMemberWithTimeTakenAfterLock() throws Exception {
        AnonymousAuthResponse response = service.authenticate(CODE);

        InOrder order = inOrder(tossClient, transactionManager, memberRepository, sessionRepository,
                refreshTokenRepository);
        order.verify(tossClient).exchangeAnonymousCode(CODE);
        order.verify(transactionManager).getTransaction(any());
        order.verify(memberRepository).ensureIdentity(ANON_KEY, Instant.parse("2026-10-03T12:00:00.123456Z"));
        order.verify(memberRepository).findIdentityForUpdate(ANON_KEY);
        ArgumentCaptor<AuthSession> session = ArgumentCaptor.forClass(AuthSession.class);
        order.verify(sessionRepository).save(session.capture());
        ArgumentCaptor<AuthRefreshToken> refresh = ArgumentCaptor.forClass(AuthRefreshToken.class);
        order.verify(refreshTokenRepository).save(refresh.capture());
        order.verify(transactionManager).commit(any());

        assertThat(response.isNewMember()).isTrue();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.member().memberId()).isEqualTo("7");
        assertThat(response.member().onboardingStatus()).isEqualTo("NOT_COMPLETED");
        assertThat(response.expiresIn()).isEqualTo(899);
        assertThat(response.refreshExpiresIn()).isEqualTo(1_209_600);

        LocalDateTime issuedAt = LocalDateTime.ofInstant(ISSUED_AT, ZoneOffset.UTC);
        assertThat(member.getLastLoginAt()).isEqualTo(issuedAt);
        assertThat(session.getValue().getExpiresAt()).isEqualTo(issuedAt.plusDays(14));
        assertThat(refresh.getValue().getSession()).isSameAs(session.getValue());
        assertThat(refresh.getValue().getGeneration()).isZero();
        assertThat(refresh.getValue().getExpiresAt()).isEqualTo(session.getValue().getExpiresAt());
        assertThat(refresh.getValue().getTokenHash()).isEqualTo(MessageDigest.getInstance("SHA-256")
                .digest(response.refreshToken().getBytes(StandardCharsets.UTF_8)));

        var claims = SignedJWT.parse(response.accessToken()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.getStringClaim("sid")).isEqualTo(session.getValue().getSessionKey());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-10-03T12:00:05Z"));
    }

    /** 이미 인증한 회원은 첫 인증이 아니며 온보딩 상태를 유지하고 로그인 시각만 새 값이 된다. */
    @Test
    void reauthenticationKeepsOnboardingAndIsNotNew() {
        LocalDateTime earlier = LocalDateTime.of(2026, 10, 1, 0, 0);
        member.recordAuthentication(earlier);
        member.completeOnboarding(earlier);

        AnonymousAuthResponse response = service.authenticate(CODE);

        assertThat(response.isNewMember()).isFalse();
        assertThat(response.member().onboardingStatus()).isEqualTo("COMPLETED");
        assertThat(member.getOnboardingCompletedAt()).isEqualTo(earlier);
        assertThat(member.getLastLoginAt()).isEqualTo(LocalDateTime.ofInstant(ISSUED_AT, ZoneOffset.UTC));
    }

    /** ACTIVE가 아니면 AUTH_008이고, 세션·Refresh·로그인 시각을 바꾸지 않고 rollback한다. */
    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = {"BLOCKED", "WITHDRAWN"})
    void rejectsInactiveMemberWithoutChanges(MemberStatus status) {
        ReflectionTestUtils.setField(member, "status", status);

        assertThatThrownBy(() -> service.authenticate(CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_INACTIVE));
        assertThat(member.getStatus()).isEqualTo(status);
        assertThat(member.getLastLoginAt()).isNull();
        verify(sessionRepository, never()).save(any());
        verify(refreshTokenRepository, never()).save(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /** 토스 교환이 실패하면 트랜잭션을 시작하지 않고 분류된 오류를 그대로 낸다. */
    @Test
    void doesNotStartTransactionWhenExchangeFails() {
        BusinessException rejected = new BusinessException(ErrorCode.AUTH_CODE_REJECTED);
        when(tossClient.exchangeAnonymousCode(CODE)).thenThrow(rejected);

        assertThatThrownBy(() -> service.authenticate(CODE)).isSameAs(rejected);
        verifyNoInteractions(transactionManager, memberRepository, sessionRepository, refreshTokenRepository);
    }

    /** 응답 TTL을 만들 수 없는 설정·시각이면 발급하지 않고 rollback 후 500이다. */
    @Test
    void rollsBackWhenResponseTtlIsBelowOneSecond() {
        properties.setAccessTtlSeconds(2);
        properties.setSessionTtlSeconds(1);

        assertThatThrownBy(() -> service.authenticate(CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
        verify(refreshTokenRepository, never()).save(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /** TX 안 실패는 원인 타입으로 503/500을 정하고 rollback한다. 무결성 오류도 409가 아니라 500이며 메시지는 로그에 없다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresInsideTransaction")
    void classifiesFailureInsideTransaction(String label, RuntimeException failure, ErrorCode expected,
                                            CapturedOutput output) {
        when(refreshTokenRepository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.authenticate(CODE))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(output.getAll()).doesNotContain(ANON_KEY);
    }

    static Stream<Arguments> failuresInsideTransaction() {
        String secret = "Duplicate entry '" + ANON_KEY + "'";
        return Stream.of(
                arguments("잠금 대기 실패", new CannotAcquireLockException(secret), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB timeout", new QueryTimeoutException(secret), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB 연결 실패", new DataAccessResourceFailureException(secret), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("트랜잭션 timeout", new TransactionTimedOutException(secret), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("무결성 위반", new DataIntegrityViolationException(secret), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("서명 실패", new JwtEncodingException(secret), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("미분류", new IllegalStateException(secret), ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** commit이 실패하면 성공 결과를 돌려주지 않고 500이다. */
    @Test
    void returnsNothingWhenCommitFails() {
        doThrow(new TransactionSystemException("commit failed"))
                .when(transactionManager).commit(any());

        assertThatThrownBy(() -> service.authenticate(CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** 트랜잭션을 시작하지 못하면(DB 연결 불가) 503이다. */
    @Test
    void mapsTransactionStartFailureToUnavailable() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("no connection"));

        assertThatThrownBy(() -> service.authenticate(CODE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(memberRepository);
    }

    /** 응답 객체의 문자열 표현에 토큰이 없다. */
    @Test
    void hidesTokensInResponseToString() {
        AnonymousAuthResponse response = service.authenticate(CODE);

        assertThat(response.toString()).doesNotContain(response.accessToken(), response.refreshToken());
        assertThat(Duration.ofSeconds(response.expiresIn())).isPositive();
    }
}
