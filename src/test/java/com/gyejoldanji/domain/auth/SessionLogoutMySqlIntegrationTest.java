package com.gyejoldanji.domain.auth;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.gyejoldanji.domain.auth.TokenRefreshMySqlIntegrationTest.FaultInjectingDataSource;
import com.gyejoldanji.domain.auth.TokenRefreshMySqlIntegrationTest.MutableClock;
import com.gyejoldanji.domain.auth.TokenRefreshMySqlIntegrationTest.Target;
import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
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
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureJdbc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL과 운영 Security 체인으로 POST /api/auth/logout을 검증한다(작업 07: T16~T19·T30·T32의 logout 부분).
 *
 * <p>익명 인증 서비스가 실제 DB에 만든 세션·Refresh와 실제 RSA 서명 Access 토큰으로 요청 → 해시 hint → 회원·세션·토큰 행 잠금 →
 * commit까지 확인한다. 토스 교환만 대체한다. 컨텍스트 구성·DB 속성·장애 주입 DataSource·조절 가능한 Clock은
 * {@link TokenRefreshMySqlIntegrationTest}의 것을 그대로 쓴다(전용 DB·{@code ddl-auto=validate}·JVM과 legacy URL 시간대
 * Asia/Seoul). {@code AUTH_IT_DB_URL}이 있을 때만 실행한다.
 *
 * <p>잠금 대기는 테스트 쪽 트랜잭션이 행을 {@code FOR UPDATE}로 잡은 상태에서 요청을 보내고 performance_schema의 실제 대기로 확인한다.
 * commit 실패는 flush가 끝난 뒤 JDBC commit 호출 직전의 명확한 실패이며, DB commit 뒤 응답만 잃은(결과 불명확) 상황은 다루지 않는다.
 * 실제 토큰·해시는 실패 메시지에도 나오지 않게 로그 검사를 boolean으로 한다.
 */
@SpringBootTest(classes = TokenRefreshMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class SessionLogoutMySqlIntegrationTest {

    private static final String PREFIX = "it07-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final String LOGOUT = "/api/auth/logout";
    private static final String LOGGED_OUT = "{\"success\":true,\"code\":\"200\",\"message\":\"세션이 종료되었습니다.\"}";
    /** 로그인 시각. 이후 시각은 Access 유효 시간(15분) 안에 둔다. 모두 나노초가 있다. */
    private static final Instant T0 = Instant.parse("2026-10-04T05:04:05.123456789Z");
    private static final Instant T1 = Instant.parse("2026-10-04T05:06:07.987654321Z");
    private static final Instant T2 = Instant.parse("2026-10-04T05:07:08.000000999Z");
    private static final Instant T3 = Instant.parse("2026-10-04T05:08:09.000001001Z");
    private static final Duration WAIT = Duration.ofSeconds(15);

    @MockitoBean
    private TossAnonymousAuthClient tossClient;
    @Autowired
    private AnonymousAuthService anonymousAuthService;
    @Autowired
    private ServiceTokenService tokenService;
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

    /** Refresh MySQL 테스트와 같은 전용 DB·시간대·보안 설정. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        TokenRefreshMySqlIntegrationTest.properties(registry);
    }

    @AfterAll
    static void restoreTimeZone() {
        TokenRefreshMySqlIntegrationTest.restoreTimeZone();
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
     * T16·T18: Access 없이 body Refresh로 그 세션만 LOGOUT·잠금 뒤 나노초 Clock → UTC 마이크로초로 폐기하고 정확한 성공 JSON을 준다(KST
     * JVM). 세션 키·만료·세대·Refresh 이력·회원 값은 그대로이며 행을 지우지 않는다. 이후 그 세션의 Access 보호 요청과 Refresh는
     * AUTH_003이고, 같은 회원의 다른 세션과 다른 회원은 그대로 Access·Refresh를 쓸 수 있다.
     */
    @Test
    void endsOnlyThatSession(CapturedOutput output) throws Exception {
        Login login = login("main");
        Login sibling = login("main");
        Login stranger = login("stranger");
        assertThat(sibling.memberId()).isEqualTo(login.memberId());
        Map<String, Object> session = sessionRow(login.sessionId());
        List<Map<String, Object>> tokens = tokenRows(login.sessionId());
        Map<String, Object> member = memberRow(login.memberId());
        Map<String, Object> siblingState = state(sibling);
        Map<String, Object> strangerState = state(stranger);
        List<Integer> counts = counts();
        clock.set(T1);

        expectLoggedOut(logout(login.refreshToken(), null));

        Map<String, Object> revoked = sessionRow(login.sessionId());
        assertRevoked(revoked, "LOGOUT", T1);
        assertThat(withoutRevocation(revoked)).isEqualTo(withoutRevocation(session));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(revoked_at, '%Y-%m-%dT%H:%i:%s.%f') FROM auth_sessions "
                + "WHERE id = ?", String.class, login.sessionId())).isEqualTo("2026-10-04T05:06:07.987654");
        assertThat(tokenRows(login.sessionId())).as("Refresh 이력 불변").isEqualTo(tokens);
        assertThat(memberRow(login.memberId())).as("회원 불변").isEqualTo(member);
        assertThat(state(sibling)).as("같은 회원의 다른 세션 불변").isEqualTo(siblingState);
        assertThat(state(stranger)).as("다른 회원 불변").isEqualTo(strangerState);
        assertThat(counts()).as("행 삭제·추가 없음").isEqualTo(counts);

        clock.set(T2);
        expectSecurityError(me(login.accessToken()), "AUTH_003");
        expectAuthError(refresh(login.refreshToken()), "AUTH_003", 401);
        assertThat(sessionRow(login.sessionId())).isEqualTo(revoked);

        me(sibling.accessToken()).andExpect(status().isOk());
        me(stranger.accessToken()).andExpect(status().isOk());
        String rotated = refreshToken(refresh(sibling.refreshToken()).andExpect(status().isOk()).andReturn());
        refresh(stranger.refreshToken()).andExpect(status().isOk());
        assertNotLogged(output, login.refreshToken(), login.accessToken(), hex(login.refreshToken()), rotated);
    }

    /** T16: 같은 토큰으로 다시 종료해도 200이고 최초 폐기 시각·사유를 유지하며 세션 행을 다시 쓰지 않는다. */
    @Test
    void repeatedLogoutKeepsFirstRevocation() throws Exception {
        Login login = login("repeat");
        clock.set(T1);
        expectLoggedOut(logout(login.refreshToken(), null));
        Map<String, Object> first = state(login);
        clock.set(T2);

        expectLoggedOut(logout(login.refreshToken(), null));
        expectLoggedOut(logout(login.refreshToken(), null));

        assertThat(state(login)).isEqualTo(first);
        assertRevoked(sessionRow(login.sessionId()), "LOGOUT", T1);
    }

    /** T17·T36: Access가 만료됐거나 잘못된 Bearer가 붙어도 Bearer를 보지 않고 body Refresh로 종료한다. */
    @ParameterizedTest
    @ValueSource(strings = {"expired", "Bearer not-a-valid-token", "Basic dXNlcjpwYXNz"})
    void endsSessionRegardlessOfAccessToken(String authorization) throws Exception {
        Login login = login("access-" + authorization.hashCode());
        String header = authorization.equals("expired")
                ? "Bearer " + tokenService.issue(login.memberId(), login.sessionKey(), T0.minusSeconds(3_600),
                T0.plusSeconds(1_209_600)).accessToken()
                : authorization;
        clock.set(T1);

        expectLoggedOut(logout(login.refreshToken(), header));

        assertRevoked(sessionRow(login.sessionId()), "LOGOUT", T1);
    }

    /** Refresh에서는 거부되는 상태. 로그아웃에서는 모두 그 세션 종료 사유가 된다. */
    enum Allowed {
        /** 회전으로 소비된 과거 Refresh. */
        CONSUMED_TOKEN,
        /** 소비하지 않았지만 세션 세대와 다른 토큰(비정상 데이터). */
        GENERATION_MISMATCH,
        /** 토큰 행만 만료. */
        TOKEN_EXPIRED,
        /** 잠금 뒤 시각이 세션 만료를 지남. */
        SESSION_EXPIRED,
        BLOCKED,
        WITHDRAWN
    }

    /**
     * T16: 소비 이력·세대 불일치·토큰 만료·세션 만료·BLOCKED/WITHDRAWN 회원의 토큰도 그 세션을 LOGOUT으로 종료한다(200). 소비 시각·세대·
     * 토큰 이력·회원 상태는 바꾸지 않는다. 과거 토큰으로 종료하면 회전으로 받은 새 Access·Refresh도 이후 쓸 수 없다.
     */
    @ParameterizedTest
    @EnumSource(Allowed.class)
    void endsSessionInStatesRefreshRejects(Allowed state) throws Exception {
        Login login = login("allowed-" + state);
        Instant now = T2;
        String newAccess = null;
        String newRefresh = null;
        clock.set(T1);
        switch (state) {
            case CONSUMED_TOKEN -> {
                MvcResult rotated = refresh(login.refreshToken()).andExpect(status().isOk()).andReturn();
                newAccess = JsonPath.read(rotated.getResponse().getContentAsString(), "$.data.accessToken");
                newRefresh = refreshToken(rotated);
            }
            case GENERATION_MISMATCH -> jdbc.update("UPDATE auth_sessions SET current_refresh_generation = 1 WHERE id = ?",
                    login.sessionId());
            case TOKEN_EXPIRED -> jdbc.update("UPDATE auth_refresh_tokens SET expires_at = '2026-10-04 05:06:07.987654' "
                    + "WHERE session_id = ?", login.sessionId());
            case SESSION_EXPIRED -> now = sessionExpiresAt(login).plusSeconds(1);
            case BLOCKED, WITHDRAWN -> jdbc.update("UPDATE members SET status = ? WHERE id = ?", state.name(),
                    login.memberId());
        }
        Map<String, Object> session = sessionRow(login.sessionId());
        List<Map<String, Object>> tokens = tokenRows(login.sessionId());
        Map<String, Object> member = memberRow(login.memberId());
        clock.set(now);

        expectLoggedOut(logout(login.refreshToken(), null));

        Map<String, Object> revoked = sessionRow(login.sessionId());
        assertRevoked(revoked, "LOGOUT", now);
        assertThat(withoutRevocation(revoked)).isEqualTo(withoutRevocation(session));
        assertThat(tokenRows(login.sessionId())).isEqualTo(tokens);
        assertThat(memberRow(login.memberId())).isEqualTo(member);
        if (newAccess != null) {
            clock.set(T3);
            expectSecurityError(me(newAccess), "AUTH_003");
            expectAuthError(refresh(newRefresh), "AUTH_003", 401);
        }
    }

    /**
     * T16: 재사용 탐지로 이미 폐기된 세션은 과거·회전 토큰 어느 쪽으로 종료해도 200이며 REFRESH_REUSE와 최초 폐기 시각을 덮어쓰지 않는다.
     */
    @Test
    void reuseRevokedSessionKeepsReuseReason() throws Exception {
        Login login = login("reuse");
        clock.set(T1);
        String rotated = refreshToken(refresh(login.refreshToken()).andExpect(status().isOk()).andReturn());
        clock.set(T2);
        expectAuthError(refresh(login.refreshToken()), "AUTH_006", 401);
        Map<String, Object> before = state(login);
        clock.set(T3);

        expectLoggedOut(logout(login.refreshToken(), null));
        expectLoggedOut(logout(rotated, null));

        assertThat(state(login)).isEqualTo(before);
        assertRevoked(sessionRow(login.sessionId()), "REFRESH_REUSE", T2);
    }

    /** T16: 형식은 맞지만 발급하지 않은 Refresh도 200이며 어떤 행도 바꾸지 않는다. */
    @Test
    void unknownTokenIsOkWithoutChanges() throws Exception {
        Login login = login("unknown");
        Map<String, Object> before = state(login);
        List<Integer> counts = counts();
        clock.set(T1);

        expectLoggedOut(logout("Z".repeat(43), null));
        expectLoggedOut(logout(login.refreshToken().toLowerCase(Locale.ROOT), null));

        assertThat(state(login)).isEqualTo(before);
        assertThat(counts()).isEqualTo(counts);
        me(login.accessToken()).andExpect(status().isOk());
    }

    /**
     * hint를 얻은 뒤 잠금을 기다리는 동안 사라지는 행. 회원·세션 삭제는 회원 → 세션 순으로 잠근 쪽이, 토큰 삭제는 정리 작업처럼 세션을
     * 잠근 쪽이 한다(Refresh MySQL 테스트에서 관측한 해시 인덱스 deadlock을 피하는 실제 순서).
     */
    enum Gone {
        MEMBER("members"),
        SESSION("auth_sessions"),
        TOKEN("auth_sessions");

        final String lockedTable;

        Gone(String lockedTable) {
            this.lockedTable = lockedTable;
        }
    }

    /**
     * T32: 잠금을 기다리는 동안 회원·세션·토큰 행이 사라지면 그 자리에서 바꾸지 않고 200이다. 토큰만 사라진 세션도 폐기하지 않고(대상
     * 없음) 다른 회원 행은 그대로다.
     */
    @ParameterizedTest
    @EnumSource(Gone.class)
    void rowGoneWhileWaitingForLockIsOk(Gone gone) throws Exception {
        Login login = login("gone-" + gone);
        Login stranger = login("gone-stranger-" + gone);
        Map<String, Object> strangerState = state(stranger);
        clock.set(T1);

        try (Connection holder = lockRow(gone.lockedTable, gone == Gone.MEMBER ? login.memberId() : login.sessionId())) {
            Future<ResultActions> request = pool.submit(() -> logout(login.refreshToken(), null));
            awaitLockWaits(gone.lockedTable, 1, request);
            switch (gone) {
                case MEMBER -> {
                    deleteSessions(holder, login.memberId());
                    execute(holder, "DELETE FROM members WHERE id = ?", login.memberId());
                }
                case SESSION -> deleteSessions(holder, login.memberId());
                case TOKEN -> execute(holder, "DELETE FROM auth_refresh_tokens WHERE session_id = ?", login.sessionId());
            }
            holder.commit();
            expectLoggedOut(request.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }

        assertThat(tokenRows(login.sessionId())).isEmpty();
        int sessions = jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id = ?", Integer.class,
                login.sessionId());
        assertThat(sessions).isEqualTo(gone == Gone.TOKEN ? 1 : 0);
        if (gone == Gone.TOKEN) {
            assertThat(sessionRow(login.sessionId())).containsEntry("revoked_at", null).containsEntry("revoke_reason", null);
        }
        assertThat(state(stranger)).isEqualTo(strangerState);
    }

    /** 잠금을 기다리는 동안 생기는 변경. 회원 상태·세션 폐기는 회원 → 세션 순서로 잠근 트랜잭션이 바꾼다. */
    enum Change {
        EXPIRED_WHILE_MEMBER_LOCKED("members"),
        /** 마지막(토큰) 잠금 뒤에 시각을 읽는지 확인한다. */
        EXPIRED_WHILE_TOKEN_LOCKED("auth_refresh_tokens"),
        BLOCKED("members"),
        REUSE_REVOKED("members");

        final String lockedTable;

        Change(String lockedTable) {
            this.lockedTable = lockedTable;
        }
    }

    /**
     * T32: 잠금 대기 중 세션 만료에 도달하거나 회원이 차단돼도 종료를 거부하지 않고 마지막 잠금 뒤 시각으로 LOGOUT 폐기한다. 대기 중 다른
     * 트랜잭션이 먼저 폐기(REFRESH_REUSE)했으면 그 최초값을 유지한 채 200이다.
     */
    @ParameterizedTest
    @EnumSource(Change.class)
    void changesWhileWaitingForLockDoNotPreventLogout(Change change) throws Exception {
        Login login = login("wait-" + change);
        clock.set(T1);
        Instant afterLock = switch (change) {
            case EXPIRED_WHILE_MEMBER_LOCKED, EXPIRED_WHILE_TOKEN_LOCKED -> sessionExpiresAt(login).plusNanos(1_000_999);
            default -> T2;
        };
        long lockedId = change.lockedTable.equals("members") ? login.memberId() : tokenId(login.sessionId(), 0);

        try (Connection holder = lockRow(change.lockedTable, lockedId)) {
            Future<ResultActions> request = pool.submit(() -> logout(login.refreshToken(), null));
            awaitLockWaits(change.lockedTable, 1, request);
            switch (change) {
                case BLOCKED -> execute(holder, "UPDATE members SET status = 'BLOCKED' WHERE id = ?", login.memberId());
                case REUSE_REVOKED -> {
                    execute(holder, "SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE", login.sessionId());
                    execute(holder, "UPDATE auth_sessions SET revoked_at = '2026-10-04 05:06:30.000001', revoke_reason = "
                            + "'REFRESH_REUSE' WHERE id = ?", login.sessionId());
                }
                default -> {
                }
            }
            clock.set(afterLock);
            holder.commit();
            expectLoggedOut(request.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        }

        Map<String, Object> session = sessionRow(login.sessionId());
        if (change == Change.REUSE_REVOKED) {
            assertRevoked(session, "REFRESH_REUSE", Instant.parse("2026-10-04T05:06:30.000001Z"));
        } else {
            assertRevoked(session, "LOGOUT", afterLock);
        }
        assertThat(memberRow(login.memberId())).containsEntry("status", change == Change.BLOCKED ? "BLOCKED" : "ACTIVE");
    }

    /**
     * T19 A: 로그아웃이 회원·세션을 잠그고 토큰 잠금을 기다리는 중에 같은 토큰의 Refresh가 오면 회원 잠금에서 기다린다. 로그아웃이 폐기를
     * commit한 뒤 Refresh는 AUTH_003이며 회전 흔적(소비·세대·새 행)이 없다.
     */
    @Test
    void logoutFirstThenRefreshIsRejected() throws Exception {
        Login login = login("race-logout-first");
        clock.set(T1);

        ResultActions loggedOut;
        ResultActions refreshed;
        try (Connection holder = lockRow("auth_refresh_tokens", tokenId(login.sessionId(), 0))) {
            Future<ResultActions> logout = pool.submit(() -> logout(login.refreshToken(), null));
            awaitLockWaits("auth_refresh_tokens", 1, logout);
            Future<ResultActions> refresh = pool.submit(() -> refresh(login.refreshToken()));
            awaitLockWaits("members", 1, logout, refresh);
            clock.set(T2);
            holder.commit();
            loggedOut = logout.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            refreshed = refresh.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        expectLoggedOut(loggedOut);
        expectAuthError(refreshed, "AUTH_003", 401);
        Map<String, Object> session = sessionRow(login.sessionId());
        assertRevoked(session, "LOGOUT", T2);
        assertThat(session).containsEntry("current_refresh_generation", 0);
        assertThat(tokenRows(login.sessionId())).hasSize(1).first()
                .satisfies(row -> assertThat(row.get("consumed_at")).isNull());
        expectSecurityError(me(login.accessToken()), "AUTH_003");
    }

    /**
     * T19 B: Refresh가 회원·세션을 잠그고 토큰 잠금을 기다리는 중에 회전 전 토큰으로 로그아웃하면 회원 잠금에서 기다린다. Refresh가 회전을
     * commit한 뒤 로그아웃은 소비된 그 토큰으로도 세션을 종료한다(200). 먼저 발급된 새 Access·Refresh도 이후 쓸 수 없다.
     */
    @Test
    void refreshFirstThenLogoutStillEndsSession() throws Exception {
        Login login = login("race-refresh-first");
        clock.set(T1);

        ResultActions refreshed;
        ResultActions loggedOut;
        try (Connection holder = lockRow("auth_refresh_tokens", tokenId(login.sessionId(), 0))) {
            Future<ResultActions> refresh = pool.submit(() -> refresh(login.refreshToken()));
            awaitLockWaits("auth_refresh_tokens", 1, refresh);
            Future<ResultActions> logout = pool.submit(() -> logout(login.refreshToken(), null));
            awaitLockWaits("members", 1, refresh, logout);
            clock.set(T2);
            holder.commit();
            refreshed = refresh.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            loggedOut = logout.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        MvcResult rotated = refreshed.andExpect(status().isOk()).andReturn();
        expectLoggedOut(loggedOut);
        Map<String, Object> session = sessionRow(login.sessionId());
        assertRevoked(session, "LOGOUT", T2);
        assertThat(session).containsEntry("current_refresh_generation", 1);
        List<Map<String, Object>> rows = tokenRows(login.sessionId());
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("consumed_at")).isEqualByComparingTo(epochMicros(T2));
        assertThat(rows.get(1).get("consumed_at")).isNull();

        clock.set(T3);
        String body = rotated.getResponse().getContentAsString();
        expectSecurityError(me(JsonPath.read(body, "$.data.accessToken")), "AUTH_003");
        expectAuthError(refresh(JsonPath.read(body, "$.data.refreshToken")), "AUTH_003", 401);
        expectSecurityError(me(login.accessToken()), "AUTH_003");
    }

    /**
     * 회원 행 잠금을 기다리다 MySQL lock wait timeout(테스트에서 1초)이 나면 200이 아니라 503 COMMON_008이고 폐기가 없다(자동 재시도
     * 없음). 잠금이 풀린 뒤 다시 요청하면 종료된다.
     */
    @Test
    void lockWaitTimeoutIsServiceUnavailable() throws Exception {
        Login login = login("lock-timeout");
        Map<String, Object> before = state(login);
        clock.set(T1);

        try (Connection holder = lockRow("members", login.memberId())) {
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(true);
            Future<ResultActions> request = pool.submit(() -> logout(login.refreshToken(), null));
            awaitLockWaits("members", 1, request);
            expectAuthError(request.get(WAIT.toSeconds(), TimeUnit.SECONDS), "COMMON_008", 503);
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(false);
            holder.rollback();
        }

        assertThat(state(login)).isEqualTo(before);
        clock.set(T2);
        expectLoggedOut(logout(login.refreshToken(), null));
        assertRevoked(sessionRow(login.sessionId()), "LOGOUT", T2);
    }

    /** DB 연결을 얻지 못하면 200(이미 없음)이 아니라 503 COMMON_008이고 아무것도 바꾸지 않는다. */
    @Test
    void connectionOutageIsServiceUnavailable() throws Exception {
        Login login = login("outage");
        Map<String, Object> before = state(login);
        clock.set(T1);

        FaultInjectingDataSource.FAIL_CONNECTION.set(true);
        ResultActions result = logout(login.refreshToken(), null);
        FaultInjectingDataSource.FAIL_CONNECTION.set(false);

        expectAuthError(result, "COMMON_008", 503);
        assertThat(state(login)).isEqualTo(before);
    }

    /**
     * T30·T26: flush의 세션 UPDATE를 MySQL이 거부하면(TIMESTAMP 범위 밖 폐기 시각 — STRICT 모드의 실제 DB 오류) 200이나 일반 409가
     * 아니라 500 COMMON_003이고 폐기가 남지 않는다. 메시지 없는 오류 진단은 남고 SQL 오류 원문·토큰·해시는 로그에 없다.
     */
    @Test
    void flushFailureIsInternalErrorWithoutRevocation(CapturedOutput output) throws Exception {
        Login login = login("flush-fail");
        Map<String, Object> before = state(login);
        clock.set(Instant.parse("2040-01-01T00:00:00.000001Z"));

        expectAuthError(logout(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(state(login)).isEqualTo(before);
        String logs = output.getAll();
        assertThat(logs.contains("로그아웃 트랜잭션 실패")).as("메시지 없는 오류 진단은 남음").isTrue();
        assertThat(logs.contains(DataIntegrityViolationException.class.getName())).as("일반 409 대상 타입이어도 500").isTrue();
        assertThat(logs.contains("MysqlDataTruncation")).as("MySQL이 UPDATE를 거부한 원인 타입은 남음").isTrue();
        assertThat(logs.contains("Incorrect datetime value")).as("SQL 오류 원문 없음").isFalse();
        assertNotLogged(output, login.refreshToken(), hex(login.refreshToken()));
        clock.set(T1);
        expectLoggedOut(logout(login.refreshToken(), null));
    }

    /**
     * T30: flush가 끝나 같은 커넥션에 폐기가 반영된 뒤 JDBC commit이 실패하면 500이고(200 아님), 새 조회에서 폐기가 없다. 장애가 사라진 뒤
     * 다시 요청하면 그때의 시각으로 종료된다.
     */
    @Test
    void commitFailureAfterFlushLeavesSessionActive(CapturedOutput output) throws Exception {
        Login login = login("commit-fail");
        Map<String, Object> before = state(login);
        clock.set(T1);
        FaultInjectingDataSource.FAIL_COMMIT.set(new Target(login.sessionId(), 0));

        expectAuthError(logout(login.refreshToken(), null), "COMMON_003", 500);

        assertThat(FaultInjectingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션의 세대·토큰 수·폐기")
                .containsExactly(0, 1, 1);
        assertThat(state(login)).isEqualTo(before);
        me(login.accessToken()).andExpect(status().isOk());
        clock.set(T2);
        expectLoggedOut(logout(login.refreshToken(), null));
        assertRevoked(sessionRow(login.sessionId()), "LOGOUT", T2);
        assertThat(output.getAll().contains("injected commit failure")).as("예외 메시지 없음").isFalse();
        assertNotLogged(output, login.refreshToken());
    }

    private record Login(long memberId, long sessionId, String sessionKey, String accessToken, String refreshToken) {
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

    private ResultActions logout(String refreshToken, String authorization) throws Exception {
        var request = post(LOGOUT).contentType("application/json")
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mockMvc.perform(request);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + accessToken));
    }

    private static String refreshToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.refreshToken");
    }

    /** 로그아웃이 바꿀 수 있는 세션 값(UTC epoch는 UNIX_TIMESTAMP로 읽어 JVM 시간대와 무관하다). */
    private Map<String, Object> sessionRow(long sessionId) {
        return jdbc.queryForMap("SELECT session_key, UNIX_TIMESTAMP(expires_at) AS expires_at, UNIX_TIMESTAMP(revoked_at) "
                + "AS revoked_at, revoke_reason, current_refresh_generation, updated_at FROM auth_sessions WHERE id = ?",
                sessionId);
    }

    /** 세션의 Refresh 이력(세대 순). */
    private List<Map<String, Object>> tokenRows(long sessionId) {
        return jdbc.queryForList("SELECT id, generation, HEX(token_hash) AS hash, UNIX_TIMESTAMP(expires_at) AS expires_at, "
                + "UNIX_TIMESTAMP(consumed_at) AS consumed_at, updated_at FROM auth_refresh_tokens WHERE session_id = ? "
                + "ORDER BY generation", sessionId);
    }

    private Map<String, Object> memberRow(long memberId) {
        return jdbc.queryForMap("SELECT status, onboarding_completed_at, last_login_at, updated_at FROM members "
                + "WHERE id = ?", memberId);
    }

    /** 세션·Refresh 이력·회원 값 전체. */
    private Map<String, Object> state(Login login) {
        return Map.of("session", sessionRow(login.sessionId()), "tokens", tokenRows(login.sessionId()),
                "member", memberRow(login.memberId()));
    }

    /** 이번 실행의 회원·세션·Refresh 행 수. */
    private List<Integer> counts() {
        String like = PREFIX + "%";
        return List.of(
                jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id LIKE ?", Integer.class, like),
                jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                        + "WHERE m.provider_user_id LIKE ?", Integer.class, like),
                jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                        + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id LIKE ?", Integer.class, like));
    }

    private long tokenId(long sessionId, int generation) {
        return jdbc.queryForObject("SELECT id FROM auth_refresh_tokens WHERE session_id = ? AND generation = ?",
                Long.class, sessionId, generation);
    }

    private Instant sessionExpiresAt(Login login) {
        BigDecimal epoch = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expires_at) FROM auth_sessions WHERE id = ?",
                BigDecimal.class, login.sessionId());
        return Instant.ofEpochSecond(epoch.longValue(), epoch.remainder(BigDecimal.ONE).movePointRight(9).longValue());
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

    /** 정확한 성공 JSON(data·토큰 없음)과 캐시 금지 헤더. */
    private static void expectLoggedOut(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(content().json(LOGGED_OUT, JsonCompareMode.STRICT));
    }

    /** 인증 체인 오류: 캐시 금지, WWW-Authenticate 없음. */
    private static void expectAuthError(ResultActions result, String code, int status) throws Exception {
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

    private static void assertRevoked(Map<String, Object> session, String reason, Instant at) {
        assertThat(session.get("revoke_reason")).isEqualTo(reason);
        assertThat((BigDecimal) session.get("revoked_at")).isEqualByComparingTo(epochMicros(at));
    }

    /** 폐기 값과 그때 함께 바뀌는 updated_at을 뺀 세션 값(키·만료·세대). */
    private static Map<String, Object> withoutRevocation(Map<String, Object> session) {
        Map<String, Object> rest = new HashMap<>(session);
        rest.keySet().removeAll(List.of("revoked_at", "revoke_reason", "updated_at"));
        return rest;
    }

    /** 실제 토큰·해시(hex 대소문자 무관)는 실패 메시지에도 나오지 않게 boolean과 순번으로만 확인한다. */
    private static void assertNotLogged(CapturedOutput output, String... values) {
        String logs = output.getAll().toLowerCase(Locale.ROOT);
        for (int i = 0; i < values.length; i++) {
            assertThat(logs.contains(values[i].toLowerCase(Locale.ROOT))).as("민감값 %d 로그 없음", i).isFalse();
        }
    }

    private static String hex(String refreshToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** DB TIMESTAMP(6)에 저장될 UTC epoch 초(마이크로초 자리까지). */
    private static BigDecimal epochMicros(Instant instant) {
        Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
        return BigDecimal.valueOf(micros.getEpochSecond()).add(BigDecimal.valueOf(micros.getNano() / 1_000, 6));
    }

    private static String databaseName(String jdbcUrl) {
        String path = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        path = path.substring(path.indexOf('/') + 1);
        int end = path.indexOf('?');
        return end < 0 ? path : path.substring(0, end);
    }
}
