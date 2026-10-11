package com.gyejoldanji.domain.analytics.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchResponse;
import com.gyejoldanji.domain.analytics.service.AnalyticsService;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.security.SecurityTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 운영 Security 체인·분석 본문 필터와 실제 JWT를 거쳐 수집 API의 HTTP 계약을 확인한다. 검증·저장은 서비스 테스트에서 본다. */
@SpringBootTest(classes = {SecurityTestConfig.class, AnalyticsController.class, ServiceTokenService.class})
class AnalyticsControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final String EVENTS = "/api/analytics/events";
    private static final byte[] BODY = "{\"events\":[{\"eventName\":\"HOME_VIEW\"}]}".getBytes(StandardCharsets.UTF_8);

    @MockitoBean
    private AuthSessionRepository sessionRepository;
    @MockitoBean
    private AnalyticsService analyticsService;
    @Autowired
    private ServiceTokenService tokenService;
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private String authorization;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        SecurityTestConfig.register(registry);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Instant now = Instant.now();
        authorization = "Bearer " + tokenService.issue(42L, SID, now, now.plusSeconds(3600)).accessToken();
        when(sessionRepository.findAuthenticationView(SID, 42L)).thenReturn(Optional.of(
                new View(7L, 42L, MemberStatus.ACTIVE,
                        LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC), null)));
    }

    /** 원본 본문을 그대로 현재 회원과 서비스에 넘기고 공통 성공 형식·캐시 금지로 응답한다. */
    @Test
    void collectsWithRawBodyForCurrentMember() throws Exception {
        when(analyticsService.collect(42L, BODY)).thenReturn(new AnalyticsBatchResponse(2, 1));

        mockMvc.perform(events(BODY).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.message").value("행동 이벤트 수집에 성공했습니다."))
                .andExpect(jsonPath("$.data.acceptedCount").value(2))
                .andExpect(jsonPath("$.data.duplicateCount").value(1));
        verify(analyticsService).collect(42L, BODY);
    }

    @Test
    void rejectsMissingTokenBeforeService() throws Exception {
        mockMvc.perform(events(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_005"));
        verifyNoInteractions(analyticsService);
    }

    /** 262,145바이트 본문은 인증·서비스 전에 413 ANALYTICS_004다. */
    @Test
    void rejectsOversizedBodyBeforeAuthentication() throws Exception {
        String prefix = "{\"events\":[";
        byte[] body = (prefix + " ".repeat(262_145 - prefix.length() - 2) + "]}").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(events(body).header("Authorization", authorization))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ANALYTICS_004"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verifyNoInteractions(analyticsService, sessionRepository);
    }

    /** JSON이 아니거나 UTF-8이 아니거나 압축 전송이면 415 ANALYTICS_005다. */
    @Test
    void rejectsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post(EVENTS).contentType("text/plain").content(BODY).header("Authorization", authorization))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ANALYTICS_005"));
        mockMvc.perform(post(EVENTS).contentType("application/json;charset=ISO-8859-1").content(BODY)
                        .header("Authorization", authorization))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(events(BODY).header("Content-Encoding", "gzip").header("Authorization", authorization))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(analyticsService);
    }

    /** 분석 오류는 고정 code·message만 담고 errors·입력값을 내보내지 않는다. */
    @Test
    void validationErrorHasNoErrorsOrInput() throws Exception {
        when(analyticsService.collect(eq(42L), any()))
                .thenThrow(new BusinessException(ErrorCode.ANALYTICS_INVALID_REQUEST));

        mockMvc.perform(events(BODY).header("Authorization", authorization))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ANALYTICS_001"))
                .andExpect(jsonPath("$.message").value(ErrorCode.ANALYTICS_INVALID_REQUEST.getMessage()))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(content().string(not(containsString("HOME_VIEW"))));
    }

    private static MockHttpServletRequestBuilder events(byte[] body) {
        return post(EVENTS).contentType("application/json").content(body);
    }

    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus, LocalDateTime getExpiresAt,
                        LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
