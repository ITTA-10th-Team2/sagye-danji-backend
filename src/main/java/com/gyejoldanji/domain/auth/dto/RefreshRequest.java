package com.gyejoldanji.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Refresh 요청. 서비스가 발급한 Refresh 원문 하나만 받는다.
 *
 * <p>추가 필드는 무시하며 회원·세션 선택에 쓰지 않는다. JSON 문자열만 받고(숫자·boolean 자동 변환 금지) 원문을 그대로 둔다(trim·대소문자
 * 변환·Base64 재인코딩 없음). 형식 오류는 파싱 단계에서 COMMON_004, 값 오류는 검증 단계에서 COMMON_001이 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshRequest(
        @NotNull
        @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "형식이 올바르지 않습니다.")
        @JsonDeserialize(using = AnonymousAuthRequest.JsonStringOnlyDeserializer.class)
        String refreshToken) {

    /** 로그·디버그 출력에 토큰 원문이 남지 않게 가린다. */
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=[REDACTED]]";
    }
}
