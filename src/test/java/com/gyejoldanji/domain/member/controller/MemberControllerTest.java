package com.gyejoldanji.domain.member.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/members/me를 운영 Security 체인·실제 서명 토큰·CurrentMember 주입으로 검증한다. 회원·세션 Repository는 대체하고 실제
 * DB 흐름은 MySQL 통합 테스트에서 확인한다.
 */
@SpringBootTest(classes = {SecurityTestConfig.class, MemberController.class, MemberService.class,
        ServiceTokenService.class, MemberControllerTest.TestBeans.class})
class MemberControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        SecurityTestConfig.register(registry);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(sessionRepository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(new View(7L, 42L,
                MemberStatus.ACTIVE, LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null)));
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

    private ResultActions me() throws Exception {
        return mockMvc.perform(get("/api/members/me").header("Authorization", bearer()));
    }

    private String bearer() {
        Instant now = Instant.now();
        return "Bearer " + tokenService.issue(42L, SID, now, now.plusSeconds(1_209_600)).accessToken();
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static Iterable<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static Member member(LocalDateTime completedAt) {
        Member member = Member.create("TOSS_ANON", "anon-key");
        ReflectionTestUtils.setField(member, "id", 42L);
        if (completedAt != null) {
            member.completeOnboarding(completedAt);
        }
        return member;
    }

    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus, LocalDateTime getExpiresAt,
                        LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
