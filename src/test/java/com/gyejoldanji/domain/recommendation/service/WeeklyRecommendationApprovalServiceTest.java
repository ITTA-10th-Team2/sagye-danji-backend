package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationApprovalRow;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyRecommendationApprovalServiceTest {

    @Mock
    private SeasonalContentRepository repository;

    @Test
    void changesOnlyApprovalAndKeepsWeeklyAssignment() {
        WeeklyRecommendationApprovalService service = new WeeklyRecommendationApprovalService(repository);
        SeasonalContent content = content();
        content.assignRecommendation(3);
        when(repository.findAllByRecommendationStatusTrue()).thenReturn(List.of(content));

        service.applyApprovals(List.of(new WeeklyRecommendationApprovalRow(2, "단감", false)));

        assertThat(content.isRecommendationStatus()).isTrue();
        assertThat(content.isRecommendationApproved()).isFalse();
        assertThat(content.getRecommendationOrder()).isEqualTo(3);

        service.applyApprovals(List.of(new WeeklyRecommendationApprovalRow(2, "단감", true)));
        assertThat(content.isRecommendationApproved()).isTrue();
        assertThat(content.getRecommendationOrder()).isEqualTo(3);
    }

    @Test
    void ignoresSheetRowsThatAreNotAssignedToCurrentWeek() {
        WeeklyRecommendationApprovalService service = new WeeklyRecommendationApprovalService(repository);
        SeasonalContent content = content();
        when(repository.findAllByRecommendationStatusTrue()).thenReturn(List.of());

        service.applyApprovals(List.of(new WeeklyRecommendationApprovalRow(2, "단감", false)));

        assertThat(content.isRecommendationStatus()).isFalse();
        assertThat(content.isRecommendationApproved()).isFalse();
        verify(repository, never()).findAllByMaterial("단감");
    }

    private SeasonalContent content() {
        return SeasonalContent.create(
                "ffb930c2-ac8a-409e-b0d6-1aaf30216161",
                SeasonType.AUTUMN, ContentCategory.FOOD, "단감", null, null,
                OptimalPeriod.PEAK, "전국", "농촌진흥청", null,
                "단감 맛보기", "제철 단감을 맛봐요.");
    }
}
