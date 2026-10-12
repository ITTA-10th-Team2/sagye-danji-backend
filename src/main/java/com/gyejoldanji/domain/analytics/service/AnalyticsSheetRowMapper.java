package com.gyejoldanji.domain.analytics.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** DB 원본을 analytics_events 시트의 A~W 열로 바꾼다. properties_json은 재직렬화하지 않는다. */
@Component
@RequiredArgsConstructor
public class AnalyticsSheetRowMapper {

    public static final List<String> HEADERS = List.of(
            "event_id", "user_key", "session_id", "event_time", "received_at", "service_version", "os", "event_name",
            "event_source", "sequence", "target", "source", "photo_count", "record_id", "season", "previous_screen",
            "source_screen", "method", "screen_name", "duration_ms", "jar_page_id", "sticker_count", "properties_json");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.ROOT);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;

    public List<Object> toRow(AnalyticsEvent event, String secret) {
        JsonNode properties = objectMapper.readTree(event.getProperties());
        return List.of(
                event.getEventId(), AnalyticsUserKey.generate(secret, event.getMemberId()), cell(event.getSessionId()),
                time(event.getOccurredAt()), time(event.getReceivedAt()), cell(event.getServiceVersion()),
                event.getOs() == null ? "" : event.getOs().name(), event.getEventName(), event.getEventSource().name(),
                cell(event.getSequence()), property(properties, "target"), property(properties, "source"),
                property(properties, "photoCount"), property(properties, "recordId"), property(properties, "season"),
                property(properties, "previousScreen"), property(properties, "sourceScreen"), property(properties, "method"),
                property(properties, "screenName"), property(properties, "durationMs"), property(properties, "jarPageId"),
                property(properties, "stickerCount"), event.getProperties());
    }

    private static Object cell(Object value) {
        return value == null ? "" : value;
    }

    private static Object property(JsonNode properties, String name) {
        JsonNode value = properties.get(name);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isNumber() ? value.numberValue() : value.stringValue();
    }

    private static String time(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(KST).format(TIME_FORMAT);
    }
}
