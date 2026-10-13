package com.gyejoldanji.domain.analytics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchResponse;
import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.domain.analytics.service.AnalyticsEventValidator;
import com.gyejoldanji.domain.analytics.service.AnalyticsService;
import com.gyejoldanji.domain.analytics.service.ServerAnalyticsRecorder;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.config.ClockConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureJdbc;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 실제 MySQL에서 같은 회원의 동시 배치 중복 처리, 서로 다른 회원의 동시 첫 배치(갭 락 데드락 없음), UTC 시각 저장을 확인한다. {@code AUTH_IT_DB_URL}이 있을 때만 실행하며 그 DB에
 * {@code docs/db/migrations/06-analytics-events.sql}이 적용돼 있어야 한다(ddl-auto=validate). JVM과 legacy URL 시간대는
 * Asia/Seoul로 둬 9시간 어긋남을 잡는다.
 */
@SpringBootTest(classes = AnalyticsMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class AnalyticsMySqlIntegrationTest {

    private static final String SESSION = "9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static TimeZone originalTimeZone;
    /** 설정되면 기존 eventId 조회 직후 요청들이 여기서 서로를 기다린다. 조회와 INSERT 사이를 겹치게 만드는 test 전용 장치. */
    private static volatile CyclicBarrier afterLookup;

    @Autowired
    private AnalyticsService service;
    @Autowired
    private ServerAnalyticsRecorder recorder;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    private JdbcTemplate jdbc;
    private ExecutorService pool;
    private final List<Long> members = new ArrayList<>();
    private long memberId;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {Member.class, AnalyticsEvent.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AnalyticsEventRepository.class})
    @Import({ClockConfig.class, AnalyticsEventValidator.class, AnalyticsService.class, ServerAnalyticsRecorder.class})
    static class Config {

        /** 분석 Repository를 감싸 기존 eventId 조회가 끝난 뒤 {@link #afterLookup}에서 기다리게 한다. */
        @Bean
        static BeanPostProcessor pauseAfterExistingEventLookup() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof AnalyticsEventRepository target)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(AnalyticsEventRepository.class.getClassLoader(),
                            new Class<?>[]{AnalyticsEventRepository.class}, (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(target, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                CyclicBarrier barrier = afterLookup;
                                if (barrier != null && method.getName().startsWith("findExistingEventIds")) {
                                    barrier.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                                }
                                return result;
                            });
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    }

    @AfterAll
    static void restoreTimeZone() {
        if (originalTimeZone != null) {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 앱 기본 DB가 아닌지 확인하고 이번 실행 전용 회원을 만든다. */
    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        String appUrl = environment.getProperty("DB_URL");
        if (appUrl != null) {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).as("앱 기본 DB에서 실행하지 않는다")
                    .isNotEqualTo(appUrl.replaceFirst("^jdbc:mysql://[^/]+/", "").replaceFirst("\\?.*$", ""));
        }
        memberId = newMember();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() {
        afterLookup = null;
        pool.shutdownNow();
        for (long id : members) {
            jdbc.update("DELETE FROM analytics_events WHERE member_id = ?", id);
            jdbc.update("DELETE FROM members WHERE id = ?", id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"referrer\":\"external__owned_social\",\"shareScreen\":\"JAR\"}",
            "{\"referrer\":\"external_share\",\"shareScreen\":\"JAR\","
                    + "\"utmSource\":\"Instagram\",\"utmCampaign\":\"1020_launch\"}"})
    void sessionStartPreservesPropertiesAndIsStoredOnce(String properties) {
        byte[] body = """
                {"events":[{"eventId":"%s","eventName":"SESSION_START","sessionId":"%s",
                "serviceVersion":"1.0.0","os":"WEB","sequence":1,"occurredAt":"2026-10-01T01:02:03Z",
                "properties":%s}]}
                """.formatted(UUID.randomUUID(), SESSION, properties).getBytes(StandardCharsets.UTF_8);

        assertThat(service.collect(memberId, body)).isEqualTo(new AnalyticsBatchResponse(1, 0));
        assertThat(service.collect(memberId, body)).isEqualTo(new AnalyticsBatchResponse(0, 1));
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(jdbc.queryForList("SELECT event_name, event_source, properties "
                + "FROM analytics_events WHERE member_id = ?", memberId))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("event_name")).isEqualTo("SESSION_START");
                    assertThat(row.get("event_source")).isEqualTo("CLIENT");
                    assertThat(mapper.readTree((String) row.get("properties"))).isEqualTo(mapper.readTree(properties));
                });
    }

    /**
     * 이벤트가 하나도 없는 서로 다른 신규 회원 둘이 동시에 첫 배치를 보낸다. 둘 다 기존 키 조회를 마친 뒤에야 INSERT하도록 맞춰도 둘 다
     * 성공한다(REPEATABLE READ + 없는 키 FOR UPDATE의 갭 락이면 서로의 INSERT를 막아 데드락이 난다).
     */
    @Test
    void differentNewMembersFirstBatchesBothSucceedConcurrently() throws Exception {
        long other = newMember();
        afterLookup = new CyclicBarrier(2);

        Future<AnalyticsBatchResponse> first =
                pool.submit(() -> service.collect(memberId, body(UUID.randomUUID().toString())));
        Future<AnalyticsBatchResponse> second =
                pool.submit(() -> service.collect(other, body(UUID.randomUUID().toString())));

        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new AnalyticsBatchResponse(1, 0));
        assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new AnalyticsBatchResponse(1, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_events WHERE member_id IN (?, ?)",
                Integer.class, memberId, other)).isEqualTo(2);
    }

    /**
     * 테스트가 회원 행을 잠근 채 [e1,e2]와 [e2,e3]를 동시에 보내 둘 다 잠금 대기에 들어가게 한 뒤 푼다. 합계는 accepted 3·duplicate 1이고
     * 행은 3개다. 같은 배치 재전송은 0/3이다. 발생 시각은 KST JVM에서도 UTC 마이크로초로 저장된다.
     */
    @Test
    void concurrentOverlappingBatchesStoreEachEventOnce() throws Exception {
        String e1 = UUID.randomUUID().toString();
        String e2 = UUID.randomUUID().toString();
        String e3 = UUID.randomUUID().toString();
        Future<AnalyticsBatchResponse> first;
        Future<AnalyticsBatchResponse> second;
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement select = lock.prepareStatement("SELECT id FROM members WHERE id = ? FOR UPDATE")) {
                select.setLong(1, memberId);
                select.executeQuery().close();
            }
            first = pool.submit(() -> service.collect(memberId, body(e1, e2)));
            second = pool.submit(() -> service.collect(memberId, body(e2, e3)));
            awaitLockWaits(first, second);
            lock.commit();
        }

        AnalyticsBatchResponse a = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        AnalyticsBatchResponse b = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(a.acceptedCount() + b.acceptedCount()).isEqualTo(3);
        assertThat(a.duplicateCount() + b.duplicateCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_events WHERE member_id = ?", Integer.class,
                memberId)).isEqualTo(3);

        assertThat(service.collect(memberId, body(e1, e2, e3))).isEqualTo(new AnalyticsBatchResponse(0, 3));
        assertThat(jdbc.queryForObject("SELECT CONCAT(DATE_FORMAT(occurred_at, '%Y-%m-%dT%H:%i:%s.%f'), '|', "
                        + "event_source, '|', JSON_EXTRACT(properties, '$.entrySource')) "
                        + "FROM analytics_events WHERE member_id = ? AND event_id = ?", String.class, memberId, e1))
                .isEqualTo("2026-10-01T01:02:03.123456|CLIENT|\"APP_OPEN\"");
    }

    /** 요청 둘이 members 행 잠금을 실제로 기다릴 때까지 기다린다(performance_schema). 먼저 끝난 요청은 실패로 본다. */
    private void awaitLockWaits(Future<?>... requests) throws Exception {
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
                    WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = 'members'
                    """, Integer.class);
            if (waiting != null && waiting >= requests.length) {
                return;
            }
            Thread.sleep(20);
        }
        fail("members 행 잠금 대기가 " + requests.length + "개 생기지 않았다");
    }

    /**
     * 서버 이벤트는 트랜잭션 밖에서 불려 SERVER 행 1건을 남긴다. 빈 스티커 저장은 stickerCount 0이고, 세션 헤더는 소문자로, 틀린 OS 헤더는
     * 그 칸만 NULL이다. 발생·수신 시각은 같고 KST JVM에서도 UTC로 저장된다.
     */
    @Test
    void recorderStoresOneServerEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Analytics-Session-Id", SESSION.toUpperCase());
        request.addHeader("X-Client-Version", "1.0.0");
        request.addHeader("X-Client-OS", "ios");

        recorder.stickersSaved(memberId, 31L, 0, request);

        assertThat(jdbc.queryForList("SELECT event_name, event_source, session_id, service_version, os, `sequence`, "
                + "JSON_EXTRACT(properties, '$.jarPageId') AS jar_page_id, "
                + "JSON_EXTRACT(properties, '$.stickerCount') AS sticker_count, occurred_at = received_at AS same_time, "
                + "ABS(TIMESTAMPDIFF(SECOND, occurred_at, UTC_TIMESTAMP(6))) < 60 AS utc_now "
                + "FROM analytics_events WHERE member_id = ?", memberId))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("event_name")).isEqualTo("STICKER_SAVE");
                    assertThat(row.get("event_source")).isEqualTo("SERVER");
                    assertThat(row.get("session_id")).isEqualTo(SESSION);
                    assertThat(row.get("service_version")).isEqualTo("1.0.0");
                    assertThat(row.get("os")).isNull();
                    assertThat(row.get("sequence")).isNull();
                    assertThat(String.valueOf(row.get("jar_page_id"))).isEqualTo("31");
                    assertThat(String.valueOf(row.get("sticker_count"))).isEqualTo("0");
                    assertThat(((Number) row.get("same_time")).intValue()).isEqualTo(1);
                    assertThat(((Number) row.get("utc_now")).intValue()).isEqualTo(1);
                });
    }

    /** 이번 실행 전용 ACTIVE 회원을 만들고 정리 목록에 넣는다. */
    private long newMember() {
        String anonKey = "it-analytics-" + UUID.randomUUID();
        jdbc.update("INSERT INTO members (identity_provider, provider_user_id, status, created_at, updated_at) "
                + "VALUES ('TOSS_ANON', ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", anonKey);
        long id = jdbc.queryForObject("SELECT id FROM members WHERE provider_user_id = ?", Long.class, anonKey);
        members.add(id);
        return id;
    }

    private static byte[] body(String... eventIds) {
        StringBuilder json = new StringBuilder("{\"events\":[");
        for (int i = 0; i < eventIds.length; i++) {
            json.append(i == 0 ? "" : ",").append("""
                    {"eventId":"%s","eventName":"HOME_VIEW","sessionId":"%s","serviceVersion":"1.0.0","os":"WEB",\
                    "sequence":%d,"occurredAt":"2026-10-01T01:02:03.123456789Z","properties":{"entrySource":"APP_OPEN"}}"""
                    .formatted(eventIds[i], SESSION, i + 1));
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
