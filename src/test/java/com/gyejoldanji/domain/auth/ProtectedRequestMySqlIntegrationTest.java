package com.gyejoldanji.domain.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.gyejoldanji.domain.auth.controller.AuthController;
import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.domain.member.controller.MemberController;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.member.service.MemberService;
import com.gyejoldanji.global.security.CurrentMember;
import com.gyejoldanji.global.security.SecurityTestConfig;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureJdbc;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL과 운영 Security 체인으로 보호 요청을 검증한다(작업 04: T08·T09·T10·T25·T26·T27·T35·T36의 서버 범위).
 *
 * <p>익명 인증 서비스가 실제 DB에 만든 세션과 실제 RSA 서명 Access 토큰으로 JWT 검증 → DB 세션 조회 → CurrentMember →
 * GET /api/members/me까지 확인한다. 토스 교환만 대체한다. {@code AUTH_IT_DB_URL}이 있을 때만 실행하며 준비 조건은
 * {@link AuthPersistenceMySqlIntegrationTest}와 같다(전용 DB·마이그레이션 적용). JVM과 legacy URL 시간대는 Asia/Seoul로 둔다.
 * DB 장애는 테스트 전용 DataSource 래퍼가 연결 획득을 실패시켜 만든다.
 */
@SpringBootTest(classes = ProtectedRequestMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class ProtectedRequestMySqlIntegrationTest {

    private static final String PREFIX = "it04-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static TimeZone originalTimeZone;

    @MockitoBean
    private TossAnonymousAuthClient tossClient;
    @MockitoSpyBean
    private MemberService memberService;
    @Autowired
    private AnonymousAuthService anonymousAuthService;
    @Autowired
    private ServiceTokenService tokenService;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Environment environment;

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {Member.class, AuthSession.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AuthSessionRepository.class})
    @EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
    @Import({SecurityTestConfig.class, ServiceTokenService.class, AnonymousAuthService.class, AuthController.class,
            MemberService.class, MemberController.class})
    static class Config {

        /** 앱 DataSource를 연결 장애 주입용 래퍼로 감싼다. 테스트가 켤 때만 동작한다. */
        @Bean
        static BeanPostProcessor connectionOutageInjection() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource target && !(bean instanceof OutageDataSource)
                            ? new OutageDataSource(target) : bean;
                }
            };
        }
    }

    /** 전용 DB URL에 legacy serverTimezone을 붙이고 JVM 기본 시간대를 Asia/Seoul로 둔다. 필수 보안 설정·테스트 RSA 키를 공급한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("AUTH_IT_DB_URL");
        if (url.contains("connectionTimeZone") || url.contains("forceConnectionTimeZoneToSession")
                || url.contains("serverTimezone")) {
            throw new IllegalStateException("AUTH_IT_DB_URL에는 시간대 파라미터를 넣지 않는다");
        }
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        registry.add("spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "serverTimezone=Asia/Seoul");
        registry.add("spring.datasource.username", () -> System.getenv("AUTH_IT_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("AUTH_IT_DB_PASSWORD"));
        SecurityTestConfig.register(registry);
    }

    @AfterAll
    static void restoreTimeZone() {
        if (originalTimeZone != null) {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 앱 기본 DB가 아닌지 확인하고, code 앞에 이번 실행 접두어를 붙여 anonKey로 돌려주는 교환 대체를 둔다. */
    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        String target = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String appUrl = environment.getProperty("DB_URL");
        if (appUrl != null) {
            assertThat(target).as("앱 기본 DB에서 실행하지 않는다").isNotEqualTo(databaseName(appUrl));
        }
        OutageDataSource.FAIL.set(false);
        when(tossClient.exchangeAnonymousCode(anyString())).thenAnswer(invocation -> PREFIX + invocation.getArgument(0));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 이번 실행이 만든 행만 FK 역순으로 지운다. */
    @AfterEach
    void cleanUp() {
        OutageDataSource.FAIL.set(false);
        String like = PREFIX + "%";
        jdbc.update("DELETE t FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE s FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                + "WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE FROM members WHERE provider_user_id LIKE ?", like);
    }

    /**
     * T35·T08: 실제 발급 토큰 → JWT 검증 → DB 세션 → CurrentMember(회원 ID, 세션 PK) → me 응답. 조회만 하며 회원·세션을 바꾸지
     * 않는다. Bearer가 없으면 401 COMMON_005다.
     */
    @Test
    void realTokenReachesMeAsCurrentMember(CapturedOutput output) throws Exception {
        Login login = login("flow");
        Map<String, Object> before = rowState(login);

        mockMvc.perform(get("/api/members/me").header("Authorization", login.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memberId").value(String.valueOf(login.memberId())))
                .andExpect(jsonPath("$.data.onboardingStatus").value("NOT_COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").doesNotExist())
                .andExpect(authenticated().withAuthenticationPrincipal(
                        new CurrentMember(login.memberId(), login.sessionId())));

        verify(memberService).getMe(login.memberId());
        assertThat(rowState(login)).as("보호 요청은 회원·세션을 갱신하지 않는다").isEqualTo(before);
        expectError(mockMvc.perform(get("/api/members/me")), 401, "COMMON_005");
        assertThat(output.getAll()).doesNotContain(login.accessToken(), PREFIX + "flow");
    }

    /** 완료 시각은 DB의 UTC 값 그대로 Z로 응답한다(JVM·legacy URL Asia/Seoul). */
    @Test
    void returnsStoredUtcCompletionTime() throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        Login login = login("onboarded");
        jdbc.update("UPDATE members SET onboarding_completed_at = '2026-10-01 12:00:00.123456' WHERE id = ?",
                login.memberId());

        mockMvc.perform(get("/api/members/me").header("Authorization", login.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-01T12:00:00.123456Z"));
    }

    /** T35: 폐기·절대 만료 세션은 유효한 JWT여도 401 AUTH_003이며 컨트롤러를 부르지 않는다. */
    @Test
    void rejectsRevokedOrExpiredSession() throws Exception {
        Login revoked = login("revoked");
        jdbc.update("UPDATE auth_sessions SET revoked_at = UTC_TIMESTAMP(6), revoke_reason = 'LOGOUT' WHERE id = ?",
                revoked.sessionId());
        expectError(me(revoked), 401, "AUTH_003");

        Login expired = login("expired");
        // ck_auth_sessions_expiry(expires_at > created_at)를 지키도록 생성 시각도 앞당긴다.
        jdbc.update("UPDATE auth_sessions SET created_at = UTC_TIMESTAMP(6) - INTERVAL 1 DAY, "
                + "expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", expired.sessionId());
        expectError(me(expired), 401, "AUTH_003");

        verify(memberService, never()).getMe(any());
    }

    /** 비활성 회원의 기존 세션은 보호 요청에서 401 AUTH_003, 새 익명 인증에서는 403 AUTH_008로 구분된다. */
    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "BLOCKED"})
    void distinguishesInactiveMemberOnProtectedAndAnonymous(String status) throws Exception {
        Login login = login("inactive-" + status);
        jdbc.update("UPDATE members SET status = ? WHERE id = ?", status, login.memberId());

        expectError(me(login), 401, "AUTH_003");
        expectError(mockMvc.perform(post("/api/auth/anonymous").contentType("application/json")
                .content("{\"code\":\"inactive-" + status + "\"}")), 403, "AUTH_008");
        verify(memberService, never()).getMe(any());
    }

    /** T10: 정상 서명이어도 sub와 sid의 소유 회원이 다르거나 sid가 없는 세션이면 401 AUTH_003이다. */
    @Test
    void rejectsMismatchedOrUnknownSession() throws Exception {
        Login a = login("owner-a");
        Login b = login("owner-b");
        Instant now = Instant.now();

        String crossed = tokenService.issue(b.memberId(), a.sessionKey(), now, now.plusSeconds(600)).accessToken();
        expectError(mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + crossed)),
                401, "AUTH_003");
        String unknown = tokenService.issue(a.memberId(), UUID.randomUUID().toString(), now, now.plusSeconds(600))
                .accessToken();
        expectError(mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + unknown)),
                401, "AUTH_003");

        verify(memberService, never()).getMe(any());
        me(a).andExpect(status().isOk());
    }

    /**
     * T27·T09: DB 연결 장애면 유효 JWT도 503 COMMON_008이고 통과시키지 않는다. 같은 장애 중에도 만료·변조·누락 토큰은 DB를 보지
     * 않고 401로 끝난다(DB를 봤다면 503).
     */
    @Test
    void databaseOutageIsServiceUnavailableAndJwtErrorsSkipDatabase() throws Exception {
        Login login = login("outage");
        Instant past = Instant.now().minusSeconds(3_600);
        String expired = tokenService.issue(login.memberId(), login.sessionKey(), past, past.plusSeconds(1_209_600))
                .accessToken();
        String tampered = tamperSignature(login.accessToken());

        OutageDataSource.FAIL.set(true);
        expectError(me(login), 503, "COMMON_008");
        expectError(mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + expired)),
                401, "AUTH_001");
        expectError(mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + tampered)),
                401, "AUTH_002");
        expectError(mockMvc.perform(get("/api/members/me")), 401, "COMMON_005");
        verify(memberService, never()).getMe(any());

        OutageDataSource.FAIL.set(false);
        me(login).andExpect(status().isOk());
        verify(memberService, times(1)).getMe(login.memberId());
    }

    /**
     * T36·작업 03 회귀: 잘못된 Bearer가 붙은 anonymous도 본문 code로 인증하고, 발급 토큰은 보호 요청에 바로 쓰인다. 16,384바이트
     * 초과는 서비스·DB 전에 413이며 다른 메서드는 403이다.
     */
    @Test
    void anonymousKeepsBodyContractWithInvalidBearer() throws Exception {
        String body = mockMvc.perform(post("/api/auth/anonymous").header("Authorization", "Bearer not-a-valid-token")
                        .contentType("application/json").content("{\"code\":\"t36\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = jsonString(body, "accessToken");
        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(jsonString(body, "memberId")));

        String oversized = "{\"code\":\"t36-big\",\"pad\":\"" + "a".repeat(16_384) + "\"}";
        expectError(mockMvc.perform(post("/api/auth/anonymous").header("Authorization", "Bearer x")
                .contentType("application/json").content(oversized)), 413, "COMMON_009");
        expectError(mockMvc.perform(get("/api/auth/anonymous")), 403, "COMMON_006");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id = ?", Integer.class,
                PREFIX + "t36-big")).isZero();
    }

    /** T25: 토스 한도 초과의 429는 허용 Origin에서 Retry-After를 노출한다. */
    @Test
    void rateLimitExposesRetryAfterToAllowedOrigin() throws Exception {
        when(tossClient.exchangeAnonymousCode(anyString())).thenThrow(new TossAnonymousAuthClient.RateLimitedException(30));

        expectError(mockMvc.perform(post("/api/auth/anonymous").header("Origin", SecurityTestConfig.ALLOWED_ORIGIN)
                        .contentType("application/json").content("{\"code\":\"limited\"}")), 429, "AUTH_014")
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().string("Access-Control-Allow-Origin", SecurityTestConfig.ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
    }

    private record Login(long memberId, long sessionId, String sessionKey, String accessToken) {

        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    /** 실제 익명 인증 서비스로 회원·세션을 만들고 토큰의 sid에 해당하는 세션 PK를 찾는다. */
    private Login login(String code) throws Exception {
        AnonymousAuthResponse response = anonymousAuthService.authenticate(code);
        String sessionKey = SignedJWT.parse(response.accessToken()).getJWTClaimsSet().getStringClaim("sid");
        Long sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE session_key = ?", Long.class,
                sessionKey);
        return new Login(Long.parseLong(response.member().memberId()), sessionId, sessionKey, response.accessToken());
    }

    private ResultActions me(Login login) throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", login.bearer()));
    }

    /** 회원·세션 행에서 보호 요청이 바꾸면 안 되는 값. */
    private Map<String, Object> rowState(Login login) {
        return jdbc.queryForMap("SELECT m.status, m.updated_at AS member_updated, m.last_login_at, "
                + "s.expires_at, s.revoked_at, s.updated_at AS session_updated FROM auth_sessions s "
                + "JOIN members m ON m.id = s.member_id WHERE s.id = ?", login.sessionId());
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** 서명 첫 글자를 바꾼다(마지막 글자는 남는 비트만 바뀌어 같은 서명으로 디코딩될 수 있다). */
    private static String tamperSignature(String token) {
        int i = token.lastIndexOf('.') + 1;
        return token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as(field).isTrue();
        return matcher.group(1);
    }

    private static String databaseName(String jdbcUrl) {
        String path = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        path = path.substring(path.indexOf('/') + 1);
        int end = path.indexOf('?');
        return end < 0 ? path : path.substring(0, end);
    }

    /** 테스트 전용 DataSource 래퍼. {@link #FAIL}이 켜져 있으면 연결 획득을 일시 장애로 실패시킨다. */
    static final class OutageDataSource extends DelegatingDataSource {

        static final AtomicBoolean FAIL = new AtomicBoolean();

        OutageDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            failIfDown();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            failIfDown();
            return super.getConnection(username, password);
        }

        private static void failIfDown() throws SQLException {
            if (FAIL.get()) {
                throw new SQLTransientConnectionException("test outage");
            }
        }
    }
}
