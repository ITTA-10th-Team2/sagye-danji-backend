package com.gyejoldanji.domain.recommendation.client.google;

import com.gyejoldanji.domain.recommendation.client.WeeklyRecommendationSheetClient;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationApprovalRow;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationSheetRow;
import com.gyejoldanji.domain.recommendation.config.WeeklyRecommendationProperties;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 주간 추천 5개 컬럼의 일괄 출력과 승인 상태 읽기를 담당한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class GoogleSheetsWeeklyRecommendationClient implements WeeklyRecommendationSheetClient {

    private static final int RECOMMENDATION_COUNT = 7;
    private static final int APPROVAL_COLUMN_INDEX = 4;
    private static final Pattern START_ROW_PATTERN = Pattern.compile("![A-Za-z]+(\\d+)");

    private final GoogleSheetsGateway gateway;
    private final WeeklyRecommendationProperties properties;

    /** Google Sheets 연동이 켜졌을 때 주간 추천 시트 설정을 검증한다. */
    @PostConstruct
    void validateConfiguration() {
        if (!StringUtils.hasText(properties.getSpreadsheetId())) {
            throw new IllegalStateException("Google Sheets 연동이 활성화되면 spreadsheet-id가 필요합니다.");
        }
        if (!properties.getSheetRange().matches(".+![A-Za-z]+\\d+:[A-Za-z]+")) {
            throw new IllegalStateException("추천 sheet-range는 헤더를 제외한 A1 범위 형식이어야 합니다.");
        }
    }

    /** 기존 추천 행을 지우고 헤더와 이번 주 추천 7개를 배치로 기록한다. */
    @Override
    public void overwriteWeeklyRecommendations(List<WeeklyRecommendationSheetRow> rows) {
        if (rows.size() != RECOMMENDATION_COUNT) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_CANDIDATES_INSUFFICIENT);
        }

        String sheetName = sheetName();
        int firstRow = firstRowNumber();
        gateway.clearValues(spreadsheetId(), properties.getSheetRange());

        List<List<Object>> values = rows.stream()
                .map(row -> List.<Object>of(
                        row.week(), row.type(), row.material(), row.stage(), row.approved()))
                .toList();
        Map<String, List<List<Object>>> updates = new LinkedHashMap<>();
        updates.put(sheetName + "!A1:E1", List.of(List.of("주차", "구분", "소재", "단계", "승인")));
        updates.put(sheetName + "!A" + firstRow + ":E" + (firstRow + rows.size() - 1), values);
        gateway.batchUpdateValues(spreadsheetId(), updates);
        gateway.applyCheckboxValidation(
                spreadsheetId(), sheetName, firstRow, firstRow + rows.size() - 1, APPROVAL_COLUMN_INDEX);
    }

    /** 비어 있지 않은 추천 행의 소재와 승인 체크 상태를 읽는다. */
    @Override
    public List<WeeklyRecommendationApprovalRow> readApprovalRows() {
        List<List<Object>> values = gateway.readValues(spreadsheetId(), properties.getSheetRange());
        int firstRow = firstRowNumber();
        List<WeeklyRecommendationApprovalRow> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            List<Object> cells = values.get(index);
            String material = text(cells, 2);
            if (!StringUtils.hasText(material)) {
                continue;
            }
            rows.add(new WeeklyRecommendationApprovalRow(
                    firstRow + index,
                    material,
                    booleanValue(cells, 4)));
        }
        return rows;
    }

    private boolean booleanValue(List<Object> cells, int index) {
        if (index >= cells.size() || cells.get(index) == null) {
            return false;
        }
        Object value = cells.get(index);
        if (value instanceof Boolean checked) {
            return checked;
        }
        return "TRUE".equals(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
    }

    private String text(List<Object> cells, int index) {
        return index >= cells.size() || cells.get(index) == null
                ? ""
                : String.valueOf(cells.get(index)).trim();
    }

    private int firstRowNumber() {
        Matcher matcher = START_ROW_PATTERN.matcher(properties.getSheetRange());
        if (!matcher.find()) {
            throw new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String sheetName() {
        int separator = properties.getSheetRange().indexOf('!');
        if (separator < 1) {
            throw new BusinessException(ErrorCode.GOOGLE_SHEETS_API_ERROR);
        }
        return properties.getSheetRange().substring(0, separator);
    }

    private String spreadsheetId() {
        return properties.getSpreadsheetId();
    }
}
