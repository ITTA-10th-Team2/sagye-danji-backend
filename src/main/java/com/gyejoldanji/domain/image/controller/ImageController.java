package com.gyejoldanji.domain.image.controller;

import com.gyejoldanji.domain.image.dto.ImagePresignedUrlRequest;
import com.gyejoldanji.domain.image.dto.ImagePresignedUrlResponse;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import com.gyejoldanji.global.security.CurrentMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원이 기록 이미지를 저장소에 직접 업로드하기 위한 URL을 발급하는 API. */
@Tag(name = "Image", description = "기록 이미지 업로드 API")
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageStorageService imageStorageService;

    /** 형식·크기가 서명된 5분짜리 업로드 URL과 기록 API에 보낼 객체 키를 발급한다. */
    @Operation(summary = "이미지 업로드 URL 발급",
            description = "응답의 uploadUrl로 requiredHeaders를 그대로 붙여 파일을 PUT한 뒤, objectKey를 기록 생성·수정 "
                    + "요청에 보냅니다. image/jpeg·image/png, 최대 10MB만 허용합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공",
                    content = @Content(schema = @Schema(implementation = ImagePresignedUrlResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "요청값 검증 실패·지원하지 않는 형식·크기 초과",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "저장소 접근 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/presigned-url")
    public ApiResponse<ImagePresignedUrlResponse> issueUploadUrl(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Valid @RequestBody ImagePresignedUrlRequest request,
            HttpServletResponse response) {
        // 서명 URL은 일회성 자격 증명이므로 브라우저·중간 캐시에 남기지 않는다.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        return ApiResponse.ok("이미지 업로드 URL 발급에 성공했습니다.",
                imageStorageService.issueUploadUrl(currentMember.memberId(), request));
    }
}
