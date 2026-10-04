package com.gyejoldanji.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.dto.RefreshResponse;
import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository.OwnerIds;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.dao.PessimisticLockingFailureException;
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
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Refresh 서비스의 잠금 순서·잠금 뒤 시각·판정 우선순위·회전·재사용 폐기 commit·실패 분류를 DB 없이 확인한다.
 *
 * <p>Repository·트랜잭션 관리자·Clock을 대체하고 토큰은 실제 RSA 키로 만든다. 실제 MySQL의 잠금 대기·동시 요청·rollback·commit 실패는
 * {@code TokenRefreshMySqlIntegrationTest}에서 검증한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenRefreshServiceTest {

    private static final String RAW = "Abc-_0123456789abcdefghijklmnopqrstuvwxyzAB";
    private static final byte[] HASH = sha256(RAW);
    private static final LocalDateTime LOGIN = LocalDateTime.parse("2026-10-04T03:00:00.000001");
    private static final LocalDateTime SESSION_END = LOGIN.plusDays(14);
    /** 마지막(토큰) 잠금 뒤 Clock 값(나노초). 마이크로초로 잘라 쓴다. */
    private static final Instant AFTER_LOCK = Instant.parse("2026-10-04T03:10:05.123456789Z");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-04T03:10:05.123456");
    private static final LocalDateTime EARLIER = LocalDateTime.parse("2026-10-04T03:05:00.000001");
    /** DB 예외 메시지에 섞일 수 있는 값. 로그에 남으면 안 된다. */
    private static final String SECRET = "Duplicate entry 'SECRET-VALUE'";

    private static ServiceTokenService realTokenService;

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

    private ServiceTokenService tokenService;
    private TokenRefreshService service;
    private Member member;
    private AuthSession session;
    private AuthRefreshToken token;

    @BeforeAll
    static void createTokenService() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setAudience("test-audience");
        properties.getJwt().setKeyId("test-key");
        realTokenService = new ServiceTokenService(properties, TestJwtKeys.generate("RSA", 2048));
    }

    @BeforeEach
    void setUp() {
        tokenService = spy(realTokenService);
        service = new TokenRefreshService(new TransactionTemplate(transactionManager), memberRepository,
                sessionRepository, refreshTokenRepository, tokenService, clock);
        member = Member.create("TOSS_ANON", "SECRET-ANON-KEY");
        ReflectionTestUtils.setField(member, "id", 42L);
        member.recordAuthentication(LOGIN);
        session = session(member, 7L, SESSION_END);
        token = token(session, 100L);

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(refreshTokenRepository.findOwnerIdsByHash(aryEq(HASH))).thenReturn(Optional.of(new Owner(42L, 7L)));
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(refreshTokenRepository.findByTokenHashForUpdate(aryEq(HASH))).thenReturn(Optional.of(token));
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(clock.instant()).thenReturn(AFTER_LOCK);
    }

    /**
     * T12: TX → 원문 해시로 hint → 회원 → 세션 → 토큰 잠금 → 시각 1회 → 발급 → 새 행 저장 → commit 순서다. 이전 토큰 소비, 세대 +1,
     * 증가된 세대·같은 절대 만료의 새 해시 행. 세션 키·만료와 회원 값은 그대로이며 응답 TTL은 잠금 뒤 시각 기준 내림 값이다.
     */
    @Test
    void rotatesWithTimeReadAfterLastLock() throws Exception {
        RefreshResponse response = service.refresh(RAW);

        InOrder order = inOrder(transactionManager, refreshTokenRepository, memberRepository, sessionRepository, clock,
                tokenService);
        order.verify(transactionManager).getTransaction(any());
        order.verify(refreshTokenRepository).findOwnerIdsByHash(aryEq(HASH));
        order.verify(memberRepository).findByIdForUpdate(42L);
        order.verify(sessionRepository).findByIdForUpdate(7L);
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(aryEq(HASH));
        order.verify(clock).instant();
        order.verify(tokenService).issue(42L, session.getSessionKey(), Instant.parse("2026-10-04T03:10:05.123456Z"),
                Instant.parse("2026-10-18T03:00:00.000001Z"));
        ArgumentCaptor<AuthRefreshToken> saved = ArgumentCaptor.forClass(AuthRefreshToken.class);
        order.verify(refreshTokenRepository).save(saved.capture());
        order.verify(transactionManager).commit(any());
        verify(clock, times(1)).instant();
        verify(memberRepository, never()).findById(anyLong());
        verify(sessionRepository, never()).findById(anyLong());
        verify(refreshTokenRepository, never()).findById(anyLong());

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(899);
        assertThat(response.refreshExpiresIn()).isEqualTo(1_208_994);
        assertThat(response.refreshToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(RAW);
        var claims = SignedJWT.parse(response.accessToken()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getStringClaim("sid")).isEqualTo(session.getSessionKey());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-10-04T03:10:05Z"));

        assertThat(token.getConsumedAt()).isEqualTo(NOW);
        assertThat(token.getGeneration()).isZero();
        assertThat(session.getCurrentRefreshGeneration()).isOne();
        assertThat(saved.getValue().getSession()).isSameAs(session);
        assertThat(saved.getValue().getGeneration()).isOne();
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(SESSION_END);
        assertThat(saved.getValue().getConsumedAt()).isNull();
        assertThat(saved.getValue().getTokenHash()).isEqualTo(sha256(response.refreshToken()));
        assertThat(session.getExpiresAt()).isEqualTo(SESSION_END);
        assertThat(session.getRevokedAt()).isNull();
        assertThat(member.getLastLoginAt()).isEqualTo(LOGIN);
        assertThat(member.getOnboardingCompletedAt()).isNull();
    }

    /** hint가 없으면 AUTH_005이며 아무 행도 잠그지 않고 시각도 읽지 않는다. */
    @Test
    void unknownRefreshIsInvalidWithoutLocking() {
        when(refreshTokenRepository.findOwnerIdsByHash(any())).thenReturn(Optional.empty());

        expectError(ErrorCode.AUTH_REFRESH_INVALID);
        verifyNoInteractions(memberRepository, sessionRepository, clock);
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());
    }

    /** 잠금 조회에서 회원·세션이 사라졌으면 즉시 AUTH_003, 토큰이 사라졌으면 즉시 AUTH_005다. 다음 행을 잠그지 않고 시각도 읽지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"member", "session", "token"})
    void rowGoneWhileLockingIsRejectedImmediately(String gone) {
        switch (gone) {
            case "member" -> when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());
            case "session" -> when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());
            default -> when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        }

        expectError(gone.equals("token") ? ErrorCode.AUTH_REFRESH_INVALID : ErrorCode.AUTH_SESSION_INVALID);
        if (gone.equals("member")) {
            verifyNoInteractions(sessionRepository);
        }
        if (!gone.equals("token")) {
            verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());
        }
        verifyNoInteractions(clock);
        expectNoChanges();
    }

    /** hint는 권한 확정이 아니다. 잠근 세션의 회원이 다르면 AUTH_003, 잠근 토큰의 세션이 다르면 AUTH_005이며 바꾸지 않는다. */
    @Test
    void rechecksOwnershipAfterLocking() {
        Member other = Member.create("TOSS_ANON", "other");
        ReflectionTestUtils.setField(other, "id", 43L);
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session(other, 7L, SESSION_END)));
        expectError(ErrorCode.AUTH_SESSION_INVALID);

        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        AuthRefreshToken foreign = token(session(member, 8L, SESSION_END), 101L);
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(foreign));
        expectError(ErrorCode.AUTH_REFRESH_INVALID);

        assertThat(foreign.getConsumedAt()).isNull();
        expectNoChanges();
    }

    /** 비활성 회원은 AUTH_003이다. 소비한 토큰이어도 재사용 폐기보다 세션 무효가 먼저다. */
    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = {"BLOCKED", "WITHDRAWN"})
    void inactiveMemberIsSessionInvalidBeforeReuse(MemberStatus status) {
        ReflectionTestUtils.setField(member, "status", status);
        token.consume(EARLIER);

        expectError(ErrorCode.AUTH_SESSION_INVALID);
        assertThat(token.getConsumedAt()).isEqualTo(EARLIER);
        assertThat(session.getRevokedAt()).isNull();
        verify(refreshTokenRepository, never()).save(any());
        verify(transactionManager, never()).commit(any());
    }

    /** 이미 폐기된 세션은 소비한 토큰이 와도 AUTH_003이며 최초 폐기 시각·사유를 유지한다. */
    @Test
    void revokedSessionIsSessionInvalidBeforeReuseAndKeepsFirstRevocation() {
        session.revoke(SessionRevokeReason.LOGOUT, EARLIER);
        token.consume(EARLIER);

        expectError(ErrorCode.AUTH_SESSION_INVALID);
        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
        assertThat(session.getRevokedAt()).isEqualTo(EARLIER);
        verify(refreshTokenRepository, never()).save(any());
        verify(transactionManager, never()).commit(any());
    }

    /** 잠금 뒤 시각이 세션 만료와 같거나 지나면 AUTH_003이다(now == expiresAt 거부). 소비한 토큰도 같다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredSessionIsSessionInvalid(boolean consumed) {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-18T03:00:00.000001999Z"));
        if (consumed) {
            token.consume(EARLIER);
        }

        expectError(ErrorCode.AUTH_SESSION_INVALID);
        assertThat(session.getRevokedAt()).isNull();
        verify(refreshTokenRepository, never()).save(any());
    }

    /** 세션은 유효한데 토큰만 만료된 비정상 데이터는 AUTH_005이며 바꾸지 않는다. */
    @Test
    void tokenExpiredWhileSessionValidIsInvalidRefresh() {
        ReflectionTestUtils.setField(token, "expiresAt", NOW);

        expectError(ErrorCode.AUTH_REFRESH_INVALID);
        expectNoChanges();
    }

    /**
     * T13: 소비한 토큰이나 세대가 다른 토큰은 그 세션만 REFRESH_REUSE로 폐기하고(잠금 뒤 시각) commit한 뒤 AUTH_006이다. 토큰을
     * 발급·저장하지 않고 소비 시각·세대를 바꾸지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"consumed", "older-generation", "newer-generation"})
    void reuseRevokesSessionAndCommitsBeforeError(String kind) {
        switch (kind) {
            case "consumed" -> token.consume(EARLIER);
            case "older-generation" -> session.advanceGeneration();
            default -> ReflectionTestUtils.setField(token, "generation", 1);
        }
        int generation = session.getCurrentRefreshGeneration();
        LocalDateTime consumedAt = token.getConsumedAt();

        expectError(ErrorCode.AUTH_REFRESH_REUSED);

        InOrder order = inOrder(sessionRepository, refreshTokenRepository, clock, transactionManager);
        order.verify(sessionRepository).findByIdForUpdate(7L);
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(any());
        order.verify(clock).instant();
        order.verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.REFRESH_REUSE);
        assertThat(session.getCurrentRefreshGeneration()).isEqualTo(generation);
        assertThat(token.getConsumedAt()).isEqualTo(consumedAt);
        verify(tokenService, never()).issue(anyLong(), anyString(), any(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    /**
     * T32: 검사를 통과한 현재 토큰이라도 응답 TTL이 1초 미만이면 바꾸지 않고 AUTH_003이다(세션 0.5초 남음, 1.1초 남았지만 Access exp
     * 내림으로 0). 같은 잔여 시간의 이전 토큰은 TTL보다 재사용 판정이 먼저라 폐기 commit 뒤 AUTH_006이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-04T03:10:05.623456Z", "2026-10-04T03:10:06.223456Z"})
    void shortRemainingTtlRefusesCurrentTokenButStillRevokesReuse(String sessionEnd) {
        ReflectionTestUtils.setField(session, "expiresAt", LocalDateTime.parse(sessionEnd.replace("Z", "")));
        ReflectionTestUtils.setField(token, "expiresAt", session.getExpiresAt());

        expectError(ErrorCode.AUTH_SESSION_INVALID);
        verify(transactionManager).rollback(any());
        expectNoChanges();

        token.consume(EARLIER);
        expectError(ErrorCode.AUTH_REFRESH_REUSED);
        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.REFRESH_REUSE);
        verify(transactionManager).commit(any());
    }

    /**
     * T30: TX 안 실패는 원인 타입으로 503/500을 정하고 rollback한다. 서명 실패나 TTL 외 IllegalStateException을 AUTH_003으로 바꾸지 않고,
     * 무결성 오류도 409가 아니라 500이다. 메시지·토큰·해시는 로그에 없다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresInsideTransaction")
    void classifiesFailureInsideTransaction(String label, String where, RuntimeException failure, ErrorCode expected,
                                            CapturedOutput output) {
        switch (where) {
            case "member-lock" -> when(memberRepository.findByIdForUpdate(42L)).thenThrow(failure);
            case "token-lock" -> when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenThrow(failure);
            case "issue" -> doThrow(failure).when(tokenService).issue(anyLong(), anyString(), any(), any());
            default -> when(refreshTokenRepository.save(any())).thenThrow(failure);
        }

        expectError(expected);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(output.getAll()).doesNotContain(SECRET, RAW, HexFormat.of().formatHex(HASH));
    }

    static Stream<Arguments> failuresInsideTransaction() {
        return Stream.of(
                arguments("잠금 대기 실패", "member-lock", new CannotAcquireLockException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("deadlock", "token-lock", new PessimisticLockingFailureException(SECRET),
                        ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB timeout", "token-lock", new QueryTimeoutException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB 연결 실패", "member-lock", new DataAccessResourceFailureException(SECRET),
                        ErrorCode.SERVICE_UNAVAILABLE),
                arguments("트랜잭션 timeout", "save", new TransactionTimedOutException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("저장 무결성 위반", "save", new DataIntegrityViolationException(SECRET),
                        ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("서명 실패", "issue", new JwtEncodingException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("TTL 외 IllegalStateException", "issue", new IllegalStateException(SECRET),
                        ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** 서명 실패는 상태를 바꾸기 전에 나서 메모리의 엔티티도 바뀌지 않는다(실제 rollback은 MySQL 테스트). */
    @Test
    void signingFailureHappensBeforeAnyChange() {
        doThrow(new JwtEncodingException("injected")).when(tokenService).issue(anyLong(), anyString(), any(), any());

        expectError(ErrorCode.INTERNAL_SERVER_ERROR);
        expectNoChanges();
    }

    /** 회전·재사용 폐기 모두 commit이 실패하면 토큰이나 AUTH_006을 돌려주지 않고 500이다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureReturnsNeitherTokensNorReuseError(boolean reuse, CapturedOutput output) {
        if (reuse) {
            token.consume(EARLIER);
        }
        doThrow(new TransactionSystemException("commit failed " + SECRET)).when(transactionManager).commit(any());

        expectError(ErrorCode.INTERNAL_SERVER_ERROR);
        verify(transactionManager).commit(any());
        assertThat(output.getAll()).doesNotContain(SECRET, RAW);
    }

    /** 트랜잭션을 시작하지 못하면(DB 연결 불가) 503이고 조회도 하지 않는다. */
    @Test
    void transactionStartFailureIsUnavailable() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("no connection"));

        expectError(ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(refreshTokenRepository, memberRepository, sessionRepository);
    }

    /** 응답 객체의 문자열 표현에 토큰이 없다. */
    @Test
    void hidesTokensInResponseToString() {
        RefreshResponse response = service.refresh(RAW);

        assertThat(response.toString()).doesNotContain(response.accessToken(), response.refreshToken());
    }

    private void expectError(ErrorCode expected) {
        assertThatThrownBy(() -> service.refresh(RAW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** 회전·폐기 흔적이 없고 commit하지 않았다. */
    private void expectNoChanges() {
        assertThat(token.getConsumedAt()).isNull();
        assertThat(session.getCurrentRefreshGeneration()).isZero();
        assertThat(session.getRevokedAt()).isNull();
        verify(refreshTokenRepository, never()).save(any());
        verify(transactionManager, never()).commit(any());
    }

    private static AuthSession session(Member owner, long id, LocalDateTime expiresAt) {
        AuthSession session = AuthSession.start(owner, UUID.randomUUID(), LOGIN, Duration.ofDays(14));
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "expiresAt", expiresAt);
        return session;
    }

    private static AuthRefreshToken token(AuthSession session, long id) {
        AuthRefreshToken token = AuthRefreshToken.issue(session, HASH);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    /** hint projection 대체(스칼라 ID 두 개). */
    private record Owner(Long getMemberId, Long getSessionId) implements OwnerIds {
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
