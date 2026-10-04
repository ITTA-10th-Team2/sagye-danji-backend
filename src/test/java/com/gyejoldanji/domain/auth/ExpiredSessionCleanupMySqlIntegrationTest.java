package com.gyejoldanji.domain.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.gyejoldanji.domain.auth.TokenRefreshMySqlIntegrationTest.MutableClock;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.scheduler.ExpiredAuthSessionCleanupScheduler;
import com.gyejoldanji.domain.auth.service.ExpiredAuthSessionCleanupService;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.auth.service.SessionLogoutService;
import com.gyejoldanji.domain.auth.service.TokenRefreshService;
import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.config.properties.AuthProperties;
import com.gyejoldanji.global.security.SecurityTestConfig;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 실제 MySQL로 만료 이력 정리를 검증한다(작업 08: T28의 삭제 범위·시각 경계·배치 commit·rollback·Refresh/logout 경합).
 *
 * <p>정리는 DB 전체에서 대상을 찾으므로 공유 DB를 쓰지 않는다. {@code AUTH_IT_DB_URL}의 서버·계정(CREATE/DROP DATABASE 권한 필요)으로
 * 이번 실행 전용 DB를 새로 만들고 회원·인증·기록·사진 테이블을 Hibernate {@code create-only}로 만든 뒤, 끝나면 그 DB만 삭제한다. 전용 인증 DB와
 * 앱 기본 DB의 행은 건드리지 않는다. 스케줄러는 이 컨텍스트에 등록하지 않아 자동 실행이 없다. {@code AUTH_IT_DB_URL}이 있을 때만 실행한다.
 *
 * <p>JVM과 legacy URL 시간대는 Asia/Seoul이다. 시각 fixture는 {@code FROM_UNIXTIME(epoch)}로 넣고 {@code UNIX_TIMESTAMP}로 읽어, 앱의
 * Instant 바인딩과 다른 경로로 기대값을 정한다(바인딩 오차가 기대값에서 상쇄되지 않는다).
 *
 * <p>배치 관측·일시 정지·장애는 테스트 전용 DataSource 래퍼가 정리 SQL을 알아보고 켤 때만 주입한다. 잠금 대기는 performance_schema의
 * 실제 대기로 확인한다. commit 실패는 삭제가 같은 커넥션에 반영된 뒤 JDBC commit 직전의 명확한 실패이며 결과 불명확 상황은 다루지 않는다.
 */
@SpringBootTest(classes = ExpiredSessionCleanupMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=create-only",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class ExpiredSessionCleanupMySqlIntegrationTest {

    /** 이번 실행 전용 DB. CREATE DATABASE는 이미 있으면 실패하므로 남의 DB를 쓰지 않는다. */
    private static final String DATABASE = "gyejol_danji_cleanup_it_" + UUID.randomUUID().toString().substring(0, 8);
    /** 정리 실행 시각(나노초). 서비스는 마이크로초로 잘라 쓴다. */
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00.123456789Z");
    /** 기본 보관 7일의 cutoff = NOW(마이크로초) - 7일. */
    private static final Instant CUTOFF = Instant.parse("2026-09-27T03:00:00.123456Z");
    private static final Duration WAIT = Duration.ofSeconds(15);
    /** 주입한 DB 오류 메시지에 넣는 값. 로그에 남으면 안 된다. */
    private static final String SECRET = "Duplicate entry 'SECRET-SENTINEL'";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static TimeZone originalTimeZone;
    private static boolean databaseCreated;

    @Autowired
    private ExpiredAuthSessionCleanupService cleanupService;
    @Autowired
    private TokenRefreshService refreshService;
    @Autowired
    private SessionLogoutService logoutService;
    @Autowired
    private AuthSessionRepository sessionRepository;
    @Autowired
    private AuthProperties authProperties;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    private JdbcTemplate jdbc;
    private ExecutorService pool;
    private int memberSeq;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {Member.class, AuthSession.class, Record.class, Image.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AuthSessionRepository.class})
    @EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
    @Import({SecurityTestConfig.class, ServiceTokenService.class, TokenRefreshService.class, SessionLogoutService.class,
            ExpiredAuthSessionCleanupService.class})
    static class Config {

        /** 정리·Refresh·로그아웃이 함께 쓰는 조절 가능한 UTC Clock. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** 앱 DataSource를 정리 관측·장애 주입 래퍼로 감싼다. 테스트가 켤 때만 동작한다. */
        @Bean
        static BeanPostProcessor cleanupFaults() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource target && !(bean instanceof CleanupDataSource)
                            ? new CleanupDataSource(target) : bean;
                }
            };
        }
    }

    /** 전용 DB를 한 번만 만들고 그 DB로 연결한다. legacy serverTimezone·JVM 기본 시간대는 Asia/Seoul이다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("AUTH_IT_DB_URL");
        if (url.contains("connectionTimeZone") || url.contains("forceConnectionTimeZoneToSession")
                || url.contains("serverTimezone")) {
            throw new IllegalStateException("AUTH_IT_DB_URL에는 시간대 파라미터를 넣지 않는다");
        }
        String tempUrl = url.replaceFirst("(//[^/?]+/)[^?]*", "$1" + DATABASE);
        if (!DATABASE.equals(databaseName(tempUrl))) {
            throw new IllegalStateException("AUTH_IT_DB_URL에서 DB 이름 위치를 찾지 못했다");
        }
        if (!databaseCreated) {
            try (Connection connection = DriverManager.getConnection(url, System.getenv("AUTH_IT_DB_USERNAME"),
                    System.getenv("AUTH_IT_DB_PASSWORD"));
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4");
            }
            databaseCreated = true;
        }
        if (originalTimeZone == null) {
            originalTimeZone = TimeZone.getDefault();
        }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        registry.add("spring.datasource.url",
                () -> tempUrl + (tempUrl.contains("?") ? "&" : "?") + "serverTimezone=Asia/Seoul");
        registry.add("spring.datasource.username", () -> System.getenv("AUTH_IT_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("AUTH_IT_DB_PASSWORD"));
        SecurityTestConfig.register(registry);
    }

    /** 이번 실행이 만든 DB만 삭제하고 시간대를 되돌린다. 남은 잠금이 있어도 무한정 기다리지 않는다. */
    @AfterAll
    static void dropDatabase() throws SQLException {
        try {
            if (databaseCreated) {
                try (Connection connection = DriverManager.getConnection(System.getenv("AUTH_IT_DB_URL"),
                        System.getenv("AUTH_IT_DB_USERNAME"), System.getenv("AUTH_IT_DB_PASSWORD"));
                     Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION lock_wait_timeout = 30");
                    statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
                }
            }
        } finally {
            if (originalTimeZone != null) {
                TimeZone.setDefault(originalTimeZone);
            }
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).as("이번 실행 전용 DB").isEqualTo(DATABASE);
        clock.set(NOW);
        CleanupDataSource.reset();
        pool = Executors.newFixedThreadPool(2);
    }

    /** 전용 DB의 fixture를 FK 역순으로 모두 지운다(다른 DB는 건드리지 않는다). */
    @AfterEach
    void cleanUp() {
        CleanupDataSource.reset();
        pool.shutdownNow();
        authProperties.setExpiredSessionRetentionDays(7);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        for (String table : List.of("images", "records", "auth_refresh_tokens", "auth_sessions", "members")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * T28 경계·보존(기본 7일, KST JVM·legacy URL): cutoff보다 1마이크로초 먼저 만료된 세션만 대상이고 정확히 cutoff·1마이크로초 뒤는
     * 남는다. 대상은 폐기 여부와 관계없이 현재·소비된 Refresh 이력까지 모두 지우며 이력이 없는 세션도 지운다. 미만료 세션의 과거 이력,
     * 일찍 로그아웃했지만 만료+7일 전인 세션, 회원(세션이 모두 지워진 회원 포함)·기록·사진은 그대로다.
     */
    @Test
    void deletesOnlySessionsExpiredBeforeCutoffWithAllTheirHistory(CapturedOutput output) {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        assertThat(environment.getProperty("spring.datasource.url")).contains("serverTimezone=Asia/Seoul");
        long a = member();
        long b = member();
        long beforeCutoff = session(a, CUTOFF.minus(1, ChronoUnit.MICROS), null, null);
        tokens(beforeCutoff, 3);
        long atCutoff = session(a, CUTOFF, null, null);
        tokens(atCutoff, 1);
        long afterCutoff = session(a, CUTOFF.plus(1, ChronoUnit.MICROS), null, null);
        tokens(afterCutoff, 1);
        long active = session(a, NOW.plus(10, ChronoUnit.DAYS), null, null);
        tokens(active, 3);
        long loggedOutEarly = session(a, CUTOFF.plus(1, ChronoUnit.DAYS), CUTOFF.minus(20, ChronoUnit.DAYS), "LOGOUT");
        tokens(loggedOutEarly, 2);
        long revokedOld = session(a, CUTOFF.minus(1, ChronoUnit.DAYS), CUTOFF.minus(10, ChronoUnit.DAYS),
                "REFRESH_REUSE");
        tokens(revokedOld, 2);
        long withoutTokens = session(a, CUTOFF.minus(3, ChronoUnit.DAYS), null, null);
        long onlySessionOfB = session(b, CUTOFF.minus(30, ChronoUnit.DAYS), null, null);
        tokens(onlySessionOfB, 2);
        records(a, 2);
        records(b, 1);
        Map<String, Object> preserved = preserved();
        List<Map<String, Object>> sessions = sessions();
        List<Map<String, Object>> tokens = tokens();
        assertThat(epoch(atCutoff)).as("fixture가 정확히 cutoff").isEqualByComparingTo(epochMicros(CUTOFF));

        cleanupService.cleanUp();

        Set<Long> kept = Set.of(atCutoff, afterCutoff, active, loggedOutEarly);
        assertThat(sessions()).isEqualTo(only(sessions, "id", kept));
        assertThat(tokens()).isEqualTo(only(tokens, "session_id", kept));
        assertThat(preserved()).as("회원·기록·사진 불변").isEqualTo(preserved);
        assertThat(orphanTokens()).isZero();
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=4, refreshTokens=7");
    }

    /** 바꾼 보관 기간(30일)을 적용한다: NOW(마이크로초) - 30일보다 1마이크로초 먼저 만료된 세션만 지우고 7일만 지난 세션은 남긴다. */
    @Test
    void appliesConfiguredRetentionDays() {
        authProperties.setExpiredSessionRetentionDays(30);
        Instant cutoff30 = NOW.truncatedTo(ChronoUnit.MICROS).minus(30, ChronoUnit.DAYS);
        long m = member();
        long target = session(m, cutoff30.minus(1, ChronoUnit.MICROS), null, null);
        tokens(target, 2);
        long atCutoff = session(m, cutoff30, null, null);
        long pastSevenDays = session(m, CUTOFF.minus(1, ChronoUnit.DAYS), null, null);
        tokens(pastSevenDays, 1);
        List<Map<String, Object>> sessions = sessions();
        List<Map<String, Object>> tokens = tokens();

        cleanupService.cleanUp();

        Set<Long> kept = Set.of(atCutoff, pastSevenDays);
        assertThat(sessions()).isEqualTo(only(sessions, "id", kept));
        assertThat(tokens()).isEqualTo(only(tokens, "session_id", kept));
    }

    /** 후보 조회는 만료 순서와 관계없이 PK 오름차순으로 최대 limit개이고, cutoff와 같은 세션은 고르지 않는다(Instant 바인딩, KST JVM). */
    @Test
    void candidateQueryTakesLowestIdsFirstUpToLimit() {
        long m = member();
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // 뒤에 만든(PK가 큰) 세션일수록 먼저 만료된다.
            targets.add(session(m, CUTOFF.minus(i + 1, ChronoUnit.MICROS), null, null));
        }
        session(m, CUTOFF, null, null);

        List<Long> three = sessionRepository.findExpiredIds(CUTOFF, 3);
        List<Long> all = sessionRepository.findExpiredIds(CUTOFF, 200);

        assertThat(three).containsExactlyElementsOf(targets.subList(0, 3));
        assertThat(all).containsExactlyElementsOf(targets);
    }

    /** 대상이 없으면 후보 조회 한 번으로 끝나고 아무것도 잠그거나 바꾸지 않는다. */
    @Test
    void noCandidatesChangesNothing(CapturedOutput output) {
        bystander();
        Map<String, Object> before = everything();

        cleanupService.cleanUp();

        assertThat(everything()).isEqualTo(before);
        assertThat(CleanupDataSource.BATCHES.get()).isEqualTo(1);
        assertThat(CleanupDataSource.ROW_LOCKS.get()).isZero();
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=0, refreshTokens=0");
    }

    /** 대상이 정확히 200개면 한 배치로 모두 지우고, 다음 후보 조회(별도 조회에서 0개)로 끝난다. */
    @Test
    void exactlyOneFullBatch() {
        long bystander = bystander();
        long m = member();
        expiredSessions(m, 200, i -> i % 2);
        Map<String, Object> kept = kept(bystander);
        List<Long> remaining = observeRemainingAtEachBatch(m);

        cleanupService.cleanUp();

        assertThat(remaining).containsExactly(200L, 0L);
        assertThat(count("auth_sessions WHERE member_id = " + m)).isZero();
        assertThat(kept(bystander)).as("대상 외 세션·이력·회원·기록·사진 불변").isEqualTo(kept);
        assertThat(orphanTokens()).isZero();
    }

    /**
     * 401개 대상(만료 순서는 PK와 반대, 두 번째 배치는 Refresh 이력이 없음)은 200·200·1개 세 배치로 누락 없이 지운다. 각 배치의 후보 조회
     * 직전에 별도 커넥션으로 본 남은 대상 수와 가장 작은 ID가 401/첫 ID → 201/201번째 → 1/401번째 → 0이므로, 앞 배치가 PK 순으로 이미
     * commit된 뒤에 다음 배치가 시작됐다. 이력을 지우지 않은 배치가 있어도 반복을 멈추지 않는다.
     */
    @Test
    void manyBatchesCommitBeforeTheNextBatch(CapturedOutput output) {
        long bystander = bystander();
        long m = member();
        List<Long> ids = expiredSessions(m, 401, i -> i >= 200 && i < 400 ? 0 : 2);
        Map<String, Object> kept = kept(bystander);
        List<Long> minIds = new CopyOnWriteArrayList<>();
        List<Long> remaining = observeRemainingAtEachBatch(m, minIds);

        cleanupService.cleanUp();

        assertThat(remaining).containsExactly(401L, 201L, 1L, 0L);
        assertThat(minIds).containsExactly(ids.get(0), ids.get(200), ids.get(400));
        assertThat(count("auth_sessions WHERE member_id = " + m)).isZero();
        assertThat(kept(bystander)).as("대상 외 세션·이력·회원·기록·사진 불변").isEqualTo(kept);
        assertThat(orphanTokens()).isZero();
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=401, refreshTokens=402");
    }

    /**
     * token 삭제 뒤 session 삭제가 실패한 두 번째 배치는 token·session 삭제가 함께 rollback되고 첫 배치 commit은 남는다. 실행은 실패
     * 예외로 끝나며 세 번째 배치를 조회하지 않고 완료 로그도 없다. 실패 직전 같은 커넥션에서는 그 배치의 이력이 지워진 상태였다.
     */
    @Test
    void sessionDeleteFailureRollsBackOnlyThatBatch(CapturedOutput output) {
        long bystander = bystander();
        long m = member();
        List<Long> ids = expiredSessions(m, 450, i -> 2);
        Map<String, Object> kept = kept(bystander);
        List<Map<String, Object>> sessions = sessions();
        List<Map<String, Object>> tokens = tokens();
        long otherTokens = count("auth_refresh_tokens") - 900;
        long otherSessions = count("auth_sessions") - 450;
        CleanupDataSource.FAIL_SESSION_DELETE_AT.set(2);

        assertThatThrownBy(cleanupService::cleanUp).isInstanceOf(DataAccessException.class);

        assertThat(CleanupDataSource.SEEN_BEFORE_FAILURE.get()).as("실패 직전 같은 커넥션: 세션 250개 남음, 2배치 이력 삭제됨")
                .containsExactly(otherSessions + 250, otherTokens + 100);
        Set<Long> rolledBack = Set.copyOf(ids.subList(200, 450));
        assertThat(only(sessions(), "member_id", Set.of(m))).isEqualTo(only(sessions, "id", rolledBack));
        assertThat(only(tokens(), "session_id", Set.copyOf(ids))).isEqualTo(only(tokens, "session_id", rolledBack));
        assertThat(CleanupDataSource.BATCHES.get()).as("세 번째 배치 없음").isEqualTo(2);
        assertThat(kept(bystander)).as("대상 외 세션·이력·회원·기록·사진 불변").isEqualTo(kept);
        assertThat(output.getAll().contains("정리 완료")).isFalse();
    }

    /**
     * 삭제가 같은 커넥션에 반영된 뒤 JDBC commit 직전 실패한 두 번째 배치는 rollback되어 새 조회에서 복원돼 있고 첫 배치 commit은 남는다.
     * 스케줄러 경계에서 실패를 메시지 없이(주입한 SQL 오류 문구 없음) 남기며 완료로 기록하지 않는다. 장애가 사라진 다음 실행은 나머지를 지운다.
     */
    @Test
    void commitFailureRollsBackThatBatchAndIsLoggedWithoutMessage(CapturedOutput output) {
        long bystander = bystander();
        long m = member();
        List<Long> ids = expiredSessions(m, 450, i -> 1);
        Map<String, Object> kept = kept(bystander);
        List<Map<String, Object>> sessions = sessions();
        List<Map<String, Object>> tokens = tokens();
        long otherTokens = count("auth_refresh_tokens") - 450;
        long otherSessions = count("auth_sessions") - 450;
        CleanupDataSource.FAIL_COMMIT_AT.set(2);

        new ExpiredAuthSessionCleanupScheduler(cleanupService).cleanUp();

        assertThat(CleanupDataSource.SEEN_BEFORE_FAILURE.get()).as("commit 직전 같은 커넥션: 2배치 세션·이력 삭제 반영")
                .containsExactly(otherSessions + 50, otherTokens + 50);
        Set<Long> rolledBack = Set.copyOf(ids.subList(200, 450));
        assertThat(only(sessions(), "member_id", Set.of(m))).isEqualTo(only(sessions, "id", rolledBack));
        assertThat(only(tokens(), "session_id", Set.copyOf(ids))).isEqualTo(only(tokens, "session_id", rolledBack));
        assertThat(CleanupDataSource.BATCHES.get()).isEqualTo(2);
        String logs = output.getAll();
        assertThat(logs.contains("만료 인증 세션 정리 실패")).isTrue();
        assertThat(logs.contains("SECRET-SENTINEL")).as("주입한 오류 메시지 없음").isFalse();
        assertThat(logs.contains("injected commit failure")).isFalse();
        assertThat(logs.contains("정리 완료")).isFalse();

        cleanupService.cleanUp();

        assertThat(count("auth_sessions WHERE member_id = " + m)).isZero();
        assertThat(kept(bystander)).as("대상 외 세션·이력·회원·기록·사진 불변").isEqualTo(kept);
        assertThat(orphanTokens()).isZero();
    }

    /**
     * 실제 잠금 범위: 만료 세션 260개 사이사이에 활성 세션을 섞어 두고(활성 세션 바로 뒤가 대상), 정리가 첫 배치를 쥔 채 멈춘 두 시점(후보를
     * 모두 잠근 뒤 이력 삭제 직전, 이력·세션을 지운 뒤 commit 직전)에 performance_schema.data_locks를 본다. 이번 DB의 레코드 잠금은 모두
     * X,REC_NOT_GAP(gap·next-key·supremum 없음)이고, auth_sessions PRIMARY 행 ID가 고른 200개와 같으며, 이력 잠금은 그 세션들의 이력뿐이고
     * 회원 잠금은 없다. 정리 트랜잭션은 READ COMMITTED다. 그동안 다른 연결은 대상 밖 세션·이력을 FOR UPDATE NOWAIT로 바로 잠그고, 새
     * 세션과 대상 바로 앞 활성 세션의 새 Refresh 이력을 기다림 없이 INSERT할 수 있다. 고른 세션은 NOWAIT로 잠글 수 없다(탐침 대조).
     */
    @Test
    void locksOnlyTheSelectedSessionRows() throws Exception {
        long m = member();
        Mixed fixture = mixedSessions(m, 260, 40);
        List<Long> selected = fixture.expired().subList(0, 200);
        List<Long> free = new ArrayList<>(fixture.active());
        free.addAll(fixture.expired().subList(200, 260));
        long activeBeforeTarget = fixture.active().get(0);
        assertThat(selected).as("활성 세션 바로 뒤가 고른 대상").contains(activeBeforeTarget + 1);
        Set<Long> targetTokens = Set.copyOf(jdbc.queryForList("SELECT id FROM auth_refresh_tokens WHERE session_id IN ("
                + joined(selected) + ")", Long.class));
        long activeToken = jdbc.queryForObject("SELECT id FROM auth_refresh_tokens WHERE session_id = ?", Long.class,
                activeBeforeTarget);
        List<Map<String, Object>> active = only(sessions(), "id", fixture.active());
        List<Map<String, Object>> activeTokens = only(tokens(), "session_id", fixture.active());
        Pause locked = new Pause();
        Pause deleted = new Pause();
        CleanupDataSource.BEFORE_TOKEN_DELETE.set(locked);
        CleanupDataSource.BEFORE_COMMIT.set(deleted);

        Future<Throwable> cleanup = pool.submit(() -> outcome(cleanupService::cleanUp));
        try {
            locked.awaitReached();
            assertThat(CleanupDataSource.ROW_LOCKS.get()).as("후보마다 PK 단건 잠금 1회").isEqualTo(200);
            assertOnlyRowsLocked(selected, Set.of());
            assertOthersUsable(selected, free, activeToken, activeBeforeTarget, m);
            locked.release();
            deleted.awaitReached();
            assertOnlyRowsLocked(selected, targetTokens);
            assertOthersUsable(selected, free, activeToken, activeBeforeTarget, m);
        } finally {
            locked.release();
            deleted.release();
        }
        assertThat(cleanup.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNull();

        assertThat(only(sessions(), "member_id", Set.of(m))).isEqualTo(active);
        assertThat(only(tokens(), "session_id", Set.copyOf(fixture.expired()))).isEmpty();
        assertThat(only(tokens(), "session_id", fixture.active())).isEqualTo(activeTokens);
        assertThat(CleanupDataSource.BATCHES.get()).as("후보 조회: 200·60·0").isEqualTo(3);
        assertThat(CleanupDataSource.ROW_LOCKS.get()).isEqualTo(260);
    }

    /**
     * 정리가 멈춘 동안 이번 DB의 레코드 잠금을 테이블·인덱스별로 나눠 본다. 모두 한 트랜잭션(READ COMMITTED)의 X,REC_NOT_GAP이어야 하고,
     * auth_sessions는 PRIMARY의 고른 세션 행만, auth_refresh_tokens는 PRIMARY의 그 세션 이력 행과 보조 인덱스의 그 세션 항목만, 회원은
     * 없음이다.
     */
    private void assertOnlyRowsLocked(List<Long> sessions, Set<Long> tokenIds) {
        List<Map<String, Object>> locks = jdbc.queryForList("SELECT ENGINE_TRANSACTION_ID AS trx, OBJECT_NAME AS tbl, "
                + "INDEX_NAME AS idx, LOCK_MODE AS mode, LOCK_DATA AS data FROM performance_schema.data_locks "
                + "WHERE OBJECT_SCHEMA = DATABASE() AND LOCK_TYPE = 'RECORD'");
        assertThat(locks).filteredOn(lock -> !"X,REC_NOT_GAP".equals(lock.get("mode")))
                .extracting(lock -> lock.get("tbl") + " " + lock.get("idx") + " " + lock.get("mode") + " " + lock.get("data"))
                .as("행 잠금이 아닌 레코드 잠금(gap·next-key·supremum)").isEmpty();
        assertThat(locks).filteredOn(lock -> "auth_sessions".equals(lock.get("tbl")) && !"PRIMARY".equals(lock.get("idx")))
                .as("auth_sessions 보조 인덱스 잠금").isEmpty();
        assertThat(rowIds(locks, "auth_sessions", "PRIMARY")).as("auth_sessions PRIMARY 행 잠금 ID").isEqualTo(Set.copyOf(sessions));
        assertThat(rowIds(locks, "auth_refresh_tokens", "PRIMARY")).as("auth_refresh_tokens PRIMARY 행 잠금 ID").isEqualTo(tokenIds);
        assertThat(locks).filteredOn(lock -> "auth_refresh_tokens".equals(lock.get("tbl")) && !"PRIMARY".equals(lock.get("idx")))
                .extracting(lock -> Long.valueOf(((String) lock.get("data")).split(",")[0].strip()))
                .as("이력 보조 인덱스 잠금의 session_id").allMatch(sessions::contains);
        assertThat(locks).filteredOn(lock -> "members".equals(lock.get("tbl"))).as("회원 행 잠금").isEmpty();
        Set<Object> transactions = locks.stream().map(lock -> lock.get("trx")).collect(Collectors.toSet());
        assertThat(transactions).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT trx_isolation_level FROM information_schema.INNODB_TRX WHERE trx_id = ?",
                String.class, transactions.iterator().next())).isEqualTo("READ COMMITTED");
    }

    /**
     * 다른 연결이 대상 밖 세션과 활성 세션의 이력을 FOR UPDATE NOWAIT로 바로 잠그고, 새 세션과 대상 바로 앞 활성 세션의 다음 세대 이력을
     * 기다림 없이(잠금 대기 1초 안) INSERT할 수 있다(모두 rollback). 고른 세션은 NOWAIT가 3572로 거부된다.
     */
    private void assertOthersUsable(List<Long> locked, List<Long> free, long activeToken, long activeBeforeTarget,
                                    long memberId) throws SQLException {
        for (long id : free) {
            try (Connection probe = lockRow("auth_sessions", id, " NOWAIT")) {
                probe.rollback();
            }
        }
        try (Connection probe = lockRow("auth_refresh_tokens", activeToken, " NOWAIT")) {
            probe.rollback();
        }
        insertWithoutWaiting("INSERT INTO auth_sessions (member_id, session_key, current_refresh_generation, expires_at, "
                + "created_at, updated_at) VALUES (?, ?, 0, FROM_UNIXTIME(?), '2026-09-01 00:00:00', '2026-09-01 00:00:00')",
                memberId, UUID.randomUUID().toString(), epochMicros(NOW.plus(14, ChronoUnit.DAYS)));
        insertWithoutWaiting("INSERT INTO auth_refresh_tokens (session_id, token_hash, generation, expires_at, created_at, "
                + "updated_at) VALUES (?, ?, 1, FROM_UNIXTIME(?), '2026-09-01 00:00:00', '2026-09-01 00:00:00')",
                activeBeforeTarget, sha256(randomToken()), epochMicros(NOW.plus(10, ChronoUnit.DAYS)));
        assertThatThrownBy(() -> lockRow("auth_sessions", locked.get(100), " NOWAIT").close())
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(3572));
    }

    /** 다른 연결에서 잠금 대기 1초로 INSERT하고 rollback한다. 정리가 그 자리를 잠갔으면 lock wait timeout으로 실패한다. */
    private void insertWithoutWaiting(String sql, Object... args) throws SQLException {
        try (Connection probe = dataSource.getConnection()) {
            try (Statement statement = probe.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
            }
            probe.setAutoCommit(false);
            try (PreparedStatement statement = probe.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                assertThat(statement.executeUpdate()).isEqualTo(1);
            } finally {
                probe.rollback();
                probe.setAutoCommit(true);
                try (Statement statement = probe.createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
            }
        }
    }

    private static Set<Long> rowIds(List<Map<String, Object>> locks, String table, String index) {
        return locks.stream().filter(lock -> table.equals(lock.get("tbl")) && index.equals(lock.get("idx")))
                .map(lock -> Long.valueOf((String) lock.get("data"))).collect(Collectors.toSet());
    }

    private static String joined(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 후보 조회 직후 다른 트랜잭션이 지우는 후보 범위. */
    enum Vanish {
        ALL, HALF
    }

    /**
     * 후보 조회 뒤 첫 행 잠금 전에 다른 트랜잭션(다른 정리 실행에 해당)이 그 배치 후보의 전부·절반을 지우고 commit하면, 정리는 사라진 행을
     * 건너뛰고(전부면 그 배치는 0개 삭제) 실행을 끝내지 않는다. 다음 배치가 새 트랜잭션에서 후보를 다시 골라 남은 대상을 모두 지우고, 후보
     * 조회가 비어야 끝난다. 완료 로그는 이번 실행이 지운 건수다.
     */
    @ParameterizedTest
    @EnumSource(Vanish.class)
    void candidatesDeletedBeforeLockAreSkippedWithoutEndingTheRun(Vanish vanish, CapturedOutput output) {
        long bystander = bystander();
        long m = member();
        List<Long> ids = expiredSessions(m, 450, i -> 1);
        Map<String, Object> kept = kept(bystander);
        List<Long> gone = ids.subList(0, vanish == Vanish.ALL ? 200 : 100);
        CleanupDataSource.BEFORE_FIRST_ROW_LOCK.set(() -> deleteElsewhere(gone));

        cleanupService.cleanUp();

        assertThat(count("auth_sessions WHERE member_id = " + m)).isZero();
        assertThat(CleanupDataSource.BATCHES.get()).as("후보 조회: 200·200·50·0").isEqualTo(4);
        assertThat(CleanupDataSource.ROW_LOCKS.get()).as("사라진 후보도 잠금을 시도").isEqualTo(450);
        assertThat(kept(bystander)).isEqualTo(kept);
        assertThat(orphanTokens()).isZero();
        int mine = 450 - gone.size();
        assertThat(output.getAll()).contains("만료 인증 세션 정리 완료: sessions=" + mine + ", refreshTokens=" + mine);
    }

    /**
     * 실제 MySQL 잠금 대기 timeout(테스트 커넥션 1초)으로 행 잠금이 실패하면 그 배치는 아무것도 지우지 않고 실행이 실패로 끝난다. 스케줄러는
     * MySQL 오류 문구 없이 실패만 남기고 빈 후보·완료로 바꾸지 않는다. 잠금이 풀린 뒤 다음 실행은 정상 정리한다.
     */
    @Test
    void lockWaitTimeoutFailsTheRunWithoutSqlMessage(CapturedOutput output) throws Exception {
        long m = member();
        List<Long> ids = expiredSessions(m, 3, i -> 1);
        Map<String, Object> before = everything();

        try (Connection holder = lockRow("auth_sessions", ids.get(1))) {
            CleanupDataSource.SHORT_LOCK_WAIT.set(true);
            Future<?> run = pool.submit(new ExpiredAuthSessionCleanupScheduler(cleanupService)::cleanUp);
            awaitLockWaits("auth_sessions", run);
            run.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            CleanupDataSource.SHORT_LOCK_WAIT.set(false);
            holder.rollback();
        }

        assertThat(everything()).isEqualTo(before);
        String logs = output.getAll();
        assertThat(logs.contains("만료 인증 세션 정리 실패")).isTrue();
        assertThat(logs.contains("Lock wait timeout exceeded")).as("MySQL 오류 문구 없음").isFalse();
        assertThat(logs.contains("정리 완료")).isFalse();

        cleanupService.cleanUp();
        assertThat(count("auth_sessions WHERE member_id = " + m)).isZero();
    }

    /** 대상 세션에 대해 정리와 겹치는 인증 작업. */
    enum Operation {
        REFRESH, LOGOUT
    }

    /**
     * 인증 작업 선행: Refresh·로그아웃이 회원·대상 세션을 잠그고 토큰을 기다리는 동안 정리는 그 세션 행을 기다린다(실제 대기 관측). 인증
     * 작업이 끝나면(Refresh는 만료 세션 AUTH_003·변경 없음, 로그아웃은 정상) 정리가 이어서 그 세션과 이력을 모두 지운다. 이후 같은 토큰은
     * Refresh AUTH_005(행 없음)·로그아웃 정상이다. 같은 회원의 다른 세션·회원은 그대로이고 고아 이력·부분 삭제가 없다.
     */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void cleanupWaitsForAuthOperationHoldingTheSession(Operation operation) throws Exception {
        Contended fixture = contended();
        Map<String, Object> others = others(fixture);

        Future<Throwable> auth;
        Future<Throwable> cleanup;
        try (Connection holder = lockRow("auth_refresh_tokens", fixture.currentTokenId())) {
            auth = pool.submit(() -> run(operation, fixture.currentToken()));
            awaitLockWaits("auth_refresh_tokens", auth);
            cleanup = pool.submit(() -> outcome(cleanupService::cleanUp));
            awaitLockWaits("auth_sessions", auth, cleanup);
            holder.rollback();
        }

        expect(operation, auth.get(WAIT.toSeconds(), TimeUnit.SECONDS), ErrorCode.AUTH_SESSION_INVALID);
        assertThat(cleanup.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNull();
        assertGoneAndOthersKept(fixture, others);
        expect(operation, run(operation, fixture.currentToken()), ErrorCode.AUTH_REFRESH_INVALID);
    }

    /**
     * 정리 선행: 정리가 대상 세션을 잠근 채(이력 삭제 직전에 멈춤) 회원은 잠그지 않으므로 Refresh·로그아웃은 회원을 잡고 그 세션 행을
     * 기다린다(실제 대기 관측). 정리가 token→session을 지우고 commit하면 Refresh는 잠근 세션이 없어 AUTH_003, 로그아웃은 변경 없이 정상이다.
     * 같은 회원의 다른 세션·회원은 그대로이고 고아 이력·부분 삭제가 없다.
     */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void authOperationWaitsForCleanupHoldingTheSession(Operation operation) throws Exception {
        Contended fixture = contended();
        Map<String, Object> others = others(fixture);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CleanupDataSource.BEFORE_TOKEN_DELETE.set(() -> {
            locked.countDown();
            await(release);
        });

        Future<Throwable> cleanup = pool.submit(() -> outcome(cleanupService::cleanUp));
        try {
            assertThat(locked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("정리가 대상 세션을 잠갔다").isTrue();
            try (Connection probe = lockRow("members", fixture.memberId(), " NOWAIT")) {
                probe.rollback(); // 정리 중에도 회원 행은 바로 잠글 수 있다(정리는 회원을 잠그지 않는다).
            }
            Future<Throwable> auth = pool.submit(() -> run(operation, fixture.currentToken()));
            awaitLockWaits("auth_sessions", auth, cleanup);
            release.countDown();

            assertThat(cleanup.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNull();
            expect(operation, auth.get(WAIT.toSeconds(), TimeUnit.SECONDS), ErrorCode.AUTH_SESSION_INVALID);
        } finally {
            release.countDown();
        }
        assertGoneAndOthersKept(fixture, others);
        expect(operation, run(operation, fixture.currentToken()), ErrorCode.AUTH_REFRESH_INVALID);
    }

    /** 경합 fixture: 한 회원의 정리 대상 세션(소비 1·현재 1 이력)과 활성 세션, 기록·사진. */
    private record Contended(long memberId, long target, long currentTokenId, String currentToken, long active) {
    }

    private Contended contended() {
        long m = member();
        long target = session(m, CUTOFF.minus(1, ChronoUnit.DAYS), null, null);
        List<String> raws = tokens(target, 2);
        long currentTokenId = jdbc.queryForObject("SELECT id FROM auth_refresh_tokens WHERE session_id = ? AND "
                + "generation = 1", Long.class, target);
        long active = session(m, NOW.plus(10, ChronoUnit.DAYS), null, null);
        tokens(active, 1);
        records(m, 1);
        return new Contended(m, target, currentTokenId, raws.get(1), active);
    }

    /** 경합 대상 이외의 상태(활성 세션·이력·회원·기록·사진). */
    private Map<String, Object> others(Contended fixture) {
        Set<Long> active = Set.of(fixture.active());
        return Map.of("sessions", only(sessions(), "id", active), "tokens", only(tokens(), "session_id", active),
                "preserved", preserved());
    }

    private void assertGoneAndOthersKept(Contended fixture, Map<String, Object> others) {
        assertThat(count("auth_sessions WHERE id = " + fixture.target())).isZero();
        assertThat(count("auth_refresh_tokens WHERE session_id = " + fixture.target())).isZero();
        assertThat(others(fixture)).isEqualTo(others);
        assertThat(orphanTokens()).isZero();
    }

    private Throwable run(Operation operation, String refreshToken) {
        return outcome(() -> {
            if (operation == Operation.REFRESH) {
                refreshService.refresh(refreshToken);
            } else {
                logoutService.logout(refreshToken);
            }
        });
    }

    /** Refresh는 기대 코드의 BusinessException, 로그아웃은 예외 없음(행이 사라져도 정상). */
    private static void expect(Operation operation, Throwable result, ErrorCode refreshCode) {
        if (operation == Operation.LOGOUT) {
            assertThat(result).isNull();
        } else {
            assertThat(result).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) result).getErrorCode()).isEqualTo(refreshCode);
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static Throwable outcome(Action action) {
        try {
            action.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 정리를 풀어 주지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 각 배치의 후보 조회 직전에 별도 커넥션(autocommit)으로 그 회원의 남은 세션 수와 가장 작은 ID를 기록한다. 정리 스레드의 JdbcTemplate은
     * 진행 중인 정리 트랜잭션 커넥션을 같이 쓰므로(commit 전 삭제도 보임) DataSource에서 직접 커넥션을 빌린다.
     */
    private List<Long> observeRemainingAtEachBatch(long memberId, List<Long> minIds) {
        List<Long> remaining = new CopyOnWriteArrayList<>();
        CleanupDataSource.BEFORE_CANDIDATE_QUERY.set(batch -> {
            try (Connection separate = dataSource.getConnection();
                 PreparedStatement statement = separate.prepareStatement("SELECT COUNT(*), MIN(id) FROM auth_sessions "
                         + "WHERE member_id = ?")) {
                assertThat(separate.getAutoCommit()).isTrue();
                statement.setLong(1, memberId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    remaining.add(rs.getLong(1));
                    long minId = rs.getLong(2);
                    if (!rs.wasNull()) {
                        minIds.add(minId);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        return remaining;
    }

    private List<Long> observeRemainingAtEachBatch(long memberId) {
        return observeRemainingAtEachBatch(memberId, new ArrayList<>());
    }

    // ---- fixture: 시각은 FROM_UNIXTIME(UTC epoch)으로 넣는다(세션 시간대와 무관한 TIMESTAMP 저장) ----

    private long member() {
        String key = "it08-" + (++memberSeq);
        jdbc.update("INSERT INTO members (identity_provider, provider_user_id, status, created_at, updated_at) "
                + "VALUES ('TOSS_ANON', ?, 'ACTIVE', '2026-09-01 00:00:00', '2026-09-01 00:00:00')", key);
        return jdbc.queryForObject("SELECT id FROM members WHERE provider_user_id = ?", Long.class, key);
    }

    private long session(long memberId, Instant expiresAt, Instant revokedAt, String reason) {
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_sessions (member_id, session_key, current_refresh_generation, expires_at, revoked_at, "
                + "revoke_reason, created_at, updated_at) VALUES (?, ?, 0, FROM_UNIXTIME(?), FROM_UNIXTIME(?), ?, "
                + "'2026-09-01 00:00:00', '2026-09-01 00:00:00')", memberId, key, epochMicros(expiresAt),
                revokedAt == null ? null : epochMicros(revokedAt), reason);
        return jdbc.queryForObject("SELECT id FROM auth_sessions WHERE session_key = ?", Long.class, key);
    }

    /** 세션 만료와 같은 만료의 Refresh 이력 count개(세대 0..count-1). 마지막이 현재 토큰, 앞은 소비된 이력이다. 원문을 반환한다. */
    private List<String> tokens(long sessionId, int count) {
        BigDecimal expiresAt = epoch(sessionId);
        List<String> raws = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int generation = 0; generation < count; generation++) {
            String raw = randomToken();
            raws.add(raw);
            rows.add(new Object[] {sessionId, sha256(raw), generation, expiresAt,
                    generation < count - 1 ? expiresAt.subtract(BigDecimal.valueOf(86_400L * 13)) : null});
        }
        insertTokens(rows);
        if (count > 0) {
            jdbc.update("UPDATE auth_sessions SET current_refresh_generation = ? WHERE id = ?", count - 1, sessionId);
        }
        return raws;
    }

    /** 한 회원의 정리 대상 세션 count개(PK가 클수록 먼저 만료)를 만들고 i번째에 이력 tokenCount(i)개를 붙인다. ID를 PK 순으로 반환한다. */
    private List<Long> expiredSessions(long memberId, int count, IntUnaryOperator tokenCount) {
        List<Object[]> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sessions.add(new Object[] {memberId, UUID.randomUUID().toString(),
                    epochMicros(CUTOFF.minus(1, ChronoUnit.DAYS).minusSeconds(i))});
        }
        jdbc.batchUpdate("INSERT INTO auth_sessions (member_id, session_key, current_refresh_generation, expires_at, "
                + "created_at, updated_at) VALUES (?, ?, 0, FROM_UNIXTIME(?), '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", sessions);
        List<Long> ids = jdbc.queryForList("SELECT id FROM auth_sessions WHERE member_id = ? ORDER BY id", Long.class,
                memberId);
        List<Object[]> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal expiresAt = epochMicros(CUTOFF.minus(1, ChronoUnit.DAYS).minusSeconds(i));
            for (int generation = 0; generation < tokenCount.applyAsInt(i); generation++) {
                tokens.add(new Object[] {ids.get(i), sha256(randomToken()), generation, expiresAt, null});
            }
        }
        insertTokens(tokens);
        return ids;
    }

    /** 정리 대상 세션과 활성 세션의 ID(각각 PK 순). */
    private record Mixed(List<Long> expired, List<Long> active) {
    }

    /**
     * 한 회원의 정리 대상 세션 expiredCount개(PK가 클수록 먼저 만료) 사이에 every개마다 활성 세션 1개를 끼우고 끝에도 1개를 둔다. 세션마다
     * 이력 1개를 붙인다.
     */
    private Mixed mixedSessions(long memberId, int expiredCount, int every) {
        List<Boolean> expiredFlags = new ArrayList<>();
        List<Object[]> sessions = new ArrayList<>();
        for (int i = 0; i <= expiredCount; i++) {
            if (i % every == every / 2 || i == expiredCount) {
                expiredFlags.add(false);
                sessions.add(new Object[] {memberId, UUID.randomUUID().toString(),
                        epochMicros(NOW.plus(10, ChronoUnit.DAYS))});
            }
            if (i < expiredCount) {
                expiredFlags.add(true);
                sessions.add(new Object[] {memberId, UUID.randomUUID().toString(),
                        epochMicros(CUTOFF.minus(1, ChronoUnit.DAYS).minusSeconds(i))});
            }
        }
        jdbc.batchUpdate("INSERT INTO auth_sessions (member_id, session_key, current_refresh_generation, expires_at, "
                + "created_at, updated_at) VALUES (?, ?, 0, FROM_UNIXTIME(?), '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", sessions);
        List<Long> ids = jdbc.queryForList("SELECT id FROM auth_sessions WHERE member_id = ? ORDER BY id", Long.class,
                memberId);
        List<Long> expired = new ArrayList<>();
        List<Long> active = new ArrayList<>();
        List<Object[]> tokens = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            (expiredFlags.get(i) ? expired : active).add(ids.get(i));
            tokens.add(new Object[] {ids.get(i), sha256(randomToken()), 0, sessions.get(i)[2], null});
        }
        insertTokens(tokens);
        return new Mixed(expired, active);
    }

    /** 다른 커넥션(autocommit)에서 세션들의 이력 → 세션을 지우고 commit한다(다른 정리 실행에 해당). */
    private void deleteElsewhere(List<Long> sessionIds) {
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            assertThat(other.getAutoCommit()).isTrue();
            statement.executeUpdate("DELETE FROM auth_refresh_tokens WHERE session_id IN (" + joined(sessionIds) + ")");
            statement.executeUpdate("DELETE FROM auth_sessions WHERE id IN (" + joined(sessionIds) + ")");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 정리 스레드를 hook 지점에서 멈추고 테스트가 풀어 준다. */
    private static final class Pause implements Runnable {

        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void run() {
            reached.countDown();
            await(released);
        }

        void awaitReached() throws InterruptedException {
            assertThat(reached.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("정리가 멈춤 지점에 도달").isTrue();
        }

        void release() {
            released.countDown();
        }
    }

    private void insertTokens(List<Object[]> rows) {
        jdbc.batchUpdate("INSERT INTO auth_refresh_tokens (session_id, token_hash, generation, expires_at, consumed_at, "
                + "created_at, updated_at) VALUES (?, ?, ?, FROM_UNIXTIME(?), FROM_UNIXTIME(?), '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", rows);
    }

    /** 회원의 기록 count개와 기록마다 사진 2장. */
    private void records(long memberId, int count) {
        for (int i = 0; i < count; i++) {
            String memo = "memo-" + memberId + "-" + i;
            jdbc.update("INSERT INTO records (member_id, record_date, season, memo, created_at, updated_at) VALUES "
                    + "(?, '2026-09-01', 'AUTUMN', ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00')", memberId, memo);
            long recordId = jdbc.queryForObject("SELECT id FROM records WHERE memo = ?", Long.class, memo);
            for (int order = 0; order < 2; order++) {
                jdbc.update("INSERT INTO images (record_id, original_key, thumbnail_key, source, sort_order, created_at) "
                        + "VALUES (?, ?, NULL, 'CAMERA', ?, '2026-09-01 00:00:00')", recordId,
                        "original/" + recordId + "/" + order, order);
            }
        }
    }

    /** 정리 대상이 아닌 회원의 세션(미만료·정확히 cutoff)·이력·기록·사진을 만들고 그 회원 ID를 반환한다. */
    private long bystander() {
        long m = member();
        tokens(session(m, NOW.plus(10, ChronoUnit.DAYS), null, null), 2);
        tokens(session(m, CUTOFF, null, null), 1);
        records(m, 1);
        return m;
    }

    /** 그 회원의 세션·이력과 회원·기록·사진 전체. fixture를 모두 만든 뒤에 찍는다. */
    private Map<String, Object> kept(long bystander) {
        Set<Long> ids = Set.copyOf(jdbc.queryForList("SELECT id FROM auth_sessions WHERE member_id = ?", Long.class,
                bystander));
        return Map.of("sessions", only(sessions(), "id", ids), "tokens", only(tokens(), "session_id", ids),
                "preserved", preserved());
    }

    // ---- 상태 조회(UTC epoch는 UNIX_TIMESTAMP로 읽어 JVM·세션 시간대와 무관) ----

    private List<Map<String, Object>> sessions() {
        return jdbc.queryForList("SELECT id, member_id, session_key, current_refresh_generation, UNIX_TIMESTAMP(expires_at) "
                + "AS expires_at, UNIX_TIMESTAMP(revoked_at) AS revoked_at, revoke_reason, created_at, updated_at "
                + "FROM auth_sessions ORDER BY id");
    }

    /** Refresh 이력(해시 값은 비교·출력하지 않는다. 해시는 변경 불가 컬럼이다). */
    private List<Map<String, Object>> tokens() {
        return jdbc.queryForList("SELECT id, session_id, generation, UNIX_TIMESTAMP(expires_at) AS expires_at, "
                + "UNIX_TIMESTAMP(consumed_at) AS consumed_at, created_at, updated_at FROM auth_refresh_tokens ORDER BY id");
    }

    /** 정리가 바꾸면 안 되는 회원·기록·사진 전체. */
    private Map<String, Object> preserved() {
        return Map.of("members", jdbc.queryForList("SELECT * FROM members ORDER BY id"),
                "records", jdbc.queryForList("SELECT * FROM records ORDER BY id"),
                "images", jdbc.queryForList("SELECT * FROM images ORDER BY id"));
    }

    private Map<String, Object> everything() {
        return Map.of("sessions", sessions(), "tokens", tokens(), "preserved", preserved());
    }

    private List<Map<String, Object>> only(List<Map<String, Object>> rows, String column, Collection<Long> values) {
        return rows.stream().filter(row -> values.contains(((Number) row.get(column)).longValue())).toList();
    }

    private long orphanTokens() {
        return count("auth_refresh_tokens t LEFT JOIN auth_sessions s ON s.id = t.session_id WHERE s.id IS NULL");
    }

    private long count(String from) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class);
    }

    private BigDecimal epoch(long sessionId) {
        return jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expires_at) FROM auth_sessions WHERE id = ?", BigDecimal.class,
                sessionId);
    }

    /** 테스트 쪽 트랜잭션으로 행을 잠근다. */
    private Connection lockRow(String table, long id) throws SQLException {
        return lockRow(table, id, "");
    }

    private Connection lockRow(String table, long id, String option) throws SQLException {
        Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        try (PreparedStatement statement = holder.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE"
                + option)) {
            statement.setLong(1, id);
            statement.executeQuery().close();
        } catch (SQLException e) {
            holder.close();
            throw e;
        }
        return holder;
    }

    /** 이번 DB의 해당 테이블에서 레코드 잠금 대기가 생길 때까지 기다린다(대기 없이 끝나면 실패). */
    private void awaitLockWaits(String table, Future<?>... running) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (Future<?> future : running) {
                if (future.isDone()) {
                    fail(table + " 잠금 대기 없이 작업이 끝났다: " + future.get());
                }
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = w.ENGINE AND requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = ?
                    """, Integer.class, table);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        fail(table + " 행 잠금 대기가 생기지 않았다");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** UTC epoch 초(마이크로초 자리까지). */
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

    /**
     * 정리 SQL을 알아보는 테스트 전용 DataSource 래퍼. 켤 때만 동작한다.
     *
     * <ul>
     *   <li>정리 후보 조회({@code SELECT id FROM auth_sessions WHERE expires_at < ?}, 잠금 없음)를 준비할 때마다 배치 번호를 올리고 그
     *   커넥션에 기억한다. {@link #BEFORE_CANDIDATE_QUERY}가 있으면 조회 직전에 부른다.</li>
     *   <li>그 커넥션의 세션 PK 단건 잠금({@code ... from auth_sessions ... for update})을 {@link #ROW_LOCKS}로 센다.
     *   {@link #BEFORE_FIRST_ROW_LOCK}: 후보 조회 뒤 첫 행 잠금 직전에 한 번 부른다(그사이 다른 트랜잭션의 삭제 재현).</li>
     *   <li>{@link #BEFORE_TOKEN_DELETE}: 후보를 모두 잠근 뒤 이력 삭제 직전에 한 번 부른다(정리 일시 정지).</li>
     *   <li>{@link #BEFORE_COMMIT}: 그 배치가 이력·세션을 지운 뒤 JDBC commit 직전에 한 번 부른다(정리 일시 정지).</li>
     *   <li>{@link #FAIL_SESSION_DELETE_AT}: 그 배치의 세션 삭제 준비를 한 번 실패시킨다(이력은 이미 삭제됨).</li>
     *   <li>{@link #FAIL_COMMIT_AT}: 그 배치 커넥션의 JDBC commit을 실제 commit 전에 한 번 실패시킨다.</li>
     *   <li>두 실패 모두 직전에 그 커넥션에서 본 {전체 세션 수, 전체 이력 수}를 {@link #SEEN_BEFORE_FAILURE}에 남긴다. 실패 메시지에는
     *   로그에 나오면 안 되는 표식을 넣는다.</li>
     *   <li>{@link #SHORT_LOCK_WAIT}: 켜진 동안 빌린 커넥션의 innodb_lock_wait_timeout을 1초로 두고 반납 전에 되돌린다.</li>
     * </ul>
     */
    static final class CleanupDataSource extends DelegatingDataSource {

        static final AtomicInteger BATCHES = new AtomicInteger();
        static final AtomicInteger ROW_LOCKS = new AtomicInteger();
        static final AtomicReference<IntConsumer> BEFORE_CANDIDATE_QUERY = new AtomicReference<>();
        static final AtomicReference<Runnable> BEFORE_FIRST_ROW_LOCK = new AtomicReference<>();
        static final AtomicReference<Runnable> BEFORE_TOKEN_DELETE = new AtomicReference<>();
        static final AtomicReference<Runnable> BEFORE_COMMIT = new AtomicReference<>();
        static final AtomicInteger FAIL_SESSION_DELETE_AT = new AtomicInteger();
        static final AtomicInteger FAIL_COMMIT_AT = new AtomicInteger();
        static final AtomicReference<List<Long>> SEEN_BEFORE_FAILURE = new AtomicReference<>();
        static final AtomicBoolean SHORT_LOCK_WAIT = new AtomicBoolean();

        CleanupDataSource(DataSource target) {
            super(target);
        }

        static void reset() {
            BATCHES.set(0);
            ROW_LOCKS.set(0);
            BEFORE_CANDIDATE_QUERY.set(null);
            BEFORE_FIRST_ROW_LOCK.set(null);
            BEFORE_TOKEN_DELETE.set(null);
            BEFORE_COMMIT.set(null);
            FAIL_SESSION_DELETE_AT.set(0);
            FAIL_COMMIT_AT.set(0);
            SEEN_BEFORE_FAILURE.set(null);
            SHORT_LOCK_WAIT.set(false);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            boolean shortLockWait = SHORT_LOCK_WAIT.get();
            if (shortLockWait) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                }
            }
            int[] batch = {0};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = ((String) args[0]).strip().toLowerCase(Locale.ROOT);
                                if (sql.contains("from auth_sessions") && sql.contains("expires_at <")) {
                                    batch[0] = BATCHES.incrementAndGet();
                                    IntConsumer hook = BEFORE_CANDIDATE_QUERY.get();
                                    if (hook != null) {
                                        hook.accept(batch[0]);
                                    }
                                } else if (batch[0] > 0 && sql.contains("from auth_sessions") && sql.contains("for update")) {
                                    ROW_LOCKS.incrementAndGet();
                                    run(BEFORE_FIRST_ROW_LOCK);
                                } else if (sql.startsWith("delete") && sql.contains("auth_refresh_tokens")) {
                                    run(BEFORE_TOKEN_DELETE);
                                } else if (sql.startsWith("delete") && sql.contains("auth_sessions")) {
                                    failAt(FAIL_SESSION_DELETE_AT, batch[0], connection, "injected session delete failure");
                                }
                            }
                            case "commit" -> {
                                if (batch[0] > 0) {
                                    run(BEFORE_COMMIT);
                                }
                                failAt(FAIL_COMMIT_AT, batch[0], connection, "injected commit failure");
                            }
                            case "close" -> {
                                if (shortLockWait && !connection.isClosed()) {
                                    try (Statement statement = connection.createStatement()) {
                                        statement.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                                    }
                                }
                            }
                            default -> {
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        /** 한 번만 부르는 hook. */
        private static void run(AtomicReference<Runnable> hook) {
            Runnable once = hook.getAndSet(null);
            if (once != null) {
                once.run();
            }
        }

        private static void failAt(AtomicInteger trigger, int batch, Connection connection, String message)
                throws SQLException {
            if (batch == 0 || !trigger.compareAndSet(batch, 0)) {
                return;
            }
            List<Long> seen = new ArrayList<>();
            for (String table : List.of("auth_sessions", "auth_refresh_tokens")) {
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    seen.add(rs.getLong(1));
                }
            }
            SEEN_BEFORE_FAILURE.set(seen);
            throw new SQLException(message + ": " + SECRET);
        }
    }
}
