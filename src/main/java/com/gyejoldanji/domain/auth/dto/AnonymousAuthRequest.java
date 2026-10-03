package com.gyejoldanji.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 익명 인증 요청. 토스 Web SDK가 발급한 일회용 {@code code} 하나만 받는다.
 *
 * <p>추가 필드는 값·타입과 관계없이 무시한다. {@code code}는 JSON 문자열만 받고(숫자·boolean 자동 변환 금지) 원문을 그대로
 * 둔다(trim·대소문자·정규화 없음). 형식 오류는 파싱 단계에서 COMMON_004, 값 오류는 검증 단계에서 COMMON_001이 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnonymousAuthRequest(
        @NotNull
        // OpenAPI pattern \S(ECMAScript)와 같은 비공백 판정. Java의 \s·isBlank·@NotBlank는 공백 집합이 달라
        // ECMAScript 공백(탭·LF·VT·FF·CR·U+2028·U+2029·U+FEFF·Zs 전체)을 직접 나열한다.
        @Pattern(regexp = "(?s).*[^\\t\\n\\x0B\\f\\r\\u2028\\u2029\\uFEFF\\p{Zs}].*",
                message = "공백이 아닌 문자를 포함해야 합니다.")
        @CodePointLength(max = 4096)
        @JsonDeserialize(using = JsonStringOnlyDeserializer.class)
        String code) {

    /** 로그·디버그 출력에 code 원문이 남지 않게 가린다. */
    @Override
    public String toString() {
        return "AnonymousAuthRequest[code=[REDACTED]]";
    }

    /** JSON 문자열 토큰만 받는다. 숫자·boolean·배열·객체는 파싱 오류로 처리한다(null은 Jackson이 먼저 null로 넘긴다). */
    static final class JsonStringOnlyDeserializer extends ValueDeserializer<String> {

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.hasToken(JsonToken.VALUE_STRING)) {
                return p.getString();
            }
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
    }
}
