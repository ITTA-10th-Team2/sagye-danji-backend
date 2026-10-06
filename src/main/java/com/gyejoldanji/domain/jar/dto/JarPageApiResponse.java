package com.gyejoldanji.domain.jar.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지 페이지 조회 API의 공통 응답 래퍼 Swagger 스키마. */
@Schema(description = "단지 페이지 조회 응답")
public record JarPageApiResponse(
        @Schema(example = "true") boolean success,
        @Schema(example = "200") String code,
        @Schema(example = "단지 페이지 조회에 성공했습니다.") String message,
        JarPageResponse data
) { }
