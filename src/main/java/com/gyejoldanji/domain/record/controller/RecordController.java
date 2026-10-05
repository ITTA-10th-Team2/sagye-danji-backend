package com.gyejoldanji.domain.record.controller;

import com.gyejoldanji.domain.record.dto.RecordCreateRequest;
import com.gyejoldanji.domain.record.dto.RecordCursorPageResponse;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordUpdateRequest;
import com.gyejoldanji.domain.record.service.RecordCommandService;
import com.gyejoldanji.domain.record.service.RecordQueryService;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.common.response.ErrorResponse;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.security.CurrentMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원의 기록과 이미지 메타데이터를 조회·생성·수정·삭제하는 API. */
@Tag(name = "Record", description = "사진 기록 조회·생성·수정·삭제 API")
@Validated
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordCommandService recordCommandService;
    private final RecordQueryService recordQueryService;

    /** 현재 회원의 특정 연도 전체 기록을 최신순 cursor 페이지로 조회한다. */
    @Operation(summary = "전체 기록 목록 조회",
            description = "특정 연도의 내 기록을 recordDate DESC, id DESC 순서로 cursor 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = RecordCursorPageResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "연도·크기·커서 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<RecordCursorPageResponse> findAll(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Parameter(description = "조회할 달력 연도", example = "2026")
            @RequestParam @Min(2000) @Max(2100) int year,
            @Parameter(description = "이전 응답의 nextCursor", example = "MjAyNi0xMC0wNHwxMDE")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("기록 목록 조회에 성공했습니다.",
                recordQueryService.findAll(currentMember.memberId(), year, cursor, size));
    }

    /** 현재 회원의 특정 연도·계절 단지 기록을 최신순 cursor 페이지로 조회한다. */
    @Operation(summary = "계절 단지 기록 목록 조회",
            description = "특정 연도와 계절의 내 기록을 cursor 기반으로 조회합니다. 기본 페이지 크기는 5개입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "단지 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = RecordCursorPageResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "연도·계절·커서 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/seasons/{season}")
    public ApiResponse<RecordCursorPageResponse> findBySeason(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Parameter(description = "조회할 계절", example = "AUTUMN")
            @PathVariable SeasonType season,
            @Parameter(description = "조회할 달력 연도", example = "2026")
            @RequestParam @Min(2000) @Max(2100) int year,
            @Parameter(description = "이전 응답의 nextCursor", example = "MjAyNi0xMC0wNHwxMDE")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "5")
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int size,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("계절 단지 기록 목록 조회에 성공했습니다.",
                recordQueryService.findBySeason(currentMember.memberId(), year, season, cursor, size));
    }

    /** 현재 회원이 소유한 기록과 정렬된 이미지 메타데이터를 상세 조회한다. */
    @Operation(summary = "기록 상세 조회", description = "현재 회원이 소유한 기록과 이미지 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상세 조회 성공",
                    content = @Content(schema = @Schema(implementation = RecordResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 기록 ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "소유한 기록 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{recordId}")
    public ApiResponse<RecordResponse> findDetail(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Parameter(description = "조회할 양수 기록 ID", example = "101")
            @PathVariable @Positive Long recordId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("기록 상세 조회에 성공했습니다.",
                recordQueryService.findDetail(currentMember.memberId(), recordId));
    }

    /** 업로드를 마친 객체 키들로 기록과 이미지 메타데이터를 생성한다. */
    @Operation(summary = "기록 생성", description = "현재 회원의 날짜·메모와 1~10개 이미지 메타데이터를 저장합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "기록 생성 성공",
                    content = @Content(schema = @Schema(implementation = RecordResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청값 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결된 이미지 키",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecordResponse> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Valid @RequestBody RecordCreateRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.created("기록 생성에 성공했습니다.",
                recordCommandService.create(currentMember.memberId(), request));
    }

    /** 현재 회원이 소유한 기록의 날짜·메모와 최종 이미지 구성을 수정한다. */
    @Operation(summary = "기록 수정", description = "날짜·메모를 수정하고 images가 있으면 최종 이미지 구성으로 교체합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록 수정 성공",
                    content = @Content(schema = @Schema(implementation = RecordResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청값 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "소유한 기록 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결된 이미지 키",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{recordId}")
    public ApiResponse<RecordResponse> update(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Parameter(description = "수정할 양수 기록 ID. 기록 생성 응답의 data.id 사용", example = "1")
            @PathVariable @Positive Long recordId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "images를 생략하면 기존 이미지를 유지합니다. images를 보내면 수정 후 최종 이미지 목록을 "
                            + "전달해야 하며, 각 항목의 type은 필수입니다.",
                    content = @Content(
                            schema = @Schema(implementation = RecordUpdateRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "이미지 변경 없이 날짜와 메모만 수정",
                                            summary = "images를 생략하여 기존 이미지 유지",
                                            value = """
                                                    {
                                                      "recordDate": "2026-10-05",
                                                      "memo": "메모만 수정했어요"
                                                    }
                                                    """),
                                    @ExampleObject(
                                            name = "기존 이미지 유지 및 신규 이미지 추가",
                                            summary = "EXISTING과 NEW를 함께 사용하는 수정",
                                            value = """
                                                    {
                                                      "recordDate": "2026-10-05",
                                                      "memo": "사진 순서를 수정했어요",
                                                      "images": [
                                                        {
                                                          "type": "EXISTING",
                                                          "imageId": 1,
                                                          "sortOrder": 0
                                                        },
                                                        {
                                                          "type": "NEW",
                                                          "objectKey": "record-images/42/2026/10/new-image.jpg",
                                                          "source": "GALLERY",
                                                          "sortOrder": 1
                                                        }
                                                      ]
                                                    }
                                                    """)
                            }))
            @Valid @RequestBody RecordUpdateRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.ok("기록 수정에 성공했습니다.",
                recordCommandService.update(currentMember.memberId(), recordId, request));
    }

    /** 현재 회원이 소유한 기록과 연결 이미지 메타데이터를 삭제한다. */
    @Operation(summary = "기록 삭제", description = "현재 회원의 기록과 연결 이미지 메타데이터를 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기록 삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 기록 ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "소유한 기록 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{recordId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
            @Parameter(description = "삭제할 양수 기록 ID. 기록 생성 응답의 data.id 사용", example = "1")
            @PathVariable @Positive Long recordId,
            HttpServletResponse response) {
        noStore(response);
        recordCommandService.delete(currentMember.memberId(), recordId);
        return ApiResponse.ok("기록 삭제에 성공했습니다.");
    }

    /** 개인 기록 응답이 브라우저·중간 캐시에 남지 않게 한다. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
