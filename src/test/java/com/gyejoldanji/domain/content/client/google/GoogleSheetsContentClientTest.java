package com.gyejoldanji.domain.content.client.google;

import com.gyejoldanji.domain.content.client.model.SeasonalContentSheetRow;
import com.gyejoldanji.domain.content.client.model.SheetSyncStatus;
import com.gyejoldanji.domain.content.config.SeasonalContentSyncProperties;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleSheetsContentClientTest {

    @Mock
    private GoogleSheetsGateway gateway;

    private GoogleSheetsContentClient client;

    @BeforeEach
    void setUp() {
        SeasonalContentSyncProperties properties = new SeasonalContentSyncProperties();
        properties.setSpreadsheetId("spreadsheet-id");
        properties.setSheetRange("contents!A2:R");
        client = new GoogleSheetsContentClient(gateway, properties);
    }

    @Test
    void mapsAThroughRColumnsToContentRow() {
        when(gateway.readValues("spreadsheet-id", "contents!A2:R")).thenReturn(List.of(List.of(
                "ffb930c2-ac8a-409e-b0d6-1aaf30216161",
                " AUTUMN ",
                "SCENERY",
                "단풍",
                "20261001",
                "20261130",
                "PEAK",
                "전국",
                "산림청",
                "20260918",
                "단풍 보기",
                "가까운 공원에서 단풍을 감상해요.",
                true,
                "PROCESSING",
                "2026-10-02T05:40:00Z",
                "2026-10-01T04:00:00Z",
                42,
                "이전 오류"
        )));

        SeasonalContentSheetRow row = client.readRows().getFirst();

        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.season()).isEqualTo("AUTUMN");
        assertThat(row.material()).isEqualTo("단풍");
        assertThat(row.availableStartDate()).isEqualTo("20261001");
        assertThat(row.optimalPeriod()).isEqualTo("PEAK");
        assertThat(row.active()).isEqualTo("true");
        assertThat(row.syncStatus()).isEqualTo(SheetSyncStatus.PROCESSING);
        assertThat(row.processingStartedAt()).isEqualTo(Instant.parse("2026-10-02T05:40:00Z"));
        assertThat(row.syncedAt()).isEqualTo(Instant.parse("2026-10-01T04:00:00Z"));
        assertThat(row.dbId()).isEqualTo(42L);
        assertThat(row.errorMessage()).isEqualTo("이전 오류");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatesProcessingStateAndClearsPreviousErrorInOneBatch() {
        Instant startedAt = Instant.parse("2026-10-02T06:00:00Z");

        client.markProcessing(7, startedAt);

        ArgumentCaptor<Map<String, List<List<Object>>>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gateway).batchUpdateValues(org.mockito.ArgumentMatchers.eq("spreadsheet-id"), updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).containsEntry(
                "contents!N7:O7", List.of(List.of("PROCESSING", startedAt.toString())));
        assertThat(updatesCaptor.getValue()).containsEntry("contents!R7", List.of(List.of("")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksUnsupportedSyncStatusAsFailed() {
        when(gateway.readValues("spreadsheet-id", "contents!A2:R")).thenReturn(List.of(List.of(
                "ffb930c2-ac8a-409e-b0d6-1aaf30216161",
                "AUTUMN",
                "SCENERY",
                "단풍",
                "20261001",
                "20261130",
                "PEAK",
                "전국",
                "산림청",
                "20260918",
                "단풍 보기",
                "가까운 공원에서 단풍을 감상해요.",
                true,
                "UNKNOWN"
        )));

        SeasonalContentSheetRow row = client.readRows().getFirst();

        ArgumentCaptor<Map<String, List<List<Object>>>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gateway).batchUpdateValues(org.mockito.ArgumentMatchers.eq("spreadsheet-id"), updatesCaptor.capture());
        assertThat(row.syncStatus()).isEqualTo(SheetSyncStatus.FAILED);
        assertThat(updatesCaptor.getValue()).containsEntry(
                "contents!N2:O2", List.of(List.of("FAILED", "")));
        assertThat(updatesCaptor.getValue()).containsEntry(
                "contents!R2", List.of(List.of("동기화 상태 값이 지원되지 않습니다.")));
    }
}
