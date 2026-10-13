package com.gyejoldanji.global.infrastructure.google;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.GridProperties;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.gyejoldanji.global.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleSheetsGatewayTest {

    private static final String SPREADSHEET = "test-spreadsheet";
    private static final String TAB = "analytics_events";
    private static final String FIELDS = "sheets.properties(sheetId,title,gridProperties.rowCount)";
    private static final String COLUMN_FIELDS = "sheets.properties(sheetId,title,gridProperties.columnCount)";

    @Mock
    private Sheets sheets;
    @Mock
    private Sheets.Spreadsheets spreadsheets;

    private GoogleSheetsGateway gateway;

    @BeforeEach
    void setUp() {
        when(sheets.spreadsheets()).thenReturn(spreadsheets);
        gateway = new GoogleSheetsGateway(sheets);
    }

    @Test
    void exactRangeAndRawValuesAreReusedAfterAmbiguousWriteFailure() throws Exception {
        Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
        Sheets.Spreadsheets.Values.BatchUpdate update = mock(Sheets.Spreadsheets.Values.BatchUpdate.class);
        when(spreadsheets.values()).thenReturn(values);
        when(values.batchUpdate(eq(SPREADSHEET), any())).thenReturn(update);
        when(update.execute()).thenThrow(new IOException("response lost after write")).thenReturn(null);
        String range = "'analytics_events'!A2:W2";
        List<List<Object>> rows = List.of(List.of("event-id", "=SUM(1,2)", 7));

        gateway.batchUpdateValues(SPREADSHEET, Map.of(range, rows));

        ArgumentCaptor<BatchUpdateValuesRequest> requests = ArgumentCaptor.forClass(BatchUpdateValuesRequest.class);
        verify(values, times(2)).batchUpdate(eq(SPREADSHEET), requests.capture());
        assertThat(requests.getAllValues().get(1)).isSameAs(requests.getAllValues().getFirst());
        BatchUpdateValuesRequest request = requests.getValue();
        assertThat(request.getValueInputOption()).isEqualTo("RAW");
        assertThat(request.getData()).singleElement().satisfies(data -> {
            assertThat(data.getRange()).isEqualTo(range);
            assertThat(data.getValues()).isEqualTo(rows);
        });
        verify(values, never()).append(any(), any(), any());
    }

    @Test
    void onlyMissingRowsAreAddedToMatchingTab() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(sheet("other", 9, 1000), sheet(TAB, 42, 100))));
        Sheets.Spreadsheets.BatchUpdate update = mock(Sheets.Spreadsheets.BatchUpdate.class);
        when(spreadsheets.batchUpdate(eq(SPREADSHEET), any())).thenReturn(update);

        gateway.ensureRowCapacity(SPREADSHEET, TAB, 105);

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> requests = ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET), requests.capture());
        assertThat(requests.getValue().getRequests()).singleElement().satisfies(request -> {
            assertThat(request.getAppendDimension().getSheetId()).isEqualTo(42);
            assertThat(request.getAppendDimension().getDimension()).isEqualTo("ROWS");
            assertThat(request.getAppendDimension().getLength()).isEqualTo(5);
        });
    }

    @Test
    void sufficientGridDoesNotWrite() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(sheet(TAB, 42, 100))));

        gateway.ensureRowCapacity(SPREADSHEET, TAB, 100);

        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void missingTabFailsWithoutWriting() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(sheet("other", 9, 100))));

        assertThatThrownBy(() -> gateway.ensureRowCapacity(SPREADSHEET, TAB, 101))
                .isInstanceOf(BusinessException.class);
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void missingGridMetadataFailsWithoutWriting() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(new Sheet().setProperties(
                new SheetProperties().setSheetId(42).setTitle(TAB)))));

        assertThatThrownBy(() -> gateway.ensureRowCapacity(SPREADSHEET, TAB, 1))
                .isInstanceOf(BusinessException.class);
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void metadataRetriesRateLimit() throws Exception {
        Spreadsheet result = new Spreadsheet().setSheets(List.of(sheet(TAB, 42, 100)));
        Sheets.Spreadsheets.Get get = metadata(result);
        when(get.execute()).thenThrow(httpError(429)).thenReturn(result);

        gateway.ensureRowCapacity(SPREADSHEET, TAB, 100);

        verify(get, times(2)).execute();
    }

    @Test
    void rowExpansionRetriesServerFailure() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(sheet(TAB, 42, 100))));
        Sheets.Spreadsheets.BatchUpdate update = mock(Sheets.Spreadsheets.BatchUpdate.class);
        when(spreadsheets.batchUpdate(eq(SPREADSHEET), any())).thenReturn(update);
        when(update.execute()).thenThrow(httpError(503)).thenReturn(null);

        gateway.ensureRowCapacity(SPREADSHEET, TAB, 101);

        verify(update, times(2)).execute();
    }

    @Test
    void permanentMetadataErrorIsNotRetried() throws Exception {
        Sheets.Spreadsheets.Get get = metadata(new Spreadsheet());
        when(get.execute()).thenThrow(httpError(403));

        assertThatThrownBy(() -> gateway.ensureRowCapacity(SPREADSHEET, TAB, 1))
                .isInstanceOf(BusinessException.class);
        verify(get).execute();
    }

    @Test
    void repeatedMetadataFailureStopsAfterThreeAttempts() throws Exception {
        Sheets.Spreadsheets.Get get = metadata(new Spreadsheet());
        when(get.execute()).thenThrow(httpError(503));

        assertThatThrownBy(() -> gateway.ensureRowCapacity(SPREADSHEET, TAB, 1))
                .isInstanceOf(BusinessException.class);
        verify(get, times(3)).execute();
    }

    @ParameterizedTest
    @CsvSource({"1, 25", "23, 25", "24, 25", "25, 27", "26, 27"})
    void onlyMissingColumnsAreAddedToMatchingTab(int columns, int requiredColumns) throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(
                new Sheet().setProperties(new SheetProperties().setSheetId(9).setTitle("other")
                        .setGridProperties(new GridProperties().setColumnCount(26))),
                columnSheet(columns))), COLUMN_FIELDS);
        Sheets.Spreadsheets.BatchUpdate update = mock(Sheets.Spreadsheets.BatchUpdate.class);
        when(spreadsheets.batchUpdate(eq(SPREADSHEET), any())).thenReturn(update);

        gateway.ensureColumnCapacity(SPREADSHEET, TAB, requiredColumns);

        ArgumentCaptor<BatchUpdateSpreadsheetRequest> requests = ArgumentCaptor.forClass(BatchUpdateSpreadsheetRequest.class);
        verify(spreadsheets).batchUpdate(eq(SPREADSHEET), requests.capture());
        assertThat(requests.getValue().getRequests()).singleElement().satisfies(request -> {
            assertThat(request.getAppendDimension().getSheetId()).isEqualTo(42);
            assertThat(request.getAppendDimension().getDimension()).isEqualTo("COLUMNS");
            assertThat(request.getAppendDimension().getLength()).isEqualTo(requiredColumns - columns);
        });
    }

    @ParameterizedTest
    @CsvSource({"25, 25", "26, 25", "27, 27", "28, 27"})
    void sufficientColumnsDoNotWrite(int columns, int requiredColumns) throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(columnSheet(columns))), COLUMN_FIELDS);

        gateway.ensureColumnCapacity(SPREADSHEET, TAB, requiredColumns);

        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void missingColumnCountFailsWithoutWriting() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(sheet(TAB, 42, 100))), COLUMN_FIELDS);

        assertThatThrownBy(() -> gateway.ensureColumnCapacity(SPREADSHEET, TAB, 25))
                .isInstanceOf(BusinessException.class);
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void missingColumnGridMetadataFailsWithoutWriting() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(new Sheet().setProperties(
                new SheetProperties().setSheetId(42).setTitle(TAB)))), COLUMN_FIELDS);

        assertThatThrownBy(() -> gateway.ensureColumnCapacity(SPREADSHEET, TAB, 25))
                .isInstanceOf(BusinessException.class);
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void missingSheetsMetadataFailsWithoutWriting() throws Exception {
        metadata(new Spreadsheet(), COLUMN_FIELDS);

        assertThatThrownBy(() -> gateway.ensureColumnCapacity(SPREADSHEET, TAB, 25))
                .isInstanceOf(BusinessException.class);
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void columnMetadataRetriesRateLimit() throws Exception {
        Spreadsheet result = new Spreadsheet().setSheets(List.of(columnSheet(25)));
        Sheets.Spreadsheets.Get get = metadata(result, COLUMN_FIELDS);
        when(get.execute()).thenThrow(httpError(429)).thenReturn(result);

        gateway.ensureColumnCapacity(SPREADSHEET, TAB, 25);

        verify(get, times(2)).execute();
        verify(spreadsheets, never()).batchUpdate(any(), any());
    }

    @Test
    void columnExpansionRetriesServerFailure() throws Exception {
        metadata(new Spreadsheet().setSheets(List.of(columnSheet(24))), COLUMN_FIELDS);
        Sheets.Spreadsheets.BatchUpdate update = mock(Sheets.Spreadsheets.BatchUpdate.class);
        when(spreadsheets.batchUpdate(eq(SPREADSHEET), any())).thenReturn(update);
        when(update.execute()).thenThrow(httpError(503)).thenReturn(null);

        gateway.ensureColumnCapacity(SPREADSHEET, TAB, 25);

        verify(update, times(2)).execute();
    }

    private Sheets.Spreadsheets.Get metadata(Spreadsheet result) throws IOException {
        return metadata(result, FIELDS);
    }

    private Sheets.Spreadsheets.Get metadata(Spreadsheet result, String fields) throws IOException {
        Sheets.Spreadsheets.Get get = mock(Sheets.Spreadsheets.Get.class);
        when(spreadsheets.get(SPREADSHEET)).thenReturn(get);
        when(get.setFields(fields)).thenReturn(get);
        when(get.execute()).thenReturn(result);
        return get;
    }

    private static Sheet sheet(String title, int id, int rows) {
        return new Sheet().setProperties(new SheetProperties().setSheetId(id).setTitle(title)
                .setGridProperties(new GridProperties().setRowCount(rows)));
    }

    private static Sheet columnSheet(int columns) {
        return new Sheet().setProperties(new SheetProperties().setSheetId(42).setTitle(TAB)
                .setGridProperties(new GridProperties().setColumnCount(columns)));
    }

    private static HttpResponseException httpError(int status) {
        return new HttpResponseException.Builder(status, "test failure", new HttpHeaders()).build();
    }
}
