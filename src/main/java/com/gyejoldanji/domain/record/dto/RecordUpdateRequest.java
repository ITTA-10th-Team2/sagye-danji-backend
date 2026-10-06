package com.gyejoldanji.domain.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;
/** 기록 날짜·메모와 선택적으로 단일 이미지를 수정하는 요청. */
@Schema(description = "기록 수정 요청")
public record RecordUpdateRequest(
        @Schema(description = "수정할 기록 날짜", example = "2026-10-05")
        @NotNull
        LocalDate recordDate,

        @Schema(description = "수정할 메모. null 또는 생략하면 메모 삭제", example = "사진 순서를 수정했어요",
                nullable = true)
        @CodePointLength(max = 100)
        String memo,

        @Schema(description = "교체할 단일 이미지 객체 키. 생략하면 기존 이미지를 유지",
                example = "record-images/42/2026/10/replacement.jpg", nullable = true)
        @Size(max = 512)
        String objectKey
) {
}
