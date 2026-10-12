package com.gyejoldanji.domain.analytics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ServerEventName;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureJdbc;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기 조회는 테이블 전체를 읽으므로 AUTH_IT_DB_URL의 로컬 서버에 실행 전용 DB를 생성·삭제한다(CREATE/DROP 권한 필요).
 * KST JVM·legacy URL에서도 native 표시 시각과 엔티티 조회 시각이 DB의 UTC 마이크로초와 같아야 한다.
 */
@SpringBootTest(classes = AnalyticsSheetSyncMySqlIntegrationTest.Config.class, properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "app.google-sheets.enabled=false"
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class AnalyticsSheetSyncMySqlIntegrationTest {

    private static final String DATABASE = "gyejol_danji_sheet_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDateTime OCCURRED = LocalDateTime.parse("2026-10-01T01:02:03.123456");
    private static final Instant SYNCED = Instant.parse("2026-10-12T04:05:06.654321Z");
    private static boolean databaseCreated;
    private static TimeZone originalTimeZone;

    @Autowired
    private AnalyticsEventRepository repository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = AnalyticsEvent.class)
    @EnableJpaRepositories(basePackageClasses = AnalyticsEventRepository.class)
    static class Config {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        String url = System.getenv("AUTH_IT_DB_URL");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?/.*")) {
            throw new IllegalStateException("시트 동기화 통합 테스트는 로컬 MySQL만 사용한다");
        }
        if (url.contains("connectionTimeZone") || url.contains("forceConnectionTimeZoneToSession")
                || url.contains("serverTimezone")) {
            throw new IllegalStateException("AUTH_IT_DB_URL에는 시간대 파라미터를 넣지 않는다");
        }
        String tempUrl = url.replaceFirst("(//[^/?]+/)[^?]*", "$1" + DATABASE);
        if (!tempUrl.replaceFirst("^jdbc:mysql://[^/]+/", "").replaceFirst("\\?.*$", "").equals(DATABASE)) {
            throw new IllegalStateException("AUTH_IT_DB_URL에서 DB 이름 위치를 찾지 못했다");
        }
        if (!databaseCreated) {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
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
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        try {
            if (databaseCreated) {
                try (Connection connection = connection(); Statement statement = connection.createStatement()) {
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
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        repository.deleteAllInBatch();
    }

    @Test
    void pendingRowsUseIdOrderAndLimitWhilePreservingUtcTimes() {
        AnalyticsEvent first = event(OCCURRED.plusDays(2));
        AnalyticsEvent second = event(OCCURRED.plusDays(1));
        AnalyticsEvent third = event(OCCURRED);
        repository.saveAllAndFlush(List.of(first, second, third));
        mark(List.of(first.getId()), SYNCED);

        assertThat(repository.findPendingForSheet(1)).extracting(AnalyticsEvent::getId)
                .containsExactly(second.getId());
        assertThat(repository.findPendingForSheet(10)).extracting(AnalyticsEvent::getId)
                .containsExactly(second.getId(), third.getId());
        AnalyticsEvent read = repository.findPendingForSheet(1).getFirst();
        assertThat(read.getOccurredAt()).isEqualTo(OCCURRED.plusDays(1));
        assertThat(read.getReceivedAt()).isEqualTo(OCCURRED.plusDays(1));
        assertThat(read.getProperties()).isEqualTo(jdbc.queryForObject(
                "SELECT CAST(properties AS CHAR) FROM analytics_events WHERE id = ?", String.class, second.getId()));
    }

    @Test
    void markingChangesOnlyRequestedPendingRowsAndKeepsUtcMicroseconds() {
        AnalyticsEvent previous = event(OCCURRED);
        AnalyticsEvent pending = event(OCCURRED);
        AnalyticsEvent untouched = event(OCCURRED);
        repository.saveAllAndFlush(List.of(previous, pending, untouched));
        Instant earlier = SYNCED.minusSeconds(60);
        assertThat(mark(List.of(previous.getId()), earlier)).isEqualTo(1);

        assertThat(mark(List.of(previous.getId(), pending.getId()), SYNCED)).isEqualTo(1);

        assertThat(markedAt(previous.getId())).isEqualTo("2026-10-12T04:04:06.654321");
        assertThat(markedAt(pending.getId())).isEqualTo("2026-10-12T04:05:06.654321");
        assertThat(markedAt(untouched.getId())).isNull();
        assertThat(mark(List.of(previous.getId(), pending.getId()), SYNCED.plusSeconds(60))).isZero();
        assertThat(repository.findPendingForSheet(10)).extracting(AnalyticsEvent::getId)
                .containsExactly(untouched.getId());
    }

    @Test
    void pendingExistsChangesWhenLastRowIsMarked() {
        assertThat(repository.existsBySheetSyncedAtIsNull()).isFalse();
        AnalyticsEvent pending = repository.saveAndFlush(event(OCCURRED));
        assertThat(repository.existsBySheetSyncedAtIsNull()).isTrue();

        mark(List.of(pending.getId()), SYNCED);

        assertThat(repository.existsBySheetSyncedAtIsNull()).isFalse();
        assertThat(repository.findPendingForSheet(1)).isEmpty();
    }

    private int mark(List<Long> ids, Instant at) {
        return transaction.execute(status -> repository.markSheetSynced(ids, at));
    }

    private String markedAt(long id) {
        return jdbc.queryForObject("SELECT DATE_FORMAT(sheet_synced_at, '%Y-%m-%dT%H:%i:%s.%f') "
                + "FROM analytics_events WHERE id = ?", String.class, id);
    }

    private static AnalyticsEvent event(LocalDateTime time) {
        return AnalyticsEvent.server(1L, ServerEventName.RECORD_COMPLETE, null, null, null, time,
                "{\"recordId\":123,\"photoCount\":1}");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("AUTH_IT_DB_URL"), System.getenv("AUTH_IT_DB_USERNAME"),
                System.getenv("AUTH_IT_DB_PASSWORD"));
    }
}
