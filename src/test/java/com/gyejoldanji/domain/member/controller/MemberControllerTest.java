package com.gyejoldanji.domain.member.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.member.dto.MemberResponse;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.member.service.MemberService;
import com.gyejoldanji.global.security.SecurityTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/members/me와 POST /api/members/me/onboarding/complete를 운영 Security 체인·실제 서명 토큰·CurrentMember 주입으로
 * 검증한다. 회원·세션 Repository와 트랜잭션 관리자는 대체하고 실제 DB 흐름(잠금·commit)은 MySQL 통합 테스트에서 확인한다.
 */
@SpringBootTest(classes = {SecurityTestConfig.class, MemberController.class, MemberService.class,
        ServiceTokenService.class, MemberControllerTest.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class MemberControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final String COMPLETE = "/api/members/me/onboarding/complete";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 필터·JWT 검증·서비스가 함께 쓰는 고정 시각(나노초 포함). */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
    private static final String NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS).toString();

    @MockitoBean
    private AuthSessionRepository sessionRepository;
    @MockitoBean
    private MemberRepository memberRepository;
    @MockitoBean
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ServiceTokenService tokenService;
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** 대체한 트랜잭션 관리자로 commit·rollback 호출과 실패를 확인한다. */
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        SecurityTestConfig.register(registry);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(sessionRepository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(new View(7L, 42L,
                MemberStatus.ACTIVE, utc(NOW).plusDays(1), null)));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    /** 미완료면 NOT_COMPLETED이고 완료 시각 필드는 없다. 회원 ID 문자열 외 내부·외부 식별값을 응답하지 않는다. */
    @Test
    void returnsNotCompletedWithoutCompletedAt() throws Exception {
        when(memberRepository.findById(42L)).thenReturn(Optional.of(member(null)));

        JsonNode body = body(me()
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache")));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("success", "code", "data");
        assertThat(body.path("success").booleanValue()).isTrue();
        assertThat(body.path("code").stringValue()).isEqualTo("200");
        assertThat(fieldNames(body.path("data"))).containsExactlyInAnyOrder("memberId", "onboardingStatus");
        assertThat(body.path("data").path("memberId").isString()).isTrue();
        assertThat(body.path("data").path("memberId").stringValue()).isEqualTo("42");
        assertThat(body.path("data").path("onboardingStatus").stringValue()).isEqualTo("NOT_COMPLETED");
    }

    /** 완료 시각은 저장된 UTC 값 그대로 Z로 응답한다. JVM 기본 시간대가 Asia/Seoul이어도 바뀌지 않는다. */
    @ParameterizedTest
    @CsvSource({
            "2026-10-01T12:00:00, 2026-10-01T12:00:00Z",
            "2026-10-01T23:30:00.123456, 2026-10-01T23:30:00.123456Z"})
    void returnsCompletedAtInUtc(LocalDateTime stored, String expected) throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try {
            when(memberRepository.findById(42L)).thenReturn(Optional.of(member(stored)));

            me().andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberId").value("42"))
                    .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.onboardingCompletedAt").value(expected));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** 회원은 JWT·세션 검사로 만든 CurrentMember에서만 정한다. query·header·body의 회원 선택값은 무시한다. */
    @Test
    void ignoresClientSuppliedMemberSelectors() throws Exception {
        when(memberRepository.findById(42L)).thenReturn(Optional.of(member(null)));

        mockMvc.perform(get("/api/members/me").param("memberId", "99").header("X-Member-Id", "99")
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"memberId\":\"99\",\"sessionId\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value("42"));

        verify(sessionRepository).findAuthenticationView(SID, 42L);
        verify(memberRepository).findById(42L);
        verify(memberRepository, never()).findById(99L);
    }

    /** 세션 검사 뒤 회원이 없으면 401 AUTH_003, DB 장애는 503 COMMON_008. 오류에도 캐시 금지 헤더가 그대로 있다. */
    @Test
    void mapsServiceFailuresWithCacheHeaders() throws Exception {
        when(memberRepository.findById(42L)).thenReturn(Optional.empty());
        expectError(me(), 401, "AUTH_003");

        when(memberRepository.findById(anyLong())).thenThrow(new CannotCreateTransactionException("down"));
        expectError(me(), 503, "COMMON_008");
    }

    /** 인증이 없으면 컨트롤러·회원 조회 전에 401 COMMON_005다. */
    @Test
    void requiresAuthentication() throws Exception {
        expectError(mockMvc.perform(get("/api/members/me")), 401, "COMMON_005");
        verifyNoInteractions(memberRepository);
    }

    /** MemberResponse 자체에도 null 생략이 적용된다(ApiResponse 설정과 무관). */
    @Test
    void memberResponseOmitsNullCompletedAtByItself() {
        assertThat(JSON.writeValueAsString(new MemberResponse("1", "NOT_COMPLETED", null)))
                .isEqualTo("{\"memberId\":\"1\",\"onboardingStatus\":\"NOT_COMPLETED\"}");
    }

    /**
     * 본문 없는 POST로 완료한다. 필터가 준 회원 ID·세션 PK로 회원 → 세션을 잠그고(일반 조회 없음) 서버 시각을 UTC 마이크로초 Z로
     * 응답하며, 이후 me도 COMPLETED다. 성공에도 캐시 금지 헤더가 있다.
     */
    @Test
    void completesOnboardingWithoutBody() throws Exception {
        Member member = member(null);
        stubLockedRows(member, session(member));

        JsonNode body = body(complete()
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache")));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("success", "code", "data");
        assertThat(body.path("success").booleanValue()).isTrue();
        assertThat(body.path("code").stringValue()).isEqualTo("200");
        assertThat(fieldNames(body.path("data")))
                .containsExactlyInAnyOrder("memberId", "onboardingStatus", "onboardingCompletedAt");
        assertThat(body.path("data").path("memberId").isString()).isTrue();
        assertThat(body.path("data").path("memberId").stringValue()).isEqualTo("42");
        assertThat(body.path("data").path("onboardingStatus").stringValue()).isEqualTo("COMPLETED");
        assertThat(body.path("data").path("onboardingCompletedAt").stringValue()).isEqualTo(NOW_MICROS);
        verify(memberRepository).findByIdForUpdate(42L);
        verify(sessionRepository).findByIdForUpdate(7L);
        verify(memberRepository, never()).findById(anyLong());
        verify(transactionManager).commit(any());

        when(memberRepository.findById(42L)).thenReturn(Optional.of(member));
        me().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value(NOW_MICROS));
    }

    /**
     * body·query·header의 회원 ID·세션 ID·완료 시각은 쓰지 않는다. 본문은 읽지도 않으므로 깨진 JSON·다른 Content-Type도 성공하며
     * 저장 시각은 언제나 서버 시각이다.
     */
    @Test
    void ignoresClientSuppliedOnboardingValues() throws Exception {
        String clientValues = "{\"memberId\":\"99\",\"sessionId\":\"1\",\"onboardingCompletedAt\":\"2000-01-01T00:00:00Z\"}";
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                post(COMPLETE).param("memberId", "99").param("sessionId", "1")
                        .param("onboardingCompletedAt", "2000-01-01T00:00:00Z").header("X-Member-Id", "99")
                        .contentType("application/json").content(clientValues),
                post(COMPLETE).contentType("application/json").content("{\"memberId\":"),
                post(COMPLETE).contentType("text/plain").content("memberId=99")}) {
            Member member = member(null);
            stubLockedRows(member, session(member));

            mockMvc.perform(request.header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberId").value("42"))
                    .andExpect(jsonPath("$.data.onboardingCompletedAt").value(NOW_MICROS));
            assertThat(member.getOnboardingCompletedAt()).isEqualTo(utc(NOW.truncatedTo(ChronoUnit.MICROS)));
        }
        verify(memberRepository, never()).findByIdForUpdate(99L);
        verify(sessionRepository, never()).findByIdForUpdate(1L);
    }

    /** 반복 요청은 잠금·재검사 뒤 최초 완료 시각 그대로 200이다. */
    @Test
    void repeatedCompletionKeepsFirstTime() throws Exception {
        Member member = member(LocalDateTime.parse("2026-10-01T12:00:00.000001"));
        stubLockedRows(member, session(member));

        complete().andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.onboardingCompletedAt").value("2026-10-01T12:00:00.000001Z"));
        verify(sessionRepository).findByIdForUpdate(7L);
    }

    /**
     * Bearer 누락·형식 오류·변조·만료와 필터의 무효 세션은 기존 보안 오류 계약대로 거부되고 트랜잭션·잠금 조회에 가지 않는다.
     */
    @Test
    void rejectsAuthenticationFailuresBeforeService() throws Exception {
        expectSecurityError(mockMvc.perform(post(COMPLETE)), "COMMON_005");
        expectSecurityError(mockMvc.perform(post(COMPLETE).header("Authorization", "Bearer !!!")), "AUTH_002");
        expectSecurityError(mockMvc.perform(post(COMPLETE).header("Authorization", tamper(bearer()))), "AUTH_002");
        Instant past = NOW.minusSeconds(3_600);
        String expired = "Bearer " + tokenService.issue(42L, SID, past, past.plusSeconds(1_209_600)).accessToken();
        expectSecurityError(mockMvc.perform(post(COMPLETE).header("Authorization", expired)), "AUTH_001");

        when(sessionRepository.findAuthenticationView(SID, 42L)).thenReturn(Optional.empty());
        expectSecurityError(complete(), "AUTH_003");

        verify(transactionManager, never()).getTransaction(any());
        verifyNoInteractions(memberRepository);
        verify(sessionRepository, never()).findByIdForUpdate(anyLong());
    }

    /** 필터를 통과했어도 잠금 뒤 세션이 폐기돼 있으면 401 AUTH_003이고 저장 없이 rollback한다. */
    @Test
    void rejectsSessionInvalidatedAfterFilter() throws Exception {
        Member member = member(null);
        AuthSession session = session(member);
        session.revoke(SessionRevokeReason.LOGOUT, utc(NOW).minusSeconds(1));
        stubLockedRows(member, session);

        expectError(complete(), 401, "AUTH_003");
        assertThat(member.isOnboardingCompleted()).isFalse();
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * 잠금 실패는 503 COMMON_008, flush 무결성·commit 실패는 409가 아닌 500 COMMON_003이다. 모두 캐시 금지 헤더가 있고 DB 메시지는
     * 로그·응답에 없다.
     */
    @Test
    void mapsTransactionFailuresWithCacheHeaders(CapturedOutput output) throws Exception {
        String secret = "Duplicate entry 'SECRET-DB-VALUE'";
        Member member = member(null);
        stubLockedRows(member, session(member));

        when(memberRepository.findByIdForUpdate(42L)).thenThrow(new CannotAcquireLockException(secret));
        expectError(complete(), 503, "COMMON_008");

        stubLockedRows(member, session(member));
        doThrow(new DataIntegrityViolationException(secret)).when(transactionManager).commit(any());
        expectError(complete(), 500, "COMMON_003");

        doThrow(new TransactionSystemException(secret)).when(transactionManager).commit(any());
        expectError(complete(), 500, "COMMON_003")
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(secret));
        assertThat(output.getAll()).doesNotContain(secret);
    }

    private ResultActions me() throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", bearer()));
    }

    private ResultActions complete() throws Exception {
        return mockMvc.perform(post(COMPLETE).header("Authorization", bearer()));
    }

    /** 잠금 조회 결과. 앞에서 예외로 바꾼 스텁도 덮어쓸 수 있게 doReturn을 쓴다. */
    private void stubLockedRows(Member member, AuthSession session) {
        doReturn(Optional.of(member)).when(memberRepository).findByIdForUpdate(42L);
        doReturn(Optional.of(session)).when(sessionRepository).findByIdForUpdate(7L);
    }

    /** 고정 Clock 시각에 발급한 회원 42·세션 SID의 실제 서명 Access 토큰. */
    private String bearer() {
        return "Bearer " + tokenService.issue(42L, SID, NOW, NOW.plusSeconds(1_209_600)).accessToken();
    }

    /** 서명 첫 글자를 바꾼다. */
    private static String tamper(String bearer) {
        int i = bearer.lastIndexOf('.') + 1;
        return bearer.substring(0, i) + (bearer.charAt(i) == 'A' ? 'B' : 'A') + bearer.substring(i + 1);
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

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static Iterable<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Member member(LocalDateTime completedAt) {
        Member member = Member.create("TOSS_ANON", "anon-key");
        ReflectionTestUtils.setField(member, "id", 42L);
        if (completedAt != null) {
            member.completeOnboarding(completedAt);
        }
        return member;
    }

    /** 회원 소유, 내부 PK 7, 하루 뒤 만료되는 미폐기 세션. */
    private static AuthSession session(Member owner) {
        AuthSession session = AuthSession.start(owner, UUID.fromString(SID), utc(NOW).minusDays(13),
                Duration.ofDays(14));
        ReflectionTestUtils.setField(session, "id", 7L);
        return session;
    }

    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus, LocalDateTime getExpiresAt,
                        LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
