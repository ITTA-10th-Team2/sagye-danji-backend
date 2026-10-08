package com.gyejoldanji.global.config;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.test.controller.TestController;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.gyejoldanji.global.security.AuthRequestBodyLimitFilter;
import com.gyejoldanji.global.security.CurrentMember;
import com.gyejoldanji.global.security.SecurityTestConfig;
import com.gyejoldanji.global.security.SessionValidationFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영 두 체인의 매칭·메서드·CORS·필터 등록과 JWT → 세션 검사 → CurrentMember 연결을 검증한다(T08·T09·T25·T35·T36의 체인
 * 범위). 세션 Repository는 대체하고 JWT는 실행 중 만든 RSA 키로 실제 서명·검증한다. 실제 DB 흐름은 MySQL 통합 테스트에서 본다.
 */
class SecurityConfigTest {

    private static final String ORIGIN = SecurityTestConfig.ALLOWED_ORIGIN;
    private static final String SID = UUID.randomUUID().toString();
    private static final String ONBOARDING = "/api/members/me/onboarding/complete";
    private static final AtomicInteger CONTROLLER_CALLS = new AtomicInteger();

    @TempDir
    static Path dir;
    private static String[] jwtProperties;
    private static ServiceTokenService tokenService;
    private static ServiceTokenService otherKeyTokenService;

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);

    @BeforeAll
    static void createKeys() throws Exception {
        KeyPair pair = TestJwtKeys.generate("RSA", 2048);
        jwtProperties = TestJwtKeys.properties(
                TestJwtKeys.writePem(dir, "PRIVATE KEY", pair.getPrivate().getEncoded()),
                TestJwtKeys.writePem(dir, "PUBLIC KEY", pair.getPublic().getEncoded()));
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        properties.getJwt().setAudience("test-audience");
        properties.getJwt().setKeyId("test-key");
        tokenService = new ServiceTokenService(properties, pair);
        otherKeyTokenService = new ServiceTokenService(properties, TestJwtKeys.generate("RSA", 2048));
    }

    @BeforeEach
    void resetCalls() {
        CONTROLLER_CALLS.set(0);
    }

    /** 1번 체인은 정확한 세 경로(메서드 무관)만 맡고, 본문 제한은 1번에만·세션 필터는 2번 Bearer 바로 뒤에 한 번씩 있다. */
    @Test
    void separatesChainsAndRegistersFiltersOnce() {
        runner("DEV", ORIGIN).run(context -> {
            List<SecurityFilterChain> chains = context.getBean(FilterChainProxy.class).getFilterChains();
            assertThat(chains).hasSize(2);
            SecurityFilterChain auth = chains.get(0);
            SecurityFilterChain api = chains.get(1);

            for (String path : List.of("/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout")) {
                for (String method : List.of("POST", "GET", "PUT", "DELETE", "OPTIONS")) {
                    assertThat(auth.matches(new MockHttpServletRequest(method, path))).as(method + path).isTrue();
                }
            }
            for (String path : List.of("/api/auth/anonymous/", "/api/auth", "/api/auth/other", "/api/members/me",
                    ONBOARDING)) {
                assertThat(auth.matches(new MockHttpServletRequest("POST", path))).as(path).isFalse();
                assertThat(api.matches(new MockHttpServletRequest("POST", path))).as(path).isTrue();
            }

            assertThat(count(auth, AuthRequestBodyLimitFilter.class)).isOne();
            assertThat(count(auth, CorsFilter.class)).isOne();
            assertThat(count(auth, BearerTokenAuthenticationFilter.class)).isZero();
            assertThat(count(auth, SessionValidationFilter.class)).isZero();

            List<Filter> apiFilters = api.getFilters();
            assertThat(count(api, BearerTokenAuthenticationFilter.class)).isOne();
            assertThat(count(api, SessionValidationFilter.class)).isOne();
            assertThat(count(api, CorsFilter.class)).isOne();
            assertThat(count(api, AuthRequestBodyLimitFilter.class)).isZero();
            int bearer = indexOf(apiFilters, BearerTokenAuthenticationFilter.class);
            assertThat(apiFilters.get(bearer + 1)).isInstanceOf(SessionValidationFilter.class);

            for (SecurityFilterChain chain : chains) {
                for (Class<?> disabled : List.of(CsrfFilter.class, LogoutFilter.class, BasicAuthenticationFilter.class,
                        UsernamePasswordAuthenticationFilter.class)) {
                    assertThat(count(chain, disabled)).as(disabled.getSimpleName()).isZero();
                }
            }
            assertThat(context.getBeansOfType(AuthRequestBodyLimitFilter.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionValidationFilter.class)).isEmpty();
        });
    }

    /** 세 인증 경로는 POST만 컨트롤러로 가며 잘못된 Bearer도 보지 않는다. 다른 메서드는 403 COMMON_006이다(T36). */
    @Test
    void authChainAllowsOnlyPostAndIgnoresBearer() {
        withMvc("DEV", mvc -> {
            for (String path : List.of("/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout")) {
                mvc.perform(post(path).header("Authorization", "Bearer not-a-valid-token")
                                .contentType("application/json").content("{\"code\":\"c\"}"))
                        .andExpect(status().isOk())
                        .andExpect(content().string("auth"))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            assertThat(CONTROLLER_CALLS).hasValue(3);

            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
                    HttpMethod.OPTIONS)) {
                expectError(mvc.perform(request(method, "/api/auth/anonymous")), 403, "COMMON_006");
            }
            assertThat(CONTROLLER_CALLS).hasValue(3);
            verifyNoInteractions(repository);

            // 끝 슬래시는 정확한 경로가 아니므로 보호 체인에서 Bearer를 요구한다.
            expectError(mvc.perform(post("/api/auth/anonymous/").contentType("application/json").content("{}")),
                    401, "COMMON_005");
        });
    }

    /** Bearer 누락 401 COMMON_005, 형식 오류·다른 키 AUTH_002, 순수 만료 AUTH_001. JWT 오류는 DB·컨트롤러에 가지 않는다(T08·T09). */
    @Test
    void protectedChainClassifiesJwtFailuresBeforeDatabase() {
        withMvc("DEV", mvc -> {
            expectError(mvc.perform(get("/api/members/me")), 401, "COMMON_005");
            expectError(mvc.perform(me("Bearer !!!")), 401, "AUTH_002");
            expectError(mvc.perform(me(bearer(otherKeyTokenService, Instant.now()))), 401, "AUTH_002");
            expectError(mvc.perform(me(bearer(tokenService, Instant.now().minusSeconds(3_600)))), 401, "AUTH_001");

            assertThat(CONTROLLER_CALLS).hasValue(0);
            verifyNoInteractions(repository);
        });
    }

    /** 정상 JWT·세션이면 컨트롤러가 CurrentMember(회원 ID, 내부 세션 PK)를 받고 세션 조회는 한 번이다(T35). */
    @Test
    void protectedChainInjectsCurrentMember() {
        withMvc("DEV", mvc -> {
            when(repository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(activeView()));

            mvc.perform(me(bearer(tokenService, Instant.now())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberId").value(42))
                    .andExpect(jsonPath("$.sessionId").value(7));

            assertThat(CONTROLLER_CALLS).hasValue(1);
            verify(repository).findAuthenticationView(SID, 42L);
        });
    }

    /** 무효 세션 401 AUTH_003, DB 장애 503 COMMON_008. 모두 공통 JSON이며 컨트롤러를 부르지 않는다(T10·T27·T35). */
    @Test
    void protectedChainRejectsInvalidSessionAndDatabaseOutage() {
        withMvc("DEV", mvc -> {
            String token = bearer(tokenService, Instant.now());
            when(repository.findAuthenticationView(anyString(), anyLong())).thenReturn(Optional.empty());
            expectError(mvc.perform(me(token)), 401, "AUTH_003");

            when(repository.findAuthenticationView(anyString(), anyLong()))
                    .thenThrow(new CannotCreateTransactionException("down"));
            expectError(mvc.perform(me(token)), 503, "COMMON_008");

            assertThat(CONTROLLER_CALLS).hasValue(0);
        });
    }

    /** 허용 목록 밖 경로·메서드는 거부한다. 인증 없으면 401 COMMON_005, 유효 인증이면 403 COMMON_006. */
    @Test
    void protectedChainDeniesUnlistedPathsAndMethods() {
        withMvc("DEV", mvc -> {
            when(repository.findAuthenticationView(any(), any())).thenReturn(Optional.of(activeView()));
            String token = bearer(tokenService, Instant.now());

            expectError(mvc.perform(get("/api/other")), 401, "COMMON_005");
            expectError(mvc.perform(get("/api/other").header("Authorization", token)), 403, "COMMON_006");
            expectError(mvc.perform(post("/api/members/me").header("Authorization", token)), 403, "COMMON_006");
            expectError(mvc.perform(get("/error")), 401, "COMMON_005");
            assertThat(CONTROLLER_CALLS).hasValue(0);
        });
    }

    /**
     * 온보딩 완료는 보호 체인의 정확한 POST만 인증 후 컨트롤러로 간다(CurrentMember 주입). 다른 메서드·끝 슬래시·상위 경로는 인증해도
     * 403, Bearer 없는 POST는 401이다. 허용 Origin의 POST preflight는 Authorization과 함께 통과한다.
     */
    @Test
    void protectedChainAllowsOnlyPostOnboardingCompletion() {
        withMvc("PROD", mvc -> {
            when(repository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(activeView()));
            String token = bearer(tokenService, Instant.now());

            mvc.perform(post(ONBOARDING).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberId").value(42))
                    .andExpect(jsonPath("$.sessionId").value(7));
            assertThat(CONTROLLER_CALLS).hasValue(1);

            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                expectError(mvc.perform(request(method, ONBOARDING).header("Authorization", token)), 403, "COMMON_006");
            }
            for (String path : List.of(ONBOARDING + "/", "/api/members/me/onboarding", ONBOARDING + "/extra")) {
                expectError(mvc.perform(post(path).header("Authorization", token)), 403, "COMMON_006");
            }
            expectError(mvc.perform(post(ONBOARDING)), 401, "COMMON_005");
            assertThat(CONTROLLER_CALLS).hasValue(1);

            mvc.perform(preflight(ONBOARDING, ORIGIN, "POST", "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
            assertThat(CONTROLLER_CALLS).hasValue(1);
        });
    }

    /**
     * Bearer 외 인증 방식(DPoP)과 프레임워크 기본 메타데이터 경로(/.well-known/oauth-protected-resource)는 열려 있지 않다.
     * 모두 보호 체인의 인증 요구로 처리된다.
     */
    @Test
    void acceptsOnlyBearerSchemeWithoutFrameworkExtras() {
        withMvc("PROD", mvc -> {
            String token = bearer(tokenService, Instant.now()).substring("Bearer ".length());
            expectError(mvc.perform(get("/api/members/me").header("Authorization", "DPoP " + token)
                    .header("DPoP", "not-a-proof")), 401, "COMMON_005");
            expectError(mvc.perform(get("/.well-known/oauth-protected-resource")), 401, "COMMON_005");
            expectError(mvc.perform(get("/.well-known/oauth-protected-resource/api/members/me")), 401, "COMMON_005");

            assertThat(CONTROLLER_CALLS).hasValue(0);
            verifyNoInteractions(repository);
        });
    }

    /**
     * Swagger 문서 경로는 환경과 무관하게 GET 공개다(실제 노출은 app.swagger.enabled로 springdoc 자체를 켜고 끈다). 테스트
     * API는 DEV에서만 GET 공개이며 PROD에서는 익명 401, 인증해도 403이다.
     */
    @Test
    void docsArePublicRegardlessOfEnvironmentAndTestApiIsDevOnly() {
        withMvc("DEV", mvc -> {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(content().string("docs"));
            mvc.perform(get("/api/test/response")).andExpect(status().isOk());
            expectError(mvc.perform(post("/api/test/response")), 401, "COMMON_005");
        });
        CONTROLLER_CALLS.set(0);
        withMvc("PROD", mvc -> {
            when(repository.findAuthenticationView(any(), any())).thenReturn(Optional.of(activeView()));
            for (String path : List.of("/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html")) {
                mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("docs"));
            }
            assertThat(CONTROLLER_CALLS).hasValue(3);

            expectError(mvc.perform(get("/api/test/response")), 401, "COMMON_005");
            expectError(mvc.perform(get("/api/test/response").header("Authorization", bearer(tokenService, Instant.now()))),
                    403, "COMMON_006");
            assertThat(CONTROLLER_CALLS).hasValue(3);
        });
    }

    /** 허용 Origin의 preflight만 통과하고 인증·본문·분석 헤더와 API의 GET/POST/PUT/PATCH/DELETE/OPTIONS를 허용한다(T25). */
    @Test
    void corsPreflightAllowsOnlyConfiguredOrigin() {
        withMvc("DEV", mvc -> {
            mvc.perform(preflight("/api/members/me", ORIGIN, "GET",
                            "authorization,x-analytics-session-id,x-client-version,x-client-os"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().string("Access-Control-Allow-Methods", allOf(containsString("GET"),
                            containsString("POST"), containsString("PUT"), containsString("PATCH"),
                            containsString("DELETE"), containsString("OPTIONS"))))
                    .andExpect(header().string("Access-Control-Allow-Headers", allOf(
                            containsString("authorization"),
                            containsString("x-analytics-session-id"),
                            containsString("x-client-version"),
                            containsString("x-client-os"))))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
            mvc.perform(preflight("/api/auth/anonymous", ORIGIN, "POST", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));

            mvc.perform(preflight("/api/members/me", "https://evil.example", "GET", "authorization"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(preflight("/api/auth/anonymous", ORIGIN, "PUT", "content-type"))
                    .andExpect(status().isOk());
            mvc.perform(preflight("/api/members/me", ORIGIN, "GET", "x-custom"))
                    .andExpect(status().isForbidden());

            assertThat(CONTROLLER_CALLS).hasValue(0);
            verifyNoInteractions(repository);
        });
    }

    /** 실제 요청: 허용 Origin은 성공·오류 모두 CORS 헤더와 Retry-After 노출, 비허용 Origin은 처리 전에 거부한다(T25). */
    @Test
    void corsActualRequestsExposeRetryAfterOnlyForConfiguredOrigin() {
        withMvc("DEV", mvc -> {
            when(repository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(activeView()));

            mvc.perform(me(bearer(tokenService, Instant.now())).header("Origin", ORIGIN))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
            mvc.perform(get("/api/members/me").header("Origin", ORIGIN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
            mvc.perform(post("/api/auth/anonymous").header("Origin", ORIGIN).contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
            assertThat(CONTROLLER_CALLS).hasValue(2);

            mvc.perform(post("/api/auth/anonymous").header("Origin", "https://evil.example")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            assertThat(CONTROLLER_CALLS).hasValue(2);
        });
    }

    /** Origin은 실제 값이 있어야 하며 wildcard·경로·끝 슬래시·자리표시자는 기동 실패다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "*", "https://*.example.com", "https://fe.example.com/", "https://fe.example.com/app",
            "https://APP_NAME.apps.tossmini.com", "REPLACE_WITH_ORIGIN", "null", "fe.example.com",
            "https://fe.example.com, *"})
    void rejectsInvalidOriginsAtStartup(String origins) {
        runner("DEV", origins).run(context -> assertThat(context).hasFailed());
    }

    /** PROD는 https 실제 주소만 받는다. DEV는 로컬 FE 주소를 허용한다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            DEV  | http://localhost:5173,https://fe.example.com | true
            PROD | https://fe.example.com,https://app.example.com:8443 | true
            PROD | http://localhost:5173 | false
            PROD | https://localhost | false
            PROD | https://fe.localhost | false
            PROD | https://127.0.0.1:8443 | false
            PROD | https://0.0.0.0 | false
            PROD | http://fe.example.com | false
            PROD | https://fe.example.com,http://localhost:5173 | false
            """)
    void appliesProductionOriginRules(String environment, String origins, boolean starts) {
        runner(environment, origins).run(context -> {
            if (starts) {
                assertThat(context).hasNotFailed();
            } else {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROD CORS Origin");
            }
        });
    }

    private WebApplicationContextRunner runner(String environment, String origins) {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityTestConfig.class, TestController.class, ProbeController.class)
                .withBean(AuthSessionRepository.class, () -> repository)
                .withPropertyValues(jwtProperties)
                .withPropertyValues("app.cors.allowed-origins=" + origins, "toss.app-name=test-app",
                        "toss.identity-environment=" + environment, "toss.mtls.keystore-path=not-opened.p12",
                        "toss.mtls.keystore-password=not-a-secret");
    }

    private void withMvc(String environment, MvcTest test) {
        runner(environment, ORIGIN).run(context -> {
            assertThat(context).hasNotFailed();
            test.run(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        });
    }

    @FunctionalInterface
    private interface MvcTest {
        void run(MockMvc mvc) throws Exception;
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(status == 401 ? header().string("WWW-Authenticate", "Bearer")
                        : header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static MockHttpServletRequestBuilder me(String authorization) {
        return get("/api/members/me").header("Authorization", authorization);
    }

    private static MockHttpServletRequestBuilder preflight(String path, String origin, String method, String headers) {
        return options(path).header("Origin", origin).header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers);
    }

    /** 회원 42·세션 SID의 실제 서명 Access 토큰. */
    private static String bearer(ServiceTokenService service, Instant issuedAt) {
        return "Bearer " + service.issue(42L, SID, issuedAt, issuedAt.plusSeconds(1_209_600)).accessToken();
    }

    /** 회원 42의 ACTIVE·미폐기·하루 뒤 만료 세션(내부 PK 7). */
    private static AuthenticationView activeView() {
        return new View(7L, 42L, MemberStatus.ACTIVE, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null);
    }

    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus, LocalDateTime getExpiresAt,
                        LocalDateTime getRevokedAt) implements AuthenticationView {
    }

    private static long count(SecurityFilterChain chain, Class<?> type) {
        return chain.getFilters().stream().filter(type::isInstance).count();
    }

    private static int indexOf(List<Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 체인 통과 여부를 기록하는 test 전용 컨트롤러. */
    @RestController
    static class ProbeController {

        @GetMapping("/api/members/me")
        Map<String, Long> me(@AuthenticationPrincipal CurrentMember member) {
            CONTROLLER_CALLS.incrementAndGet();
            return Map.of("memberId", member.memberId(), "sessionId", member.sessionId());
        }

        /** 모든 메서드·하위 경로를 받아 체인이 거부하는지만 본다. */
        @RequestMapping({ONBOARDING, ONBOARDING + "/", "/api/members/me/onboarding", ONBOARDING + "/extra"})
        Map<String, Long> onboarding(@AuthenticationPrincipal CurrentMember member) {
            CONTROLLER_CALLS.incrementAndGet();
            return Map.of("memberId", member.memberId(), "sessionId", member.sessionId());
        }

        @RequestMapping({"/api/auth/anonymous", "/api/auth/refresh", "/api/auth/logout"})
        String auth() {
            CONTROLLER_CALLS.incrementAndGet();
            return "auth";
        }

        @GetMapping({"/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html"})
        String docs() {
            CONTROLLER_CALLS.incrementAndGet();
            return "docs";
        }

        @RequestMapping("/api/other")
        String other() {
            CONTROLLER_CALLS.incrementAndGet();
            return "other";
        }
    }
}
