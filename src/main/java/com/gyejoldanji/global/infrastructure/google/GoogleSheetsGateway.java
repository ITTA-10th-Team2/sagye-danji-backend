package com.gyejoldanji.global.infrastructure.google;

import com.google.api.client.http.HttpResponseException;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BooleanCondition;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.DataValidationRule;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.SetDataValidationRequest;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Google Sheets의 범용 범위 읽기와 값 쓰기를 제공한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class GoogleSheetsGateway {

    /** API 요청의 최대 시도 횟수. */
    private static final int MAX_ATTEMPTS = 3;

    /** 첫 재시도 전 대기 시간. */
    private static final long INITIAL_BACKOFF_MILLIS = 200L;

    /** 인증된 Google Sheets SDK 객체. */
    private final Sheets sheets;

    /** 지정한 범위의 셀 값을 읽는다. */
    public List<List<Object>> readValues(String spreadsheetId, String range) {
        ValueRange valueRange = executeWithRetry(
                () -> sheets.spreadsheets().values().get(spreadsheetId, range).execute());
        return valueRange.getValues() == null ? Collections.emptyList() : valueRange.getValues();
    }

    /** 여러 범위의 값을 한 번의 요청으로 갱신한다. */
    public void batchUpdateValues(String spreadsheetId, Map<String, List<List<Object>>> valuesByRange) {
        List<ValueRange> data = new ArrayList<>();
        valuesByRange.forEach((range, values) -> data.add(new ValueRange().setRange(range).setValues(values)));
        BatchUpdateValuesRequest request = new BatchUpdateValuesRequest()
                .setValueInputOption("RAW")
                .setData(data);
        executeWithRetry(() -> sheets.spreadsheets().values().batchUpdate(spreadsheetId, request).execute());
    }

    /** 지정한 범위의 기존 값을 삭제한다. */
    public void clearValues(String spreadsheetId, String range) {
        executeWithRetry(() -> sheets.spreadsheets().values()
                .clear(spreadsheetId, range, new ClearValuesRequest())
                .execute());
    }

    /** 지정한 행 범위에 Google Sheets 체크박스 데이터 검증을 적용한다. */
    public void applyCheckboxValidation(
            String spreadsheetId,
            String sheetName,
            int startRowNumber,
            int endRowNumber,
            int columnIndex
    ) {
        int sheetId = findSheetId(spreadsheetId, sheetName);
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowNumber - 1)
                .setEndRowIndex(endRowNumber)
                .setStartColumnIndex(columnIndex)
                .setEndColumnIndex(columnIndex + 1);
        DataValidationRule rule = new DataValidationRule()
                .setCondition(new BooleanCondition().setType("BOOLEAN"))
                .setStrict(true)
                .setShowCustomUi(true);
        Request request = new Request().setSetDataValidation(
                new SetDataValidationRequest().setRange(range).setRule(rule));
        executeWithRetry(() -> sheets.spreadsheets().batchUpdate(
                spreadsheetId,
                new BatchUpdateSpreadsheetRequest().setRequests(List.of(request))).execute());
    }

    /** 시트 제목으로 내부 sheetId를 조회한다. */
    private int findSheetId(String spreadsheetId, String sheetName) {
        List<Sheet> sheetList = executeWithRetry(() -> sheets.spreadsheets()
                .get(spreadsheetId)
                .setFields("sheets.properties(sheetId,title)")
                .execute())
                .getSheets();
        if (sheetList == null) {
            throw new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR);
        }
        return sheetList.stream()
                .filter(sheet -> sheetName.equals(sheet.getProperties().getTitle()))
                .map(sheet -> sheet.getProperties().getSheetId())
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR));
    }

    /** 일시적 통신 오류에 제한된 지수 백오프를 적용한다. */
    private <T> T executeWithRetry(IoOperation<T> operation) {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.execute();
            } catch (IOException exception) {
                lastException = exception;
                if (!isRetryable(exception) || attempt == MAX_ATTEMPTS) {
                    break;
                }
                waitBeforeRetry(attempt);
            }
        }
        throw new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR, lastException);
    }

    /** 재시도 가능한 통신 오류인지 판별한다. */
    private boolean isRetryable(IOException exception) {
        if (exception instanceof HttpResponseException responseException) {
            int statusCode = responseException.getStatusCode();
            return statusCode == 429 || statusCode >= 500;
        }
        return true;
    }

    /** 재시도 횟수에 따라 대기 시간을 증가시킨다. */
    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(INITIAL_BACKOFF_MILLIS << (attempt - 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR, exception);
        }
    }

    /** IOException을 발생시킬 수 있는 SDK 작업을 표현한다. */
    @FunctionalInterface
    private interface IoOperation<T> {
        T execute() throws IOException;
    }
}
