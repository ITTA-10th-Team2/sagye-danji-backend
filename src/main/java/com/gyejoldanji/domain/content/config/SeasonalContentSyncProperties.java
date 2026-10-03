package com.gyejoldanji.domain.content.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** 제철 콘텐츠 Google Sheets 동기화 설정을 관리한다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.content-sync.google-sheets")
public class SeasonalContentSyncProperties {

    /** 콘텐츠를 읽을 스프레드시트 ID. */
    private String spreadsheetId;

    /** 헤더를 제외한 콘텐츠 데이터 범위. */
    @NotBlank
    private String sheetRange;

    /** 동기화 스케줄 Cron 표현식. */
    @NotBlank
    private String syncCron;

    /** 스케줄러 기준 시간대. */
    @NotBlank
    private String syncZone;

    /** 처리 중 상태를 복구할 만료 시간. */
    @Min(1)
    private long processingTimeoutMinutes;

    /** 활성화된 콘텐츠 연동에 필요한 시트 설정을 검증한다. */
    public void validateEnabledConfiguration() {
        if (!StringUtils.hasText(spreadsheetId)) {
            throw new IllegalStateException("Google Sheets 연동이 활성화되면 spreadsheet-id가 필요합니다.");
        }
        if (!sheetRange.matches(".+![A-Za-z]+\\d+:[A-Za-z]+")) {
            throw new IllegalStateException("sheet-range는 헤더를 제외한 A1 범위 형식이어야 합니다.");
        }
    }
}
