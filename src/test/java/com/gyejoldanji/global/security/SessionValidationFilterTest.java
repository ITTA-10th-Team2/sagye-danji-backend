package com.gyejoldanji.global.security;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 세션 필터의 principal 교체·무효 세션·DB 장애 처리를 검증한다(T35 단위). Repository는 대체하고 실제 DB 조회는 MySQL 통합
 * 테스트에서 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionValidationFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00.250Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String SID = UUID.randomUUID().toString();
    private static final String TOKEN = "SECRET-ACCESS-TOKEN";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);
    private final SessionValidationFilter filter = new SessionValidationFilter(
            repository, Clock.fixed(NOW, ZoneOffset.UTC), new JsonSecurityErrorHandler(JSON));

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private int chainCalls;
    private final AtomicReference<Authentication> seenByChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> {
        chainCalls++;
        seenByChain.set(SecurityContextHolder.getContext().getAuthentication());
    };

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/members/me");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 유효 세션이면 표준 인증 객체로 CurrentMember(회원 ID, 내부 세션 PK)를 넣고 기존 권한을 유지한다. 원문 토큰은 넣지 않는다. */
    @Test
    void replacesPrincipalWithCurrentMember() throws Exception {
        JwtAuthenticationToken jwt = authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L))
                .thenReturn(Optional.of(view(42L, MemberStatus.ACTIVE, NOW_UTC.plusSeconds(1), null)));

        filter.doFilter(request, response, chain);

        assertThat(chainCalls).isEqualTo(1);
        Authentication authentication = seenByChain.get();
        assertThat(authentication).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(new CurrentMember(42L, 7L));
        assertThat(authentication.getCredentials()).isNull();
        assertThat(AuthorityUtils.authorityListToSet(authentication.getAuthorities()))
                .isEqualTo(AuthorityUtils.authorityListToSet(jwt.getAuthorities())).containsExactly("FACTOR_BEARER");
        assertThat(authentication.toString()).doesNotContain(TOKEN);
        assertThat(new RequestAttributeSecurityContextRepository().loadDeferredContext(request).get()
                .getAuthentication()).isSameAs(authentication);

        // 조회만 하고 잠금·저장(만료 연장)을 하지 않는다.
        verify(repository).findAuthenticationView(SID, 42L);
        verifyNoMoreInteractions(repository);
    }

    /** 만료 1마이크로초 전은 유효하다. */
    @Test
    void acceptsSessionJustBeforeExpiry() throws Exception {
        authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L))
                .thenReturn(Optional.of(view(42L, MemberStatus.ACTIVE, NOW_UTC.plusNanos(1_000), null)));

        filter.doFilter(request, response, chain);

        assertThat(chainCalls).isEqualTo(1);
        assertThat(seenByChain.get().getPrincipal()).isInstanceOf(CurrentMember.class);
    }

    /** Bearer 인증이 없거나 익명이면 DB를 보지 않고 넘긴다(인가 단계가 401/허용을 정한다). */
    @Test
    void passesThroughWithoutJwtAuthentication() throws Exception {
        filter.doFilter(request, response, chain);
        SecurityContextHolder.setContext(new SecurityContextImpl(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(chainCalls).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(repository);
    }

    /** 세션 없음(소유자 불일치 포함)·만료·폐기는 401 AUTH_003이며 다음 처리를 부르지 않는다. */
    @ParameterizedTest
    @MethodSource("unusableSessions")
    void rejectsUnusableSession(String description, Optional<AuthenticationView> view) throws Exception {
        authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L)).thenReturn(view);

        filter.doFilter(request, response, chain);

        assertRejected(401, "AUTH_003");
    }

    static Stream<Arguments> unusableSessions() {
        return Stream.of(
                arguments("없음·소유자 불일치", Optional.empty()),
                arguments("다른 회원 소유", Optional.of(view(43L, MemberStatus.ACTIVE, NOW_UTC.plusDays(1), null))),
                arguments("만료 시각과 같음", Optional.of(view(42L, MemberStatus.ACTIVE, NOW_UTC, null))),
                arguments("만료 지남", Optional.of(view(42L, MemberStatus.ACTIVE, NOW_UTC.minusSeconds(1), null))),
                arguments("폐기", Optional.of(view(42L, MemberStatus.ACTIVE, NOW_UTC.plusDays(1),
                        NOW_UTC.minusSeconds(1)))));
    }

    /** 비활성 회원(탈퇴·차단)의 세션도 AUTH_003이다(anonymous의 AUTH_008과 구분). */
    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = {"WITHDRAWN", "BLOCKED"})
    void rejectsInactiveMember(MemberStatus status) throws Exception {
        authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L))
                .thenReturn(Optional.of(view(42L, status, NOW_UTC.plusDays(1), null)));

        filter.doFilter(request, response, chain);

        assertRejected(401, "AUTH_003");
    }

    /** DB 연결·자원·잠금·timeout 장애는 503 COMMON_008이며 인증을 통과시키지 않는다. 로그에 토큰·sid가 없다. */
    @ParameterizedTest
    @MethodSource("databaseOutages")
    void rejectsWithServiceUnavailableOnDatabaseOutage(RuntimeException failure, CapturedOutput output)
            throws Exception {
        authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L)).thenThrow(failure);

        filter.doFilter(request, response, chain);

        assertRejected(503, "COMMON_008");
        assertThat(output.getAll()).contains(failure.getClass().getName()).doesNotContain(TOKEN, SID, "db-detail");
    }

    static Stream<RuntimeException> databaseOutages() {
        return Stream.of(new CannotCreateTransactionException("db-detail"),
                new DataAccessResourceFailureException("db-detail"), new QueryTimeoutException("db-detail"),
                new PessimisticLockingFailureException("db-detail"), new TransactionTimedOutException("db-detail"));
    }

    /** 일시 장애가 아닌 조회 오류는 500 COMMON_003이며 역시 통과시키지 않는다. 예외 메시지는 남기지 않는다. */
    @Test
    void rejectsWithInternalErrorOnUnexpectedFailure(CapturedOutput output) throws Exception {
        authenticateJwt("42");
        when(repository.findAuthenticationView(SID, 42L))
                .thenThrow(new InvalidDataAccessResourceUsageException("db-detail"));

        filter.doFilter(request, response, chain);

        assertRejected(500, "COMMON_003");
        assertThat(output.getAll()).contains(InvalidDataAccessResourceUsageException.class.getName())
                .doesNotContain(TOKEN, SID, "db-detail");
    }

    private void assertRejected(int status, String code) throws Exception {
        assertThat(chainCalls).as("다음 처리 미호출").isZero();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("SecurityContext 비움").isNull();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(status == 401 ? "Bearer" : null);
        JsonNode body = JSON.readTree(response.getContentAsByteArray());
        assertThat(body.path("success").booleanValue()).isFalse();
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        assertThat(response.getContentAsString()).doesNotContain(TOKEN, SID);
    }

    /** BearerTokenAuthenticationFilter가 검증 뒤 넣는 것과 같은 JWT 인증을 SecurityContext에 둔다. */
    private static JwtAuthenticationToken authenticateJwt(String sub) {
        Jwt jwt = Jwt.withTokenValue(TOKEN).header("alg", "RS256")
                .claims(claims -> claims.putAll(Map.of("sub", sub, "sid", SID, "token_use", "access")))
                .build();
        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("FACTOR_BEARER"));
        SecurityContextHolder.setContext(new SecurityContextImpl(authentication));
        return authentication;
    }

    private static AuthenticationView view(Long memberId, MemberStatus status, LocalDateTime expiresAt,
                                           LocalDateTime revokedAt) {
        return new AuthenticationView() {
            @Override
            public Long getSessionId() {
                return 7L;
            }

            @Override
            public Long getMemberId() {
                return memberId;
            }

            @Override
            public MemberStatus getMemberStatus() {
                return status;
            }

            @Override
            public LocalDateTime getExpiresAt() {
                return expiresAt;
            }

            @Override
            public LocalDateTime getRevokedAt() {
                return revokedAt;
            }
        };
    }
}
