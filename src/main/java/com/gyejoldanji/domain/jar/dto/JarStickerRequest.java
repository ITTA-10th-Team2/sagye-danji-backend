package com.gyejoldanji.domain.jar.dto;

import com.gyejoldanji.domain.jar.enums.StickerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 단지 화면의 최종 스티커 배치를 저장하는 요청. */
@Schema(description = "단지 스티커 전체 저장 요청")
public record JarStickerRequest(
        @NotNull @Size(max = 20)
        @Schema(description = "최종 스티커 목록. 빈 배열은 전체 삭제")
        List<@Valid Item> items
) {
    /** 화면에 배치된 스티커 한 개. */
    public record Item(
            @NotNull @Schema(description = "스티커 종류", example = "CLOVER") StickerType stickerType,
            @DecimalMin("0.0") @DecimalMax("1.0") @Schema(example = "0.35") double xRatio,
            @DecimalMin("0.0") @DecimalMax("1.0") @Schema(example = "0.42") double yRatio,
            @DecimalMin("0.5") @DecimalMax("2.0") @Schema(example = "1.0") double scale,
            @DecimalMin("-180.0") @DecimalMax("180.0") @Schema(example = "0") double rotation,
            @Min(0) @Max(19) @Schema(example = "0") int zIndex
    ) { }
}
