package com.gyejoldanji.domain.analytics.dto;

import java.util.List;
import java.util.Map;

import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Swagger 설명 전용 요청 스키마. 실제 요청은 원본 바이트로 받아 {@code AnalyticsEventValidator}가 검증하므로 바인딩에 쓰지 않는다.
 */
@Schema(name = "AnalyticsBatchRequest", description = "행동 이벤트 배치. 정의되지 않은 키는 거부한다.")
public record AnalyticsBatchRequestDoc(
        @ArraySchema(minItems = 1, maxItems = 50, schema = @Schema(implementation = Event.class))
        List<Event> events) {

    @Schema(name = "AnalyticsEventRequest")
    public record Event(
            @Schema(description = "UUID v4. 재전송 시 같은 값을 보낸다", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "3f1c2b4a-8d7e-4f60-9a1b-2c3d4e5f6a7b")
            String eventId,
            @Schema(description = "수집 가능한 프론트 이벤트", requiredMode = Schema.RequiredMode.REQUIRED)
            ClientEventName eventName,
            @Schema(description = "프론트 분석 세션 UUID", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e")
            String sessionId,
            @Schema(description = "앱 버전(1~30자, 앞뒤 공백·제어문자 불가)", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "1.0.0")
            String serviceVersion,
            @Schema(description = "클라이언트 OS", requiredMode = Schema.RequiredMode.REQUIRED)
            ClientOs os,
            @Schema(description = "세션 안 순번(선택, 1 이상 정수)", example = "1")
            Integer sequence,
            @Schema(description = "UTC 발생 시각(Z 표기). 지금+5분 이후는 거부", requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "2026-10-11T03:04:05.123Z")
            String occurredAt,
            @Schema(description = "이벤트별 속성 객체. 필수 속성이 없는 이벤트는 생략 가능",
                    example = "{\"entrySource\":\"APP_OPEN\"}")
            Map<String, Object> properties) {
    }
}
