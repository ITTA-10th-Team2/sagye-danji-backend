package com.gyejoldanji.domain.member.controller;

import com.gyejoldanji.domain.member.dto.MemberResponse;
import com.gyejoldanji.domain.member.service.MemberService;
import com.gyejoldanji.global.common.response.ApiResponse;
import com.gyejoldanji.global.security.CurrentMember;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 회원 API. 회원은 Security 체인이 JWT·DB 세션 검사 뒤 넣은 {@link CurrentMember}로만 정하며 body·query·header의
 * 회원 선택값은 받지 않는다.
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /** 내 정보와 온보딩 상태. 캐시 금지 헤더는 처리 전에 둬 ControllerAdvice 오류 응답에도 남긴다. */
    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@AuthenticationPrincipal(errorOnInvalidType = true) CurrentMember currentMember,
                                          HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        return ApiResponse.ok(memberService.getMe(currentMember.memberId()));
    }
}
