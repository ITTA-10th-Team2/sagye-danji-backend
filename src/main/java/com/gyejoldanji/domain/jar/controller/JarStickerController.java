package com.gyejoldanji.domain.jar.controller;

import com.gyejoldanji.domain.jar.dto.JarStickerRequest;
import com.gyejoldanji.domain.jar.dto.JarStickerResponse;
import com.gyejoldanji.domain.jar.dto.JarPageResponse;
import com.gyejoldanji.domain.jar.dto.JarPageApiResponse;
import com.gyejoldanji.domain.jar.dto.JarStickerApiResponse;
import com.gyejoldanji.domain.jar.service.JarPageQueryService;
import com.gyejoldanji.domain.jar.service.JarStickerService;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import com.gyejoldanji.global.security.CurrentMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 회원의 단지 꾸미기 스티커 배치를 제공한다. */
@Tag(name = "Jar", description = "단지 꾸미기 API")
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JarStickerController {
    private final JarStickerService service;
    private final JarPageQueryService pageQueryService;

    /** 연도·계절의 특정 단지 페이지를 기록·이미지·스티커와 함께 조회한다. */
    @Operation(summary = "단지 페이지 조회", description = "0부터 시작하는 페이지 번호로 최대 5개 기록과 스티커를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = JarPageApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "단지 페이지 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/jar-pages")
    public ApiResponse<JarPageResponse> findPage(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember member,
            @RequestParam @Min(2000) @Max(2100) int year,
            @RequestParam SeasonType season,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("단지 페이지 조회에 성공했습니다.",
                pageQueryService.find(member.memberId(), year, season, page));
    }

    /** 연도·계절별 저장 배치를 조회한다. */
    @Operation(summary = "단지 스티커 조회", description = "저장된 배치가 없으면 빈 items를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = JarStickerApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "연도·계절 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/jar-pages/{jarPageId}/stickers")
    public ApiResponse<JarStickerResponse> find(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember member,
            @Parameter(description = "단지 페이지 ID", example = "31")
            @PathVariable @Positive Long jarPageId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("단지 스티커 조회에 성공했습니다.",
                service.find(member.memberId(), jarPageId));
    }

    /** 화면의 최종 스냅샷으로 배치를 전체 저장한다. */
    @Operation(summary = "단지 스티커 전체 저장",
            description = "기존 배치를 최종 items 상태로 전체 교체합니다. zIndex는 페이지 내에서 유일해야 하며 빈 순번은 허용합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공",
                    content = @Content(schema = @Schema(implementation = JarStickerApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "배치 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/jar-pages/{jarPageId}/stickers")
    public ApiResponse<JarStickerResponse> replace(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember member,
            @PathVariable @Positive Long jarPageId,
            @Valid @RequestBody JarStickerRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("단지 스티커 저장에 성공했습니다.",
                service.replace(member.memberId(), jarPageId, request));
    }

    /** 해당 단지 페이지에 저장된 스티커 전체를 삭제한다. */
    @Operation(summary = "단지 스티커 삭제", description = "해당 페이지의 저장된 스티커 배치를 전체 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "경로값 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/jar-pages/{jarPageId}/stickers")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember member,
            @PathVariable @Positive Long jarPageId,
            HttpServletResponse response) {
        noStore(response);
        service.delete(member.memberId(), jarPageId);
        return ApiResponse.ok("단지 스티커 삭제에 성공했습니다.");
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
