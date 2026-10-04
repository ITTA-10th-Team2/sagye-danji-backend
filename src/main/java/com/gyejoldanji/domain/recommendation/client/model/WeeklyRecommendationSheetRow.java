package com.gyejoldanji.domain.recommendation.client.model;

/** 주간 추천 시트에 출력할 한 행을 표현한다. */
public record WeeklyRecommendationSheetRow(
        String week,
        String type,
        String material,
        String stage,
        boolean approved
) {
}
