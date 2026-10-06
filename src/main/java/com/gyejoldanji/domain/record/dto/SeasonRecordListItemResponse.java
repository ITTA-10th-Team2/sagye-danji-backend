package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** 계절 단지 목록에 표시할 기록과 단일 이미지. */
@Schema(description = "계절 단지 기록 목록 항목")
public record SeasonRecordListItemResponse(
        @Schema(description = "기록 ID", example = "101") Long id,
        @Schema(description = "기록 날짜", example = "2026-10-04") LocalDate recordDate,
        @Schema(description = "기록 계절", example = "AUTUMN") SeasonType season,
        @Schema(description = "기록 메모", example = "가을밤 산책 🍂", nullable = true) String memo,
        @Schema(description = "기록의 단일 이미지") RecordImageResponse image
) {
    /** 일괄 조회한 단일 이미지를 URL과 함께 변환한다. */
    public static SeasonRecordListItemResponse from(Record record, List<Image> images,
                                                     Function<String, String> viewUrlIssuer) {
        if (images.size() != 1) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_INTEGRITY_VIOLATION);
        }
        return new SeasonRecordListItemResponse(record.getId(), record.getRecordDate(), record.getSeason(),
                record.getMemo(), RecordImageResponse.from(images.getFirst(), viewUrlIssuer));
    }
}
