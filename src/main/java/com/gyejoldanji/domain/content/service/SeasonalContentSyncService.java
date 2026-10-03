package com.gyejoldanji.domain.content.service;

import com.gyejoldanji.domain.content.client.SeasonalContentSheetClient;
import com.gyejoldanji.domain.content.client.model.SeasonalContentSheetRow;
import com.gyejoldanji.domain.content.client.model.SheetSyncStatus;
import com.gyejoldanji.domain.content.config.SeasonalContentSyncProperties;
import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Google Sheets의 제철 콘텐츠를 DB에 멱등하게 동기화한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.google-sheets", name = "enabled", havingValue = "true")
public class SeasonalContentSyncService {

    /** 시트에 기록할 오류 메시지 최대 길이. */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;

    /** 시트 날짜 입력 형식. */
    private static final DateTimeFormatter SHEET_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** Google Sheets 달력 셀의 표시 날짜 형식. */
    private static final DateTimeFormatter SHEET_CALENDAR_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 콘텐츠 시트 접근 계약. */
    private final SeasonalContentSheetClient sheetClient;

    /** 제철 콘텐츠 저장소. */
    private final SeasonalContentRepository repository;

    /** 짧은 DB 작업 범위를 보장하는 트랜잭션 템플릿. */
    private final TransactionTemplate transactionTemplate;

    /** 처리 만료 정책을 포함한 동기화 설정. */
    private final SeasonalContentSyncProperties properties;

    /** 시간 경과를 검증 가능한 형태로 제공하는 시계. */
    private final Clock clock;

    /** 처리 가능한 모든 시트 행을 독립적으로 동기화한다. */
    public void synchronize() {
        Instant now = clock.instant();
        for (SeasonalContentSheetRow row : sheetClient.readRows()) {
            SeasonalContentSheetRow candidate = recoverIfExpired(row, now);
            if (candidate != null && candidate.syncStatus() == SheetSyncStatus.READY) {
                synchronizeRow(candidate, now);
            }
        }
    }

    /** 만료된 처리 중 행을 READY 상태로 복구한다. */
    private SeasonalContentSheetRow recoverIfExpired(SeasonalContentSheetRow row, Instant now) {
        if (row.syncStatus() != SheetSyncStatus.PROCESSING) {
            return row;
        }

        Instant expiresAt = row.processingStartedAt() == null
                ? Instant.MIN
                : row.processingStartedAt().plus(Duration.ofMinutes(properties.getProcessingTimeoutMinutes()));
        if (expiresAt.isAfter(now)) {
            return null;
        }

        try {
            sheetClient.markReady(row.rowNumber());
            return row.withSyncStatus(SheetSyncStatus.READY, null);
        } catch (RuntimeException exception) {
            log.warn("만료된 시트 행 복구에 실패했습니다. row={}, contentCode={}, errorType={}",
                    row.rowNumber(), safeCode(row.contentCode()), exception.getClass().getSimpleName());
            return null;
        }
    }

    /** 한 행을 검증하고 선점한 뒤 DB와 시트에 결과를 반영한다. */
    private void synchronizeRow(SeasonalContentSheetRow row, Instant now) {
        SeasonalContentSheetRow processingRow = row;
        try {
            ContentInput input = validate(row);
            String contentCode = resolveContentCode(row);
            processingRow = row.withContentCode(contentCode);
            sheetClient.markProcessing(row.rowNumber(), now);

            SeasonalContent content = upsert(contentCode, input);
            sheetClient.markSynced(row.rowNumber(), content.getId(), clock.instant());
        } catch (RuntimeException exception) {
            log.warn("시트 행 동기화에 실패했습니다. row={}, contentCode={}, errorType={}",
                    row.rowNumber(), safeCode(processingRow.contentCode()), exception.getClass().getSimpleName());
            markFailed(row.rowNumber(), toSafeErrorMessage(exception));
        }
    }

    /** 입력값을 검증하고 도메인 값으로 변환한다. */
    private ContentInput validate(SeasonalContentSheetRow row) {
        SeasonType season = enumValue(SeasonType.class, row.season(), "계절");
        ContentCategory category = enumValue(ContentCategory.class, row.category(), "카테고리");
        String material = requireText(row.material(), "소재");
        String availableStartDate = dateValue(row.availableStartDate(), "사용 가능 시작일");
        String availableEndDate = dateValue(row.availableEndDate(), "사용 가능 종료일");
        validateDateRange(availableStartDate, availableEndDate);
        OptimalPeriod optimalPeriod = enumValue(OptimalPeriod.class, row.optimalPeriod(), "최적 시기");
        String region = requireText(row.region(), "지역");
        String timingSource = requireText(row.timingSource(), "시기 출처");
        String sourceCheckedDate = dateValue(row.sourceCheckedDate(), "출처 확인일");
        String title = requireText(row.title(), "활동 제목");
        if (title.codePointCount(0, title.length()) > 100) {
            throw invalidRow("활동 제목은 100자를 초과할 수 없습니다.");
        }
        String description = requireText(row.description(), "활동 설명");
        boolean active = booleanValue(row.active());

        if (StringUtils.hasText(row.contentCode())) {
            try {
                UUID.fromString(row.contentCode());
            } catch (IllegalArgumentException exception) {
                throw invalidRow("콘텐츠 코드는 UUID 형식이어야 합니다.", exception);
            }
        }
        return new ContentInput(
                season, category, material,
                availableStartDate, availableEndDate, optimalPeriod,
                region, timingSource, sourceCheckedDate,
                title, description, active);
    }

    /** 비어 있는 콘텐츠 코드를 발급하고 시트에 먼저 기록한다. */
    private String resolveContentCode(SeasonalContentSheetRow row) {
        if (StringUtils.hasText(row.contentCode())) {
            return row.contentCode();
        }
        String contentCode = UUID.randomUUID().toString();
        sheetClient.writeContentCode(row.rowNumber(), contentCode);
        return contentCode;
    }

    /** 고유 코드 기준으로 콘텐츠를 생성하거나 수정한다. */
    private SeasonalContent upsert(String contentCode, ContentInput input) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> upsertInTransaction(contentCode, input)));
        } catch (DataIntegrityViolationException exception) {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                SeasonalContent content = repository.findByContentCode(contentCode)
                        .orElseThrow(() -> exception);
                applyInput(content, input);
                return content;
            }));
        }
    }

    /** 한 DB 트랜잭션 안에서 고유 코드 기반 생성 또는 수정을 수행한다. */
    private SeasonalContent upsertInTransaction(String contentCode, ContentInput input) {
        SeasonalContent content = repository.findByContentCode(contentCode)
                .orElseGet(() -> repository.save(SeasonalContent.create(
                        contentCode, input.season(), input.category(), input.material(),
                        input.availableStartDate(), input.availableEndDate(),
                        input.optimalPeriod(), input.region(), input.timingSource(),
                        input.sourceCheckedDate(), input.title(), input.description())));
        applyInput(content, input);
        return content;
    }

    /** 검증된 입력값과 활성 상태를 엔티티에 반영한다. */
    private void applyInput(SeasonalContent content, ContentInput input) {
        content.update(
                input.season(), input.category(), input.material(),
                input.availableStartDate(), input.availableEndDate(), input.optimalPeriod(),
                input.region(), input.timingSource(), input.sourceCheckedDate(),
                input.title(), input.description());
        if (input.active()) {
            content.activate();
        } else {
            content.deactivate();
        }
    }

    /** 시트 실패 상태 기록 오류가 전체 실행으로 전파되지 않게 격리한다. */
    private void markFailed(int rowNumber, String errorMessage) {
        try {
            sheetClient.markFailed(rowNumber, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("시트 실패 상태 기록에 실패했습니다. row={}, errorType={}",
                    rowNumber, exception.getClass().getSimpleName());
        }
    }

    /** 문자열을 지정한 Enum 값으로 변환한다. */
    private <E extends Enum<E>> E enumValue(Class<E> enumType, String value, String fieldName) {
        String validated = requireText(value, fieldName);
        try {
            return Enum.valueOf(enumType, validated.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidRow(fieldName + " 값이 지원되지 않습니다.", exception);
        }
    }

    /** TRUE 또는 FALSE 입력을 Boolean 값으로 변환한다. */
    private boolean booleanValue(String value) {
        String validated = requireText(value, "활성 여부").toUpperCase(Locale.ROOT);
        if ("TRUE".equals(validated)) {
            return true;
        }
        if ("FALSE".equals(validated)) {
            return false;
        }
        throw invalidRow("활성 여부는 TRUE 또는 FALSE여야 합니다.");
    }

    /** 선택적 날짜를 검증하고 DB 저장 형식인 yyyyMMdd로 정규화한다. */
    private String dateValue(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String validated = value.trim();
        for (DateTimeFormatter formatter : List.of(SHEET_CALENDAR_DATE_FORMATTER, SHEET_DATE_FORMATTER)) {
            try {
                return LocalDate.parse(validated, formatter).format(SHEET_DATE_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // 지원하는 다음 날짜 형식을 확인한다.
            }
        }
        throw invalidRow(fieldName + "은 yyyy-MM-dd 또는 yyyyMMdd 형식이어야 합니다.");
    }

    /** 사용 가능 종료일이 시작일보다 빠르지 않은지 검증한다. */
    private void validateDateRange(String startDate, String endDate) {
        if (startDate == null || endDate == null) {
            return;
        }
        LocalDate start = LocalDate.parse(startDate, SHEET_DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, SHEET_DATE_FORMATTER);
        if (end.isBefore(start)) {
            throw invalidRow("사용 가능 종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    /** 공백이 아닌 필수 문자열인지 검증한다. */
    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw invalidRow(fieldName + "은 비어 있을 수 없습니다.");
        }
        return value.trim();
    }

    /** 시트에 노출할 수 있는 짧은 오류 메시지를 만든다. */
    private String toSafeErrorMessage(RuntimeException exception) {
        String message = exception instanceof BusinessException && StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : "동기화 처리 중 오류가 발생했습니다.";
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /** 로그에 사용할 안전한 콘텐츠 코드 표현을 반환한다. */
    private String safeCode(String contentCode) {
        return StringUtils.hasText(contentCode) ? contentCode : "unassigned";
    }

    /** 잘못된 시트 행을 나타내는 비즈니스 예외를 생성한다. */
    private BusinessException invalidRow(String message) {
        return new BusinessException(ErrorCode.CONTENT_SYNC_INVALID_ROW, message);
    }

    /** 원인 예외를 포함한 잘못된 시트 행 예외를 생성한다. */
    private BusinessException invalidRow(String message, Throwable cause) {
        return new BusinessException(ErrorCode.CONTENT_SYNC_INVALID_ROW, message, cause);
    }

    /** 검증을 마친 콘텐츠 입력값. */
    private record ContentInput(
            SeasonType season,
            ContentCategory category,
            String material,
            String availableStartDate,
            String availableEndDate,
            OptimalPeriod optimalPeriod,
            String region,
            String timingSource,
            String sourceCheckedDate,
            String title,
            String description,
            boolean active
    ) {
    }
}
