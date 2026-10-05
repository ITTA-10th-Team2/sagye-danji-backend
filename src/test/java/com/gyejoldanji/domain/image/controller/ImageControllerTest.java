package com.gyejoldanji.domain.image.controller;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.image.client.fake.FakeImageStorageClient;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.global.security.SecurityTestConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 운영 Security chain·실제 JWT·Fake 저장소로 업로드 URL 발급 API의 HTTP 계약을 검증한다. */
@SpringBootTest(classes = {
        SecurityTestConfig.class,
        ImageController.class,
        ImageStorageService.class,
        FakeImageStorageClient.class,
        ServiceTokenService.class
})
class ImageControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final String URL = "/api/images/presigned-url";

    @MockitoBean
    private AuthSessionRepository sessionRepository;
    @Autowired
    private ServiceTokenService tokenService;
    @Autowired
    private FakeImageStorageClient storage;
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

    @Test
    void issuesUploadUrlUnderAuthenticatedMemberFolder() throws Exception {
        MvcResult result = mockMvc.perform(post(URL)
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\",\"fileSize\":2048000}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.objectKey").value(
                        org.hamcrest.Matchers.matchesPattern("record-images/42/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.jpg")))
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.requiredHeaders.content-type").value("image/jpeg"))
                .andExpect(jsonPath("$.data.requiredHeaders.content-length").value("2048000"))
                .andReturn();

        String objectKey = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.objectKey");
        assertThat(storage.contains(objectKey)).isTrue();
    }

    @Test
    void rejectsUnsupportedTypeAndOversizedFile() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/webp\",\"fileSize\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_002"));

        mockMvc.perform(post(URL)
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/png\",\"fileSize\":10485761}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_003"));
    }

    @Test
    void rejectsMissingOrNonPositiveFields() throws Exception {
        for (String body : new String[]{"{\"fileSize\":1}", "{\"contentType\":\"image/png\"}",
                "{\"contentType\":\"image/png\",\"fileSize\":0}"}) {
            mockMvc.perform(post(URL)
                            .header("Authorization", authorization)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
    }

    @Test
    void rejectsUnauthenticatedAndOtherMethods() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/png\",\"fileSize\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_005"));

        mockMvc.perform(get(URL).header("Authorization", authorization))
                .andExpect(status().isForbidden());
    }

    @Test
    void documentsOperationAndResponses() {
        for (Method method : ImageController.class.getDeclaredMethods()) {
            if (method.getName().equals("issueUploadUrl")) {
                assertThat(method.getAnnotation(Operation.class)).isNotNull();
                assertThat(method.getAnnotation(ApiResponses.class)).isNotNull();
            }
        }
    }

    /** Security filter가 조회하는 회원·세션 projection. */
    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus,
                        LocalDateTime getExpiresAt, LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
