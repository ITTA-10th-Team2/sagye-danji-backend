package com.gyejoldanji.domain.recommendation.client.model;

/** 주간 추천 시트에서 읽은 소재별 승인 상태를 표현한다. */
public record WeeklyRecommendationApprovalRow(
        int rowNumber,
        String material,
        boolean approved
) {
}
