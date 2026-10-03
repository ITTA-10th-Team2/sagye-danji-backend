package com.gyejoldanji.domain.recommendation.scheduler;

import com.gyejoldanji.domain.recommendation.service.WeeklyRecommendationSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주간 추천 시트의 승인 체크 상태를 주기적으로 DB에 반영한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class WeeklyRecommendationSyncScheduler {

    private final WeeklyRecommendationSyncService service;

    /** 설정한 주기로 추천 시트의 승인 상태를 동기화한다. */
    @Scheduled(
            cron = "${app.recommendation.google-sheets.sync-cron}",
            zone = "${app.recommendation.google-sheets.zone}"
    )
    public void synchronize() {
        service.synchronize();
    }
}
