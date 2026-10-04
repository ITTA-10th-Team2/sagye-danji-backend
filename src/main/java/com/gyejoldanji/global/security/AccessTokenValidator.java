package com.gyejoldanji.global.security;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.gyejoldanji.global.config.properties.AuthProperties;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 자체 Access JWT의 kid·claim·시각 검증. JwtDecoder가 RS256·현재 공개키로 서명을 확인한 토큰에만 실행된다.
 *
 * <p>Spring의 claim 변환은 문자열·소수 시각도 Instant로 바꾸고 Nimbus도 소수 초를 버리므로, 서명이 확인된 원본 payload를 다시
 * 읽어 JSON 타입 그대로 검사한다. 오류는 모두 모아 반환하며 만료는 {@link #EXPIRED} 코드로 따로 표시한다. 다른 오류 없이 만료만
 * 있을 때에만 호출자가 만료로 분류한다.
 */
public class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** exp 경과를 나타내는 오류 코드. */
    public static final String EXPIRED = "access_token_expired";

    /** iat·nbf에 허용하는 미래 시계 오차. exp에는 유예를 두지 않는다. */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);
    /** 9999-12-31T23:59:59Z. 날짜 변환 시 overflow가 나는 값을 막는다. */
    private static final long MAX_NUMERIC_DATE = 253_402_300_799L;
    private static final Pattern MEMBER_ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final AuthProperties.Jwt settings;
    private final Clock clock;

    public AccessTokenValidator(AuthProperties.Jwt settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Map<String, Object> claims = rawClaims(jwt.getTokenValue());
        if (claims == null) {
            return OAuth2TokenValidatorResult.failure(invalid("payload"));
        }
        List<OAuth2Error> errors = new ArrayList<>();
        require(errors, settings.getKeyId().equals(jwt.getHeaders().get("kid")), "kid");
        require(errors, settings.getIssuer().equals(claims.get("iss")), "iss");
        require(errors, isAudience(claims.get("aud")), "aud");
        require(errors, "access".equals(claims.get("token_use")), "token_use");
        require(errors, isMemberId(claims.get("sub")), "sub");
        require(errors, isUuid(claims.get("sid")), "sid");
        require(errors, isUuid(claims.get("jti")), "jti");

        Long iat = numericDate(claims.get("iat"));
        Long nbf = numericDate(claims.get("nbf"));
        Long exp = numericDate(claims.get("exp"));
        require(errors, iat != null, "iat");
        require(errors, nbf != null, "nbf");
        require(errors, exp != null, "exp");
        if (iat != null && nbf != null && exp != null) {
            Instant now = clock.instant();
            Instant latestStart = now.plus(MAX_FUTURE_SKEW);
            require(errors, !Instant.ofEpochSecond(iat).isAfter(latestStart), "iat");
            require(errors, !Instant.ofEpochSecond(nbf).isAfter(latestStart), "nbf");
            require(errors, exp > iat && nbf <= exp, "exp");
            if (!now.isBefore(Instant.ofEpochSecond(exp))) {
                errors.add(new OAuth2Error(EXPIRED, "Access token expired", null));
            }
        }
        return errors.isEmpty() ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(errors);
    }

    /** 서명이 확인된 토큰의 payload를 JSON 원래 타입(정수 Long·소수 Double·문자열 등)으로 읽는다. */
    private static Map<String, Object> rawClaims(String token) {
        try {
            return SignedJWT.parse(token).getPayload().toJSONObject();
        } catch (ParseException e) {
            return null;
        }
    }

    private boolean isAudience(Object aud) {
        String audience = settings.getAudience();
        return audience.equals(aud)
                || (aud instanceof List<?> list && list.contains(audience)
                        && list.stream().allMatch(String.class::isInstance));
    }

    /** JSON 문자열인 양의 십진수이며 signed Long 범위다. */
    private static boolean isMemberId(Object sub) {
        if (!(sub instanceof String value) || !MEMBER_ID.matcher(value).matches()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 소문자 8-4-4-4-12 정규형만 받는다. UUID.fromString은 "1-1-1-1-1" 같은 비정규형도 받으므로 쓰지 않는다. */
    private static boolean isUuid(Object value) {
        return value instanceof String text && CANONICAL_UUID.matcher(text).matches();
    }

    /** JSON 정수(소수점·지수 없음)인 epoch seconds만 받는다. 문자열·소수·누락은 null이다. */
    private static Long numericDate(Object value) {
        return value instanceof Long seconds && seconds >= 0 && seconds <= MAX_NUMERIC_DATE ? seconds : null;
    }

    private static void require(List<OAuth2Error> errors, boolean valid, String claim) {
        if (!valid) {
            errors.add(invalid(claim));
        }
    }

    private static OAuth2Error invalid(String claim) {
        return new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Invalid " + claim, null);
    }
}
