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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 로그아웃 서비스의 잠금 순서·잠금 뒤 시각·상태별 종료 허용·최초 폐기값 유지·행 부재와 DB 실패의 구분·commit 경계를 DB 없이 확인한다.
 *
 * <p>Repository·트랜잭션 관리자·Clock을 대체한다. 실제 MySQL의 잠금 대기·Refresh와의 경합·rollback·commit 실패는
 * {@code SessionLogoutMySqlIntegrationTest}에서 검증한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionLogoutServiceTest {

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

    private SessionLogoutService service;
    private Member member;
    private AuthSession session;
    private AuthRefreshToken token;

    @BeforeEach
    void setUp() {
        service = new SessionLogoutService(new TransactionTemplate(transactionManager), memberRepository,
                sessionRepository, refreshTokenRepository, clock);
        member = Member.create("TOSS_ANON", "SECRET-ANON-KEY");
        ReflectionTestUtils.setField(member, "id", 42L);
        member.recordAuthentication(LOGIN);
        session = session(member, 7L);
        token = token(session, 100L);

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(refreshTokenRepository.findOwnerIdsByHash(aryEq(HASH))).thenReturn(Optional.of(new Owner(42L, 7L)));
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(refreshTokenRepository.findByTokenHashForUpdate(aryEq(HASH))).thenReturn(Optional.of(token));
        when(clock.instant()).thenReturn(AFTER_LOCK);
    }

    /**
     * T16: TX → 원문 해시로 hint → 회원 → 세션 → 토큰 잠금 → 시각 1회 → commit 순서다. 그 세션만 LOGOUT·잠금 뒤 UTC 마이크로초로 폐기하고
     * 세대·세션 키·만료·토큰 이력·회원 값은 그대로다. 일반 조회로 엔티티를 먼저 올리지 않고 아무것도 저장·삭제하지 않는다.
     */
    @Test
    void revokesWithTimeReadAfterLastLock() {
        String sessionKey = session.getSessionKey();

        service.logout(RAW);

        InOrder order = inOrder(transactionManager, refreshTokenRepository, memberRepository, sessionRepository, clock);
        order.verify(transactionManager).getTransaction(any());
        order.verify(refreshTokenRepository).findOwnerIdsByHash(aryEq(HASH));
        order.verify(memberRepository).findByIdForUpdate(42L);
        order.verify(sessionRepository).findByIdForUpdate(7L);
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(aryEq(HASH));
        order.verify(clock).instant();
        order.verify(transactionManager).commit(any());
        verify(clock, times(1)).instant();
        verify(memberRepository, never()).findById(anyLong());
        verify(sessionRepository, never()).findById(anyLong());
        verify(refreshTokenRepository, never()).findById(anyLong());
        verify(refreshTokenRepository, never()).save(any());
        verify(sessionRepository, never()).save(any());
        verify(sessionRepository, never()).delete(any());
        verify(refreshTokenRepository, never()).delete(any());

        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
        assertThat(session.getCurrentRefreshGeneration()).isZero();
        assertThat(session.getSessionKey()).isEqualTo(sessionKey);
        assertThat(session.getExpiresAt()).isEqualTo(SESSION_END);
        assertThat(token.getConsumedAt()).isNull();
        assertThat(token.getGeneration()).isZero();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getLastLoginAt()).isEqualTo(LOGIN);
        assertThat(member.getOnboardingCompletedAt()).isNull();
    }

    /** 미등록 토큰은 아무 행도 잠그지 않고 시각도 읽지 않는다. 트랜잭션은 commit까지 끝난 뒤 정상 반환한다. */
    @Test
    void unknownTokenEndsWithoutLocking() {
        when(refreshTokenRepository.findOwnerIdsByHash(any())).thenReturn(Optional.empty());

        service.logout(RAW);

        verifyNoInteractions(memberRepository, sessionRepository, clock);
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());
        verify(transactionManager).commit(any());
    }

    /** T32: 잠금 조회에서 회원·세션·토큰이 사라졌으면 그 자리에서 바꾸지 않고 정상 반환한다. 다음 행을 잠그지 않고 시각도 읽지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"member", "session", "token"})
    void rowGoneWhileLockingEndsWithoutChanges(String gone) {
        switch (gone) {
            case "member" -> when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());
            case "session" -> when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());
            default -> when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        }

        service.logout(RAW);

        if (gone.equals("member")) {
            verifyNoInteractions(sessionRepository);
        }
        if (!gone.equals("token")) {
            verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());
        }
        verifyNoInteractions(clock);
        assertThat(session.getRevokedAt()).isNull();
        verify(transactionManager).commit(any());
    }

    /** hint는 권한 확정이 아니다. 잠근 세션의 회원이나 잠근 토큰의 세션이 다르면 어느 세션도 폐기하지 않고 대상도 바꾸지 않는다. */
    @Test
    void ownershipChangedAfterHintRevokesNothing() {
        Member other = Member.create("TOSS_ANON", "other");
        ReflectionTestUtils.setField(other, "id", 43L);
        AuthSession othersSession = session(other, 7L);
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(othersSession));
        service.logout(RAW);
        assertThat(othersSession.getRevokedAt()).isNull();
        verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());

        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        AuthSession tokensSession = session(member, 8L);
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.of(token(tokensSession, 101L)));
        service.logout(RAW);

        assertThat(session.getRevokedAt()).isNull();
        assertThat(tokensSession.getRevokedAt()).isNull();
        verifyNoInteractions(clock);
        verify(sessionRepository, never()).findByIdForUpdate(8L);
    }

    /** Refresh에서는 거부되는 상태. 로그아웃에서는 모두 그 세션을 종료한다. */
    enum Allowed {
        CONSUMED_TOKEN, OLDER_GENERATION, NEWER_GENERATION, TOKEN_EXPIRED, SESSION_EXPIRED, BLOCKED, WITHDRAWN
    }

    /**
     * T16: 소비한 과거 토큰·세대 불일치·만료 토큰·만료 세션(잠금 대기 중 만료 포함)·BLOCKED/WITHDRAWN 회원도 종료 거부 조건이 아니다.
     * 잠금 뒤 시각으로 LOGOUT 폐기하며 소비 시각·세대·회원 상태는 바꾸지 않는다.
     */
    @ParameterizedTest
    @EnumSource(Allowed.class)
    void revokesRegardlessOfStatesRefreshWouldReject(Allowed state) {
        Instant now = AFTER_LOCK;
        switch (state) {
            case CONSUMED_TOKEN -> token.consume(EARLIER);
            case OLDER_GENERATION -> session.advanceGeneration();
            case NEWER_GENERATION -> ReflectionTestUtils.setField(token, "generation", 1);
            case TOKEN_EXPIRED -> ReflectionTestUtils.setField(token, "expiresAt", EARLIER);
            case SESSION_EXPIRED -> now = Instant.parse("2026-10-18T03:00:00.000001999Z"); // 잠금 뒤 시각 == 세션 만료
            case BLOCKED, WITHDRAWN -> ReflectionTestUtils.setField(member, "status", MemberStatus.valueOf(state.name()));
        }
        when(clock.instant()).thenReturn(now);
        LocalDateTime consumedAt = token.getConsumedAt();
        int generation = session.getCurrentRefreshGeneration();
        MemberStatus status = member.getStatus();

        service.logout(RAW);

        assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.LOGOUT);
        assertThat(session.getRevokedAt()).isEqualTo(state == Allowed.SESSION_EXPIRED ? SESSION_END : NOW);
        assertThat(token.getConsumedAt()).isEqualTo(consumedAt);
        assertThat(session.getCurrentRefreshGeneration()).isEqualTo(generation);
        assertThat(member.getStatus()).isEqualTo(status);
        verify(transactionManager).commit(any());
    }

    /** 이미 폐기된 세션(로그아웃·재사용 탐지)은 다시 종료해도 최초 폐기 시각·사유를 유지하고 정상 반환한다. */
    @ParameterizedTest
    @EnumSource(value = SessionRevokeReason.class, names = {"LOGOUT", "REFRESH_REUSE"})
    void alreadyRevokedSessionKeepsFirstRevocation(SessionRevokeReason first) {
        session.revoke(first, EARLIER);

        service.logout(RAW);
        service.logout(RAW);

        assertThat(session.getRevokeReason()).isEqualTo(first);
        assertThat(session.getRevokedAt()).isEqualTo(EARLIER);
        verify(transactionManager, times(2)).commit(any());
    }

    /**
     * T30: 트랜잭션 안 DB 실패는 행 부재(정상 반환)가 아니다. 원인 타입으로 503/500을 정하고 rollback하며, 무결성 오류도 409가 아니라
     * 500이다. 메시지·토큰·해시는 로그에 없다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresInsideTransaction")
    void classifiesFailureInsideTransaction(String label, String where, RuntimeException failure, ErrorCode expected,
                                            CapturedOutput output) {
        switch (where) {
            case "hint" -> when(refreshTokenRepository.findOwnerIdsByHash(any())).thenThrow(failure);
            case "member-lock" -> when(memberRepository.findByIdForUpdate(42L)).thenThrow(failure);
            case "session-lock" -> when(sessionRepository.findByIdForUpdate(7L)).thenThrow(failure);
            default -> when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenThrow(failure);
        }

        expectError(expected);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(session.getRevokedAt()).isNull();
        assertThat(output.getAll()).doesNotContain(SECRET, RAW, HexFormat.of().formatHex(HASH));
    }

    static Stream<Arguments> failuresInsideTransaction() {
        return Stream.of(
                arguments("hint 조회 연결 실패", "hint", new DataAccessResourceFailureException(SECRET),
                        ErrorCode.SERVICE_UNAVAILABLE),
                arguments("잠금 대기 실패", "member-lock", new CannotAcquireLockException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("deadlock", "session-lock", new PessimisticLockingFailureException(SECRET),
                        ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB timeout", "token-lock", new QueryTimeoutException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("트랜잭션 timeout", "token-lock", new TransactionTimedOutException(SECRET),
                        ErrorCode.SERVICE_UNAVAILABLE),
                arguments("무결성 위반", "session-lock", new DataIntegrityViolationException(SECRET),
                        ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("미분류 실패", "hint", new IllegalStateException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /**
     * T30: 폐기를 반영한 뒤 commit(flush 포함)이 실패하면 200으로 응답하지 않는다. 미분류·무결성 실패는 500(409 아님), 잠금·연결 장애는
     * 503이다. 미등록 토큰처럼 바꿀 것이 없는 경우도 commit이 실패하면 정상 반환하지 않는다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("commitFailures")
    void commitFailureIsNotSuccess(String label, RuntimeException failure, ErrorCode expected, boolean unknownToken,
                                   CapturedOutput output) {
        if (unknownToken) {
            when(refreshTokenRepository.findOwnerIdsByHash(any())).thenReturn(Optional.empty());
        }
        doThrow(failure).when(transactionManager).commit(any());

        expectError(expected);
        verify(transactionManager).commit(any());
        assertThat(output.getAll()).doesNotContain(SECRET, RAW);
    }

    static Stream<Arguments> commitFailures() {
        return Stream.of(
                arguments("commit 실패", new TransactionSystemException("commit failed " + SECRET),
                        ErrorCode.INTERNAL_SERVER_ERROR, false),
                arguments("flush 무결성 실패", new DataIntegrityViolationException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR,
                        false),
                arguments("flush 잠금 실패", new CannotAcquireLockException(SECRET), ErrorCode.SERVICE_UNAVAILABLE, false),
                arguments("미등록 토큰의 commit 실패", new TransactionSystemException("commit failed " + SECRET),
                        ErrorCode.INTERNAL_SERVER_ERROR, true));
    }

    /** 트랜잭션을 시작하지 못하면(DB 연결 불가) 503이고 조회도 하지 않는다. */
    @Test
    void transactionStartFailureIsUnavailable() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("no connection"));

        expectError(ErrorCode.SERVICE_UNAVAILABLE);
        verifyNoInteractions(refreshTokenRepository, memberRepository, sessionRepository);
    }

    private void expectError(ErrorCode expected) {
        assertThatThrownBy(() -> service.logout(RAW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static AuthSession session(Member owner, long id) {
        AuthSession session = AuthSession.start(owner, UUID.randomUUID(), LOGIN, Duration.ofDays(14));
        ReflectionTestUtils.setField(session, "id", id);
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
