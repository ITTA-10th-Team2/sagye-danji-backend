package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.enums.PhotoSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;
import java.util.List;

/** 기록과 해당 기록에 속하는 이미지 메타데이터를 함께 생성하는 요청. */
@Schema(description = "기록 생성 요청")
public record RecordCreateRequest(
        @Schema(description = "기록 날짜. 생략하면 한국 기준 오늘", example = "2026-10-04", nullable = true)
        LocalDate recordDate,

        @Schema(description = "기록 메모. 유니코드 코드 포인트 기준 최대 100자", example = "가을밤 산책 🍂",
                nullable = true)
        @CodePointLength(max = 100)
        String memo,

        @Schema(description = "최종 이미지 목록. 1개 이상 10개 이하")
        @NotNull
        @Size(min = 1, max = 10)
        List<@Valid ImageItem> images
) {

    /** 신규 이미지의 스토리지 키와 표시 정보를 담는다. */
    @Schema(description = "신규 이미지 메타데이터")
    public record ImageItem(
            @Schema(description = "Presigned Upload로 저장한 원본 객체 키",
                    example = "record-images/42/2026/10/example.jpg")
            @NotBlank
            @Size(max = 512)
            String objectKey,

            @Schema(description = "이미지 입력 경로", example = "CAMERA")
            @NotNull
            PhotoSource source,

            @Schema(description = "0부터 연속되는 표시 순서", example = "0")
            @NotNull
            @PositiveOrZero
            Integer sortOrder
    ) {
    }
}
