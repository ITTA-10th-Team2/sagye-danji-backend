package com.gyejoldanji.domain.recommendation.controller;

import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationApiResponse;
import com.gyejoldanji.domain.recommendation.dto.TodayRecommendationResponse;
import com.gyejoldanji.domain.recommendation.service.TodayRecommendationService;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 홈 화면의 제철 추천 조회 API를 제공한다. */
@Tag(name = "Recommendation", description = "제철 콘텐츠 추천 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final TodayRecommendationService service;

    /** 한국 기준 오늘의 요일 순서에 배정되고 승인된 추천 한 건을 조회한다. */
    @Operation(summary = "오늘의 추천 조회", description = "이번 주 추천 중 오늘 요일에 배정되고 승인된 콘텐츠 한 건을 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "오늘의 추천 조회 성공",
                    content = @Content(schema = @Schema(implementation = TodayRecommendationApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "오늘 노출할 승인 추천 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/today")
    public ApiResponse<TodayRecommendationResponse> getTodayRecommendation() {
        return ApiResponse.ok("오늘의 추천 조회에 성공했습니다.", service.getTodayRecommendation());
    }
}
