package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.enums.PhotoSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.LocalDate;
import java.util.List;

/** 기록의 날짜·메모와 선택적으로 최종 이미지 구성을 수정하는 요청. */
@Schema(description = "기록 수정 요청")
public record RecordUpdateRequest(
        @Schema(description = "수정할 기록 날짜", example = "2026-10-05")
        @NotNull
        LocalDate recordDate,

        @Schema(description = "수정할 메모. null 또는 생략하면 메모 삭제", example = "사진 순서를 수정했어요",
                nullable = true)
        @CodePointLength(max = 100)
        String memo,

        @Schema(description = "수정 후 최종 이미지 목록. null 또는 생략하면 기존 이미지 유지", nullable = true)
        @Size(max = 10)
        List<@Valid ImageItem> images
) {

    /** 기존 이미지 유지 또는 신규 이미지 추가를 표현하는 최종 이미지 항목. */
    @Schema(description = "수정 후 이미지 항목")
    public record ImageItem(
            @Schema(description = "기존 이미지 유지 또는 신규 이미지 추가", example = "EXISTING")
            @NotNull
            Type type,

            @Schema(description = "EXISTING일 때 현재 기록에 속한 이미지 ID", example = "502", nullable = true)
            @Positive
            Long imageId,

            @Schema(description = "NEW일 때 업로드한 원본 객체 키",
                    example = "record-images/42/2026/10/example.jpg", nullable = true)
            @Size(max = 512)
            String objectKey,

            @Schema(description = "NEW일 때 이미지 입력 경로", example = "GALLERY", nullable = true)
            PhotoSource source,

            @Schema(description = "0부터 연속되는 최종 표시 순서", example = "0")
            @NotNull
            @PositiveOrZero
            Integer sortOrder
    ) {
    }

    /** 수정 이미지 항목이 기존 이미지인지 신규 이미지인지 구분한다. */
    public enum Type {
        /** 현재 기록에 속한 이미지를 유지한다. */
        EXISTING,
        /** 업로드된 객체 키로 새 이미지 메타데이터를 추가한다. */
        NEW
    }
}
