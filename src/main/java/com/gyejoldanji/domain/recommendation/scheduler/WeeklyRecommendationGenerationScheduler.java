package com.gyejoldanji.domain.recommendation.scheduler;

import com.gyejoldanji.domain.recommendation.service.WeeklyRecommendationGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매주 추천 후보 자동 생성을 실행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class WeeklyRecommendationGenerationScheduler {

    private final WeeklyRecommendationGenerationService service;

    /** 설정한 주간 시각에 현재 주차 후보를 생성한다. */
    @Scheduled(
            cron = "${app.recommendation.google-sheets.generation-cron}",
            zone = "${app.recommendation.google-sheets.zone}"
    )
    public void generate() {
        service.generateCurrentWeek();
    }
}
