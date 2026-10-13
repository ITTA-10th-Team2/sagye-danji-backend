package com.gyejoldanji.domain.analytics.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.enums.ServerEventName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSheetRowMapperTest {

    private static final String SECRET = "sheet-sync-test-secret-0123456789";
    private static final String EVENT_ID = "3f1c2b4a-8d7e-4f60-9a1b-2c3d4e5f6a7b";
    private static final String SESSION = "9b2e7c1d-4a3f-4e8b-8c6d-1f2a3b4c5d6e";
    private static final LocalDateTime OCCURRED = LocalDateTime.parse("2026-10-11T14:03:48.123456");
    private static final LocalDateTime RECEIVED = LocalDateTime.parse("2026-10-11T14:03:49.654321");

    private final AnalyticsSheetRowMapper mapper = new AnalyticsSheetRowMapper(JsonMapper.builder().build());

    @Test
    void hasTheTwentyFiveContractHeadersInOrder() {
        assertThat(AnalyticsSheetRowMapper.HEADERS).containsExactly(
                "event_id", "user_key", "session_id", "event_time", "received_at", "service_version", "os", "event_name",
                "event_source", "sequence", "target", "source", "photo_count", "record_id", "season", "previous_screen",
                "source_screen", "method", "screen_name", "duration_ms", "jar_page_id", "sticker_count", "properties_json",
                "referrer", "share_screen");
    }

    /** 현재 클라이언트 11종·서버 4종의 속성 위치와 비어 있어야 하는 칸을 함께 확인한다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void mapsEachEventToItsContractColumns(Enum<?> eventName, String properties, Map<Integer, Object> expectedCells) {
        AnalyticsEvent event = eventName instanceof ClientEventName client
                ? client(client, properties)
                : AnalyticsEvent.server(42L, (ServerEventName) eventName, SESSION, "1.0.0", ClientOs.ANDROID,
                        OCCURRED, properties);

        List<Object> row = mapper.toRow(event, SECRET);

        assertThat(row).hasSize(25);
        assertThat(row.subList(0, 10)).containsExactly(event.getEventId(), "e696ad3cca79513b", SESSION,
                "2026-10-11T23:03:48.123456+09:00",
                eventName instanceof ClientEventName ? "2026-10-11T23:03:49.654321+09:00"
                        : "2026-10-11T23:03:48.123456+09:00",
                "1.0.0", "ANDROID", eventName.name(), eventName instanceof ClientEventName ? "CLIENT" : "SERVER",
                eventName instanceof ClientEventName ? 3 : "");
        List<Object> expectedProperties = new ArrayList<>(Collections.nCopies(15, ""));
        expectedProperties.set(12, properties);
        expectedCells.forEach((index, value) -> expectedProperties.set(index - 10, value));
        assertThat(row.subList(10, 25)).containsExactlyElementsOf(expectedProperties);
    }

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of(ClientEventName.SESSION_START, "{}", Map.of()),
                Arguments.of(ClientEventName.SESSION_START, "{\"referrer\":\"external__owned_social\"}",
                        Map.of(23, "external__owned_social")),
                Arguments.of(ClientEventName.SESSION_START, "{\"shareScreen\":\"JAR\"}", Map.of(24, "JAR")),
                Arguments.of(ClientEventName.SESSION_START, "{\"referrer\":\"external_share\",\"shareScreen\":\"JAR\"}",
                        Map.of(23, "external_share", 24, "JAR")),
                Arguments.of(ClientEventName.ONBOARDING_START, "{}", Map.of()),
                Arguments.of(ClientEventName.ONBOARDING_SKIP, "{}", Map.of()),
                Arguments.of(ClientEventName.HOME_VIEW, "{\"entrySource\":\"APP_OPEN\"}", Map.of()),
                Arguments.of(ClientEventName.HOME_ELEMENT_TAP, "{\"target\":\"SPEECH_BUBBLE\"}",
                        Map.of(10, "SPEECH_BUBBLE")),
                Arguments.of(ClientEventName.RECORD_SCREEN_VIEW, "{}", Map.of()),
                Arguments.of(ClientEventName.RECORD_START, "{\"source\":\"GALLERY\"}", Map.of(11, "GALLERY")),
                Arguments.of(ClientEventName.JAR_VIEW, "{\"previousScreen\":\"HOME\"}", Map.of(15, "HOME")),
                Arguments.of(ClientEventName.LOCKED_SEASON_CLICK, "{\"season\":\"WINTER\"}", Map.of(14, "WINTER")),
                Arguments.of(ClientEventName.SHARE_CLICK, "{\"sourceScreen\":\"JAR\",\"method\":\"COPY_LINK\"}",
                        Map.of(16, "JAR", 17, "COPY_LINK")),
                Arguments.of(ClientEventName.SCREEN_ENGAGEMENT, "{\"screenName\":\"HOME\",\"durationMs\":86400000}",
                        Map.of(18, "HOME", 19, 86400000)),
                Arguments.of(ServerEventName.ONBOARDING_COMPLETE, "{}", Map.of()),
                Arguments.of(ServerEventName.RECORD_COMPLETE, "{\"recordId\":101,\"photoCount\":1}", Map.of(12, 1, 13, 101)),
                Arguments.of(ServerEventName.RECORD_DELETE, "{\"recordId\":101}", Map.of(13, 101)),
                Arguments.of(ServerEventName.STICKER_SAVE, "{\"jarPageId\":31,\"stickerCount\":0}", Map.of(20, 31, 21, 0)));
    }

    @Test
    void writesNullContextAndNullPropertiesAsBlankCells() {
        AnalyticsEvent event = AnalyticsEvent.server(42L, ServerEventName.RECORD_DELETE, null, null, null, OCCURRED,
                "{\"recordId\":null}");

        List<Object> row = mapper.toRow(event, SECRET);

        assertThat(row).doesNotContainNull();
        assertThat(List.of(row.get(2), row.get(5), row.get(6), row.get(9), row.get(13))).containsOnly("");
    }

    @Test
    void writesFixedSixMicrosecondDigitsAndKstDateRollover() {
        AnalyticsEvent event = AnalyticsEvent.client(42L, EVENT_ID, SESSION, ClientEventName.ONBOARDING_START,
                LocalDateTime.parse("2026-12-31T15:00:00.000001"), LocalDateTime.parse("2027-01-01T00:01:02"),
                "1.0.0", ClientOs.ANDROID, null, "{}");

        assertThat(mapper.toRow(event, SECRET).subList(3, 5))
                .containsExactly("2027-01-01T00:00:00.000001+09:00", "2027-01-01T09:01:02.000000+09:00");
    }

    @Test
    void preservesOriginalJsonAndLeavesEntrySourceOnlyInJson() {
        String original = "{ \"entrySource\" : \"APP_OPEN\" }";

        List<Object> row = mapper.toRow(client(ClientEventName.HOME_VIEW, original), SECRET);

        assertThat(row.subList(10, 22)).containsOnly("");
        assertThat(row.get(22)).isEqualTo(original);
    }

    @Test
    void preservesLongNumericPropertiesAsNumbers() {
        AnalyticsEvent event = AnalyticsEvent.server(42L, ServerEventName.RECORD_DELETE, null, null, null, OCCURRED,
                "{\"recordId\":2147483648}");

        assertThat(mapper.toRow(event, SECRET).get(13)).isEqualTo(2147483648L);
    }

    private static AnalyticsEvent client(ClientEventName name, String properties) {
        return AnalyticsEvent.client(42L, EVENT_ID, SESSION, name, OCCURRED, RECEIVED, "1.0.0", ClientOs.ANDROID,
                3, properties);
    }
}
