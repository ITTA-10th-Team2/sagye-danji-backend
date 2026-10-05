package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** 날짜와 ID 복합 정렬 위치를 opaque URL-safe cursor로 변환한다. */
@Component
public class RecordCursorCodec {

    private static final int MAX_CURSOR_LENGTH = 100;
    private static final String DELIMITER = "|";

    /** 마지막으로 반환한 기록의 정렬 위치를 다음 페이지 cursor로 만든다. */
    public String encode(LocalDate recordDate, Long recordId) {
        if (recordDate == null || recordId == null || recordId <= 0) {
            throw new BusinessException(ErrorCode.RECORD_CURSOR_INVALID);
        }
        String raw = recordDate + DELIMITER + recordId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** cursor를 날짜와 ID로 해석하며 비어 있는 값은 첫 페이지로 처리한다. */
    public Cursor decodeNullable(String encoded) {
        if (!StringUtils.hasText(encoded)) {
            return null;
        }
        if (encoded.length() > MAX_CURSOR_LENGTH) {
            throw new BusinessException(ErrorCode.RECORD_CURSOR_INVALID);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] values = raw.split("\\|", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException("invalid cursor parts");
            }
            LocalDate recordDate = LocalDate.parse(values[0]);
            long recordId = Long.parseLong(values[1]);
            if (recordId <= 0) {
                throw new IllegalArgumentException("invalid cursor id");
            }
            return new Cursor(recordDate, recordId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.RECORD_CURSOR_INVALID, exception);
        }
    }

    /** keyset 조회에 필요한 마지막 기록 날짜와 ID. */
    public record Cursor(LocalDate recordDate, long recordId) {
    }
}

