package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.recommendation.client.WeeklyRecommendationSheetClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Google Sheets의 주간 추천 승인 체크 상태를 DB에 동기화한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class WeeklyRecommendationSyncService {

    private final WeeklyRecommendationSheetClient sheetClient;
    private final WeeklyRecommendationApprovalService approvalService;

    /** 시트 읽기를 마친 뒤 승인 상태만 DB 트랜잭션으로 반영한다. */
    public void synchronize() {
        var rows = sheetClient.readApprovalRows();
        approvalService.applyApprovals(rows);
        log.info("주간 추천 승인 상태 동기화를 완료했습니다. count={}", rows.size());
    }
}
