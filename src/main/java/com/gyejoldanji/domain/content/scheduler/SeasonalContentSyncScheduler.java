package com.gyejoldanji.domain.content.scheduler;

import com.gyejoldanji.domain.content.service.SeasonalContentSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 설정된 주기에 맞춰 제철 콘텐츠 동기화를 실행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class SeasonalContentSyncScheduler {

    /** 제철 콘텐츠 동기화 서비스. */
    private final SeasonalContentSyncService syncService;

    /** 기본 5분 주기로 콘텐츠 동기화를 요청한다. */
    @Scheduled(
            cron = "${app.content-sync.google-sheets.sync-cron:0 */5 * * * *}",
            zone = "${app.content-sync.google-sheets.sync-zone:Asia/Seoul}"
    )
    public void synchronize() {
        syncService.synchronize();
    }
}
