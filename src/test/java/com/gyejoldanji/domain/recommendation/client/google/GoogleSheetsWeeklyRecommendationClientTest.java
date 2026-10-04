package com.gyejoldanji.domain.recommendation.client.google;

import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationApprovalRow;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationSheetRow;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleSheetsWeeklyRecommendationClientTest {

    @Mock
    private GoogleSheetsGateway gateway;

    private GoogleSheetsWeeklyRecommendationClient client;

    @BeforeEach
    void setUp() {
        WeeklyRecommendationProperties properties = new WeeklyRecommendationProperties();
        properties.setSpreadsheetId("spreadsheet-id");
        properties.setSheetRange("weekly_recommendations!A2:E");
        client = new GoogleSheetsWeeklyRecommendationClient(gateway, properties);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearsOldRowsAndWritesExactlySevenCheckedRows() {
        List<WeeklyRecommendationSheetRow> rows = IntStream.range(0, 7)
                .mapToObj(index -> new WeeklyRecommendationSheetRow(
                        "9/28~10/4", "필수", "소재" + index, "절정", true))
                .toList();

        client.overwriteWeeklyRecommendations(rows);

        verify(gateway).clearValues("spreadsheet-id", "weekly_recommendations!A2:E");
        ArgumentCaptor<Map<String, List<List<Object>>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gateway).batchUpdateValues(org.mockito.ArgumentMatchers.eq("spreadsheet-id"), captor.capture());
        assertThat(captor.getValue()).containsKey("weekly_recommendations!A1:E1");
        assertThat(captor.getValue().get("weekly_recommendations!A2:E8"))
                .hasSize(7)
                .allSatisfy(row -> assertThat(row.get(4)).isEqualTo(true));
        verify(gateway).applyCheckboxValidation("spreadsheet-id", "weekly_recommendations", 2, 8, 4);
    }

    @Test
    void readsOnlyMaterialAndApprovalState() {
        when(gateway.readValues("spreadsheet-id", "weekly_recommendations!A2:E"))
                .thenReturn(List.of(
                        List.of("9/28~10/4", "필수", "단감", "절정", true),
                        List.of("9/28~10/4", "추천", "고구마", "시작", false)));

        List<WeeklyRecommendationApprovalRow> rows = client.readApprovalRows();

        assertThat(rows).containsExactly(
                new WeeklyRecommendationApprovalRow(2, "단감", true),
                new WeeklyRecommendationApprovalRow(3, "고구마", false));
    }
}
