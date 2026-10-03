package com.gyejoldanji.domain.content.client.google;

import com.gyejoldanji.domain.content.client.SeasonalContentSheetClient;
import com.gyejoldanji.domain.content.client.model.SeasonalContentSheetRow;
import com.gyejoldanji.domain.content.client.model.SheetSyncStatus;
import com.gyejoldanji.domain.content.config.SeasonalContentSyncProperties;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Google Sheets 행과 콘텐츠 동기화 모델 사이의 변환 및 상태 갱신을 담당한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class GoogleSheetsContentClient implements SeasonalContentSheetClient {

    /** A1 범위에서 시작 행 번호를 찾는 패턴. */
    private static final Pattern START_ROW_PATTERN = Pattern.compile("![A-Za-z]+(\\d+)");

    /** 범용 Google Sheets 입출력 게이트웨이. */
    private final GoogleSheetsGateway gateway;

    /** 콘텐츠 시트 전용 설정. */
    private final SeasonalContentSyncProperties properties;

    /** Bean 생성 후 필수 콘텐츠 시트 설정을 검증한다. */
    @PostConstruct
    void validateConfiguration() {
        properties.validateEnabledConfiguration();
    }

    /** 콘텐츠 시트의 전체 데이터 행을 읽는다. */
    @Override
    public List<SeasonalContentSheetRow> readRows() {
        List<List<Object>> values = gateway.readValues(
                properties.getSpreadsheetId(), properties.getSheetRange());
        int firstRowNumber = extractFirstRowNumber();
        List<SeasonalContentSheetRow> rows = new ArrayList<>();

        for (int index = 0; index < values.size(); index++) {
            List<Object> cells = values.get(index);
            if (isBlankRow(cells)) {
                continue;
            }
            int rowNumber = firstRowNumber + index;
            rows.add(toRow(rowNumber, cells));
        }
        return rows;
    }

    /** 백엔드가 발급한 콘텐츠 코드를 기록한다. */
    @Override
    public void writeContentCode(int rowNumber, String contentCode) {
        update(Map.of(cellRange("A", rowNumber), singleRow(contentCode)));
    }

    /** 행을 처리 중 상태로 선점한다. */
    @Override
    public void markProcessing(int rowNumber, Instant processingStartedAt) {
        Map<String, List<List<Object>>> updates = new LinkedHashMap<>();
        updates.put(cellRange("N:O", rowNumber), singleRow(
                SheetSyncStatus.PROCESSING.name(), processingStartedAt.toString()));
        updates.put(cellRange("R", rowNumber), singleRow(""));
        update(updates);
    }

    /** 중단된 행을 다시 처리 가능한 상태로 복구한다. */
    @Override
    public void markReady(int rowNumber) {
        update(Map.of(cellRange("N:O", rowNumber), singleRow(SheetSyncStatus.READY.name(), "")));
    }

    /** DB 반영이 완료된 행에 결과를 기록한다. */
    @Override
    public void markSynced(int rowNumber, Long dbId, Instant syncedAt) {
        update(Map.of(cellRange("N:R", rowNumber), singleRow(
                SheetSyncStatus.SYNCED.name(), "", syncedAt.toString(),
                Objects.requireNonNull(dbId, "dbId"), "")));
    }

    /** 처리에 실패한 행에 간결한 오류를 기록한다. */
    @Override
    public void markFailed(int rowNumber, String errorMessage) {
        Map<String, List<List<Object>>> updates = new LinkedHashMap<>();
        updates.put(cellRange("N:O", rowNumber), singleRow(SheetSyncStatus.FAILED.name(), ""));
        updates.put(cellRange("R", rowNumber), singleRow(errorMessage));
        update(updates);
    }

    /** 원시 셀 값을 콘텐츠 행 모델로 변환한다. */
    private SeasonalContentSheetRow toRow(int rowNumber, List<Object> cells) {
        return new SeasonalContentSheetRow(
                rowNumber,
                text(cells, 0),
                text(cells, 1),
                text(cells, 2),
                text(cells, 3),
                text(cells, 4),
                text(cells, 5),
                text(cells, 6),
                text(cells, 7),
                text(cells, 8),
                text(cells, 9),
                text(cells, 10),
                text(cells, 11),
                text(cells, 12),
                status(cells, rowNumber),
                instant(cells, 14, rowNumber, "processing_started_at"),
                instant(cells, 15, rowNumber, "synced_at"),
                longValue(cells, 16, rowNumber),
                text(cells, 17));
    }

    /** 상태 셀을 Enum으로 변환하고 잘못된 값은 처리 대상에서 제외한다. */
    private SheetSyncStatus status(List<Object> cells, int rowNumber) {
        String value = text(cells, 13);
        if (!StringUtils.hasText(value)) {
            return SheetSyncStatus.DRAFT;
        }
        try {
            return SheetSyncStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            log.warn("지원하지 않는 시트 동기화 상태를 실패 처리합니다. row={}, errorType={}",
                    rowNumber, exception.getClass().getSimpleName());
            failInvalidStatus(rowNumber);
            return SheetSyncStatus.FAILED;
        }
    }

    /** 지원하지 않는 상태값을 FAILED 상태로 기록한다. */
    private void failInvalidStatus(int rowNumber) {
        try {
            markFailed(rowNumber, "동기화 상태 값이 지원되지 않습니다.");
        } catch (RuntimeException exception) {
            log.warn("잘못된 시트 상태의 실패 기록에 실패했습니다. row={}, errorType={}",
                    rowNumber, exception.getClass().getSimpleName());
        }
    }

    /** ISO-8601 시각 셀을 변환한다. */
    private Instant instant(List<Object> cells, int index, int rowNumber, String fieldName) {
        String value = text(cells, index);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            log.warn("시트 시각 형식이 올바르지 않습니다. row={}, field={}, errorType={}",
                    rowNumber, fieldName, exception.getClass().getSimpleName());
            return null;
        }
    }

    /** DB 식별자 셀을 Long으로 변환한다. */
    private Long longValue(List<Object> cells, int index, int rowNumber) {
        String value = text(cells, index);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            log.warn("시트 DB 식별자 형식이 올바르지 않습니다. row={}, errorType={}",
                    rowNumber, exception.getClass().getSimpleName());
            return null;
        }
    }

    /** 셀 값을 앞뒤 공백이 제거된 문자열로 변환한다. */
    private String text(List<Object> cells, int index) {
        if (index >= cells.size() || cells.get(index) == null) {
            return "";
        }
        return String.valueOf(cells.get(index)).trim();
    }

    /** 모든 셀이 비어 있는 행인지 확인한다. */
    private boolean isBlankRow(List<Object> cells) {
        return cells == null || cells.stream().allMatch(value -> !StringUtils.hasText(
                value == null ? "" : String.valueOf(value)));
    }

    /** 설정된 A1 범위에서 첫 데이터 행 번호를 구한다. */
    private int extractFirstRowNumber() {
        Matcher matcher = START_ROW_PATTERN.matcher(properties.getSheetRange());
        if (!matcher.find()) {
            throw new IllegalStateException("Google Sheets 범위에서 시작 행을 찾을 수 없습니다.");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** 설정된 시트 이름과 갱신 대상 열·행을 조합한다. */
    private String cellRange(String columns, int rowNumber) {
        String sheetName = properties.getSheetRange().substring(0, properties.getSheetRange().indexOf('!'));
        if (columns.contains(":")) {
            String[] split = columns.split(":");
            return sheetName + "!" + split[0] + rowNumber + ":" + split[1] + rowNumber;
        }
        return sheetName + "!" + columns + rowNumber;
    }

    /** 단일 행 갱신에 사용할 2차원 값 목록을 생성한다. */
    private List<List<Object>> singleRow(Object... values) {
        return List.of(List.of(values));
    }

    /** 공통 게이트웨이에 콘텐츠 시트 갱신을 위임한다. */
    private void update(Map<String, List<List<Object>>> updates) {
        gateway.batchUpdateValues(properties.getSpreadsheetId(), updates);
    }
}
