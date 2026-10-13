package com.gyejoldanji.domain.analytics.controller;

import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchRequestDoc;
import com.gyejoldanji.domain.analytics.dto.AnalyticsBatchResponse;
import com.gyejoldanji.domain.analytics.service.AnalyticsService;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import com.gyejoldanji.global.security.CurrentMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트 행동 이벤트 수집 API. 회원은 {@link CurrentMember}로만 정하고 본문의 회원 값은 받지 않는다. 본문은 원본 바이트로 받아
 * 분석 전용 검증기가 고정 오류 코드로만 거부한다(공통 검증 오류의 {@code errors} 배열을 쓰지 않는다).
 */
@Tag(name = "Analytics", description = "행동 이벤트 수집 API")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "행동 이벤트 수집",
            description = """
                    프론트 행동 이벤트를 1~50건 배치로 저장합니다. 같은 회원이 이미 보낸 eventId는 저장하지 않고 duplicateCount로 셉니다.
                    본문은 262,144바이트 이하 application/json(UTF-8)만 받습니다.
                    eventName 허용값: ONBOARDING_START, ONBOARDING_SKIP, HOME_VIEW, HOME_ELEMENT_TAP, RECORD_SCREEN_VIEW,
                    RECORD_START, JAR_VIEW, LOCKED_SEASON_CLICK, SHARE_CLICK, SCREEN_ENGAGEMENT, SESSION_START.
                    SESSION_START 선택 속성: referrer, shareScreen, utmSource, utmCampaign.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수집 성공(재전송 포함)",
                    content = @Content(schema = @Schema(implementation = AnalyticsBatchResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "ANALYTICS_001 형식·속성 오류, ANALYTICS_002 수집 불가 이벤트, ANALYTICS_003 발생 시각 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
                    description = "ANALYTICS_004 본문 262,144바이트 또는 50건 초과",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                    description = "ANALYTICS_005 application/json(UTF-8)이 아니거나 압축 전송",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "일시적 DB 장애",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/events")
    public ApiResponse<AnalyticsBatchResponse> collect(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AnalyticsBatchRequestDoc.class),
                            examples = @ExampleObject(name = "홈 진입", value = """
                                    {
                                      "events": [
                                        {
                                          "eventId": "3f1c2b4a-8d7e-4f60-9a1b-2c3d4e5f6a7b",
                                          "eventName": "HOME_VIEW",
                                          "sessionId": "9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e",
                                          "serviceVersion": "1.0.0",
                                          "os": "IOS",
                                          "sequence": 1,
                                          "occurredAt": "2026-10-11T03:04:05.123Z",
                                          "properties": { "entrySource": "APP_OPEN" }
                                        }
                                      ]
                                    }
                                    """)))
            @RequestBody(required = false) byte[] body,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("행동 이벤트 수집에 성공했습니다.", analyticsService.collect(currentMember.memberId(), body));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
