package com.gyejoldanji.domain.image.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** 발급한 업로드 URL과 업로드 후 기록 API에 보낼 객체 키. */
@Schema(description = "이미지 업로드 URL 발급 결과")
public record ImagePresignedUrlResponse(
        @Schema(description = "업로드 완료 후 기록 생성·수정 요청의 objectKey로 보낼 값",
                example = "record-images/42/2026/10/0b0c4a3e-6f1d-4c55-9a51-2f6c1c7f0e11.jpg")
        String objectKey,

        @Schema(description = "PUT으로 파일 본문을 업로드할 Presigned URL")
        String uploadUrl,

        @Schema(description = "업로드 URL의 UTC 만료 시각(발급 후 5분)", example = "2026-10-05T03:05:00Z")
        String expiresAt,

        @Schema(description = "업로드 PUT 요청에 그대로 보내야 하는 헤더",
                example = "{\"content-type\":\"image/jpeg\",\"content-length\":\"2048000\"}")
        Map<String, String> requiredHeaders
) {
}
