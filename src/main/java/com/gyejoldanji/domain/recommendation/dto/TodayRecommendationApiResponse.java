package com.gyejoldanji.domain.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 오늘의 추천 조회 API가 반환하는 공통 응답 래퍼의 Swagger 스키마. */
@Schema(description = "오늘의 추천 조회 응답")
public record TodayRecommendationApiResponse(
        @Schema(description = "요청 성공 여부", example = "true") boolean success,
        @Schema(description = "HTTP 상태 코드", example = "200") String code,
        @Schema(description = "응답 메시지", example = "오늘의 추천 조회에 성공했습니다.") String message,
        @Schema(description = "오늘 노출할 추천 콘텐츠") TodayRecommendationResponse data
) {
}
