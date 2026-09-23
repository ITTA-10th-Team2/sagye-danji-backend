package com.gyejoldanji.domain.test.controller;

import com.gyejoldanji.global.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/response")
    public ApiResponse<List<String>> testResponse() {
        List<String> teamMembers = List.of("팀원1", "팀원2", "팀원3", "팀원4");
        return ApiResponse.success("API 공통 응답 테스트 성공!", teamMembers);
    }
}