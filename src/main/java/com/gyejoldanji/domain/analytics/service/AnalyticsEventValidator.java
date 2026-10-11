package com.gyejoldanji.domain.analytics.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집 API 원본 본문을 파싱·검증해 저장할 이벤트로 바꾼다. 첫 오류에서 멈추며 오류에는 입력값을 담지 않는다.
 *
 * <p>전역 JsonMapper 대신 중복 키·trailing token을 거부하는 전용 JsonMapper를 쓴다. 요청마다 Clock을 한 번 읽어 미래 시각 검사와
 * 수신 시각에 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AnalyticsEventValidator {

    static final int MAX_EVENTS = 50;
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
    private static final int MIN_OCCURRED_YEAR = 1000;
    private static final int MAX_PROPERTIES_BYTES = 4_096;
    private static final int MAX_SERVICE_VERSION_CODE_POINTS = 30;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Pattern EVENT_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final Pattern SESSION_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern OCCURRED_AT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");

    private static final Set<String> EVENT_KEYS = Set.of(
            "eventId", "eventName", "sessionId", "serviceVersion", "os", "sequence", "occurredAt", "properties");

    // 프론트 src/lib/analytics.ts와 맞출 것(HOME_VIEW entrySource).
    private static final List<String> ENTRY_SOURCES = List.of("APP_OPEN", "INTERNAL_NAVIGATION", "BACK_NAVIGATION");
    // 프론트 src/lib/analytics.ts와 맞출 것(HOME_ELEMENT_TAP target).
    private static final List<String> HOME_TARGETS = List.of("CHARACTER", "SPEECH_BUBBLE", "SEASON_OBJECT");
    // 프론트 src/lib/analytics.ts와 맞출 것(AnalyticsScreen: previousScreen·sourceScreen·screenName).
    private static final List<String> SCREENS = List.of("ONBOARDING", "HOME", "RECORD_CREATE", "RECORD_DETAIL", "JAR");
    // 프론트 src/lib/analytics.ts와 맞출 것(SHARE_CLICK method).
    private static final List<String> SHARE_METHODS = List.of("SYSTEM_SHARE", "COPY_LINK");

    /** 이벤트별 허용 속성. 없는 이벤트는 속성이 없다. */
    private static final Map<ClientEventName, List<Prop>> PROPERTIES = new EnumMap<>(Map.of(
            ClientEventName.HOME_VIEW, List.of(Prop.oneOf("entrySource", true, ENTRY_SOURCES)),
            ClientEventName.HOME_ELEMENT_TAP, List.of(Prop.oneOf("target", true, HOME_TARGETS)),
            ClientEventName.RECORD_START, List.of(Prop.oneOf("source", true, names(PhotoSource.values()))),
            ClientEventName.JAR_VIEW, List.of(Prop.oneOf("previousScreen", false, SCREENS)),
            ClientEventName.LOCKED_SEASON_CLICK, List.of(Prop.oneOf("season", true, names(SeasonType.values()))),
            ClientEventName.SHARE_CLICK, List.of(
                    Prop.oneOf("sourceScreen", true, SCREENS),
                    Prop.oneOf("method", false, SHARE_METHODS)),
            ClientEventName.SCREEN_ENGAGEMENT, List.of(
                    Prop.oneOf("screenName", true, SCREENS),
                    Prop.range("durationMs", 0, 86_400_000))));

    private final Clock clock;

    /**
     * @throws BusinessException 형식·속성 오류 400 ANALYTICS_001, 수집 불가 이름 400 ANALYTICS_002, 발생 시각 오류 400 ANALYTICS_003,
     *                           {@value #MAX_EVENTS}건 초과 413 ANALYTICS_004
     */
    public List<AnalyticsEvent> parse(Long memberId, byte[] body) {
        Instant now = clock.instant();
        LocalDateTime receivedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        JsonNode root = readTree(body);
        if (!root.isObject() || !Set.of("events").containsAll(root.propertyNames())) {
            throw invalid();
        }
        JsonNode events = root.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            throw invalid();
        }
        if (events.size() > MAX_EVENTS) {
            throw new BusinessException(ErrorCode.ANALYTICS_REQUEST_TOO_LARGE);
        }
        List<AnalyticsEvent> parsed = new ArrayList<>(events.size());
        for (JsonNode event : events) {
            parsed.add(parseEvent(memberId, event, now, receivedAt));
        }
        Set<String> eventIds = new HashSet<>();
        if (!parsed.stream().allMatch(event -> eventIds.add(event.getEventId()))) {
            throw invalid();
        }
        return parsed;
    }

    private static JsonNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            // 파서 메시지에 본문 원문이 있으므로 원인을 남기지 않는다.
            throw invalid();
        }
    }

    private static AnalyticsEvent parseEvent(Long memberId, JsonNode event, Instant now, LocalDateTime receivedAt) {
        if (!event.isObject() || !EVENT_KEYS.containsAll(event.propertyNames())) {
            throw invalid();
        }
        ClientEventName eventName = eventName(event.get("eventName"));
        String eventId = uuid(event.get("eventId"), EVENT_ID);
        String sessionId = uuid(event.get("sessionId"), SESSION_ID);
        String serviceVersion = serviceVersion(event.get("serviceVersion"));
        ClientOs os = os(event.get("os"));
        Integer sequence = sequence(event);
        LocalDateTime occurredAt = occurredAt(event.get("occurredAt"), now);
        String properties = properties(eventName, event.get("properties"));
        return AnalyticsEvent.client(memberId, eventId, sessionId, eventName, occurredAt, receivedAt, serviceVersion,
                os, sequence, properties);
    }

    private static ClientEventName eventName(JsonNode node) {
        String value = text(node);
        return Arrays.stream(ClientEventName.values()).filter(name -> name.name().equals(value)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYTICS_EVENT_NOT_ALLOWED));
    }

    /** 형식이 맞으면 소문자로 바꾼다. */
    private static String uuid(JsonNode node, Pattern pattern) {
        String value = text(node);
        if (!pattern.matcher(value).matches()) {
            throw invalid();
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** 1~30 code point, 앞뒤 공백·제어문자·짝 없는 surrogate 없음. 자르거나 trim하지 않는다. */
    private static String serviceVersion(JsonNode node) {
        String value = text(node);
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > MAX_SERVICE_VERSION_CODE_POINTS || !value.strip().equals(value)
                || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                        || Character.getType(c) == Character.SURROGATE)) {
            throw invalid();
        }
        return value;
    }

    private static ClientOs os(JsonNode node) {
        String value = text(node);
        return Arrays.stream(ClientOs.values()).filter(os -> os.name().equals(value)).findFirst()
                .orElseThrow(AnalyticsEventValidator::invalid);
    }

    /** 선택. 있으면 JSON 정수 1 이상(int 범위). null·문자열·소수 표기는 거부한다. */
    private static Integer sequence(JsonNode event) {
        if (!event.has("sequence")) {
            return null;
        }
        JsonNode node = event.get("sequence");
        if (!node.isInt() || node.intValue() < 1) {
            throw invalid();
        }
        return node.intValue();
    }

    /** UTC {@code Z} 표기의 1000년 이후 실제 날짜이고 지금+5분보다 앞서야 한다. 원래 정밀도로 비교한 뒤 마이크로초로 자른다. */
    private static LocalDateTime occurredAt(JsonNode node, Instant now) {
        String value = text(node);
        if (!OCCURRED_AT.matcher(value).matches()) {
            throw occurredAtInvalid();
        }
        LocalDateTime occurredAt;
        try {
            // ISO_LOCAL_DATE_TIME은 STRICT라 2월 30일·24시·60초를 거부한다.
            occurredAt = LocalDateTime.parse(value.substring(0, value.length() - 1));
        } catch (DateTimeParseException e) {
            throw occurredAtInvalid();
        }
        // MySQL DATETIME 지원 범위(1000년~) 밖은 실제 발생 시각이 아니다.
        if (occurredAt.getYear() < MIN_OCCURRED_YEAR
                || !occurredAt.toInstant(ZoneOffset.UTC).isBefore(now.plus(MAX_FUTURE))) {
            throw occurredAtInvalid();
        }
        return occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** 이벤트의 속성 규칙을 검사하고 compact JSON으로 돌려준다. 필수 속성이 없는 이벤트는 생략할 수 있다. */
    private static String properties(ClientEventName eventName, JsonNode node) {
        List<Prop> rules = PROPERTIES.getOrDefault(eventName, List.of());
        if (node == null) {
            if (rules.stream().anyMatch(Prop::required)) {
                throw invalid();
            }
            return "{}";
        }
        if (!node.isObject()) {
            throw invalid();
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            Prop rule = rules.stream().filter(prop -> prop.name().equals(property.getKey())).findFirst()
                    .orElseThrow(AnalyticsEventValidator::invalid);
            if (!rule.accepts(property.getValue())) {
                throw invalid();
            }
        }
        if (rules.stream().anyMatch(prop -> prop.required() && !node.has(prop.name()))) {
            throw invalid();
        }
        String json = MAPPER.writeValueAsString(node);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PROPERTIES_BYTES) {
            throw invalid();
        }
        return json;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) {
            throw invalid();
        }
        return node.stringValue();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.ANALYTICS_INVALID_REQUEST);
    }

    private static BusinessException occurredAtInvalid() {
        return new BusinessException(ErrorCode.ANALYTICS_OCCURRED_AT_INVALID);
    }

    /** 속성 규칙. values가 있으면 허용 문자열, 없으면 min~max JSON 정수다. */
    private record Prop(String name, boolean required, Set<String> values, int min, int max) {

        static Prop oneOf(String name, boolean required, List<String> values) {
            return new Prop(name, required, Set.copyOf(values), 0, 0);
        }

        static Prop range(String name, int min, int max) {
            return new Prop(name, true, null, min, max);
        }

        boolean accepts(JsonNode value) {
            return values != null
                    ? value.isString() && values.contains(value.stringValue())
                    : value.isInt() && value.intValue() >= min && value.intValue() <= max;
        }
    }
}
