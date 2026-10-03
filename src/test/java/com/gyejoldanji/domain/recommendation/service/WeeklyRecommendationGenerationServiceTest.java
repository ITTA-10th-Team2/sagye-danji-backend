package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.recommendation.client.WeeklyRecommendationSheetClient;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationSheetRow;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyRecommendationGenerationServiceTest {

    @Mock
    private WeeklyRecommendationSelectionService selectionService;

    @Mock
    private WeeklyRecommendationSheetClient sheetClient;

    @Test
    @SuppressWarnings("unchecked")
    void overwritesSheetWithSevenApprovedRows() {
        WeeklyRecommendationProperties properties = new WeeklyRecommendationProperties();
        properties.setZone("Asia/Seoul");
        WeeklyRecommendationGenerationService service = new WeeklyRecommendationGenerationService(
                selectionService,
                sheetClient,
                properties,
                Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC));
        List<SeasonalContent> selected = IntStream.range(0, 7)
                .mapToObj(this::content)
                .toList();
        IntStream.range(0, 7).forEach(index -> selected.get(index).assignRecommendation(index));
        when(selectionService.selectForCurrentWeek()).thenReturn(selected);

        service.generateCurrentWeek();

        ArgumentCaptor<List<WeeklyRecommendationSheetRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(sheetClient).overwriteWeeklyRecommendations(captor.capture());
        assertThat(captor.getValue()).hasSize(7).allMatch(WeeklyRecommendationSheetRow::approved);
        assertThat(captor.getValue()).extracting(WeeklyRecommendationSheetRow::week)
                .containsOnly("9/28~10/4");
        assertThat(captor.getValue().getFirst().type()).isEqualTo("필수");
        assertThat(captor.getValue().getFirst().stage()).isEqualTo("절정");
    }

    private SeasonalContent content(int index) {
        return SeasonalContent.create(
                UUID.randomUUID().toString(), SeasonType.AUTUMN, ContentCategory.FOOD,
                "소재" + index, null, null, OptimalPeriod.PEAK, "전국", "출처", null,
                "제목" + index, "설명" + index);
    }
}
