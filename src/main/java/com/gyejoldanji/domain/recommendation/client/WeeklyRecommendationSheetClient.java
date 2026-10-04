package com.gyejoldanji.domain.recommendation.client;

import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationApprovalRow;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationSheetRow;

import java.util.List;

/** 주간 추천 서비스가 사용하는 Google Sheets 접근 계약. */
public interface WeeklyRecommendationSheetClient {

    /** 기존 데이터를 지우고 이번 주 추천 7개를 덮어쓴다. */
    void overwriteWeeklyRecommendations(List<WeeklyRecommendationSheetRow> rows);

    /** 현재 주간 추천 시트의 소재별 승인 상태를 읽는다. */
    List<WeeklyRecommendationApprovalRow> readApprovalRows();
}
