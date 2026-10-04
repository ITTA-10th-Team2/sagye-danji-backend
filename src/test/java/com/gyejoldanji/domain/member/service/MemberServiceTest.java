package com.gyejoldanji.domain.member.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.member.dto.MemberResponse;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.security.CurrentMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
 * 온보딩 완료 서비스의 잠금 순서·잠금 뒤 시각·재검사·최초값 보존·실패 분류를 DB 없이 확인한다.
 *
 * <p>Repository·트랜잭션 관리자·Clock을 대체한다. 실제 MySQL의 잠금 대기·동시 요청·rollback·commit 실패는
 * {@code MemberOnboardingMySqlIntegrationTest}에서 검증한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberServiceTest {

    private static final CurrentMember CURRENT = new CurrentMember(42L, 7L);
    /** 마지막 잠금 뒤 Clock 값(나노초). 마이크로초로 잘라 쓴다. */
    private static final Instant AFTER_LOCK = Instant.parse("2026-10-04T03:04:05.123456789Z");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-04T03:04:05.123456");
    private static final LocalDateTime EARLIER = LocalDateTime.parse("2026-10-01T00:00:00.000001");
    /** DB 예외 메시지에 섞일 수 있는 값. 로그에 남으면 안 된다. */
    private static final String SECRET = "Duplicate entry 'SECRET-VALUE'";

    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private Clock clock;

    private MemberService service;
    private Member member;
    private AuthSession session;

    @BeforeEach
    void setUp() {
        service = new MemberService(memberRepository, sessionRepository, new TransactionTemplate(transactionManager), clock);
        member = member(42L);
        session = session(member, NOW.plusDays(1));

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));
        when(clock.instant()).thenReturn(AFTER_LOCK);
    }

    /**
     * TX 시작 → 회원 잠금 → 세션 잠금 → 시각 1회 → commit 순서다. 일반 조회로 먼저 올리지 않고, 마이크로초 UTC 시각을 최초 완료로
     * 저장한다. 세션·로그인 시각은 바꾸지 않는다.
     */
    @Test
    void completesWithTimeReadAfterLastLock() {
        member.recordAuthentication(EARLIER);

        MemberResponse response = service.completeOnboarding(CURRENT);

        InOrder order = inOrder(transactionManager, memberRepository, sessionRepository, clock);
        order.verify(transactionManager).getTransaction(any());
        order.verify(memberRepository).findByIdForUpdate(42L);
        order.verify(sessionRepository).findByIdForUpdate(7L);
        order.verify(clock).instant();
        order.verify(transactionManager).commit(any());
        verify(clock, times(1)).instant();
        verify(memberRepository, never()).findById(anyLong());
        verify(sessionRepository, never()).findById(anyLong());

        assertThat(response).isEqualTo(new MemberResponse("42", "COMPLETED", "2026-10-04T03:04:05.123456Z"));
        assertThat(member.getOnboardingCompletedAt()).isEqualTo(NOW);
        assertThat(member.getLastLoginAt()).isEqualTo(EARLIER);
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getCurrentRefreshGeneration()).isZero();
        assertThat(session.getExpiresAt()).isEqualTo(NOW.plusDays(1));
    }

    /** 이미 완료한 회원도 잠금·재검사를 거친 뒤 최초 시각 그대로 성공한다. */
    @Test
    void keepsFirstCompletionTime() {
        member.completeOnboarding(EARLIER);

        MemberResponse response = service.completeOnboarding(CURRENT);

        assertThat(response.onboardingCompletedAt()).isEqualTo("2026-10-01T00:00:00.000001Z");
        assertThat(member.getOnboardingCompletedAt()).isEqualTo(EARLIER);
        verify(sessionRepository).findByIdForUpdate(7L);
        verify(clock).instant();
        verify(transactionManager).commit(any());
    }

    /** 만료 시각 직전(1마이크로초 전)은 아직 유효하다. */
    @Test
    void acceptsJustBeforeExpiry() {
        session = session(member, NOW.plusNanos(1_000));
        when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(session));

        assertThat(service.completeOnboarding(CURRENT).onboardingStatus()).isEqualTo("COMPLETED");
    }

    /**
     * 잠금 뒤 다시 본 회원·세션이 무효면(행 없음·소유자 불일치·비활성·폐기·now ≥ 만료) 401 AUTH_003이고 저장 없이 rollback한다.
     * 이미 완료한 회원도 재검사 전에 성공하지 않는다. 행이 없으면 그 자리에서 멈춘다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"회원 행 없음", "세션 행 없음", "다른 회원의 세션", "BLOCKED", "WITHDRAWN", "폐기된 세션",
            "만료 시각 도달", "완료 회원의 폐기된 세션"})
    void rejectsInvalidMemberOrSessionAfterLock(String state) {
        switch (state) {
            case "회원 행 없음" -> when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());
            case "세션 행 없음" -> when(sessionRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());
            case "다른 회원의 세션" -> when(sessionRepository.findByIdForUpdate(7L))
                    .thenReturn(Optional.of(session(member(43L), NOW.plusDays(1))));
            case "BLOCKED", "WITHDRAWN" -> ReflectionTestUtils.setField(member, "status", MemberStatus.valueOf(state));
            case "폐기된 세션" -> session.revoke(SessionRevokeReason.LOGOUT, NOW.minusSeconds(1));
            case "만료 시각 도달" -> when(sessionRepository.findByIdForUpdate(7L))
                    .thenReturn(Optional.of(session(member, NOW)));
            case "완료 회원의 폐기된 세션" -> {
                member.completeOnboarding(EARLIER);
                session.revoke(SessionRevokeReason.LOGOUT, NOW.minusSeconds(1));
            }
            default -> throw new IllegalArgumentException(state);
        }
        LocalDateTime before = member.getOnboardingCompletedAt();

        assertThatThrownBy(() -> service.completeOnboarding(CURRENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_SESSION_INVALID));
        assertThat(member.getOnboardingCompletedAt()).isEqualTo(before);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        if (state.equals("회원 행 없음")) {
            verifyNoInteractions(sessionRepository);
        }
        if (state.endsWith("행 없음")) {
            verifyNoInteractions(clock);
        }
    }

    /**
     * 잠금·조회 중 실패는 원인 타입으로 503/500을 정하고 rollback한다. 무결성 오류도 409가 아니라 500이며 예외 메시지는 로그에 없다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresInsideTransaction")
    void classifiesFailureInsideTransaction(String label, RuntimeException failure, ErrorCode expected,
                                            CapturedOutput output) {
        when(sessionRepository.findByIdForUpdate(7L)).thenThrow(failure);

        assertThatThrownBy(() -> service.completeOnboarding(CURRENT))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        assertThat(member.isOnboardingCompleted()).isFalse();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    static Stream<Arguments> failuresInsideTransaction() {
        return Stream.of(
                arguments("잠금 대기 실패", new CannotAcquireLockException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("deadlock", new PessimisticLockingFailureException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB timeout", new QueryTimeoutException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("DB 연결 실패", new DataAccessResourceFailureException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("트랜잭션 timeout", new TransactionTimedOutException(SECRET), ErrorCode.SERVICE_UNAVAILABLE),
                arguments("무결성 위반", new DataIntegrityViolationException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("미분류", new IllegalStateException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** flush·commit 실패는 성공 결과를 돌려주지 않는다. 무결성 오류는 500, 잠금 timeout은 503이다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("commitFailures")
    void returnsNothingWhenCommitFails(String label, RuntimeException failure, ErrorCode expected,
                                       CapturedOutput output) {
        doThrow(failure).when(transactionManager).commit(any());

        assertThatThrownBy(() -> service.completeOnboarding(CURRENT))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    static Stream<Arguments> commitFailures() {
        return Stream.of(
                arguments("commit 실패", new TransactionSystemException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("flush 무결성 위반", new DataIntegrityViolationException(SECRET), ErrorCode.INTERNAL_SERVER_ERROR),
                arguments("flush 잠금 timeout", new CannotAcquireLockException(SECRET), ErrorCode.SERVICE_UNAVAILABLE));
    }

    /** 트랜잭션을 시작하지 못하면(DB 연결 불가) 회원·세션·시각을 보지 않고 503이다. */
    @Test
    void mapsTransactionStartFailureToUnavailable() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("no connection"));

        assertThatThrownBy(() -> service.completeOnboarding(CURRENT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(memberRepository, sessionRepository, clock);
    }

    private static Member member(long id) {
        Member member = Member.create("TOSS_ANON", "anon-" + id);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    /** 내부 PK 7, 지정한 시각에 절대 만료되는 미폐기 세션. */
    private static AuthSession session(Member owner, LocalDateTime expiresAt) {
        Duration ttl = Duration.ofDays(14);
        AuthSession session = AuthSession.start(owner, UUID.randomUUID(), expiresAt.minus(ttl), ttl);
        ReflectionTestUtils.setField(session, "id", 7L);
        return session;
    }
}
