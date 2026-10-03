package com.gyejoldanji.domain.recommendation.dto;

import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;

/** 홈 화면에 노출할 오늘의 제철 추천 한 건. */
public record TodayRecommendationResponse(
        Long id,
        String contentCode,
        ContentCategory category,
        String material,
        String title,
        String description,
        OptimalPeriod stage,
        String region
) {
}
