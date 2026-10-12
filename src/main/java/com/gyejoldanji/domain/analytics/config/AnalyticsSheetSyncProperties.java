package com.gyejoldanji.domain.analytics.config;

import java.time.ZoneId;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 분석 시트 설정은 앱 시작을 막지 않도록 매 동기화 실행 때 검증한다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.analytics.google-sheets")
public class AnalyticsSheetSyncProperties {

    private static final String DEFAULT_CRON = "0 */10 * * * *";
    private static final String DEFAULT_ZONE = "Asia/Seoul";

    private String spreadsheetId = "";
    private String sheetName = "analytics_events";
    private String syncCron = DEFAULT_CRON;
    private String syncZone = DEFAULT_ZONE;
    // 숫자 오타도 바인딩 단계에서 앱 시작을 막지 않도록 문자열로 받는다.
    private String batchSize = "500";
    private String maxBatchesPerRun = "10";
    private String userKeySecret = "";

    public void validateForSync() {
        if (!StringUtils.hasText(spreadsheetId) || !StringUtils.hasText(sheetName)
                || !StringUtils.hasText(userKeySecret) || userKeySecret.length() < 32
                || !CronExpression.isValidExpression(syncCron)) {
            throw new IllegalStateException("Invalid analytics sheet configuration");
        }
        ZoneId.of(syncZone);
        int size = batchSizeValue();
        if (size < 1 || size > 1000 || maxBatchesValue() < 1) {
            throw new IllegalStateException("Invalid analytics sheet configuration");
        }
    }

    public int batchSizeValue() {
        return Integer.parseInt(batchSize);
    }

    public int maxBatchesValue() {
        return Integer.parseInt(maxBatchesPerRun);
    }

    /** 잘못된 예약 설정도 기본 주기에 깨어나 실행 검증에서 WARN 후 건너뛴다. */
    public String schedulingCron() {
        return CronExpression.isValidExpression(syncCron) ? syncCron : DEFAULT_CRON;
    }

    public String schedulingZone() {
        try {
            return ZoneId.of(syncZone).getId();
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
