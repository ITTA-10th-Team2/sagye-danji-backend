package com.gyejoldanji.domain.image.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 기록 이미지 업로드용 Presigned URL 발급 요청. */
@Schema(description = "이미지 업로드 URL 발급 요청")
public record ImagePresignedUrlRequest(
        @Schema(description = "업로드할 파일의 Content-Type. image/jpeg 또는 image/png", example = "image/jpeg")
        @NotBlank
        String contentType,

        @Schema(description = "업로드할 파일 크기(바이트). 최대 10MB(10485760)", example = "2048000")
        @NotNull
        @Positive
        Long fileSize
) {
}
