package com.gyejoldanji.domain.jar.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gyejoldanji.domain.analytics.service.ServerAnalyticsRecorder;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.jar.dto.JarStickerResponse;
import com.gyejoldanji.domain.jar.enums.StickerType;
import com.gyejoldanji.domain.jar.service.JarPageQueryService;
import com.gyejoldanji.domain.jar.service.JarStickerService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 운영 Security 체인과 실제 JWT로 스티커 저장 뒤 서버 행동 이벤트 연결만 확인한다. 저장 규칙은 JarStickerServiceTest에서 본다. */
@SpringBootTest(classes = {SecurityTestConfig.class, JarStickerController.class, ServiceTokenService.class})
class JarStickerControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final String STICKERS = "/api/jar-pages/31/stickers";
    private static final String TWO_ITEMS = """
            {"items":[
              {"stickerType":"CLOVER","xRatio":0.1,"yRatio":0.2,"scale":1.0,"rotation":0,"zIndex":0},
              {"stickerType":"BUBBLE","xRatio":0.3,"yRatio":0.4,"scale":1.0,"rotation":0,"zIndex":5}
            ]}
            """;

    @MockitoBean
    private AuthSessionRepository sessionRepository;
    @MockitoBean
    private JarStickerService service;
    @MockitoBean
    private JarPageQueryService pageQueryService;
    @MockitoBean
    private ServerAnalyticsRecorder analyticsRecorder;
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

    /** 저장 성공 뒤 경로의 페이지 ID와 요청 items 수로 남긴다. 빈 배열 저장은 0으로 남긴다. */
    @Test
    void recordsStickerCountAfterReplace() throws Exception {
        when(service.replace(eq(42L), eq(31L), any())).thenReturn(saved());

        replace(TWO_ITEMS).andExpect(status().isOk());
        verify(analyticsRecorder).stickersSaved(eq(42L), eq(31L), eq(2), any());

        replace("{\"items\":[]}").andExpect(status().isOk());
        verify(analyticsRecorder).stickersSaved(eq(42L), eq(31L), eq(0), any());
    }

    /** 저장 실패와 별도 DELETE API는 남기지 않는다. */
    @Test
    void doesNotRecordFailedReplaceOrDelete() throws Exception {
        when(service.replace(eq(42L), eq(31L), any())).thenThrow(new BusinessException(ErrorCode.JAR_PAGE_NOT_FOUND));
        replace(TWO_ITEMS).andExpect(status().isNotFound());

        mockMvc.perform(delete(STICKERS).header("Authorization", authorization)).andExpect(status().isOk());
        verify(service).delete(42L, 31L);

        verifyNoInteractions(analyticsRecorder);
    }

    /** recorder가 던져도 저장 응답(200, 본문)은 그대로다. */
    @Test
    void keepsReplaceResponseWhenRecorderFails() throws Exception {
        when(service.replace(eq(42L), eq(31L), any())).thenReturn(saved());
        doThrow(new IllegalStateException("analytics down")).when(analyticsRecorder)
                .stickersSaved(any(), any(), anyInt(), any());

        replace(TWO_ITEMS).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("단지 스티커 저장에 성공했습니다."))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].stickerType").value("CLOVER"));
    }

    private ResultActions replace(String body) throws Exception {
        return mockMvc.perform(put(STICKERS).header("Authorization", authorization)
                .contentType("application/json").content(body));
    }

    private static JarStickerResponse saved() {
        return new JarStickerResponse(List.of(new JarStickerResponse.Item(StickerType.CLOVER, 0.1, 0.2, 1.0, 0, 0)));
    }

    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus, LocalDateTime getExpiresAt,
                        LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
