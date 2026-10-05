package com.gyejoldanji.domain.record.controller;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.auth.service.ServiceTokenService;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordCursorPageResponse;
import com.gyejoldanji.domain.record.dto.RecordListItemResponse;
import com.gyejoldanji.domain.record.dto.RecordSummaryResponse;
import com.gyejoldanji.domain.record.service.RecordCommandService;
import com.gyejoldanji.domain.record.service.RecordQueryService;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.security.CurrentMember;
import com.gyejoldanji.global.security.SecurityTestConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 운영 Security chain과 실제 JWT를 통과해 기록 command의 HTTP·Swagger 계약을 검증한다. */
@SpringBootTest(classes = {
        SecurityTestConfig.class,
        RecordController.class,
        ServiceTokenService.class
})
class RecordControllerTest {

    private static final String SID = UUID.randomUUID().toString();
    private static final String RECORDS = "/api/records";

    @MockitoBean
    private AuthSessionRepository sessionRepository;
    @MockitoBean
    private RecordCommandService recordCommandService;
    @MockitoBean
    private RecordQueryService recordQueryService;
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

    @Test
    void createsWithActual201AndAuthenticatedMember() throws Exception {
        when(recordCommandService.create(eq(42L), any())).thenReturn(response());

        mockMvc.perform(post(RECORDS)
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("""
                                {
                                  "recordDate":"2026-10-04",
                                  "memo":"가을밤 🍂",
                                  "images":[{"objectKey":"record-images/42/a.jpg","source":"CAMERA","sortOrder":0}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("201"))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.season").value("AUTUMN"))
                .andExpect(jsonPath("$.data.images[0].id").value(501));
    }

    @Test
    void rejectsUnauthenticatedCreateBeforeService() throws Exception {
        mockMvc.perform(post(RECORDS)
                        .contentType("application/json")
                        .content("{\"images\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_005"));

        verifyNoInteractions(recordCommandService);
    }

    @Test
    void updatesAndDeletesOwnedRecord() throws Exception {
        when(recordCommandService.update(eq(42L), eq(101L), any())).thenReturn(response());

        mockMvc.perform(patch(RECORDS + "/101")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"recordDate\":\"2026-10-05\",\"memo\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(delete(RECORDS + "/101").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("기록 삭제에 성공했습니다."));
    }

    @Test
    void readsAllSeasonAndDetailWithAuthenticatedMember() throws Exception {
        RecordCursorPageResponse page = new RecordCursorPageResponse(List.of(
                new RecordListItemResponse(101L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN, "가을밤 🍂",
                        new RecordListItemResponse.CoverImageResponse(501L,
                                com.gyejoldanji.domain.image.enums.PhotoSource.CAMERA, 0), 1)), null, false);
        when(recordQueryService.findAll(42L, 2026, null, 20)).thenReturn(page);
        when(recordQueryService.findBySeason(42L, 2026, SeasonType.AUTUMN, null, 5)).thenReturn(page);
        when(recordQueryService.findDetail(42L, 101L)).thenReturn(response());
        when(recordQueryService.findSummary(42L)).thenReturn(new RecordSummaryResponse(3, 12));

        mockMvc.perform(get(RECORDS + "/summary").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.recordCount").value(3))
                .andExpect(jsonPath("$.data.recordingDayCount").value(12));

        mockMvc.perform(get(RECORDS).header("Authorization", authorization).param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].id").value(101))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        mockMvc.perform(get(RECORDS + "/seasons/AUTUMN")
                        .header("Authorization", authorization).param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].season").value("AUTUMN"));

        mockMvc.perform(get(RECORDS + "/101").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.images[0].id").value(501));
    }

    @Test
    void rejectsUnauthenticatedQueriesAndInvalidParametersBeforeService() throws Exception {
        mockMvc.perform(get(RECORDS + "/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_005"));

        mockMvc.perform(get(RECORDS).param("year", "2026"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_005"));

        mockMvc.perform(get(RECORDS).header("Authorization", authorization)
                        .param("year", "1999").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        mockMvc.perform(get(RECORDS + "/seasons/FALL").header("Authorization", authorization)
                        .param("year", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        mockMvc.perform(get(RECORDS + "/seasons/AUTUMN").header("Authorization", authorization)
                        .param("year", "2026").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void allowsPatchAndDeleteCorsPreflightOnlyForConfiguredOrigin() throws Exception {
        for (String method : List.of("PATCH", "DELETE")) {
            mockMvc.perform(options(RECORDS + "/101")
                            .header("Origin", SecurityTestConfig.ALLOWED_ORIGIN)
                            .header("Access-Control-Request-Method", method)
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", SecurityTestConfig.ALLOWED_ORIGIN))
                    .andExpect(header().string("Access-Control-Allow-Methods",
                            org.hamcrest.Matchers.containsString(method)));
        }

        mockMvc.perform(options(RECORDS + "/101")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isForbidden());
    }

    @Test
    void documentsEveryCommandWithOperationAndResponses() {
        List<String> methodNames = List.of("findSummary", "findAll", "findBySeason", "findDetail", "create",
                "update", "delete");
        for (Method method : RecordController.class.getDeclaredMethods()) {
            if (methodNames.contains(method.getName())) {
                assertThat(method.getAnnotation(Operation.class)).as(method.getName()).isNotNull();
                assertThat(method.getAnnotation(ApiResponses.class)).as(method.getName()).isNotNull();
            }
        }
    }

    @Test
    void documentsUpdateImageTypeInRequestExamples() throws Exception {
        Method update = RecordController.class.getDeclaredMethod("update", CurrentMember.class, Long.class,
                com.gyejoldanji.domain.record.dto.RecordUpdateRequest.class,
                jakarta.servlet.http.HttpServletResponse.class);
        io.swagger.v3.oas.annotations.parameters.RequestBody requestBody = update.getParameters()[2]
                .getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);

        assertThat(requestBody).isNotNull();
        assertThat(requestBody.content()[0].examples())
                .extracting(ExampleObject::value)
                .anySatisfy(value -> assertThat(value).contains("\"type\": \"EXISTING\"", "\"type\": \"NEW\""))
                .anySatisfy(value -> assertThat(value).doesNotContain("\"images\""));
    }

    private static RecordResponse response() {
        return new RecordResponse(101L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN, "가을밤 🍂",
                List.of(new RecordResponse.ImageResponse(501L,
                        com.gyejoldanji.domain.image.enums.PhotoSource.CAMERA, 0)),
                "2026-10-03T17:10:00Z", "2026-10-03T17:10:00Z");
    }

    /** Security filter가 조회하는 회원·세션 projection. */
    private record View(Long getSessionId, Long getMemberId, MemberStatus getMemberStatus,
                        LocalDateTime getExpiresAt, LocalDateTime getRevokedAt) implements AuthenticationView {
    }
}
