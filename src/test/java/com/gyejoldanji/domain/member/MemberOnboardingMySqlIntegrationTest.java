package com.gyejoldanji.domain.member;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL과 운영 Security 체인으로 POST /api/members/me/onboarding/complete를 검증한다(작업 05: T11·T40의 서버 범위).
 *
 * <p>익명 인증 서비스가 실제 DB에 만든 세션과 실제 RSA 서명 Access 토큰으로 JWT → DB 세션 → CurrentMember → 회원·세션 행 잠금 →
 * commit까지 확인한다. 토스 교환만 대체한다. {@code AUTH_IT_DB_URL}이 있을 때만 실행하며 준비 조건은
 * {@link com.gyejoldanji.domain.auth.AuthPersistenceMySqlIntegrationTest}와 같다(전용 DB·마이그레이션 적용·performance_schema 잠금
 * 관측 권한). JVM과 legacy URL 시간대는 Asia/Seoul로 둔다.
 *
 * <p>잠금 대기는 테스트 쪽 트랜잭션이 행을 {@code FOR UPDATE}로 잡은 상태에서 요청을 보내고 performance_schema의 실제 대기로 확인한다.
 * commit 실패·짧은 잠금 대기 시간은 테스트 전용 DataSource 래퍼가 켤 때만 주입한다.
 */
@SpringBootTest(classes = MemberOnboardingMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class MemberOnboardingMySqlIntegrationTest {

    private static final String PREFIX = "it05-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final String COMPLETE = "/api/members/me/onboarding/complete";
    /** 로그인 시각. 이후 시각은 Access 유효 시간(15분) 안에 둔다. 모두 나노초가 있다. */
    private static final Instant T0 = Instant.parse("2026-10-04T03:04:05.123456789Z");
    private static final Instant T1 = Instant.parse("2026-10-04T03:05:05.987654321Z");
    private static final Instant T2 = Instant.parse("2026-10-04T03:06:06.000000999Z");
    private static final Instant T3 = Instant.parse("2026-10-04T03:07:07.000001001Z");
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static TimeZone originalTimeZone;

    @MockitoBean
    private TossAnonymousAuthClient tossClient;
    @Autowired
    private AnonymousAuthService anonymousAuthService;
    @Autowired
    private ServiceTokenService tokenService;
    @Autowired
    private TestClock clock;
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
    @Import({SecurityTestConfig.class, ServiceTokenService.class, AnonymousAuthService.class, MemberService.class,
            MemberController.class})
    static class Config {

        /** auditing·JWT 검증·세션 필터·서비스가 함께 쓰는 조절 가능한 Clock. */
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
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
     * T11: 본문 없는 첫 요청이 잠금 뒤 Clock(나노초)을 UTC 마이크로초로 저장하고 응답과 DB가 같다(KST JVM). me도 COMPLETED다. 반복
     * 요청과 같은 회원의 다른 유효 세션 요청은 최초 시각 그대로 200이며 회원 행을 다시 쓰지 않는다. 세션·Refresh는 바뀌지 않는다.
     */
    @Test
    void completesOnceAndKeepsFirstTimeAcrossRepeatsAndSessions(CapturedOutput output) throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        Login first = login("repeat");
        Map<String, Object> firstSession = sessionState(first);
        clock.set(T1);

        complete(first)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.memberId").value(String.valueOf(first.memberId())))
                .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));
        assertThat(completedAt(first.memberId())).isEqualByComparingTo(epochMicros(T1));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(onboarding_completed_at, '%Y-%m-%dT%H:%i:%s.%f') "
                + "FROM members WHERE id = ?", String.class, first.memberId())).isEqualTo("2026-10-04T03:05:05.987654");
        assertThat(sessionState(first)).as("세션·Refresh 불변").isEqualTo(firstSession);
        me(first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));

        Map<String, Object> member = memberState(first.memberId());
        clock.set(T2);
        complete(first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));
        assertThat(memberState(first.memberId())).as("반복 요청은 회원 행을 다시 쓰지 않는다").isEqualTo(member);

        Login second = login("repeat");
        assertThat(second.memberId()).isEqualTo(first.memberId());
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        member = memberState(second.memberId());
        Map<String, Object> secondSession = sessionState(second);
        clock.set(T3);
        complete(second).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));
        assertThat(memberState(second.memberId())).isEqualTo(member);
        assertThat(sessionState(first)).isEqualTo(firstSession);
        assertThat(sessionState(second)).isEqualTo(secondSession);
        assertThat(output.getAll()).doesNotContain(first.accessToken(), second.accessToken(), PREFIX + "repeat");
    }

    /**
     * 같은 회원의 두 세션 요청이 모두 회원 행 잠금을 기다리다 차례로 들어가도 같은 최초 시각으로 수렴한다. 잠금 뒤 Clock은 읽을 때마다
     * 흐르므로 두 번째 요청은 다른 시각을 읽지만 최초값을 덮지 않는다. 저장 시각은 필터 검사 시각(T1)이 아닌 잠금 뒤 시각이다.
     */
    @Test
    void concurrentRequestsConvergeOnFirstTime() throws Exception {
        Login a = login("concurrent");
        Login b = login("concurrent");
        clock.set(T1);

        String first;
        String second;
        try (Connection holder = lockRow("members", a.memberId())) {
            Future<ResultActions> requestA = pool.submit(() -> complete(a));
            Future<ResultActions> requestB = pool.submit(() -> complete(b));
            awaitLockWaits("members", 2, requestA, requestB);
            clock.set(T2);
            clock.tickEachRead(Duration.ofNanos(1_000_001));
            holder.commit();

            first = completedAtIn(requestA.get(WAIT.toSeconds(), TimeUnit.SECONDS).andExpect(status().isOk()));
            second = completedAtIn(requestB.get(WAIT.toSeconds(), TimeUnit.SECONDS).andExpect(status().isOk()));
        }
        assertThat(first).isEqualTo(second);
        Instant stored = Instant.parse(first);
        assertThat(stored).isAfter(T2);
        assertThat(completedAt(a.memberId())).isEqualByComparingTo(epochMicros(stored));
    }

    /**
     * 잠금을 기다리는 동안 생기는 변경과 테스트 쪽 트랜잭션이 잡는 행.
     *
     * <p>세션의 revoked_at은 FK 인덱스(member_id, revoked_at, expires_at)에 있어 바꿀 때 InnoDB가 부모 회원 행에 공유 잠금을 건다.
     * 그래서 폐기는 실제 로그아웃·Refresh처럼 회원 → 세션 순서로 잠근 트랜잭션이 한다(세션만 잡고 폐기하면 온보딩과 deadlock).
     * 세션 잠금 대기 중 변경은 FK 인덱스를 건드리지 않는 만료 시각 도달·세션 행 삭제(정리 작업)로 확인한다.
     */
    enum Change {
        EXPIRED_WHILE_MEMBER_LOCKED("members"),
        /** 마지막(세션) 잠금 뒤에 시각을 읽는지 확인한다. */
        EXPIRED_WHILE_SESSION_LOCKED("auth_sessions"),
        REVOKED("members"),
        REVOKED_AFTER_COMPLETION("members"),
        BLOCKED("members"),
        WITHDRAWN("members"),
        SESSION_DELETED("members"),
        SESSION_DELETED_WHILE_SESSION_LOCKED("auth_sessions"),
        MEMBER_DELETED("members");

        final String lockedTable;

        Change(String lockedTable) {
            this.lockedTable = lockedTable;
        }
    }

    /**
     * 필터를 통과한 요청이 회원 또는 세션 행 잠금을 기다리는 동안 만료 시각 도달·폐기·비활성화·행 삭제가 생기면, 잠금 뒤 재검사로
     * 401 AUTH_003이고 아무것도 저장하지 않는다. 이미 완료한 회원도 성공으로 넘기지 않는다.
     */
    @ParameterizedTest
    @EnumSource(Change.class)
    void rechecksChangesCommittedWhileWaitingForLock(Change change) throws Exception {
        Login login = login("wait-" + change);
        clock.set(T1);
        if (change == Change.REVOKED_AFTER_COMPLETION) {
            complete(login).andExpect(status().isOk());
            clock.set(T2);
        }
        BigDecimal before = completedAt(login.memberId());
        long lockedId = change.lockedTable.equals("members") ? login.memberId() : login.sessionId();

        try (Connection holder = lockRow(change.lockedTable, lockedId)) {
            Future<ResultActions> request = pool.submit(() -> complete(login));
            awaitLockWaits(change.lockedTable, 1, request);
            switch (change) {
                case EXPIRED_WHILE_MEMBER_LOCKED, EXPIRED_WHILE_SESSION_LOCKED ->
                        clock.set(sessionExpiresAt(login)); // now == expiresAt
                case REVOKED, REVOKED_AFTER_COMPLETION -> execute(holder, "UPDATE auth_sessions "
                        + "SET revoked_at = '2026-10-04 03:06:00', revoke_reason = 'LOGOUT' WHERE id = ?", login.sessionId());
                case BLOCKED, WITHDRAWN -> execute(holder, "UPDATE members SET status = ? WHERE id = ?", change.name(),
                        login.memberId());
                case SESSION_DELETED, SESSION_DELETED_WHILE_SESSION_LOCKED -> deleteSessions(holder, login.memberId());
                case MEMBER_DELETED -> {
                    deleteSessions(holder, login.memberId());
                    execute(holder, "DELETE FROM members WHERE id = ?", login.memberId());
                }
            }
            holder.commit();
            expectError(request.get(WAIT.toSeconds(), TimeUnit.SECONDS), 401, "AUTH_003");
        }

        Integer members = jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE id = ?", Integer.class,
                login.memberId());
        if (change == Change.MEMBER_DELETED) {
            assertThat(members).isZero();
            return;
        }
        assertThat(completedAt(login.memberId())).as("완료 시각 불변").isEqualTo(before);
        switch (change) {
            case REVOKED, REVOKED_AFTER_COMPLETION -> assertThat(jdbc.queryForObject(
                    "SELECT revoke_reason FROM auth_sessions WHERE id = ?", String.class, login.sessionId()))
                    .isEqualTo("LOGOUT");
            case BLOCKED, WITHDRAWN -> assertThat(jdbc.queryForObject("SELECT status FROM members WHERE id = ?",
                    String.class, login.memberId())).isEqualTo(change.name());
            default -> {
            }
        }
    }

    /**
     * 회원 행 잠금을 기다리다 MySQL lock wait timeout(테스트에서 1초)이 나면 503 COMMON_008이고 저장·세션 변경이 없다. 잠금이 풀린 뒤
     * 같은 Access로 다시 요청하면 성공한다(자동 재시도는 없다).
     */
    @Test
    void lockWaitTimeoutIsServiceUnavailableAndRetryable(CapturedOutput output) throws Exception {
        Login login = login("lock-timeout");
        Map<String, Object> session = sessionState(login);
        clock.set(T1);

        try (Connection holder = lockRow("members", login.memberId())) {
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(true);
            Future<ResultActions> request = pool.submit(() -> complete(login));
            awaitLockWaits("members", 1, request);
            expectError(request.get(WAIT.toSeconds(), TimeUnit.SECONDS), 503, "COMMON_008");
            FaultInjectingDataSource.SHORT_LOCK_WAIT.set(false);
            holder.rollback();
        }

        assertThat(completedAt(login.memberId())).isNull();
        assertThat(sessionState(login)).isEqualTo(session);
        complete(login).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));
        assertThat(output.getAll()).doesNotContain(login.accessToken());
    }

    /**
     * T40(서버): 완료 시각이 flush된 뒤 JDBC commit이 실패하면 500 COMMON_003이고, 새 조회에서 완료 시각이 없다. 세션·Refresh는 그대로라
     * 장애가 사라진 뒤 같은 Access로 me 조회와 완료 재시도가 된다. 실패 원인 메시지는 로그에 남기지 않는다.
     */
    @Test
    void commitFailureAfterFlushKeepsSessionAndAllowsRetry(CapturedOutput output) throws Exception {
        Login login = login("commit-fail");
        Map<String, Object> session = sessionState(login);
        clock.set(T1);
        FaultInjectingDataSource.FAIL_COMPLETION_COMMIT.set(login.memberId());

        expectError(complete(login), 500, "COMMON_003");

        assertThat(FaultInjectingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션에 flush된 완료 시각")
                .isEqualByComparingTo(epochMicros(T1));
        assertThat(completedAt(login.memberId())).as("새 조회").isNull();
        assertThat(sessionState(login)).isEqualTo(session);
        me(login).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("NOT_COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").doesNotExist());

        clock.set(T2);
        complete(login).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:06:06Z"));
        assertThat(completedAt(login.memberId())).isEqualByComparingTo(epochMicros(T2));
        assertThat(sessionState(login)).isEqualTo(session);
        assertThat(output.getAll()).doesNotContain(login.accessToken(), "injected commit failure");
    }

    /** body·query·header의 다른 회원 ID·세션 ID·완료 시각으로 다른 회원을 바꾸거나 저장 시각을 정할 수 없다. */
    @Test
    void clientValuesCannotSelectOtherMemberOrTime() throws Exception {
        Login a = login("client-a");
        Login b = login("client-b");
        clock.set(T1);

        mockMvc.perform(post(COMPLETE).param("memberId", String.valueOf(b.memberId()))
                        .param("sessionId", String.valueOf(b.sessionId())).header("X-Member-Id", b.memberId())
                        .header("Authorization", a.bearer()).contentType("application/json")
                        .content("{\"memberId\":\"" + b.memberId() + "\",\"sessionId\":\"" + b.sessionId()
                                + "\",\"onboardingCompletedAt\":\"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(String.valueOf(a.memberId())))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-04T03:05:05.987654Z"));

        assertThat(completedAt(a.memberId())).isEqualByComparingTo(epochMicros(T1));
        assertThat(completedAt(b.memberId())).isNull();
    }

    /**
     * Bearer 누락·변조·만료는 기존 JWT 오류, 이미 완료한 회원이라도 폐기된 세션은 401 AUTH_003이다. 모두 완료 시각을 바꾸지 않는다.
     */
    @Test
    void rejectsInvalidCredentialsWithoutChanges() throws Exception {
        Login login = login("invalid");
        clock.set(T1);
        complete(login).andExpect(status().isOk());
        clock.set(T2);

        expectSecurityError(mockMvc.perform(post(COMPLETE)), "COMMON_005");
        expectSecurityError(mockMvc.perform(post(COMPLETE).header("Authorization", tamper(login.bearer()))), "AUTH_002");
        Instant past = T2.minusSeconds(3_600);
        String expired = tokenService.issue(login.memberId(), login.sessionKey(), past, past.plusSeconds(1_209_600))
                .accessToken();
        expectSecurityError(mockMvc.perform(post(COMPLETE).header("Authorization", "Bearer " + expired)), "AUTH_001");
        jdbc.update("UPDATE auth_sessions SET revoked_at = '2026-10-04 03:06:00', revoke_reason = 'LOGOUT' "
                + "WHERE id = ?", login.sessionId());
        expectSecurityError(complete(login), "AUTH_003");

        assertThat(completedAt(login.memberId())).isEqualByComparingTo(epochMicros(T1));
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

    private ResultActions complete(Login login) throws Exception {
        return mockMvc.perform(post(COMPLETE).header("Authorization", login.bearer()));
    }

    private ResultActions me(Login login) throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", login.bearer()));
    }

    private BigDecimal completedAt(long memberId) {
        return jdbc.queryForObject("SELECT UNIX_TIMESTAMP(onboarding_completed_at) FROM members WHERE id = ?",
                BigDecimal.class, memberId);
    }

    /** 온보딩 완료가 바꾸면 안 되는 회원 값과 갱신 시각. */
    private Map<String, Object> memberState(long memberId) {
        return jdbc.queryForMap("SELECT status, onboarding_completed_at, last_login_at, updated_at FROM members "
                + "WHERE id = ?", memberId);
    }

    /** 온보딩 완료가 바꾸면 안 되는 세션·Refresh 값(만료·폐기·세대·소비·갱신 시각·토큰 수). */
    private Map<String, Object> sessionState(Login login) {
        return jdbc.queryForMap("SELECT s.expires_at, s.revoked_at, s.revoke_reason, s.current_refresh_generation, "
                + "s.updated_at, t.generation, t.expires_at AS token_expires, t.consumed_at, t.updated_at AS token_updated, "
                + "(SELECT COUNT(*) FROM auth_refresh_tokens c WHERE c.session_id = s.id) AS tokens "
                + "FROM auth_sessions s JOIN auth_refresh_tokens t ON t.session_id = s.id WHERE s.id = ?",
                login.sessionId());
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

    private static String completedAtIn(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.onboardingCompletedAt");
    }

    private static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static void expectSecurityError(ResultActions result, String code) throws Exception {
        expectError(result, 401, code).andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    /** 서명 첫 글자를 바꾼다. */
    private static String tamper(String bearer) {
        int i = bearer.lastIndexOf('.') + 1;
        return bearer.substring(0, i) + (bearer.charAt(i) == 'A' ? 'B' : 'A') + bearer.substring(i + 1);
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

    /** 테스트가 정하는 UTC Clock. step을 주면 읽을 때마다 그만큼 흐른 뒤의 값을 준다. */
    static final class TestClock extends Clock {

        private Instant instant = T0;
        private Duration step = Duration.ZERO;

        synchronized void set(Instant instant) {
            this.instant = instant;
            this.step = Duration.ZERO;
        }

        synchronized void tickEachRead(Duration step) {
            this.step = step;
        }

        @Override
        public synchronized Instant instant() {
            instant = instant.plus(step);
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
     *   <li>{@link #FAIL_COMPLETION_COMMIT}: 그 회원의 완료 시각이 같은 커넥션에 flush돼 있는 commit 한 번을 실패시키고, 실패 직전 그
     *   커넥션에서 본 값을 {@link #SEEN_BEFORE_FAILURE}에 남긴다. 완료 시각이 없는 다른 commit은 통과시킨다.</li>
     *   <li>{@link #SHORT_LOCK_WAIT}: 켜진 동안 빌린 커넥션의 innodb_lock_wait_timeout을 1초로 두고 반납 전에 기본값으로 되돌린다.</li>
     * </ul>
     */
    static final class FaultInjectingDataSource extends DelegatingDataSource {

        static final AtomicReference<Long> FAIL_COMPLETION_COMMIT = new AtomicReference<>();
        static final AtomicReference<BigDecimal> SEEN_BEFORE_FAILURE = new AtomicReference<>();
        static final AtomicBoolean SHORT_LOCK_WAIT = new AtomicBoolean();

        FaultInjectingDataSource(DataSource target) {
            super(target);
        }

        static void reset() {
            FAIL_COMPLETION_COMMIT.set(null);
            SEEN_BEFORE_FAILURE.set(null);
            SHORT_LOCK_WAIT.set(false);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            boolean shortLockWait = SHORT_LOCK_WAIT.get();
            if (shortLockWait) {
                execute(connection, "SET SESSION innodb_lock_wait_timeout = 1");
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            failCompletionCommit(connection);
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

        private static void failCompletionCommit(Connection connection) throws SQLException {
            Long memberId = FAIL_COMPLETION_COMMIT.get();
            if (memberId == null) {
                return;
            }
            BigDecimal pending;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT UNIX_TIMESTAMP(onboarding_completed_at) FROM members WHERE id = ?")) {
                statement.setLong(1, memberId);
                try (ResultSet rs = statement.executeQuery()) {
                    pending = rs.next() ? rs.getBigDecimal(1) : null;
                }
            }
            if (pending != null && FAIL_COMPLETION_COMMIT.compareAndSet(memberId, null)) {
                SEEN_BEFORE_FAILURE.set(pending);
                throw new SQLException("injected commit failure");
            }
        }
    }
}
