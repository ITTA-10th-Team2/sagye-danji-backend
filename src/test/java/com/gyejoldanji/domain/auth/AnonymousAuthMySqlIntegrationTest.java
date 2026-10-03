package com.gyejoldanji.domain.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.gyejoldanji.domain.auth.dto.AnonymousAuthResponse;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.service.AnonymousAuthService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService.IssuedTokens;
import com.gyejoldanji.domain.auth.toss.TossAnonymousAuthClient;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.ClockConfig;
import com.gyejoldanji.global.config.JwtKeyConfig;
import com.gyejoldanji.global.config.TestJwtKeys;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL로 익명 인증 트랜잭션을 검증한다(작업 03: T01·T02·T03·T20·T22 일부·T30).
 *
 * <p>{@code AUTH_IT_DB_URL}이 있을 때만 실행한다. 준비 조건·권한은 {@link AuthPersistenceMySqlIntegrationTest}와 같다(전용 DB,
 * 마이그레이션 적용, performance_schema 잠금 관측 권한). 토스 교환은 대체하고, JWT는 실행 중 만든 RSA 키로 실제 서명한다.
 * commit 실패는 테스트 전용 DataSource 래퍼가 JDBC commit 한 번을 실패시켜 만든다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class AnonymousAuthMySqlIntegrationTest {

    private static final String PREFIX = "it03-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final Instant T0 = Instant.parse("2026-10-03T03:04:05.123456789Z");
    private static final Instant T1 = Instant.parse("2026-10-03T03:09:07.654321987Z");
    private static final Duration SESSION_TTL = Duration.ofSeconds(1_209_600);
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static TimeZone originalTimeZone;

    @MockitoBean
    private TossAnonymousAuthClient tossClient;
    @MockitoSpyBean
    private ServiceTokenService tokenService;
    @Autowired
    private AnonymousAuthService service;
    @Autowired
    private MutableClock clock;
    @Autowired
    private KeyPair jwtKeyPair;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    private JdbcTemplate jdbc;
    private final Map<String, String> anonKeyByCode = new ConcurrentHashMap<>();
    private final AtomicBoolean exchangeSawTransaction = new AtomicBoolean();

    @TestConfiguration
    @EntityScan(basePackageClasses = {Member.class, AuthSession.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AuthSessionRepository.class})
    @EnableConfigurationProperties(AuthProperties.class)
    @Import({ClockConfig.class, JwtKeyConfig.class, ServiceTokenService.class, AnonymousAuthService.class})
    static class Config {

        /** auditing과 서비스가 함께 쓰는 조절 가능한 Clock. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** 앱 DataSource를 commit 장애 주입용 래퍼로 감싼다. 테스트가 켤 때만 동작한다. */
        @Bean
        static BeanPostProcessor commitFailureInjection() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource target && !(bean instanceof CommitFailingDataSource)
                            ? new CommitFailingDataSource(target) : bean;
                }
            };
        }
    }

    /** 전용 DB URL에 legacy serverTimezone을 붙이고 JVM 기본 시간대를 Asia/Seoul로 둔다. 테스트 RSA 키를 공급한다. */
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
        TestJwtKeys.register(registry);
    }

    @AfterAll
    static void restoreTimeZone() {
        if (originalTimeZone != null) {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 앱 기본 DB가 아닌지 확인하고, code를 anonKey로 바꾸는 교환 대체를 둔다(교환 중 TX 여부를 기록). */
    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        String target = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String appUrl = environment.getProperty("DB_URL");
        if (appUrl != null) {
            assertThat(target).as("앱 기본 DB에서 실행하지 않는다").isNotEqualTo(databaseName(appUrl));
        }
        clock.set(T0);
        CommitFailingDataSource.FAIL_NEXT_COMMIT.set(null);
        when(tossClient.exchangeAnonymousCode(anyString())).thenAnswer(invocation -> {
            exchangeSawTransaction.compareAndSet(false, TransactionSynchronizationManager.isActualTransactionActive());
            String code = invocation.getArgument(0);
            return anonKeyByCode.getOrDefault(code, code);
        });
    }

    /** 이번 실행이 만든 행만 FK 역순으로 지운다. */
    @AfterEach
    void cleanUp() {
        String like = PREFIX + "%";
        jdbc.update("DELETE t FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE s FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                + "WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE FROM members WHERE provider_user_id LIKE ?", like);
    }

    /** T01: 신규 anonKey는 회원·세션·Refresh 각 1행, 첫 인증, UTC 마이크로초(KST JVM), 해시·세대·만료·서명이 계약대로다. */
    @Test
    void firstAuthenticationCreatesMemberSessionAndRefresh() throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        String anonKey = key("new");

        AnonymousAuthResponse response = service.authenticate(anonKey);

        assertThat(exchangeSawTransaction.get()).as("토스 교환 중에는 DB 트랜잭션이 없다").isFalse();
        assertThat(response.isNewMember()).isTrue();
        assertThat(response.member().onboardingStatus()).isEqualTo("NOT_COMPLETED");
        Long memberId = jdbc.queryForObject("SELECT id FROM members WHERE provider_user_id = ?", Long.class, anonKey);
        assertThat(response.member().memberId()).isEqualTo(String.valueOf(memberId));
        assertThat(counts(anonKey)).containsExactly(1, 1, 1);

        Instant issuedAt = T0.truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> member = jdbc.queryForMap("SELECT status, UNIX_TIMESTAMP(created_at) AS created, "
                + "UNIX_TIMESTAMP(last_login_at) AS last_login FROM members WHERE id = ?", memberId);
        assertThat(member.get("status")).isEqualTo("ACTIVE");
        assertThat((BigDecimal) member.get("created")).isEqualByComparingTo(epochMicros(issuedAt));
        assertThat((BigDecimal) member.get("last_login")).isEqualByComparingTo(epochMicros(issuedAt));

        Map<String, Object> session = jdbc.queryForMap("SELECT id, session_key, current_refresh_generation AS generation, "
                + "UNIX_TIMESTAMP(expires_at) AS expires, revoked_at FROM auth_sessions WHERE member_id = ?", memberId);
        assertThat(((Number) session.get("generation")).intValue()).isZero();
        assertThat((BigDecimal) session.get("expires")).isEqualByComparingTo(epochMicros(issuedAt.plus(SESSION_TTL)));
        assertThat(session.get("revoked_at")).isNull();

        Map<String, Object> token = jdbc.queryForMap("SELECT generation, token_hash, UNIX_TIMESTAMP(expires_at) AS expires, "
                + "consumed_at FROM auth_refresh_tokens WHERE session_id = ?", session.get("id"));
        assertThat(((Number) token.get("generation")).intValue()).isZero();
        assertThat((BigDecimal) token.get("expires")).isEqualByComparingTo((BigDecimal) session.get("expires"));
        assertThat((byte[]) token.get("token_hash")).isEqualTo(sha256(response.refreshToken()));
        assertThat(token.get("consumed_at")).isNull();

        SignedJWT jwt = SignedJWT.parse(response.accessToken());
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) jwtKeyPair.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(String.valueOf(memberId));
        assertThat(jwt.getJWTClaimsSet().getStringClaim("sid")).isEqualTo(session.get("session_key"));
    }

    /** T02: 같은 anonKey의 새 code는 같은 회원·온보딩을 유지하고 새 세션을 추가한다. 기존 세션은 폐기하지 않는다. */
    @Test
    void reauthenticationKeepsMemberAndOnboarding() {
        String anonKey = key("again");
        AnonymousAuthResponse first = service.authenticate(anonKey);
        LocalDateTime onboardedAt = LocalDateTime.of(2026, 10, 3, 3, 5, 0, 500_000_000);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> memberRepository
                .findById(Long.valueOf(first.member().memberId())).orElseThrow().completeOnboarding(onboardedAt));

        clock.set(T1);
        anonKeyByCode.put("second-code", anonKey);
        AnonymousAuthResponse second = service.authenticate("second-code");

        assertThat(second.isNewMember()).isFalse();
        assertThat(second.member().memberId()).isEqualTo(first.member().memberId());
        assertThat(second.member().onboardingStatus()).isEqualTo("COMPLETED");
        assertThat(counts(anonKey)).containsExactly(1, 2, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                + "WHERE m.provider_user_id = ? AND s.revoked_at IS NOT NULL", Integer.class, anonKey)).isZero();
        assertThat(lastLogin(anonKey)).isEqualByComparingTo(epochMicros(T1.truncatedTo(ChronoUnit.MICROS)));
        assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(onboarding_completed_at) FROM members WHERE provider_user_id = ?",
                BigDecimal.class, anonKey)).isEqualByComparingTo(epochMicros(onboardedAt.toInstant(ZoneOffset.UTC)));
    }

    /** T20: BLOCKED·WITHDRAWN은 AUTH_008이며 상태 복구·새 세션·로그인 시각 변경이 없다. */
    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "WITHDRAWN"})
    void inactiveMemberGetsNoSession(String status) {
        String anonKey = key("inactive-" + status);
        service.authenticate(anonKey);
        assertThat(jdbc.update("UPDATE members SET status = ? WHERE provider_user_id = ?", status, anonKey)).isOne();
        BigDecimal lastLogin = lastLogin(anonKey);

        clock.set(T1);
        assertThatThrownBy(() -> service.authenticate(anonKey)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_INACTIVE));

        assertThat(jdbc.queryForObject("SELECT status FROM members WHERE provider_user_id = ?", String.class, anonKey))
                .isEqualTo(status);
        assertThat(counts(anonKey)).containsExactly(1, 1, 1);
        assertThat(lastLogin(anonKey)).isEqualByComparingTo(lastLogin);
    }

    /**
     * T03: 같은 신규 anonKey 두 요청이 겹치면 두 번째가 실제 잠금 대기 후 진행한다. 회원 1명, 세션 2개, 첫 성공만 신규이며
     * 두 번째는 잠금 대기 뒤 새로 읽은 시각으로 발급한다.
     */
    @Test
    void concurrentFirstAuthenticationHasOneNewMember() throws Exception {
        String anonKey = key("concurrent");
        CountDownLatch holderIssuing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger issueCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (issueCalls.getAndIncrement() == 0) {
                holderIssuing.countDown();
                await(release);
            }
            return invocation.callRealMethod();
        }).when(tokenService).issue(anyLong(), anyString(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AnonymousAuthResponse> holder = pool.submit(() -> service.authenticate(anonKey));
            awaitOrFail(holderIssuing, holder);
            anonKeyByCode.put("contender-code", anonKey);
            Future<AnonymousAuthResponse> contender = pool.submit(() -> service.authenticate("contender-code"));
            awaitMemberLockWait(contender);
            clock.set(T1); // 잠금 대기 중 시간이 흐른다
            release.countDown();

            AnonymousAuthResponse first = holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            AnonymousAuthResponse second = contender.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(first.isNewMember()).isTrue();
            assertThat(second.isNewMember()).isFalse();
            assertThat(second.member().memberId()).isEqualTo(first.member().memberId());
            assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(counts(anonKey)).containsExactly(1, 2, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT s.session_key) FROM auth_sessions s JOIN members m "
                + "ON m.id = s.member_id WHERE m.provider_user_id = ?", Integer.class, anonKey)).isEqualTo(2);
        Instant later = T1.truncatedTo(ChronoUnit.MICROS);
        assertThat(lastLogin(anonKey)).isEqualByComparingTo(epochMicros(later));
        assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(MAX(s.expires_at)) FROM auth_sessions s JOIN members m "
                + "ON m.id = s.member_id WHERE m.provider_user_id = ?", BigDecimal.class, anonKey))
                .isEqualByComparingTo(epochMicros(later.plus(SESSION_TTL)));
    }

    /** T03: 먼저 잠근 요청이 rollback되면 기다리던 다음 요청이 첫 성공이 된다. */
    @Test
    void nextRequestBecomesFirstWhenFirstRollsBack() throws Exception {
        String anonKey = key("first-rollback");
        CountDownLatch holderIssuing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger issueCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (issueCalls.getAndIncrement() == 0) {
                holderIssuing.countDown();
                await(release);
                throw new JwtEncodingException("injected signing failure");
            }
            return invocation.callRealMethod();
        }).when(tokenService).issue(anyLong(), anyString(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AnonymousAuthResponse> holder = pool.submit(() -> service.authenticate(anonKey));
            awaitOrFail(holderIssuing, holder);
            anonKeyByCode.put("contender-code", anonKey);
            Future<AnonymousAuthResponse> contender = pool.submit(() -> service.authenticate("contender-code"));
            awaitMemberLockWait(contender);
            release.countDown();

            assertThatThrownBy(() -> holder.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
            assertThat(contender.get(WAIT.toSeconds(), TimeUnit.SECONDS).isNewMember()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(counts(anonKey)).containsExactly(1, 1, 1);
    }

    /** T30: 서명 실패는 회원 upsert까지 전부 rollback하고 500이다. */
    @Test
    void signingFailureRollsBackEverything() {
        String anonKey = key("sign-fail");
        doThrow(new JwtEncodingException("injected")).when(tokenService).issue(anyLong(), anyString(), any(), any());

        assertThatThrownBy(() -> service.authenticate(anonKey)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
        assertThat(counts(anonKey)).containsExactly(0, 0, 0);
    }

    /** T30: 저장 중 예상 밖 unique 위반(실제 MySQL)은 409가 아니라 500이며 전부 rollback한다. */
    @Test
    void unexpectedUniqueViolationIsInternalErrorNotConflict() {
        byte[] existingHash = sha256(service.authenticate(key("hash-owner")).refreshToken());
        doAnswer(invocation -> {
            IssuedTokens real = (IssuedTokens) invocation.callRealMethod();
            return new IssuedTokens(real.accessToken(), real.expiresIn(), real.refreshToken(), real.refreshExpiresIn(),
                    existingHash);
        }).when(tokenService).issue(anyLong(), anyString(), any(), any());
        String anonKey = key("hash-collision");

        assertThatThrownBy(() -> service.authenticate(anonKey)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
        assertThat(counts(anonKey)).containsExactly(0, 0, 0);
    }

    /** T30: flush가 끝난 뒤 JDBC commit이 실패하면 500이고, 새 커넥션에서 신규 회원·세션·Refresh가 보이지 않는다. */
    @Test
    void commitFailureAfterFlushLeavesNoNewMember() {
        String anonKey = key("commit-new");
        CommitFailingDataSource.FAIL_NEXT_COMMIT.set(anonKey);

        assertThatThrownBy(() -> service.authenticate(anonKey)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));

        assertThat(CommitFailingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션에 flush된 행")
                .containsExactly(1, 1, 1);
        assertThat(counts(anonKey)).containsExactly(0, 0, 0);
    }

    /** T30: 기존 회원의 commit 실패는 새 세션·Refresh를 남기지 않고 lastLoginAt을 이전 값으로 둔다. */
    @Test
    void commitFailureKeepsExistingMemberUnchanged() {
        String anonKey = key("commit-existing");
        service.authenticate(anonKey);
        BigDecimal lastLogin = lastLogin(anonKey);
        clock.set(T1);
        CommitFailingDataSource.FAIL_NEXT_COMMIT.set(anonKey);

        assertThatThrownBy(() -> service.authenticate(anonKey)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));

        assertThat(CommitFailingDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션에 flush된 행")
                .containsExactly(1, 2, 2);
        assertThat(counts(anonKey)).containsExactly(1, 1, 1);
        assertThat(lastLogin(anonKey)).isEqualByComparingTo(lastLogin);
    }

    /** 회원·세션·Refresh 행 수. */
    private int[] counts(String anonKey) {
        return new int[] {
                jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id = ?", Integer.class, anonKey),
                jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                        + "WHERE m.provider_user_id = ?", Integer.class, anonKey),
                jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                        + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id = ?", Integer.class, anonKey)};
    }

    private BigDecimal lastLogin(String anonKey) {
        return jdbc.queryForObject("SELECT UNIX_TIMESTAMP(last_login_at) FROM members WHERE provider_user_id = ?",
                BigDecimal.class, anonKey);
    }

    /** 이번 DB의 members 행에서 레코드 잠금 대기가 생길 때까지 기다린다(대기 없이 끝나면 실패). */
    private void awaitMemberLockWait(Future<?> contender) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (contender.isDone()) {
                contender.get();
                fail("잠금 대기 없이 진행됐다");
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = w.ENGINE AND requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = 'members'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("두 번째 요청이 회원 행 잠금 대기에 들어가지 않았다");
    }

    private static void awaitOrFail(CountDownLatch latch, Future<?> work) throws Exception {
        if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            if (work.isDone()) {
                work.get();
            }
            fail("첫 요청이 발급 단계에 도달하지 못했다");
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(WAIT.multipliedBy(3).toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("해제 대기 시간 초과");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal epochMicros(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano() / 1_000, 6));
    }

    private static String key(String suffix) {
        return PREFIX + suffix;
    }

    private static String databaseName(String jdbcUrl) {
        Matcher matcher = Pattern.compile("jdbc:mysql://[^/]+/([^?]+)").matcher(jdbcUrl);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 테스트가 정하는 시각을 돌려주는 UTC Clock. */
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
     * 테스트 전용 DataSource 래퍼. {@link #FAIL_NEXT_COMMIT}이 지정되면 다음 JDBC commit 한 번을 실패시킨다. 실패 직전 같은
     * 커넥션(아직 commit 전)에서 그 anonKey의 회원·세션·Refresh 행 수를 기록해 flush가 끝났음을 보인다.
     */
    static final class CommitFailingDataSource extends DelegatingDataSource {

        static final AtomicReference<String> FAIL_NEXT_COMMIT = new AtomicReference<>();
        static final AtomicReference<int[]> SEEN_BEFORE_FAILURE = new AtomicReference<>();

        CommitFailingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            String anonKey = FAIL_NEXT_COMMIT.getAndSet(null);
                            if (anonKey != null) {
                                SEEN_BEFORE_FAILURE.set(countOn(connection, anonKey));
                                throw new SQLException("injected commit failure");
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private static int[] countOn(Connection connection, String anonKey) throws SQLException {
            String[] queries = {
                    "SELECT COUNT(*) FROM members WHERE provider_user_id = ?",
                    "SELECT COUNT(*) FROM auth_sessions s JOIN members m ON m.id = s.member_id WHERE m.provider_user_id = ?",
                    "SELECT COUNT(*) FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                            + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id = ?"};
            int[] counts = new int[queries.length];
            for (int i = 0; i < queries.length; i++) {
                try (PreparedStatement statement = connection.prepareStatement(queries[i])) {
                    statement.setString(1, anonKey);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        counts[i] = rs.getInt(1);
                    }
                }
            }
            return counts;
        }
    }
}
