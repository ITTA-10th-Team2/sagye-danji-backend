package com.gyejoldanji.domain.recommendation.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 주간 추천 생성과 Google Sheets 승인 동기화 설정을 관리한다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.recommendation.google-sheets")
public class WeeklyRecommendationProperties {

    /** 주간 추천 탭이 포함된 스프레드시트 ID. */
    private String spreadsheetId;

    /** 헤더를 제외한 주간 추천 시트 범위. */
    @NotBlank
    private String sheetRange;

    /** 주간 추천 생성 Cron 표현식. */
    @NotBlank
    private String generationCron;

    /** 추천 승인 동기화 Cron 표현식. */
    @NotBlank
    private String syncCron;

    /** 날짜 계산과 스케줄 실행 기준 시간대. */
    @NotBlank
    private String zone;
}
