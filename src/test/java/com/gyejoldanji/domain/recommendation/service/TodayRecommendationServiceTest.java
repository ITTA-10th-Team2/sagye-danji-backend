package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationResponse;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodayRecommendationServiceTest {

    @Mock
    private SeasonalContentRepository repository;

    private TodayRecommendationService service;

    @BeforeEach
    void setUp() {
        WeeklyRecommendationProperties properties = new WeeklyRecommendationProperties();
        properties.setZone("Asia/Seoul");
        service = new TodayRecommendationService(
                repository,
                properties,
                Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void returnsOnlyTheContentAssignedToToday() {
        SeasonalContent content = content();
        content.assignRecommendation(6);
        when(repository.findByRecommendationStatusTrueAndRecommendationApprovedTrueAndRecommendationOrder(6))
                .thenReturn(Optional.of(content));

        TodayRecommendationResponse response = service.getTodayRecommendation();

        assertThat(response.material()).isEqualTo("단감");
        verify(repository).findByRecommendationStatusTrueAndRecommendationApprovedTrueAndRecommendationOrder(6);
    }

    @Test
    void throwsNotFoundWhenTodaySlotIsNotApproved() {
        when(repository.findByRecommendationStatusTrueAndRecommendationApprovedTrueAndRecommendationOrder(6))
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::getTodayRecommendation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
    }

    private SeasonalContent content() {
        return SeasonalContent.create(
                "ffb930c2-ac8a-409e-b0d6-1aaf30216161",
                SeasonType.AUTUMN, ContentCategory.FOOD, "단감", null, null,
                OptimalPeriod.PEAK, "전국", "농촌진흥청", null,
                "단감 맛보기", "제철 단감을 맛봐요.");
    }
}
