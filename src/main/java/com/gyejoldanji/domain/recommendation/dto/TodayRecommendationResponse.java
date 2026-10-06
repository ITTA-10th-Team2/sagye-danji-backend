package com.gyejoldanji.domain.recommendation.dto;

import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import io.swagger.v3.oas.annotations.media.Schema;

/** 홈 화면에 노출할 오늘의 제철 추천 한 건. */
@Schema(description = "오늘 노출할 제철 추천 콘텐츠")
public record TodayRecommendationResponse(
        @Schema(description = "추천 콘텐츠 ID", example = "17") Long id,
        @Schema(description = "콘텐츠 고유 코드", example = "AUTUMN-017") String contentCode,
        @Schema(description = "콘텐츠 카테고리", example = "ACTIVITY_LIFESTYLE") ContentCategory category,
        @Schema(description = "추천 소재", example = "억새") String material,
        @Schema(description = "추천 제목", example = "지금이 제철인 밤") String title,
        @Schema(description = "추천 설명", example = "여유롭게 보내는 가을밤을 기록해 보세요.") String description,
        @Schema(description = "제철 단계", example = "PEAK") OptimalPeriod stage,
        @Schema(description = "추천 지역", example = "전국", nullable = true) String region
) {
}
