package com.gyejoldanji.domain.analytics.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.enums.EventSource;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 수집 본문 검증 순서·오류 코드·저장 값 변환을 고정 Clock으로 확인한다. */
class AnalyticsEventValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00.123456789Z");
    private static final String SESSION = "9B2E7C1D-4A3F-1E8B-0C6D-1F2A3B4C5D6E";

    private final AnalyticsEventValidator validator = new AnalyticsEventValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    /** 11종 최소 예시가 모두 통과하고 CLIENT·소문자 UUID·마이크로초 시각·compact 속성으로 바뀐다. */
    @Test
    void acceptsMinimalExampleOfEveryClientEvent() {
        Map<String, String> uppercaseId = event("HOME_VIEW", "{ \"entrySource\" : \"APP_OPEN\" }");
        uppercaseId.put("eventId", q("3F1C2B4A-8D7E-4F60-9A1B-2C3D4E5F6A7B"));
        List<AnalyticsEvent> events = parse(body(
                event("ONBOARDING_START", "{}"),
                event("ONBOARDING_SKIP", null),
                uppercaseId,
                event("HOME_ELEMENT_TAP", "{\"target\":\"SPEECH_BUBBLE\"}"),
                event("RECORD_SCREEN_VIEW", "{}"),
                event("RECORD_START", "{\"source\":\"GALLERY\"}"),
                event("JAR_VIEW", "{}"),
                event("LOCKED_SEASON_CLICK", "{\"season\":\"WINTER\"}"),
                event("SHARE_CLICK", "{\"sourceScreen\":\"RECORD_DETAIL\",\"method\":\"SYSTEM_SHARE\"}"),
                event("SCREEN_ENGAGEMENT", "{\"screenName\":\"JAR\",\"durationMs\":86400000}"),
                event("SESSION_START", null)));

        assertThat(events).hasSize(11);
        AnalyticsEvent home = events.get(2);
        assertThat(home.getMemberId()).isEqualTo(42L);
        assertThat(home.getEventId()).isEqualTo("3f1c2b4a-8d7e-4f60-9a1b-2c3d4e5f6a7b");
        assertThat(home.getSessionId()).isEqualTo("9b2e7c1d-4a3f-1e8b-0c6d-1f2a3b4c5d6e");
        assertThat(home.getEventName()).isEqualTo("HOME_VIEW");
        assertThat(home.getEventSource()).isEqualTo(EventSource.CLIENT);
        assertThat(home.getOs()).isEqualTo(ClientOs.IOS);
        assertThat(home.getSequence()).isEqualTo(1);
        assertThat(home.getServiceVersion()).isEqualTo("1.0.0");
        assertThat(home.getOccurredAt()).isEqualTo(LocalDateTime.parse("2026-10-11T02:59:59.987654"));
        assertThat(home.getReceivedAt()).isEqualTo(LocalDateTime.parse("2026-10-11T03:00:00.123456"));
        assertThat(home.getProperties()).isEqualTo("{\"entrySource\":\"APP_OPEN\"}");
        assertThat(events.get(1).getProperties()).isEqualTo("{}");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{}", "{\"referrer\":\"search\"}", "{\"shareScreen\":\"JAR\"}",
            "{\"referrer\":\"external_share\",\"shareScreen\":\"RECORD_DETAIL\"}",
            "{\"referrer\":\"external__owned_social\"}", "{\"referrer\":\"New-channel.v2_123\"}"})
    void acceptsOptionalSessionStartProperties(String properties) {
        assertThat(parse(body(event("SESSION_START", properties)))).singleElement().satisfies(event -> {
            assertThat(event.getEventName()).isEqualTo("SESSION_START");
            assertThat(event.getProperties()).isEqualTo(properties == null ? "{}" : properties);
        });
    }

    @Test
    void acceptsOneAndSixtyFourCharacterReferrersButRejectsSixtyFive() {
        for (int length : new int[]{1, 64}) {
            String properties = "{\"referrer\":" + q("a".repeat(length)) + "}";
            assertThat(parse(body(event("SESSION_START", properties))).getFirst().getProperties()).isEqualTo(properties);
        }
        assertRejected(body(event("SESSION_START", "{\"referrer\":" + q("a".repeat(65)) + "}")),
                ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\" \"", "\"search space\"", "\"한글\"", "\"<script>\"",
            "\"search\\n\"", "123", "null", "true", "[]", "{}"})
    void rejectsInvalidSessionStartReferrers(String referrer) {
        assertRejected(body(event("SESSION_START", "{\"referrer\":" + referrer + "}")),
                ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ONBOARDING", "HOME", "RECORD_CREATE", "RECORD_DETAIL", "JAR"})
    void acceptsEveryShareScreen(String screen) {
        String properties = "{\"shareScreen\":" + q(screen) + "}";
        assertThat(parse(body(event("SESSION_START", properties))).getFirst().getProperties()).isEqualTo(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"shareScreen\":\"UNKNOWN\"}", "{\"shareScreen\":\"home\"}",
            "{\"shareScreen\":null}", "{\"shareScreen\":1}", "{\"referrer\":\"search\",\"extra\":\"x\"}", "null"})
    void rejectsInvalidSessionStartProperties(String properties) {
        assertRejected(body(event("SESSION_START", properties)), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    /** 빈 본문·JSON 아님·객체 아님·다른 최상위 키·events 없음/배열 아님/빈 배열·null 원소는 001이다. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "nope", "[]", "null", "{}", "{\"events\":{}}", "{\"events\":[]}",
            "{\"events\":[null]}", "{\"events\":[1]}"})
    void rejectsMalformedEnvelope(String body) {
        assertRejected(body, ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    @Test
    void rejectsOtherTopLevelKeyAndTrailingTokens() {
        assertRejected("{\"events\":[" + json(event("JAR_VIEW", null)) + "],\"memberId\":1}",
                ErrorCode.ANALYTICS_INVALID_REQUEST);
        assertRejected(body(event("JAR_VIEW", null)) + "{}", ErrorCode.ANALYTICS_INVALID_REQUEST);
        assertThatThrownBy(() -> validator.parse(42L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYTICS_INVALID_REQUEST));
    }

    /** 50건은 통과하고 51건은 원소를 보기 전에 413 004다. */
    @Test
    void acceptsFiftyAndRejectsFiftyOneAsTooLarge() {
        assertThat(parse(body(IntStream.range(0, 50).mapToObj(i -> event("JAR_VIEW", null)).toList()))).hasSize(50);

        String fiftyOne = "{\"events\":[" + String.join(",", Collections.nCopies(51, "null")) + "]}";
        assertRejected(fiftyOne, ErrorCode.ANALYTICS_REQUEST_TOO_LARGE);
    }

    /** 서버 이벤트 이름과 모르는 이름은 002, 이름이 없거나 문자열이 아니면 001이다. */
    @ParameterizedTest
    @CsvSource({
            "'\"ONBOARDING_COMPLETE\"', ANALYTICS_EVENT_NOT_ALLOWED",
            "'\"RECORD_COMPLETE\"', ANALYTICS_EVENT_NOT_ALLOWED",
            "'\"home_view\"', ANALYTICS_EVENT_NOT_ALLOWED",
            "'\"UNKNOWN\"', ANALYTICS_EVENT_NOT_ALLOWED",
            "'1', ANALYTICS_INVALID_REQUEST",
            "'null', ANALYTICS_INVALID_REQUEST"})
    void rejectsEventNamesOutsideClientEvents(String eventName, ErrorCode expected) {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put("eventName", eventName);
        assertRejected(body(event), expected);
    }

    /** 서버가 정하는 값(회원·출처·수신 시각)이나 모르는 키는 001이다. */
    @ParameterizedTest
    @ValueSource(strings = {"userId", "memberId", "eventSource", "receivedAt"})
    void rejectsUndefinedEventKeys(String key) {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put(key, "1");
        assertRejected(body(event), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    @Test
    void rejectsDuplicateJsonKey() {
        String event = json(event("JAR_VIEW", null));
        String duplicated = event.substring(0, event.length() - 1) + ",\"os\":\"WEB\"}";
        assertRejected("{\"events\":[" + duplicated + "]}", ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    /** sequence는 1 이상 int 정수만 받는다. 소수 표기·null·문자열·0·int 초과는 001이다. */
    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1e0", "null", "\"1\"", "0", "2147483648"})
    void rejectsNonIntegerSequence(String sequence) {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put("sequence", sequence);
        assertRejected(body(event), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    @Test
    void acceptsMissingAndMaximumSequence() {
        Map<String, String> missing = event("JAR_VIEW", null);
        missing.remove("sequence");
        Map<String, String> max = event("JAR_VIEW", null);
        max.put("sequence", "2147483647");

        List<AnalyticsEvent> events = parse(body(missing, max));

        assertThat(events.get(0).getSequence()).isNull();
        assertThat(events.get(1).getSequence()).isEqualTo(Integer.MAX_VALUE);
    }

    /** 지금+5분은 정확히 같아도 003이고 1초 전은 통과한다(원래 정밀도로 비교). */
    @Test
    void rejectsOccurredAtFromExactlyFiveMinutesAhead() {
        Map<String, String> exact = event("JAR_VIEW", null);
        exact.put("occurredAt", q("2026-10-11T03:05:00.123456789Z"));
        assertRejected(body(exact), ErrorCode.ANALYTICS_OCCURRED_AT_INVALID);

        Map<String, String> justBefore = event("JAR_VIEW", null);
        justBefore.put("occurredAt", q("2026-10-11T03:04:59.123456789Z"));
        assertThat(parse(body(justBefore)).get(0).getOccurredAt())
                .isEqualTo(LocalDateTime.parse("2026-10-11T03:04:59.123456"));
    }

    /** 1000년 1월 1일 0시(UTC)는 통과한다. 그 앞은 아래 거부 목록에서 003이다. */
    @Test
    void acceptsYearThousandAsEarliestOccurredAt() {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put("occurredAt", q("1000-01-01T00:00:00Z"));
        assertThat(parse(body(event)).get(0).getOccurredAt()).isEqualTo(LocalDateTime.parse("1000-01-01T00:00:00"));
    }

    /** Z가 아닌 표기·없는 날짜·1000년 미만은 003, 문자열이 아니면 001이다. */
    @ParameterizedTest
    @CsvSource({
            "'\"0999-12-31T23:59:59.999999999Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"0000-01-01T00:00:00Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-10-11T12:00:00+09:00\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-10-11T03:00:00\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-10-11 03:00:00Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-02-30T03:00:00Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-10-11T24:00:00Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'\"2026-10-11T03:00:00.1234567890Z\"', ANALYTICS_OCCURRED_AT_INVALID",
            "'1760151600000', ANALYTICS_INVALID_REQUEST"})
    void rejectsInvalidOccurredAt(String occurredAt, ErrorCode expected) {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put("occurredAt", occurredAt);
        assertRejected(body(event), expected);
    }

    /** 필수 속성 누락·허용값 밖·모르는 키·null 값·타입 오류·범위 밖·객체 아닌 properties는 001이다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            HOME_VIEW         | {}
            HOME_VIEW         |
            HOME_VIEW         | {"entrySource":"DEEPLINK"}
            HOME_VIEW         | {"entrySource":"APP_OPEN","extra":"x"}
            HOME_VIEW         | {"entrySource":null}
            RECORD_START      | {"source":"camera"}
            JAR_VIEW          | {"previousScreen":"RECORD"}
            JAR_VIEW          | null
            JAR_VIEW          | []
            JAR_VIEW          | "{}"
            SHARE_CLICK       | {"method":"COPY_LINK"}
            SCREEN_ENGAGEMENT | {"screenName":"HOME"}
            SCREEN_ENGAGEMENT | {"screenName":"HOME","durationMs":-1}
            SCREEN_ENGAGEMENT | {"screenName":"HOME","durationMs":86400001}
            SCREEN_ENGAGEMENT | {"screenName":"HOME","durationMs":1000.0}
            SCREEN_ENGAGEMENT | {"screenName":"HOME","durationMs":"1000"}
            """)
    void rejectsInvalidProperties(String eventName, String properties) {
        Map<String, String> event = event(eventName, properties);
        if (properties == null) {
            event.remove("properties");
        }
        assertRejected(body(event), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    /** 앞뒤 공백·공백만·제어문자·31자 이상 serviceVersion과 대소문자가 다른 os는 001이다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            serviceVersion | " 1.0.0"
            serviceVersion | "   "
            serviceVersion | ""
            serviceVersion | "1.0\\n"
            serviceVersion | "1234567890123456789012345678901"
            os             | "ios"
            sessionId      | "not-a-uuid"
            eventId        | "9b2e7c1d-4a3f-1e8b-8c6d-1f2a3b4c5d6e"
            """)
    void rejectsInvalidContextFields(String key, String value) {
        Map<String, String> event = event("JAR_VIEW", null);
        event.put(key, value);
        assertRejected(body(event), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    /** 대소문자만 다른 eventId는 같은 이벤트라 요청 안 중복 001이다. */
    @Test
    void rejectsEventIdsDifferingOnlyInCase() {
        Map<String, String> lower = event("JAR_VIEW", null);
        lower.put("eventId", q("3f1c2b4a-8d7e-4f60-9a1b-2c3d4e5f6a7b"));
        Map<String, String> upper = event("JAR_VIEW", null);
        upper.put("eventId", q("3F1C2B4A-8D7E-4F60-9A1B-2C3D4E5F6A7B"));
        assertRejected(body(lower, upper), ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    private void assertRejected(String body, ErrorCode expected) {
        assertThatThrownBy(() -> validator.parse(42L, body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(expected);
                    assertThat(e.getMessage()).isEqualTo(expected.getMessage());
                });
    }

    /** 유효한 기본 이벤트. 값은 JSON 원문이다. properties가 null이면 키를 넣지 않는다. */
    private static Map<String, String> event(String eventName, String properties) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("eventId", q(UUID.randomUUID().toString()));
        event.put("eventName", q(eventName));
        event.put("sessionId", q(SESSION));
        event.put("serviceVersion", q("1.0.0"));
        event.put("os", q("IOS"));
        event.put("sequence", "1");
        event.put("occurredAt", q("2026-10-11T02:59:59.987654321Z"));
        if (properties != null) {
            event.put("properties", properties);
        }
        return event;
    }

    private List<AnalyticsEvent> parse(String body) {
        return validator.parse(42L, body.getBytes(StandardCharsets.UTF_8));
    }

    @SafeVarargs
    private static String body(Map<String, String>... events) {
        return body(List.of(events));
    }

    private static String body(List<Map<String, String>> events) {
        return events.stream().map(AnalyticsEventValidatorTest::json)
                .collect(Collectors.joining(",", "{\"events\":[", "]}"));
    }

    private static String json(Map<String, String> event) {
        return event.entrySet().stream().map(e -> q(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String q(String value) {
        return "\"" + value + "\"";
    }
}
