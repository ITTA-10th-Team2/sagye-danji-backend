package com.gyejoldanji.domain.analytics.scheduler;

import com.gyejoldanji.domain.analytics.service.AnalyticsSheetSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 별도 스프레드시트로 행동 이벤트를 기본 10분마다 내보낸다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"app.google-sheets.enabled", "app.analytics.google-sheets.enabled"}, havingValue = "true")
public class AnalyticsSheetSyncScheduler {

    private final AnalyticsSheetSyncService syncService;

    @Scheduled(cron = "#{@analyticsSheetSyncProperties.schedulingCron()}",
            zone = "#{@analyticsSheetSyncProperties.schedulingZone()}")
    public void synchronize() {
        syncService.synchronize();
    }
}
