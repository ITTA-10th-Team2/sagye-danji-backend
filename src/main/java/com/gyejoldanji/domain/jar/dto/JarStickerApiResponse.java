package com.gyejoldanji.domain.jar.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지 스티커 API의 공통 응답 래퍼 Swagger 스키마. */
@Schema(description = "단지 스티커 응답")
public record JarStickerApiResponse(
        @Schema(example = "true") boolean success,
        @Schema(example = "200") String code,
        @Schema(example = "단지 스티커 조회에 성공했습니다.") String message,
        JarStickerResponse data
) { }
