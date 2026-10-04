package com.gyejoldanji.domain.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.gyejoldanji.domain.auth.controller.AuthController;
import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService.IssuedTokens;
import com.gyejoldanji.domain.auth.service.SessionLogoutService;
import com.gyejoldanji.domain.auth.service.TokenRefreshService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.domain.member.controller.MemberController;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.member.service.MemberService;
import com.gyejoldanji.global.security.SecurityTestConfig;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL과 운영 Security 체인으로 POST /api/auth/refresh를 검증한다(작업 06: T12~T15·T30·T32).
 *
 * <p>익명 인증 서비스가 실제 DB에 만든 세션·Refresh와 실제 RSA 서명 Access 토큰으로 요청 → 해시 hint → 회원·세션·토큰 행 잠금 →
 * commit까지 확인한다. 토스 교환만 대체한다. {@code AUTH_IT_DB_URL}이 있을 때만 실행하며 준비 조건은
 * {@link AuthPersistenceMySqlIntegrationTest}와 같다(전용 DB·마이그레이션 적용·performance_schema 잠금 관측 권한). JVM과 legacy URL
 * 시간대는 Asia/Seoul로 둔다.
 *
 * <p>잠금 대기는 테스트 쪽 트랜잭션이 행을 {@code FOR UPDATE}로 잡은 상태에서 요청을 보내고 performance_schema의 실제 대기로 확인한다.
 * flush·commit 실패, 짧은 잠금 대기, 연결 장애는 테스트 전용 DataSource 래퍼가 켤 때만 주입한다. commit 실패는 flush가 끝난 뒤 JDBC
 * commit 호출 직전의 명확한 실패이며, DB commit 뒤 응답만 잃은(결과 불명확) 상황은 다루지 않는다.
 */
@SpringBootTest(classes = TokenRefreshMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class TokenRefreshMySqlIntegrationTest {

    private static final String PREFIX = "it06-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final String REFRESH = "/api/auth/refresh";
    /** 로그인 시각. 이후 시각은 Access 유효 시간(15분) 안에 둔다. 모두 나노초가 있다. */
    private static final Instant T0 = Instant.parse("2026-10-04T05:04:05.123456789Z");
    private static final Instant T1 = Instant.parse("2026-10-04T05:06:07.987654321Z");
    private static final Instant T2 = Instant.parse("2026-10-04T05:07:08.000000999Z");
    private static final Instant T3 = Instant.parse("2026-10-04T05:08:09.000001001Z");
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static TimeZone originalTimeZone;

    @MockitoBean
    private TossAnonymousAuthClient tossClient;
    @MockitoSpyBean
    private ServiceTokenService tokenService;
    @Autowired
    private AnonymousAuthService anonymousAuthService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Environment environment;

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private ExecutorService pool;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {Member.class, AuthSession.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AuthSessionRepository.class})
    @EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
    @Import({SecurityTestConfig.class, ServiceTokenService.class, AnonymousAuthService.class, TokenRefreshService.class,
            SessionLogoutService.class, AuthController.class, MemberService.class, MemberController.class})
    static class Config {

        /** auditing·JWT 검증·세션 필터·서비스가 함께 쓰는 조절 가능한 Clock. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** 앱 DataSource를 장애 주입용 래퍼로 감싼다. 테스트가 켤 때만 동작한다. */
        @Bean
        static BeanPostProcessor faultInjection() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource target && !(bean instanceof FaultInjectingDataSource)
                            ? new FaultInjectingDataSource(target) : bean;
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
        clock.set(T0);
        FaultInjectingDataSource.reset();
        when(tossClient.exchangeAnonymousCode(anyString())).thenAnswer(invocation -> PREFIX + invocation.getArgument(0));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        pool = Executors.newFixedThreadPool(2);
    }

    /** 이번 실행이 만든 행만 FK 역순으로 지운다. */
    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        FaultInjectingDataSource.reset();
        String like = PREFIX + "%";
        jdbc.update("DELETE t FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE s FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                + "WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE FROM members WHERE provider_user_id LIKE ?", like);
    }

    /**
     * T12: Access 없이·만료된 Bearer가 붙어도 body Refresh로 회전한다. 이전 행 consumed(잠금 뒤 나노초 Clock → UTC 마이크로초), 세대 +1,
     * 같은 절대 만료의 새 행(원문 UTF-8 정확한 SHA-256만 저장). 세션 UUID·만료·이전 이력·회원 값·같은 회원의 다른 세션은 그대로이고,
     * 응답 TTL은 잠금 뒤 시각 기준 내림 값이다(KST JVM). 새 Access로 보호 API를 쓰고 새 Refresh로 다시 회전할 수 있다.
     */
    @Test
    void rotatesAndKeepsSessionExpiryHistoryAndMember(CapturedOutput output) throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        Login login = login("rotate");
        Login other = login("rotate");
        Map<String, Object> session = sessionRow(login.sessionId());
        Map<String, Object> member = memberRow(login.memberId());
        Map<String, Object> otherState = state(other);
        String expired = tokenService.issue(login.memberId(), login.sessionKey(), T0.minusSeconds(3_600),
                T0.plusSeconds(1_209_600)).accessToken();
        clock.set(T1);

        MvcResult result = refresh(login.refreshToken(), "Bearer " + expired)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(899))
                .andExpect(jsonPath("$.data.refreshExpiresIn").value(1_209_477))
                .andReturn();
        Tokens rotated = tokens(result);

        assertThat(rotated.refreshToken()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(login.refreshToken());
        var claims = SignedJWT.parse(rotated.accessToken()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(login.memberId()));
        assertThat(claims.getStringClaim("sid")).isEqualTo(login.sessionKey());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-10-04T05:06:07Z"));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(Instant.parse("2026-10-04T05:21:07Z"));

        List<Map<String, Object>> rows = tokenRows(login.sessionId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("generation", 0).containsEntry("hash", hex(login.refreshToken()));
        assertThat((BigDecimal) rows.get(0).get("consumed_at")).isEqualByComparingTo(epochMicros(T1));
        assertThat(rows.get(1)).containsEntry("generation", 1).containsEntry("hash", hex(rotated.refreshToken()))
                .containsEntry("consumed_at", null).containsEntry("hash_length", 32L);
        assertThat(rows.get(1).get("expires_at")).isEqualTo(session.get("expires_at"));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(consumed_at, '%Y-%m-%dT%H:%i:%s.%f') FROM auth_refresh_tokens "
                + "WHERE session_id = ? AND generation = 0", String.class, login.sessionId()))
                .isEqualTo("2026-10-04T05:06:07.987654");
        assertThat(countByHash(sha256(Base64.getUrlDecoder().decode(rotated.refreshToken())))).as("난수 바이트 해시 아님")
                .isZero();
        Map<String, Object> after = sessionRow(login.sessionId());
        assertThat(after).containsEntry("current_refresh_generation", 1)
                .containsEntry("session_key", session.get("session_key"))
                .containsEntry("expires_at", session.get("expires_at"))
                .containsEntry("revoked_at", null);
        assertThat(memberRow(login.memberId())).isEqualTo(member);
        assertThat(state(other)).as("다른 세션 불변").isEqualTo(otherState);

        clock.set(T2);
        me(rotated.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(String.valueOf(login.memberId())));
        Tokens again = tokens(refresh(rotated.refreshToken(), null).andExpect(status().isOk()).andReturn());
        assertThat(tokenRows(login.sessionId())).hasSize(3);
        assertThat(sessionRow(login.sessionId())).containsEntry("current_refresh_generation", 2);
        me(again.accessToken()).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(login.refreshToken(), rotated.refreshToken(), rotated.accessToken(),
                again.refreshToken(), hex(login.refreshToken()), PREFIX + "rotate");
    }

    /**
     * T13: 회전 뒤 이전 Refresh 재사용은 401 AUTH_006이고, 별도 조회에서 그 세션의 REFRESH_REUSE 폐기 commit이 보인다. 기존·새 Access와
     * 회전으로 받은 새 Refresh는 이후 거부되고(AUTH_003), 반복 재사용도 최초 폐기값을 유지한 채 AUTH_003이다. 같은 회원의 다른 세션은 정상이다.
     */
    @Test
    void reuseRevokesOnlyThatSessionAndRejectsItsTokens(CapturedOutput output) throws Exception {
        Login login = login("reuse");
        Login other = login("reuse");
        clock.set(T1);
        Tokens rotated = tokens(refresh(login.refreshToken(), null).andExpect(status().isOk()).andReturn());
        Map<String, Object> otherState = state(other);
        clock.set(T2);

        expectRefreshError(refresh(login.refreshToken(), null), "AUTH_006");

        Map<String, Object> revoked = sessionRow(login.sessionId());
        assertThat(revoked).containsEntry("revoke_reason", "REFRESH_REUSE").containsEntry("current_refresh_generation", 1);
        assertThat((BigDecimal) revoked.get("revoked_at")).isEqualByComparingTo(epochMicros(T2));
        assertThat(tokenRows(login.sessionId())).hasSize(2);
        assertThat(state(other)).isEqualTo(otherState);

        clock.set(T3);
        expectSecurityError(me(login.accessToken()), "AUTH_003");
        expectSecurityError(me(rotated.accessToken()), "AUTH_003");
        expectRefreshError(refresh(rotated.refreshToken(), null), "AUTH_003");
        expectRefreshError(refresh(login.refreshToken(), null), "AUTH_003");
        assertThat(sessionRow(login.sessionId())).isEqualTo(revoked);

        me(other.accessToken()).andExpect(status().isOk());
        refresh(other.refreshToken(), null).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(login.refreshToken(), rotated.refreshToken(), rotated.accessToken());
    }

    /** 소비하지 않았지만 세대가 다른 토큰(비정상 데이터)도 재사용으로 보고 그 세션을 폐기 commit한 뒤 AUTH_006이다. */
    @Test
    void generationMismatchIsReuse() throws Exception {
        Login login = login("generation");
        jdbc.update("UPDATE auth_sessions SET current_refresh_generation = 1 WHERE id = ?", login.sessionId());
        clock.set(T1);

        expectRefreshError(refresh(login.refreshToken(), null), "AUTH_006");

        assertThat(sessionRow(login.sessionId())).containsEntry("revoke_reason", "REFRESH_REUSE");
        assertThat(tokenRows(login.sessionId())).hasSize(1).first().satisfies(row -> assertThat(row.get("consumed_at")).isNull());
    }

    /**
     * T14: 같은 Refresh 두 요청이 회원 행 잠금을 함께 기다리다 차례로 들어가면 하나는 회전 200, 다른 하나는 소비된 토큰을 보고 재사용
     * 폐기 commit 뒤 AUTH_006이다. 최종적으로 세션은 폐기되어 200으로 받은 새 토큰도 쓸 수 없다.
     */
    @Test
    void concurrentSameRefreshEndsWithSessionRevoked() throws Exception {
        Login login = login("concurrent");
        clock.set(T1);

        MvcResult a;
        MvcResult b;
        try (Connection holder = lockRow("members", login.memberId())) {
            Future<ResultActions> first = pool.submit(() -> refresh(login.refreshToken(), null));
            Future<ResultActions> second = pool.submit(() -> refresh(login.refreshToken(), null));
            awaitLockWaits("members", 2, first, second);
            clock.set(T2);
            holder.commit();
            a = first.get(WAIT.toSeconds(), TimeUnit.SECONDS).andReturn();
            b = second.get(WAIT.toSeconds(), TimeUnit.SECONDS).andReturn();
        }
        MvcResult ok = a.getResponse().getStatus() == 200 ? a : b;
        MvcResult reused = ok == a ? b : a;
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(reused.getResponse().getContentAsString(), "$.code")).isEqualTo("AUTH_006");

        assertThat(sessionRow(login.sessionId())).containsEntry("revoke_reason", "REFRESH_REUSE")
                .containsEntry("current_refresh_generation", 1);
        assertThat(tokenRows(login.sessionId())).hasSize(2);
        Tokens rotated = tokens(ok);
        expectRefreshError(refresh(rotated.refreshToken(), null), "AUTH_003");
        expectSecurityError(me(rotated.accessToken()), "AUTH_003");
    }

    /** T15: 형식은 맞지만 발급하지 않은 Refresh는 AUTH_005이고 이번 실행의 행을 바꾸지 않는다. */
    @Test
    void unknownRefreshIsInvalid() throws Exception {
        Login login = login("unknown");
        Map<String, Object> before = state(login);
        clock.set(T1);

        expectRefreshError(refresh("Z".repeat(43), null), "AUTH_005");
        expectRefreshError(refresh(login.refreshToken().toLowerCase(), null), "AUTH_005");

        assertThat(state(login)).isEqualTo(before);
    }

    /** 세션 무효·토큰만 만료의 판정. 모두 바꾸지 않는다(폐기 세션은 최초 사유 유지). */
    enum Invalid {
        /** 잠금 뒤 시각 == 세션 만료. 소비한 토큰이어도 재사용보다 먼저다. */
        SESSION_EXPIRED("AUTH_003"),
        BLOCKED("AUTH_003"),
        WITHDRAWN("AUTH_003"),
        /** 로그아웃된 세션의 이전(소비한) 토큰. 재사용 폐기보다 AUTH_003이 먼저다. */
        REVOKED_WITH_CONSUMED_TOKEN("AUTH_003"),
        /** 세션은 유효한데 토큰 행만 만료(비정상 데이터). */
        TOKEN_ONLY_EXPIRED("AUTH_005");

        final String code;

        Invalid(String code) {
            this.code = code;
        }
    }

    /** T15: 만료·비활성·폐기 세션은 AUTH_003, 토큰만 만료는 AUTH_005이며 아무것도 바꾸지 않는다. 같은 회원의 다른 세션은 그대로다. */
    @ParameterizedTest
    @EnumSource(Invalid.class)
    void rejectsInvalidSessionOrExpiredTokenWithoutChanges(Invalid invalid) throws Exception {
        Login login = login("invalid-" + invalid);
        clock.set(T1);
        switch (invalid) {
            case SESSION_EXPIRED -> {
                refresh(login.refreshToken(), null).andExpect(status().isOk()); // 처음 토큰을 소비해 둔다
                clock.set(sessionExpiresAt(login));
            }
            case BLOCKED, WITHDRAWN -> jdbc.update("UPDATE members SET status = ? WHERE id = ?", invalid.name(),
                    login.memberId());
            case REVOKED_WITH_CONSUMED_TOKEN -> {
                refresh(login.refreshToken(), null).andExpect(status().isOk());
                jdbc.update("UPDATE auth_sessions SET revoked_at = '2026-10-04 05:06:30', revoke_reason = 'LOGOUT' "
                        + "WHERE id = ?", login.sessionId());
                clock.set(T2);
            }
            case TOKEN_ONLY_EXPIRED -> jdbc.update("UPDATE auth_refresh_tokens SET expires_at = '2026-10-04 05:06:07.987654' "
                    + "WHERE session_id = ?", login.sessionId());
        }
        Map<String, Object> before = state(login);

        expectRefreshError(refresh(login.refreshToken(), null), invalid.code);

        assertThat(state(login)).isEqualTo(before);
    }

    /**
     * T32·T13: 응답 TTL이 1초 미만인 현재 토큰(세션 0.5초 남음, 1.1초 남았지만 Access exp 내림으로 0)은 AUTH_003이고 소비·세대·새 행이
     * 없다. 같은 잔여 시간에 이전 토큰을 다시 쓰면 TTL보다 재사용 판정이 먼저라 폐기를 commit하고 AUTH_006이다.
     */
    @ParameterizedTest
    @ValueSource(longs = {500_000_000L, 1_100_000_000L})
    void shortTtlRefusesCurrentTokenButReuseStillRevokes(long remainingNanos) throws Exception {
        Login login = login("short-" + remainingNanos);
        clock.set(T1);
        Tokens rotated = tokens(refresh(login.refreshToken(), null).andExpect(status().isOk()).andReturn());
        Map<String, Object> before = state(login);
        Instant almost = sessionExpiresAt(login).minusNanos(remainingNanos).plusNanos(999);
        clock.set(almost);

        expectRefreshError(refresh(rotated.refreshToken(), null), "AUTH_003");
        assertThat(state(login)).isEqualTo(before);

        expectRefreshError(refresh(login.refreshToken(), null), "AUTH_006");
        Map<String, Object> revoked = sessionRow(login.sessionId());
        assertThat(revoked).containsEntry("revoke_reason", "REFRESH_REUSE");
        assertThat((BigDecimal) revoked.get("revoked_at")).isEqualByComparingTo(epochMicros(almost));
    }

    /**
     * 잠금을 기다리는 동안 생기는 변경과 테스트 쪽 트랜잭션이 잡는 행.
     *
     * <p>세션 폐기·회원 상태 변경은 실제 로그아웃·Refresh처럼 회원 → 세션 순서로 잠근 트랜잭션이 한다(세션의 revoked_at은 FK
     * 인덱스에 있어 바꿀 때 부모 회원 행도 잠근다). 토큰 삭제는 정리 작업처럼 세션을 잠근 쪽이 한다. 요청이 해시 인덱스로 토큰을 잠그는
     * 중에 토큰 행만 잡고 PK로 지우면 해시 인덱스 레코드를 서로 기다려 InnoDB deadlock이 난다(실제 MySQL에서 확인).
     */
    enum Change {
        EXPIRED_WHILE_MEMBER_LOCKED("members", "AUTH_003"),
        EXPIRED_WHILE_SESSION_LOCKED("auth_sessions", "AUTH_003"),
        /** 마지막(토큰) 잠금 뒤에 시각을 읽는지 확인한다. */
        EXPIRED_WHILE_TOKEN_LOCKED("auth_refresh_tokens", "AUTH_003"),
        TTL_ZERO_WHILE_TOKEN_LOCKED("auth_refresh_tokens", "AUTH_003"),
        REVOKED("members", "AUTH_003"),
        BLOCKED("members", "AUTH_003"),
        MEMBER_DELETED("members", "AUTH_003"),
        SESSION_DELETED_WHILE_SESSION_LOCKED("auth_sessions", "AUTH_003"),
        /** 세션은 남고 토큰 행만 사라지면 토큰 잠금 조회가 비어 AUTH_005다. */
        TOKEN_DELETED_WHILE_SESSION_LOCKED("auth_sessions", "AUTH_005");

        final String lockedTable;
        final String code;

        Change(String lockedTable, String code) {
            this.lockedTable = lockedTable;
            this.code = code;
        }
    }

    /**
     * T32: hint를 얻은 요청이 회원·세션·토큰 행 잠금을 기다리는 동안 만료 도달·응답 TTL 0·폐기·비활성화·행 삭제가 생기면 잠금 뒤 재조회와
     * 마지막 잠금 뒤 시각으로 거부하고 회전·폐기를 남기지 않는다.
     */
    @ParameterizedTest
    @EnumSource(Change.class)
    void rechecksChangesCommittedWhileWaitingForLock(Change change) throws Exception {
        Login login = login("wait-" + change);
        clock.set(T1);
        long lockedId = switch (change.lockedTable) {
            case "members" -> login.memberId();
            case "auth_sessions" -> login.sessionId();
            default -> tokenId(login.sessionId(), 0);
        };

        try (Connection holder = lockRow(change.lockedTable, lockedId)) {
            Future<ResultActions> request = pool.submit(() -> refresh(login.refreshToken(), null));
            awaitLockWaits(change.lockedTable, 1, request);
            switch (change) {
                case EXPIRED_WHILE_MEMBER_LOCKED, EXPIRED_WHILE_SESSION_LOCKED, EXPIRED_WHILE_TOKEN_LOCKED ->
                        clock.set(sessionExpiresAt(login)); // now == expiresAt
                case TTL_ZERO_WHILE_TOKEN_LOCKED -> clock.set(sessionExpiresAt(login).minusMillis(500));
                case REVOKED -> {
                    execute(holder, "SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE", login.sessionId());
                    execute(holder, "UPDATE auth_sessions SET revoked_at = '2026-10-04 05:06:30', revoke_reason = "
                            + "'LOGOUT' WHERE id = ?", login.sessionId());
                }
                case BLOCKED -> execute(holder, "UPDATE members SET status = 'BLOCKED' WHERE id = ?", login.memberId());
                case MEMBER_DELETED -> {
                    deleteSessions(holder, login.memberId());
                    execute(holder, "DELETE FROM members WHERE id = ?", login.memberId());
                }
                case SESSION_DELETED_WHILE_SESSION_LOCKED -> deleteSessions(holder, login.memberId());
                case TOKEN_DELETED_WHILE_SESSION_LOCKED ->
                        execute(holder, "DELETE FROM auth_refresh_tokens WHERE session_id = ?", login.sessionId());
            }
            holder.commit();
            expectRefreshError(request.get(WAIT.toSeconds(), TimeUnit.SECONDS), change.code);
        }

        List<Map<String, Object>> rows = tokenRows(login.sessionId());
        assertThat(rows).as("새 토큰 행 없음").hasSizeLessThanOrEqualTo(1);
        rows.forEach(row -> assertThat(row.get("consumed_at")).as("소비 안 함").isNull());
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id = ?", Integer.class,
                login.sessionId());
        if (sessions == 1) {
            Map<String, Object> session = sessionRow(login.sessionId());
            assertThat(session).containsEntry("current_refresh_generation", 0);
            assertThat(session.get("revoke_reason")).isEqualTo(change == Change.REVOKED ? "LOGOUT" : null);
        }
    }

    /**
     * 회원 행 잠금을 기다리다 MySQL lock wait timeout(테스트에서 1초)이 나면 503 COMMON_008이고 변경이 없다(자동 재시도 없음). 잠금이
     * 풀린 뒤 같은 Refresh로 다시 요청하면 회전된다.
     */
    @Test
    void lockWaitTimeoutIsServiceUnavailableWithoutChanges(CapturedOutput output) throws Exception {
        Login login = login("lock-timeout");
        Map<String, Object> before = state(login);
        clock.set(T1);

        try (Connection holder = lockRow("members", login.memberId())) {
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(true);
            Future<ResultActions> request = pool.submit(() -> refresh(login.refreshToken(), null));
            awaitLockWaits("members", 1, request);
            expectRefreshError(request.get(WAIT.toSeconds(), TimeUnit.SECONDS), "COMMON_008", 503);
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(false);
            holder.rollback();
        }

        assertThat(state(login)).isEqualTo(before);
        refresh(login.refreshToken(), null).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain(login.refreshToken());
    }

    /** DB 연결을 얻지 못하면 503 COMMON_008이고 아무것도 바꾸지 않는다. */
    @Test
    void connectionOutageIsServiceUnavailable() throws Exception {
        Login login = login("outage");
        Map<String, Object> before = state(login);
        clock.set(T1);

        FaultInjectingDataSource.FAIL_CONNECTION.set(true);
        ResultActions result = refresh(login.refreshToken(), null);
        FaultInjectingDataSource.FAIL_CONNECTION.set(false);

        expectRefreshError(result, "COMMON_008", 503);
        assertThat(state(login)).isEqualTo(before);
    }

    /** T30: JWT 서명 실패는 500 COMMON_003이고 소비·세대·새 행이 남지 않는다. */
    @Test
    void signingFailureLeavesNothing(CapturedOutput output) throws Exception {
        Login login = login("sign-fail");
        Map<String, Object> before = state(login);
        clock.set(T1);
        doThrow(new JwtEncodingException("injected signing failure"))
                .when(tokenService).issue(anyLong(), anyString(), any(), any());

        expectRefreshError(refresh(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(state(login)).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("injected signing failure", login.refreshToken());
    }

    /**
     * T30·T26: 새 행 저장의 예상 밖 unique 위반(실제 MySQL)은 409가 아니라 500이며 소비·세대 변경이 남지 않는다. MySQL 오류 원문
     * (Duplicate entry와 해시 바이트의 이스케이프 표기)은 운영 application.yml의 로깅 설정으로 로그에 남지 않고, 메시지 없는 오류 타입·
     * 스택 진단은 남는다. 실패 메시지에도 해시가 나오지 않게 결과를 boolean으로 비교한다.
     */
    @Test
    void unexpectedUniqueViolationOnNewRowIsInternalError(CapturedOutput output) throws Exception {
        Login owner = login("hash-owner");
        Login login = login("hash-collision");
        Map<String, Object> before = state(login);
        byte[] existingHash = sha256(owner.refreshToken().getBytes(StandardCharsets.UTF_8));
        doAnswer(invocation -> {
            IssuedTokens real = (IssuedTokens) invocation.callRealMethod();
            return new IssuedTokens(real.accessToken(), real.expiresIn(), real.refreshToken(), real.refreshExpiresIn(),
                    existingHash);
        }).when(tokenService).issue(anyLong(), anyString(), any(), any());
        clock.set(T1);

        expectRefreshError(refresh(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(state(login).equals(before)).as("소비·세대·새 행이 남지 않음").isTrue();
        String logs = output.getAll();
        assertThat(logs.contains("Refresh 트랜잭션 실패")).as("메시지 없는 오류 진단은 남음").isTrue();
        assertThat(logs.contains(SQLIntegrityConstraintViolationException.class.getName())).as("원인 타입은 남음").isTrue();
        String escapedHash = duplicateEntryValue(owner.sessionId(), existingHash);
        assertThat(escapedHash.contains("\\x")).as("MySQL 오류 메시지의 해시는 바이너리 이스케이프 표기").isTrue();
        assertThat(logs.contains(escapedHash)).as("해시 이스케이프 표기 없음").isFalse();
        assertThat(logs.toLowerCase(Locale.ROOT).contains(HexFormat.of().formatHex(existingHash)))
                .as("해시 hex 없음").isFalse();
        assertThat(logs.contains("Duplicate entry")).as("SQL 오류 원문 없음").isFalse();
    }

    /**
     * 같은 해시 행을 테스트 커넥션에서 직접 INSERT해 MySQL이 오류 메시지에 쓰는 값 표기({@code Duplicate entry '<값>'})를 얻는다.
     * 탐지 문자열을 추측하지 않으려는 것이며 값은 출력하지 않는다. 실패한 INSERT라 남는 행은 없다.
     */
    private String duplicateEntryValue(long sessionId, byte[] hash) throws SQLException {
        String prefix = "Duplicate entry '";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO auth_refresh_tokens (session_id, "
                     + "token_hash, generation, expires_at, created_at, updated_at) VALUES (?, ?, 99, "
                     + "'2026-10-18 00:00:00', '2026-10-04 00:00:00', '2026-10-04 00:00:00')")) {
            statement.setLong(1, sessionId);
            statement.setBytes(2, hash);
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            String message = e.getMessage();
            int start = message.indexOf(prefix);
            int end = message.lastIndexOf("' for key");
            if (start >= 0 && end > start + prefix.length()) {
                return message.substring(start + prefix.length(), end);
            }
        }
        throw new AssertionError("같은 해시의 Duplicate entry 오류를 재현하지 못했다");
    }

    /**
     * T30: flush 중(세션 세대 UPDATE와 새 행 INSERT가 같은 커넥션에 이미 반영된 뒤) 이전 토큰 UPDATE가 실패하면 500이고, 새 조회에서
     * 소비·세대·새 행이 모두 없다.
     */
    @Test
    void flushFailureRollsBackPartialRotation() throws Exception {
        Login login = login("flush-fail");
        Map<String, Object> before = state(login);
        clock.set(T1);
        FaultInjectingDataSource.FAIL_TOKEN_UPDATE.set(new Target(login.sessionId(), 0));

        expectRefreshError(refresh(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(FaultInjectingDataSource.SEEN_BEFORE_FAILURE.get()).as("실패 직전 같은 커넥션의 세대·토큰 수·폐기")
                .containsExactly(1, 2, 0);
        assertThat(state(login)).isEqualTo(before);
    }

    /**
     * T30: flush가 끝난 뒤 JDBC commit이 실패하면 500이고 새 조회에서 소비·세대·새 행이 없다. 같은 커넥션에는 회전이 반영돼 있었다. 장애가
     * 사라진 뒤 같은 Refresh가 여전히 현재 토큰으로 회전된다(서버 상태 확인용이며 FE의 같은 Refresh 자동 재전송 허용이 아니다).
     */
    @Test
    void commitFailureAfterFlushLeavesNoRotation(CapturedOutput output) throws Exception {
        Login login = login("commit-fail");
        Map<String, Object> before = state(login);
        clock.set(T1);
        FaultInjectingDataSource.FAIL_COMMIT.set(new Target(login.sessionId(), 0));

        expectRefreshError(refresh(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(FaultInjectingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션의 세대·토큰 수·폐기")
                .containsExactly(1, 2, 0);
        assertThat(state(login)).isEqualTo(before);
        clock.set(T2);
        refresh(login.refreshToken(), null).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain("injected commit failure", login.refreshToken());
    }

    /** T30: 재사용 폐기의 commit이 실패하면 AUTH_006이 아니라 500이고 세션은 폐기되지 않았다. 다시 재사용하면 폐기 commit 뒤 AUTH_006이다. */
    @Test
    void reuseRevocationCommitFailureIsNotReported() throws Exception {
        Login login = login("reuse-commit-fail");
        clock.set(T1);
        refresh(login.refreshToken(), null).andExpect(status().isOk());
        Map<String, Object> before = state(login);
        clock.set(T2);
        FaultInjectingDataSource.FAIL_COMMIT.set(new Target(login.sessionId(), 1));

        expectRefreshError(refresh(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(FaultInjectingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션의 세대·토큰 수·폐기")
                .containsExactly(1, 2, 1);
        assertThat(state(login)).isEqualTo(before);
        clock.set(T3);
        expectRefreshError(refresh(login.refreshToken(), null), "AUTH_006");
        assertThat((BigDecimal) sessionRow(login.sessionId()).get("revoked_at")).isEqualByComparingTo(epochMicros(T3));
    }

    private record Login(long memberId, long sessionId, String sessionKey, String accessToken, String refreshToken) {
    }

    private record Tokens(String accessToken, String refreshToken) {
    }

    /** 실제 익명 인증 서비스로 회원·세션·Refresh를 만들고 토큰의 sid에 해당하는 세션 PK를 찾는다. */
    private Login login(String code) throws Exception {
        AnonymousAuthResponse response = anonymousAuthService.authenticate(code);
        String sessionKey = SignedJWT.parse(response.accessToken()).getJWTClaimsSet().getStringClaim("sid");
        Long sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE session_key = ?", Long.class,
                sessionKey);
        return new Login(Long.parseLong(response.member().memberId()), sessionId, sessionKey, response.accessToken(),
                response.refreshToken());
    }

    private ResultActions refresh(String refreshToken, String authorization) throws Exception {
        var request = post(REFRESH).contentType("application/json")
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mockMvc.perform(request);
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + accessToken));
    }

    private static Tokens tokens(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new Tokens(JsonPath.read(body, "$.data.accessToken"), JsonPath.read(body, "$.data.refreshToken"));
    }

    /** Refresh가 바꿀 수 있는 세션 값(UTC epoch는 UNIX_TIMESTAMP로 읽어 JVM 시간대와 무관하다). */
    private Map<String, Object> sessionRow(long sessionId) {
        return jdbc.queryForMap("SELECT session_key, UNIX_TIMESTAMP(expires_at) AS expires_at, UNIX_TIMESTAMP(revoked_at) "
                + "AS revoked_at, revoke_reason, current_refresh_generation, updated_at FROM auth_sessions WHERE id = ?",
                sessionId);
    }

    /** 세션의 Refresh 이력(세대 순). */
    private List<Map<String, Object>> tokenRows(long sessionId) {
        return jdbc.queryForList("SELECT id, generation, HEX(token_hash) AS hash, LENGTH(token_hash) AS hash_length, "
                + "UNIX_TIMESTAMP(expires_at) AS expires_at, UNIX_TIMESTAMP(consumed_at) AS consumed_at, updated_at "
                + "FROM auth_refresh_tokens WHERE session_id = ? ORDER BY generation", sessionId);
    }

    /** 회전·폐기가 바꾸는 값 전체(세션·Refresh 이력)와 바꾸면 안 되는 회원 값. */
    private Map<String, Object> state(Login login) {
        return Map.of("session", sessionRow(login.sessionId()), "tokens", tokenRows(login.sessionId()),
                "member", memberRow(login.memberId()));
    }

    private Map<String, Object> memberRow(long memberId) {
        return jdbc.queryForMap("SELECT status, onboarding_completed_at, last_login_at, updated_at FROM members "
                + "WHERE id = ?", memberId);
    }

    private long tokenId(long sessionId, int generation) {
        return jdbc.queryForObject("SELECT id FROM auth_refresh_tokens WHERE session_id = ? AND generation = ?",
                Long.class, sessionId, generation);
    }

    private int countByHash(byte[] hash) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens WHERE token_hash = ?", Integer.class,
                (Object) hash);
    }

    private Instant sessionExpiresAt(Login login) {
        return instant(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expires_at) FROM auth_sessions WHERE id = ?",
                BigDecimal.class, login.sessionId()));
    }

    /** 테스트 쪽 트랜잭션으로 행을 잠근다. 요청이 기다리는 동안 상태를 바꾸고 commit해 잠금을 넘긴다. */
    private Connection lockRow(String table, long id) throws SQLException {
        Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        execute(holder, "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", id);
        return holder;
    }

    private static void deleteSessions(Connection holder, long memberId) throws SQLException {
        execute(holder, "DELETE t FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                + "WHERE s.member_id = ?", memberId);
        execute(holder, "DELETE FROM auth_sessions WHERE member_id = ?", memberId);
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }

    /** 이번 DB의 해당 테이블에서 레코드 잠금 대기가 expected개 이상 생길 때까지 기다린다(대기 없이 끝나면 실패). */
    private void awaitLockWaits(String table, int expected, Future<?>... requests) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (Future<?> request : requests) {
                if (request.isDone()) {
                    request.get();
                    fail("잠금 대기 없이 끝났다");
                }
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = w.ENGINE AND requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = ?
                    """, Integer.class, table);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        fail(table + " 행 잠금 대기가 " + expected + "개 생기지 않았다");
    }

    private static void expectRefreshError(ResultActions result, String code) throws Exception {
        expectRefreshError(result, code, 401);
    }

    /** 인증 체인 오류: 캐시 금지, WWW-Authenticate 없음. */
    private static void expectRefreshError(ResultActions result, String code, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** 보호 체인의 401(세션 필터): WWW-Authenticate: Bearer. */
    private static void expectSecurityError(ResultActions result, String code) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String hex(String refreshToken) {
        return HexFormat.of().withUpperCase().formatHex(sha256(refreshToken.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** DB TIMESTAMP(6)에 저장될 UTC epoch 초(마이크로초 자리까지). */
    private static BigDecimal epochMicros(Instant instant) {
        Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
        return BigDecimal.valueOf(micros.getEpochSecond()).add(BigDecimal.valueOf(micros.getNano() / 1_000, 6));
    }

    private static Instant instant(BigDecimal epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds.longValue(),
                epochSeconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
    }

    private static String databaseName(String jdbcUrl) {
        String path = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        path = path.substring(path.indexOf('/') + 1);
        int end = path.indexOf('?');
        return end < 0 ? path : path.substring(0, end);
    }

    /** 테스트가 정하는 UTC Clock. */
    static final class MutableClock extends Clock {

        private volatile Instant instant = T0;

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 테스트 전용 DataSource 래퍼. 켤 때만 동작한다.
     *
     * <ul>
     *   <li>{@link #FAIL_COMMIT}: 그 세션의 세대 증가나 폐기가 같은 커넥션에 flush돼 있는 JDBC commit 한 번을 실패시킨다(변경이 없는
     *   다른 commit은 통과).</li>
     *   <li>{@link #FAIL_TOKEN_UPDATE}: flush 중 Refresh 행 UPDATE 준비 한 번을 실패시킨다(flush 실패).</li>
     *   <li>두 경우 모두 실패 직전 그 커넥션에서 본 {세대, 토큰 수, 폐기 여부}를 {@link #SEEN_BEFORE_FAILURE}에 남긴다.</li>
     *   <li>{@link #SHORT_LOCK_WAIT}: 켜진 동안 빌린 커넥션의 innodb_lock_wait_timeout을 1초로 두고 반납 전에 기본값으로 되돌린다.</li>
     *   <li>{@link #FAIL_CONNECTION}: 켜진 동안 연결 획득을 일시 장애로 실패시킨다.</li>
     * </ul>
     */
    static final class FaultInjectingDataSource extends DelegatingDataSource {

        static final AtomicReference<Target> FAIL_COMMIT = new AtomicReference<>();
        static final AtomicReference<Target> FAIL_TOKEN_UPDATE = new AtomicReference<>();
        static final AtomicReference<List<Integer>> SEEN_BEFORE_FAILURE = new AtomicReference<>();
        static final AtomicBoolean SHORT_LOCK_WAIT = new AtomicBoolean();
        static final AtomicBoolean FAIL_CONNECTION = new AtomicBoolean();

        FaultInjectingDataSource(DataSource target) {
            super(target);
        }

        static void reset() {
            FAIL_COMMIT.set(null);
            FAIL_TOKEN_UPDATE.set(null);
            SEEN_BEFORE_FAILURE.set(null);
            SHORT_LOCK_WAIT.set(false);
            FAIL_CONNECTION.set(false);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (FAIL_CONNECTION.get()) {
                throw new SQLTransientConnectionException("test outage");
            }
            Connection connection = super.getConnection();
            boolean shortLockWait = SHORT_LOCK_WAIT.get();
            if (shortLockWait) {
                execute(connection, "SET SESSION innodb_lock_wait_timeout = 1");
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            failOnce(FAIL_COMMIT, connection, true, "injected commit failure");
                        } else if ("prepareStatement".equals(method.getName())
                                && ((String) args[0]).regionMatches(true, 0, "update auth_refresh_tokens", 0, 26)) {
                            failOnce(FAIL_TOKEN_UPDATE, connection, false, "injected flush failure");
                        } else if ("close".equals(method.getName()) && shortLockWait && !connection.isClosed()) {
                            execute(connection, "SET SESSION innodb_lock_wait_timeout = DEFAULT");
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        /**
         * 대상이 켜져 있으면 이 커넥션에서 본 그 세션의 {세대, 토큰 수, 폐기 여부}를 남기고 한 번 실패시킨다. {@code onlyIfChanged}면
         * 세대가 commit된 값과 다르거나 폐기가 반영된 커넥션에서만 실패시킨다.
         */
        private static void failOnce(AtomicReference<Target> trigger, Connection connection, boolean onlyIfChanged,
                                     String message) throws SQLException {
            Target target = trigger.get();
            if (target == null) {
                return;
            }
            List<Integer> seen = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT current_refresh_generation, "
                    + "(SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = s.id), revoked_at IS NOT NULL "
                    + "FROM auth_sessions s WHERE s.id = ?")) {
                statement.setLong(1, target.sessionId());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return;
                    }
                    for (int i = 1; i <= 3; i++) {
                        seen.add(rs.getInt(i));
                    }
                }
            }
            boolean changed = seen.get(0) != target.committedGeneration() || seen.get(2) == 1;
            if ((changed || !onlyIfChanged) && trigger.compareAndSet(target, null)) {
                SEEN_BEFORE_FAILURE.set(seen);
                throw new SQLException(message);
            }
        }
    }

    /** 장애를 주입할 세션과 그 세션의 commit된 세대. */
    record Target(long sessionId, int committedGeneration) {
    }
}
