package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyRecommendationSelectionServiceTest {

    @Mock
    private SeasonalContentRepository repository;

    @Test
    void selectsExactlySevenAndAssignsUniqueDayOrders() {
        WeeklyRecommendationSelectionService service = new WeeklyRecommendationSelectionService(repository);
        List<SeasonalContent> candidates = IntStream.range(0, 8)
                .mapToObj(index -> content("소재" + index))
                .toList();
        SeasonalContent previous = content("지난 추천");
        previous.assignRecommendation(0);
        when(repository.findAllBySeason(SeasonType.AUTUMN)).thenReturn(candidates);
        when(repository.findAllByRecommendationStatusTrue()).thenReturn(List.of(previous));

        List<SeasonalContent> selected = service.selectForCurrentWeek();

        assertThat(selected).hasSize(7);
        assertThat(selected).allMatch(SeasonalContent::isRecommendationStatus);
        assertThat(selected).allMatch(SeasonalContent::isRecommendationApproved);
        assertThat(selected).extracting(SeasonalContent::getRecommendationOrder)
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(selected).doesNotHaveDuplicates();
        assertThat(previous.isRecommendationStatus()).isFalse();
        assertThat(previous.getRecommendationOrder()).isNull();
    }

    @Test
    void keepsPreviousStateWhenCandidatesAreInsufficient() {
        WeeklyRecommendationSelectionService service = new WeeklyRecommendationSelectionService(repository);
        when(repository.findAllBySeason(SeasonType.AUTUMN)).thenReturn(IntStream.range(0, 6)
                .mapToObj(index -> content("소재" + index))
                .toList());

        assertThatThrownBy(service::selectForCurrentWeek)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_CANDIDATES_INSUFFICIENT);
        verify(repository, never()).findAllByRecommendationStatusTrue();
    }

    private SeasonalContent content(String material) {
        return SeasonalContent.create(
                UUID.randomUUID().toString(), SeasonType.AUTUMN, ContentCategory.FOOD,
                material, null, null, OptimalPeriod.PEAK, "전국", "출처", null,
                material + " 제목", material + " 설명");
    }
}
