package com.gyejoldanji.domain.auth;

import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.config.ClockConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 실제 MySQL로 회원·인증 영속성 계층을 검증한다(작업 02 단위 7, T03·T22·T33의 DB 부분).
 *
 * <p>환경변수 {@code AUTH_IT_DB_URL}이 있을 때만 실행한다. 값이 있는데 접속·스키마 검증이 실패하면 건너뛰지 않고 실패한다.
 * <ul>
 *   <li>{@code AUTH_IT_DB_URL}: 앱 기본 DB({@code DB_URL})와 다른 전용 DB의 JDBC URL. 시간대 파라미터를 넣지 않는다.
 *       테스트가 legacy {@code serverTimezone=Asia/Seoul}을 덧붙여 앱의 Hikari UTC 설정이 이기는지 확인한다.</li>
 *   <li>{@code AUTH_IT_DB_USERNAME}, {@code AUTH_IT_DB_PASSWORD}: 해당 DB 권한과
 *       {@code performance_schema.data_lock_waits}, {@code data_locks}, {@code threads} SELECT 권한.</li>
 * </ul>
 * <p>스키마: 빈 DB에 {@code docs/auth/spec/migrations/02-auth-persistence-members-absent.sql}을 적용한다.
 * 회원·인증 엔티티만 {@code ddl-auto=validate}로 검사한다.
 * <p>데이터: 실행마다 고유 접두어를 가진 식별자만 만들고 각 테스트 뒤 그 행만 지운다.
 * <p>실행: {@code ./gradlew test --tests 'com.gyejoldanji.domain.auth.AuthPersistenceMySqlIntegrationTest'}
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.google-sheets.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "AUTH_IT_DB_URL", matches = ".+")
class AuthPersistenceMySqlIntegrationTest {

    /** auditing이 쓰는 고정 시각. 나노초는 DB 저장 시 마이크로초로 잘려야 한다. */
    private static final Instant FIXED = Instant.parse("2026-10-02T01:02:03.123456789Z");
    private static final Instant FIXED_MICROS = FIXED.truncatedTo(ChronoUnit.MICROS);
    private static final String PREFIX = "it7-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration HOLD_WAIT = WAIT.multipliedBy(3);
    private static TimeZone originalTimeZone;

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private AuthSessionRepository sessionRepository;
    @Autowired
    private AuthRefreshTokenRepository tokenRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    /** 회원·인증 엔티티와 Repository만 올리고, 기존 ClockConfig·auditing에 고정 Clock을 연결한다. */
    @TestConfiguration
    @EntityScan(basePackageClasses = {Member.class, AuthSession.class})
    @EnableJpaRepositories(basePackageClasses = {MemberRepository.class, AuthSessionRepository.class})
    @Import(ClockConfig.class)
    static class Config {

        /** 기존 utcDateTimeProvider가 이 Clock을 쓰도록 우선 Bean으로 둔다. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    /** 전용 DB URL에 legacy serverTimezone을 붙이고, 컨텍스트·커넥션 생성 전에 JVM 기본 시간대를 Asia/Seoul로 둔다. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
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

    /** 바꾼 JVM 기본 시간대를 되돌린다. */
    @AfterAll
    static void restoreTimeZone() {
        if (originalTimeZone != null) {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    /** 앱 기본 DB를 대상으로 실행하지 않았는지 확인한다. */
    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc = new JdbcTemplate(dataSource);
        String target = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(target).isNotBlank();
        String appUrl = environment.getProperty("DB_URL");
        if (appUrl != null) {
            assertThat(target).as("앱 기본 DB에서 실행하지 않는다").isNotEqualTo(databaseName(appUrl));
        }
    }

    /** 이번 실행이 만든 행만 FK 역순으로 지우고 남은 행이 없는지 확인한다. */
    @AfterEach
    void cleanUp() {
        String like = PREFIX + "%";
        jdbc.update("DELETE t FROM auth_refresh_tokens t JOIN auth_sessions s ON s.id = t.session_id "
                + "JOIN members m ON m.id = s.member_id WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE s FROM auth_sessions s JOIN members m ON m.id = s.member_id "
                + "WHERE m.provider_user_id LIKE ?", like);
        jdbc.update("DELETE FROM members WHERE provider_user_id LIKE ?", like);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id LIKE ?",
                Integer.class, like)).isZero();
    }

    /** 세 엔티티를 저장한 뒤 새 트랜잭션에서 ID·연결·enum·nullable·CHAR·BINARY·마이크로초를 다시 확인한다. */
    @Test
    void savesAndReloadsAuthEntitiesExactly() {
        UUID sessionKey = UUID.randomUUID();
        byte[] hash = sampleHash(1);
        Fixture f = tx.execute(s -> {
            Member member = memberRepository.save(Member.create("TOSS_ANON", key("persist")));
            AuthSession session = sessionRepository.save(AuthSession.start(member, sessionKey, now(), Duration.ofDays(14)));
            AuthRefreshToken token = tokenRepository.save(AuthRefreshToken.issue(session, hash));
            return new Fixture(member.getId(), session.getId(), token.getId(), hash);
        });

        tx.executeWithoutResult(s -> {
            Member member = memberRepository.findById(f.memberId()).orElseThrow();
            assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(member.getLastLoginAt()).isNull();
            assertThat(member.getOnboardingCompletedAt()).isNull();
            assertThat(member.getCreatedAt()).isEqualTo(utc(FIXED_MICROS));

            AuthSession session = sessionRepository.findById(f.sessionId()).orElseThrow();
            assertThat(session.getMember().getId()).isEqualTo(f.memberId());
            assertThat(session.getSessionKey()).isEqualTo(sessionKey.toString());
            assertThat(session.getCurrentRefreshGeneration()).isZero();
            assertThat(session.getExpiresAt()).isEqualTo(utc(FIXED_MICROS).plusDays(14));
            assertThat(session.getRevokedAt()).isNull();
            assertThat(session.getRevokeReason()).isNull();

            AuthRefreshToken token = tokenRepository.findById(f.tokenId()).orElseThrow();
            assertThat(token.getSession().getId()).isEqualTo(f.sessionId());
            assertThat(token.getTokenHash()).containsExactly(hash);
            assertThat(token.getGeneration()).isZero();
            assertThat(token.getExpiresAt()).isEqualTo(session.getExpiresAt());
            assertThat(token.getConsumedAt()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT LENGTH(session_key) FROM auth_sessions WHERE id = ?",
                Integer.class, f.sessionId())).isEqualTo(36);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_refresh_tokens WHERE id = ?",
                byte[].class, f.tokenId())).containsExactly(hash);

        LocalDateTime revokedAt = utc(FIXED_MICROS).plusMinutes(5);
        tx.executeWithoutResult(s -> sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow()
                .revoke(SessionRevokeReason.REFRESH_REUSE, revokedAt));
        tx.executeWithoutResult(s -> tokenRepository.findByTokenHashForUpdate(hash).orElseThrow().consume(revokedAt));

        tx.executeWithoutResult(s -> {
            AuthSession session = sessionRepository.findById(f.sessionId()).orElseThrow();
            assertThat(session.getRevokeReason()).isEqualTo(SessionRevokeReason.REFRESH_REUSE);
            assertThat(session.getRevokedAt()).isEqualTo(revokedAt);
            assertThat(tokenRepository.findById(f.tokenId()).orElseThrow().getConsumedAt()).isEqualTo(revokedAt);
        });
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM auth_sessions WHERE id = ?",
                String.class, f.sessionId())).isEqualTo("REFRESH_REUSE");
    }

    /** 고유키와 ON DELETE RESTRICT가 실제 DB에서 거부되고 기존 행은 남는다. */
    @Test
    void enforcesUniqueKeysAndRestrictForeignKeys() {
        Fixture f = issueToken("unique", Duration.ofDays(14), 2);
        String duplicateKey = key("unique");
        UUID existingKey = UUID.fromString(tx.execute(s ->
                sessionRepository.findById(f.sessionId()).orElseThrow().getSessionKey()));

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                memberRepository.saveAndFlush(Member.create("TOSS_ANON", duplicateKey))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> sessionRepository.saveAndFlush(AuthSession.start(
                memberRepository.findById(f.memberId()).orElseThrow(), existingKey, now(), Duration.ofDays(14)))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            AuthSession session = sessionRepository.findById(f.sessionId()).orElseThrow();
            session.advanceGeneration();
            tokenRepository.saveAndFlush(AuthRefreshToken.issue(session, f.hash()));
        }))
                .as("다른 세대의 같은 해시")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> tokenRepository.saveAndFlush(AuthRefreshToken.issue(
                sessionRepository.findById(f.sessionId()).orElseThrow(), sampleHash(3)))))
                .as("같은 세션의 같은 세대")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            memberRepository.deleteById(f.memberId());
            memberRepository.flush();
        })).as("세션이 있는 회원 삭제").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            sessionRepository.deleteById(f.sessionId());
            sessionRepository.flush();
        })).as("토큰이 있는 세션 삭제").isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("members", f.memberId())).isOne();
        assertThat(count("auth_sessions", f.sessionId())).isOne();
        assertThat(count("auth_refresh_tokens", f.tokenId())).isOne();
    }

    /** 보호 요청용 projection이 ID·회원 상태·시각을 그대로 주고, 소유 회원이 다르거나 세션이 없으면 비어 있다. */
    @Test
    void findAuthenticationViewMapsOwnerStateAndTimes() {
        Fixture f = issueToken("view", Duration.ofDays(14), 4);
        Long otherMemberId = tx.execute(s -> memberRepository.save(Member.create("TOSS_ANON", key("view-other"))).getId());
        String sessionKey = tx.execute(s -> sessionRepository.findById(f.sessionId()).orElseThrow().getSessionKey());

        AuthSessionRepository.AuthenticationView active = tx.execute(s ->
                sessionRepository.findAuthenticationView(sessionKey, f.memberId()).orElseThrow());
        assertThat(active.getSessionId()).isEqualTo(f.sessionId());
        assertThat(active.getMemberId()).isEqualTo(f.memberId());
        assertThat(active.getMemberStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(active.getExpiresAt()).isEqualTo(utc(FIXED_MICROS).plusDays(14));
        assertThat(active.getRevokedAt()).isNull();

        LocalDateTime revokedAt = utc(FIXED_MICROS).plusHours(1);
        tx.executeWithoutResult(s -> sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow()
                .revoke(SessionRevokeReason.LOGOUT, revokedAt));
        block(f.memberId());
        AuthSessionRepository.AuthenticationView changed = tx.execute(s ->
                sessionRepository.findAuthenticationView(sessionKey, f.memberId()).orElseThrow());
        assertThat(changed.getMemberStatus()).isEqualTo(MemberStatus.BLOCKED);
        assertThat(changed.getRevokedAt()).isEqualTo(revokedAt);

        Optional<AuthSessionRepository.AuthenticationView> otherOwner = tx.execute(s ->
                sessionRepository.findAuthenticationView(sessionKey, otherMemberId));
        Optional<AuthSessionRepository.AuthenticationView> unknownSession = tx.execute(s ->
                sessionRepository.findAuthenticationView(UUID.randomUUID().toString(), f.memberId()));
        assertThat(otherOwner).isEmpty();
        assertThat(unknownSession).isEmpty();
    }

    /** 해시만으로 소유 ID를 찾는다. 사용·만료·폐기·비활성 상태여도 이력이 있으면 찾고, 없는 해시는 비어 있다. */
    @Test
    void findOwnerIdsByHashIgnoresTokenAndOwnerState() {
        Fixture plain = issueToken("owner-plain", Duration.ofDays(14), 10);
        Fixture consumed = issueToken("owner-consumed", Duration.ofDays(14), 11);
        Fixture expired = issueToken("owner-expired", Duration.ofSeconds(1), 12);
        Fixture revoked = issueToken("owner-revoked", Duration.ofDays(14), 13);
        Fixture blocked = issueToken("owner-blocked", Duration.ofDays(14), 14);
        tx.executeWithoutResult(s -> tokenRepository.findByTokenHashForUpdate(consumed.hash()).orElseThrow().consume(now()));
        tx.executeWithoutResult(s -> sessionRepository.findByIdForUpdate(revoked.sessionId()).orElseThrow()
                .revoke(SessionRevokeReason.LOGOUT, now()));
        block(blocked.memberId());
        assertThat(utc(FIXED_MICROS).plusSeconds(1)).as("실제 현재 시각 기준 만료").isBefore(LocalDateTime.now(ZoneOffset.UTC));

        for (Fixture f : List.of(plain, consumed, expired, revoked, blocked)) {
            AuthRefreshTokenRepository.OwnerIds ids = tx.execute(s -> tokenRepository.findOwnerIdsByHash(f.hash()).orElseThrow());
            assertThat(ids.getMemberId()).isEqualTo(f.memberId());
            assertThat(ids.getSessionId()).isEqualTo(f.sessionId());
        }
        Optional<AuthRefreshTokenRepository.OwnerIds> unknownHash = tx.execute(s ->
                tokenRepository.findOwnerIdsByHash(sampleHash(99)));
        assertThat(unknownHash).isEmpty();
    }

    /** 반복 upsert는 같은 회원 1행을 유지하고 기존 상태·온보딩·시각을 덮어쓰지 않는다. */
    @Test
    void ensureIdentityKeepsExistingMemberState() {
        String anonKey = key("upsert");
        LocalDateTime first = now();
        Long id = tx.execute(s -> {
            memberRepository.ensureIdentity(anonKey, FIXED_MICROS);
            Member member = memberRepository.findIdentityForUpdate(anonKey).orElseThrow();
            member.completeOnboarding(first.plusHours(1));
            member.recordAuthentication(first.plusHours(2));
            return member.getId();
        });
        block(id);
        MemberRow before = memberRow(id);

        Long again = tx.execute(s -> {
            memberRepository.ensureIdentity(anonKey, FIXED_MICROS.plus(Duration.ofDays(1)));
            return memberRepository.findIdentityForUpdate(anonKey).orElseThrow().getId();
        });

        assertThat(again).isEqualTo(id);
        assertThat(memberRow(id)).isEqualTo(before);
        assertThat(before.status()).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id = ?", Integer.class, anonKey))
                .isOne();
    }

    /** 같은 신규 식별자의 두 트랜잭션이 겹치면 두 번째가 실제로 대기하고, 회원 1행·같은 ID·첫 인증 1회로 끝난다. */
    @Test
    void concurrentFirstAuthenticationCreatesOneMember() throws Exception {
        String anonKey = key("concurrent");
        AtomicLong holderId = new AtomicLong();
        boolean[] holderSawFirst = new boolean[1];

        Object[] contender = assertWaitsForRowLock(
                () -> {
                    memberRepository.ensureIdentity(anonKey, FIXED_MICROS);
                    Member member = memberRepository.findIdentityForUpdate(anonKey).orElseThrow();
                    holderSawFirst[0] = member.getLastLoginAt() == null;
                    member.recordAuthentication(now());
                    holderId.set(member.getId());
                },
                () -> {
                    memberRepository.ensureIdentity(anonKey, FIXED_MICROS);
                    Member member = memberRepository.findIdentityForUpdate(anonKey).orElseThrow();
                    return new Object[]{member.getId(), member.getLastLoginAt() == null};
                });

        assertThat(contender[0]).isEqualTo(holderId.get());
        assertThat(holderSawFirst[0]).isTrue();
        assertThat(contender[1]).as("두 번째는 첫 인증으로 보이지 않는다").isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id = ?", Integer.class, anonKey))
                .isOne();
    }

    /** findIdentityForUpdate는 회원 행을 잠그고, 일반 조회는 기다리지 않는다. */
    @Test
    void findIdentityForUpdateLocksMemberRow() throws Exception {
        String anonKey = key("lock-identity");
        Long id = tx.execute(s -> {
            memberRepository.ensureIdentity(anonKey, FIXED_MICROS);
            return memberRepository.findIdentityForUpdate(anonKey).orElseThrow().getId();
        });
        LocalDateTime loggedIn = now().plusMinutes(7);

        LocalDateTime seen = assertWaitsForRowLock(
                () -> memberRepository.findIdentityForUpdate(anonKey).orElseThrow().recordAuthentication(loggedIn),
                () -> memberRepository.findIdentityForUpdate(anonKey).orElseThrow().getLastLoginAt(),
                () -> assertThat(memberRepository.findById(id).orElseThrow().getLastLoginAt())
                        .as("잠금 없는 일반 조회는 대기하지 않고 커밋 전 값을 본다").isNull());

        assertThat(seen).isEqualTo(loggedIn);
    }

    /** findByIdForUpdate(회원)는 회원 행을 잠근다. */
    @Test
    void memberFindByIdForUpdateLocksMemberRow() throws Exception {
        Long id = tx.execute(s -> memberRepository.save(Member.create("TOSS_ANON", key("lock-member-id"))).getId());
        LocalDateTime completed = now().plusMinutes(9);

        LocalDateTime seen = assertWaitsForRowLock(
                () -> memberRepository.findByIdForUpdate(id).orElseThrow().completeOnboarding(completed),
                () -> memberRepository.findByIdForUpdate(id).orElseThrow().getOnboardingCompletedAt());

        assertThat(seen).isEqualTo(completed);
    }

    /** 세션 잠금만으로 같은 세션 행이 보호된다(회원은 잠그지 않는다). */
    @Test
    void sessionFindByIdForUpdateLocksSessionRow() throws Exception {
        Fixture f = issueToken("lock-session", Duration.ofDays(14), 20);

        Integer generation = assertWaitsForRowLock(
                () -> sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow().advanceGeneration(),
                () -> sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow().getCurrentRefreshGeneration());

        assertThat(generation).isEqualTo(1);
    }

    /** 토큰 잠금만으로 같은 토큰 행이 보호된다(회원·세션은 잠그지 않는다). */
    @Test
    void tokenFindByTokenHashForUpdateLocksTokenRow() throws Exception {
        Fixture f = issueToken("lock-token", Duration.ofDays(14), 21);
        LocalDateTime consumedAt = now().plusMinutes(3);

        LocalDateTime seen = assertWaitsForRowLock(
                () -> tokenRepository.findByTokenHashForUpdate(f.hash()).orElseThrow().consume(consumedAt),
                () -> tokenRepository.findByTokenHashForUpdate(f.hash()).orElseThrow().getConsumedAt());

        assertThat(seen).isEqualTo(consumedAt);
    }

    /** 회원→세션→토큰 순서로 잠그는 두 작업은 회원에서 대기하고, 해제 뒤 최신 소비 상태를 본다. */
    @Test
    void lockOrderMemberSessionTokenSerializesRefreshLikeWork() throws Exception {
        Fixture f = issueToken("lock-order", Duration.ofDays(14), 22);
        LocalDateTime consumedAt = now().plusMinutes(1);

        LocalDateTime seen = assertWaitsForRowLock(
                () -> {
                    memberRepository.findByIdForUpdate(f.memberId()).orElseThrow();
                    sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow();
                    tokenRepository.findByTokenHashForUpdate(f.hash()).orElseThrow().consume(consumedAt);
                },
                () -> {
                    memberRepository.findByIdForUpdate(f.memberId()).orElseThrow();
                    sessionRepository.findByIdForUpdate(f.sessionId()).orElseThrow();
                    return tokenRepository.findByTokenHashForUpdate(f.hash()).orElseThrow().getConsumedAt();
                });

        assertThat(seen).isEqualTo(consumedAt);
    }

    /** 대소문자·후행 공백·Unicode 정규화 차이를 다른 회원으로 저장하고 원문 그대로 돌려준다. 같은 원문은 중복되지 않는다. */
    @Test
    void distinguishesProviderUserIdsExactly() {
        String nfc = Normalizer.normalize("café", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("café", Normalizer.Form.NFD);
        List<String> keys = List.of(key("key"), key("Key"), key("key "), key(nfc), key(nfd), key("ｋｅｙ"),
                maxLengthKey());
        assertThat(nfc).isNotEqualTo(nfd);

        List<Long> ids = new ArrayList<>();
        for (String k : keys) {
            ids.add(tx.execute(s -> {
                memberRepository.ensureIdentity(k, FIXED_MICROS);
                return memberRepository.findIdentityForUpdate(k).orElseThrow().getId();
            }));
        }
        assertThat(new HashSet<>(ids)).hasSameSizeAs(keys);
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            Long id = ids.get(i);
            String stored = tx.execute(s -> memberRepository.findById(id).orElseThrow().getProviderUserId());
            assertThat(stored).isEqualTo(k);
            assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(provider_user_id) FROM members WHERE id = ?",
                    Integer.class, id)).isEqualTo(k.codePointCount(0, k.length()));
            Long again = tx.execute(s -> {
                memberRepository.ensureIdentity(k, FIXED_MICROS);
                return memberRepository.findIdentityForUpdate(k).orElseThrow().getId();
            });
            assertThat(again).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE provider_user_id LIKE ?",
                Integer.class, PREFIX + "%")).isEqualTo(keys.size());

        String tooLong = maxLengthKey() + "x";
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> memberRepository.ensureIdentity(tooLong, FIXED_MICROS)))
                .as("잘라서 저장하지 않는다")
                .isInstanceOf(DataIntegrityViolationException.class);
        Optional<Member> truncated = tx.execute(s -> memberRepository.findIdentityForUpdate(tooLong));
        assertThat(truncated).isEmpty();

        assertThat(jdbc.queryForObject("SELECT CONCAT(c.collation_name, '/', co.pad_attribute) "
                + "FROM information_schema.columns c JOIN information_schema.collations co ON co.collation_name = c.collation_name "
                + "WHERE c.table_schema = DATABASE() AND c.table_name = 'members' AND c.column_name = 'provider_user_id'",
                String.class)).isEqualTo("utf8mb4_0900_bin/NO PAD");
    }

    /** JVM이 Asia/Seoul이고 URL에 legacy serverTimezone이 있어도 실제 커넥션은 UTC이며 auditing·native 시각이 UTC로 저장된다. */
    @Test
    void storesUtcMicrosUnderSeoulJvmAndLegacyUrl() throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getJdbcUrl()).contains("serverTimezone=Asia/Seoul");
        assertThat(hikari.getDataSourceProperties())
                .containsEntry("connectionTimeZone", "+00:00")
                .containsEntry("forceConnectionTimeZoneToSession", "true");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        try (Connection raw = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
             Statement statement = raw.createStatement();
             ResultSet rs = statement.executeQuery("SELECT @@session.time_zone, @@global.time_zone")) {
            rs.next();
            assertThat(rs.getString(1)).as("Hikari 설정이 없는 대조 커넥션은 서버 기본값을 따른다").isEqualTo(rs.getString(2));
        }

        Long auditedId = tx.execute(s -> {
            Member member = memberRepository.save(Member.create("TOSS_ANON", key("utc-audit")));
            sessionRepository.save(AuthSession.start(member, UUID.randomUUID(), now(), Duration.ofDays(14)));
            return member.getId();
        });
        String nativeKey = key("utc-native");
        Long nativeId = tx.execute(s -> {
            memberRepository.ensureIdentity(nativeKey, FIXED_MICROS);
            return memberRepository.findIdentityForUpdate(nativeKey).orElseThrow().getId();
        });

        // DB 내부 epoch는 세션 시간대와 무관하다. 저장·조회 변환 오류가 상쇄되지 않도록 고정 Instant와 직접 비교한다.
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        String epoch = epochMicros(FIXED_MICROS).toPlainString();
        for (Map.Entry<String, Long> path : Map.of("auditing", auditedId, "native upsert", nativeId).entrySet()) {
            for (String column : List.of("created_at", "updated_at")) {
                expected.put(path.getKey() + " " + column, epoch);
                actual.put(path.getKey() + " " + column, jdbc.queryForObject(
                        "SELECT UNIX_TIMESTAMP(" + column + ") FROM members WHERE id = ?", BigDecimal.class, path.getValue())
                        .toPlainString());
            }
        }
        expected.put("session expires_at", epochMicros(FIXED_MICROS.plus(Duration.ofDays(14))).toPlainString());
        actual.put("session expires_at", jdbc.queryForObject(
                "SELECT UNIX_TIMESTAMP(expires_at) FROM auth_sessions WHERE member_id = ?", BigDecimal.class, auditedId)
                .toPlainString());
        assertThat(actual).isEqualTo(expected);

        for (Long id : List.of(auditedId, nativeId)) {
            LocalDateTime createdAt = tx.execute(s -> memberRepository.findById(id).orElseThrow().getCreatedAt());
            assertThat(createdAt).isEqualTo(utc(FIXED_MICROS));
            assertThat(createdAt.toInstant(ZoneOffset.UTC).toString()).isEqualTo("2026-10-02T01:02:03.123456Z");
        }
    }

    /**
     * holder가 행을 잠근 채 멈춘 동안 contender가 같은 행에서 MySQL 잠금 대기에 들어가는지 확인하고,
     * 해제 뒤 contender 결과를 돌려준다. 두 작업은 각자 별도 커넥션·트랜잭션에서 실행된다.
     */
    private <T> T assertWaitsForRowLock(Runnable holderWork, Supplier<T> contenderWork) throws Exception {
        return assertWaitsForRowLock(holderWork, contenderWork, null);
    }

    /** whileLocked가 있으면 holder가 잠금을 가진 동안 별도 트랜잭션에서 실행해 대기 없이 끝나는지 확인한다. */
    private <T> T assertWaitsForRowLock(Runnable holderWork, Supplier<T> contenderWork, Runnable whileLocked)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        AtomicLong contenderConnection = new AtomicLong();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                holderConnection.set(connectionId());
                holderWork.run();
                entityManager.flush();
                locked.countDown();
                await(release);
            }));
            awaitOrFail(locked, holder, "holder가 잠금을 얻지 못했다");

            if (whileLocked != null) {
                pool.submit(() -> tx.executeWithoutResult(s -> whileLocked.run()))
                        .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            }

            Future<T> contender = pool.submit(() -> tx.execute(s -> {
                contenderConnection.set(connectionId());
                contenderStarted.countDown();
                return contenderWork.get();
            }));
            awaitOrFail(contenderStarted, contender, "contender가 시작하지 못했다");
            assertThat(contenderConnection.get()).as("서로 다른 DB 커넥션으로 경쟁한다")
                    .isNotEqualTo(holderConnection.get());
            awaitLockWait(holderConnection.get(), contenderConnection.get(), holder, contender);

            release.countDown();
            holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return contender.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 이번 holder가 이번 contender의 레코드 잠금을 막는 관계를 실시간으로 확인한다.
     * INNODB_TRX는 빈번한 조회가 캐시 갱신을 막을 수 있으므로 잠금 관측에 사용하지 않는다.
     */
    private void awaitLockWait(long holderConnection, long contenderConnection,
                               Future<?> holder, Future<?> contender) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (holder.isDone()) {
                holder.get();
                fail("잠금 관측 전에 holder가 종료됐다");
            }
            if (contender.isDone()) {
                contender.get();
                fail("잠금 대기 없이 진행됐다");
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads requester ON requester.THREAD_ID = w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads blocker ON blocker.THREAD_ID = w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = w.ENGINE AND requested.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE requester.PROCESSLIST_ID = ? AND blocker.PROCESSLIST_ID = ?
                      AND requested.OBJECT_SCHEMA = DATABASE() AND requested.LOCK_TYPE = 'RECORD'
                    """, Integer.class, contenderConnection, holderConnection);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        var poolStats = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        fail("경쟁 트랜잭션이 holder의 행 잠금 대기에 들어가지 않았다: holder=" + holderConnection
                + " contender=" + contenderConnection + " contenderDone=" + contender.isDone()
                + " pool(active=" + poolStats.getActiveConnections() + ", idle=" + poolStats.getIdleConnections()
                + ", total=" + poolStats.getTotalConnections() + ", awaiting=" + poolStats.getThreadsAwaitingConnection() + ")"
                + " " + jdbc.queryForList("SELECT PROCESSLIST_ID, PROCESSLIST_COMMAND, PROCESSLIST_STATE "
                + "FROM performance_schema.threads WHERE PROCESSLIST_ID IN (?, ?)",
                holderConnection, contenderConnection));
    }

    /** latch가 열리지 않으면 작업 예외를 우선 드러내고 실패한다. */
    private void awaitOrFail(CountDownLatch latch, Future<?> work, String message) throws Exception {
        if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            if (work.isDone()) {
                work.get();
            }
            fail(message);
        }
    }

    /** 관측 제한 시간보다 길게 잠금을 유지하되, 실패 시에도 finally가 즉시 해제한다. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(HOLD_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("해제 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 현재 트랜잭션이 쓰는 MySQL 커넥션 ID. */
    private long connectionId() {
        return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
    }

    /** 회원·세션·토큰 한 벌을 커밋한다. */
    private Fixture issueToken(String name, Duration ttl, int hashSeed) {
        byte[] hash = sampleHash(hashSeed);
        return tx.execute(s -> {
            Member member = memberRepository.save(Member.create("TOSS_ANON", key(name)));
            AuthSession session = sessionRepository.save(AuthSession.start(member, UUID.randomUUID(), now(), ttl));
            AuthRefreshToken token = tokenRepository.save(AuthRefreshToken.issue(session, hash));
            return new Fixture(member.getId(), session.getId(), token.getId(), hash);
        });
    }

    /** 상태 변경 메서드가 없어 테스트 fixture로만 DB 상태를 BLOCKED로 바꾼다. */
    private void block(Long memberId) {
        assertThat(jdbc.update("UPDATE members SET status = 'BLOCKED' WHERE id = ?", memberId)).isOne();
    }

    /** 회원 행을 DB에서 직접 읽어 upsert 전후를 비교한다. */
    private MemberRow memberRow(Long id) {
        return jdbc.queryForObject("SELECT status, UNIX_TIMESTAMP(onboarding_completed_at), UNIX_TIMESTAMP(last_login_at), "
                        + "UNIX_TIMESTAMP(created_at), UNIX_TIMESTAMP(updated_at) FROM members WHERE id = ?",
                (rs, n) -> new MemberRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)), id);
    }

    private int count(String table, Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private LocalDateTime now() {
        return utc(FIXED_MICROS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal epochMicros(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano() / 1_000, 6));
    }

    private static String key(String suffix) {
        return PREFIX + suffix;
    }

    /** 보조 평면 문자를 포함해 정확히 255 code point인 식별자. */
    private static String maxLengthKey() {
        String base = key("max-");
        return base + "😀".repeat(255 - base.codePointCount(0, base.length()));
    }

    /** 0x00·0xFF를 포함해 BINARY 패딩·부호 처리를 드러내는 32바이트 값. */
    private static byte[] sampleHash(int seed) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed * 31 + i * 7);
        }
        hash[0] = (byte) 0xFF;
        hash[31] = 0;
        return hash;
    }

    private static String databaseName(String jdbcUrl) {
        Matcher matcher = Pattern.compile("jdbc:mysql://[^/]+/([^?]+)").matcher(jdbcUrl);
        return matcher.find() ? matcher.group(1) : "";
    }

    private record Fixture(Long memberId, Long sessionId, Long tokenId, byte[] hash) {
    }

    private record MemberRow(String status, BigDecimal onboardingCompletedAt, BigDecimal lastLoginAt,
                             BigDecimal createdAt, BigDecimal updatedAt) {
    }
}
