package com.gyejoldanji.domain.content.client.model;

import java.time.Instant;

/** Google Sheets의 제철 콘텐츠 한 행을 표현하는 불변 모델. */
public record SeasonalContentSheetRow(
        int rowNumber,
        String contentCode,
        String season,
        String category,
        String material,
        String availableStartDate,
        String availableEndDate,
        String optimalPeriod,
        String region,
        String timingSource,
        String sourceCheckedDate,
        String title,
        String description,
        String active,
        SheetSyncStatus syncStatus,
        Instant processingStartedAt,
        Instant syncedAt,
        Long dbId,
        String errorMessage
) {

    /** 처리 상태만 변경한 새 행 모델을 반환한다. */
    public SeasonalContentSheetRow withSyncStatus(SheetSyncStatus status, Instant startedAt) {
        return new SeasonalContentSheetRow(
                rowNumber, contentCode, season, category, material,
                availableStartDate, availableEndDate, optimalPeriod, region, timingSource,
                sourceCheckedDate, title, description, active,
                status, startedAt, syncedAt, dbId, errorMessage);
    }

    /** 콘텐츠 코드를 변경한 새 행 모델을 반환한다. */
    public SeasonalContentSheetRow withContentCode(String newContentCode) {
        return new SeasonalContentSheetRow(
                rowNumber, newContentCode, season, category, material,
                availableStartDate, availableEndDate, optimalPeriod, region, timingSource,
                sourceCheckedDate, title, description, active,
                syncStatus, processingStartedAt, syncedAt, dbId, errorMessage);
    }
}
