package com.gyejoldanji.domain.record.dto;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.function.Function;

/** 기록 목록·상세에서 공통으로 사용하는 이미지 표시 정보. */
@Schema(description = "기록 이미지 표시 정보")
public record RecordImageResponse(
        @Schema(description = "이미지 ID. 수정 요청의 EXISTING imageId로 사용", example = "501") Long id,
        @Schema(description = "이미지 입력 경로", example = "CAMERA") PhotoSource source,
        @Schema(description = "기록 안의 표시 순서", example = "0") int sortOrder,
        @Schema(description = "원본 이미지 조회 URL", example = "https://storage.example/original.jpg")
        String originalUrl,
        @Schema(description = "썸네일 조회 URL. 썸네일이 없으면 originalUrl과 동일",
                example = "https://storage.example/thumbnail.jpg") String thumbnailUrl
) {

    /** 객체 키 대신 원본·썸네일 조회 URL을 포함한 응답을 만든다. */
    public static RecordImageResponse from(Image image, Function<String, String> viewUrlIssuer) {
        String originalUrl = viewUrlIssuer.apply(image.getOriginalKey());
        String thumbnailUrl = image.getThumbnailKey() == null
                ? originalUrl
                : viewUrlIssuer.apply(image.getThumbnailKey());
        return new RecordImageResponse(image.getId(), image.getSource(), image.getSortOrder(),
                originalUrl, thumbnailUrl);
    }
}
