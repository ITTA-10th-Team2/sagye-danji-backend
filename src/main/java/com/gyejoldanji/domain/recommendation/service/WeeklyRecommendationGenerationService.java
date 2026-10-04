package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.recommendation.client.WeeklyRecommendationSheetClient;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationSheetRow;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** 이번 주 추천을 생성하고 Google Sheets에 현재 7개만 출력한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class WeeklyRecommendationGenerationService {

    private static final DateTimeFormatter WEEK_DATE_FORMATTER = DateTimeFormatter.ofPattern("M/d");

    private final WeeklyRecommendationSelectionService selectionService;
    private final WeeklyRecommendationSheetClient sheetClient;
    private final WeeklyRecommendationProperties properties;
    private final Clock clock;

    /** 추천 상태를 DB에 확정한 뒤 시트의 기존 행을 새 추천 7개로 교체한다. */
    public void generateCurrentWeek() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.getZone())));
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        String week = WEEK_DATE_FORMATTER.format(weekStart) + "~" + WEEK_DATE_FORMATTER.format(weekEnd);

        List<WeeklyRecommendationSheetRow> rows = selectionService.selectForCurrentWeek().stream()
                .map(content -> toSheetRow(week, content))
                .toList();
        sheetClient.overwriteWeeklyRecommendations(rows);
        log.info("이번 주 추천 7개 생성을 완료했습니다. weekStart={}", weekStart);
    }

    private WeeklyRecommendationSheetRow toSheetRow(String week, SeasonalContent content) {
        return new WeeklyRecommendationSheetRow(
                week,
                typeLabel(content.getOptimalPeriod()),
                content.getMaterial(),
                stageLabel(content.getOptimalPeriod()),
                true);
    }

    private String typeLabel(OptimalPeriod period) {
        return switch (period) {
            case PEAK -> "필수";
            case START -> "추천";
            case END -> "여유";
        };
    }

    private String stageLabel(OptimalPeriod period) {
        return switch (period) {
            case PEAK -> "절정";
            case START -> "시작";
            case END -> "끝";
        };
    }
}
