package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기록 복합 cursor의 왕복 변환과 비정상 입력 거부를 검증한다. */
class RecordCursorCodecTest {

    private final RecordCursorCodec codec = new RecordCursorCodec();

    @Test
    void encodesAndDecodesDateAndId() {
        String encoded = codec.encode(LocalDate.of(2026, 10, 4), 101L);

        RecordCursorCodec.Cursor cursor = codec.decodeNullable(encoded);

        assertThat(cursor.recordDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(cursor.recordId()).isEqualTo(101L);
        assertThat(encoded).doesNotContain("2026-10-04", "|");
    }

    @Test
    void treatsBlankCursorAsFirstPage() {
        assertThat(codec.decodeNullable(null)).isNull();
        assertThat(codec.decodeNullable(" ")).isNull();
    }

    @Test
    void rejectsMalformedCursorWithoutExposingDecoderFailure() {
        assertThatThrownBy(() -> codec.decodeNullable("not-a-cursor"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RECORD_CURSOR_INVALID));
    }
}

