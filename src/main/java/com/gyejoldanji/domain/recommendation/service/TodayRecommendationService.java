package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationResponse;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** 한국 기준 오늘의 요일 슬롯에 배정된 추천 한 건을 조회한다. */
@Service
@RequiredArgsConstructor
public class TodayRecommendationService {

    private final SeasonalContentRepository repository;
    private final WeeklyRecommendationProperties properties;
    private final Clock clock;

    /** 월요일 0부터 일요일 6까지의 슬롯으로 오늘 추천 한 건을 반환한다. */
    @Transactional(readOnly = true)
    public TodayRecommendationResponse getTodayRecommendation() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.getZone())));
        int order = today.getDayOfWeek().getValue() - 1;
        SeasonalContent content = repository
                .findByRecommendationStatusTrueAndRecommendationApprovedTrueAndRecommendationOrder(order)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        return new TodayRecommendationResponse(
                content.getId(),
                content.getContentCode(),
                content.getCategory(),
                content.getMaterial(),
                content.getTitle(),
                content.getDescription(),
                content.getOptimalPeriod(),
                content.getRegion());
    }
}
