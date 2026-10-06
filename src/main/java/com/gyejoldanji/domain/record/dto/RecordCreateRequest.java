package com.gyejoldanji.domain.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;
/** 기록과 단일 이미지를 함께 생성하는 요청. */
@Schema(description = "기록 생성 요청")
public record RecordCreateRequest(
        @Schema(description = "기록 날짜. 생략하면 한국 기준 오늘", example = "2026-10-04", nullable = true)
        LocalDate recordDate,

        @Schema(description = "기록 메모. 유니코드 코드 포인트 기준 최대 100자", example = "가을밤 산책 🍂",
                nullable = true)
        @CodePointLength(max = 100)
        String memo,

        @Schema(description = "Presigned Upload로 저장한 단일 원본 객체 키",
                example = "record-images/42/2026/10/example.jpg")
        @NotBlank
        @Size(max = 512)
        String objectKey
) {
}
