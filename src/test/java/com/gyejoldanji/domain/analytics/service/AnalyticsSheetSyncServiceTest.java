package com.gyejoldanji.domain.analytics.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gyejoldanji.domain.analytics.config.AnalyticsSheetSyncProperties;
import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.enums.ClientEventName;
import com.gyejoldanji.domain.analytics.enums.ClientOs;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 실제 시트 대신 메모리의 셀을 써서 중단 후 재시도와 정확한 쓰기 범위를 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsSheetSyncServiceTest {

    private static final String SECRET = "test-only-analytics-secret-32-characters";
    private static final long MEMBER_ID = 8675309L;
    private static final Instant NOW = Instant.parse("2026-10-12T00:01:02.123456789Z");
    private final AnalyticsEventRepository repository = mock(AnalyticsEventRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final MemoryGateway gateway = new MemoryGateway();
    private final AnalyticsSheetSyncProperties properties = new AnalyticsSheetSyncProperties();
    private final Map<Long, AnalyticsEvent> events = new LinkedHashMap<>();
    private final Set<Long> marked = new HashSet<>();
    private AnalyticsSheetSyncService service;
    private boolean markingFails;

    @BeforeEach
    void setUp() {
        properties.setSpreadsheetId("test-spreadsheet");
        properties.setUserKeySecret(SECRET);
        service = new AnalyticsSheetSyncService(repository, gateway,
                new AnalyticsSheetRowMapper(JsonMapper.builder().build()), properties,
                Clock.fixed(NOW, ZoneOffset.UTC), transactions);
        when(repository.findPendingForSheet(anyInt())).thenAnswer(call -> events.values().stream()
                .filter(event -> !marked.contains(event.getId())).limit(call.<Integer>getArgument(0)).toList());
        when(repository.existsBySheetSyncedAtIsNull()).thenAnswer(call -> marked.size() < events.size());
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            assertThat(call.<TransactionDefinition>getArgument(0).getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(inTransaction.getAndSet(true)).isFalse();
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { inTransaction.set(false); return null; }).when(transactions).commit(any());
        doAnswer(call -> { inTransaction.set(false); return null; }).when(transactions).rollback(any());
        when(repository.markSheetSynced(any(), any())).thenAnswer(call -> {
            assertThat(inTransaction.get()).isTrue();
            assertThat(call.<Instant>getArgument(1)).isEqualTo(Instant.parse("2026-10-12T00:01:02.123456Z"));
            if (markingFails) {
                throw new IllegalStateException("sensitive " + SECRET + " " + MEMBER_ID);
            }
            Collection<Long> ids = call.getArgument(0);
            marked.addAll(ids);
            return ids.size();
        });
    }

    @Test
    void writesHeaderAndRowsThenMarksAndSecondRunDoesNotWrite(CapturedOutput output) {
        AnalyticsEvent event = addEvent(1);
        service.synchronize();
        service.synchronize();

        assertThat(gateway.writes).containsExactly("'analytics_events'!A1:AA1", "'analytics_events'!A2:AA2");
        assertThat(gateway.rows.get(0)).containsExactlyElementsOf(AnalyticsSheetRowMapper.HEADERS);
        assertThat(gateway.rows.get(1).get(0)).isEqualTo(event.getEventId());
        assertThat(marked).containsExactly(1L);
        assertThat(output.getAll()).contains("written=1, existing=0, pending=false", "written=0, existing=0, pending=false");
        verify(transactions, times(1)).commit(any());
    }

    @Test
    void unrelatedHeaderStopsBeforeAnyMutationAndWarns(CapturedOutput output) {
        gateway.columns = 23;
        gateway.rows.add(List.of("unrelated_header"));
        addEvent(1);

        service.synchronize();

        assertThat(gateway.readRanges).containsExactly("'analytics_events'!A:A");
        assertThat(gateway.writes).isEmpty();
        assertThat(gateway.columnCapacityRequests).isEmpty();
        assertThat(gateway.capacityRequests).isEmpty();
        assertThat(gateway.columns).isEqualTo(23);
        assertThat(gateway.rows).containsExactly(List.of("unrelated_header"));
        assertThat(marked).isEmpty();
        verifyNoInteractions(repository, transactions);
        assertThat(output.getAll()).contains("WARN", "A1 헤더 불일치")
                .doesNotContain("unrelated_header", SECRET, "동기화 완료");
    }

    @ParameterizedTest
    @ValueSource(ints = {23, 25})
    void replacesOnlyLegacyHeaderAndPreservesEveryExistingDataCell(int columns) {
        List<Object> legacyHeader = new ArrayList<>(AnalyticsSheetRowMapper.HEADERS.subList(0, columns));
        List<Object> legacyRow = new ArrayList<>();
        legacyRow.add(addEvent(1).getEventId());
        for (int column = 1; column < columns; column++) {
            legacyRow.add("existing-cell-" + column);
        }
        gateway.rows.add(legacyHeader);
        gateway.rows.add(new ArrayList<>(legacyRow));
        service.synchronize();

        assertThat(gateway.writes).containsExactly("'analytics_events'!A1:AA1");
        assertThat(gateway.rows).containsExactly(new ArrayList<>(AnalyticsSheetRowMapper.HEADERS), legacyRow);
        assertThat(gateway.rows.get(1)).hasSize(columns);
        assertThat(marked).containsExactly(1L);
    }

    @Test
    void matchingHeaderIsReadEveryRunWithoutWritingWhenNoEventsArePending() {
        gateway.columns = 27;
        gateway.header();
        service.synchronize();
        service.synchronize();

        assertThat(gateway.writes).isEmpty();
        assertThat(gateway.columns).isEqualTo(27);
        assertThat(gateway.readRanges).containsExactly("'analytics_events'!A:A", "'analytics_events'!A1:AA1",
                "'analytics_events'!A:A", "'analytics_events'!A1:AA1");
        verifyNoInteractions(transactions);
    }

    @Test
    void repairsEditedHeaderOnNextRunWithoutTouchingData() {
        gateway.header();
        List<Object> data = List.of("existing-event", "unchanged-value");
        gateway.rows.add(data);
        service.synchronize();
        gateway.rows.getFirst().set(23, "edited-referrer");
        service.synchronize();

        assertThat(gateway.writes).containsExactly("'analytics_events'!A1:AA1");
        assertThat(gateway.rows).containsExactly(new ArrayList<>(AnalyticsSheetRowMapper.HEADERS), data);
        verifyNoInteractions(transactions);
    }

    @ParameterizedTest
    @ValueSource(ints = {23, 24, 25, 26})
    void growsNarrowGridBeforeReadingAndWritingHeader(int columns) {
        gateway.columns = columns;
        gateway.rows.add(new ArrayList<>(AnalyticsSheetRowMapper.HEADERS.subList(0, 23)));
        service.synchronize();

        assertThat(gateway.columnCapacityRequests).containsExactly(27);
        assertThat(gateway.columns).isEqualTo(27);
        assertThat(gateway.writes).containsExactly("'analytics_events'!A1:AA1");
        assertThat(gateway.rows.getFirst()).containsExactlyElementsOf(AnalyticsSheetRowMapper.HEADERS);
    }

    @Test
    void eventAlreadyOnSheetOnlyGetsMarked() {
        AnalyticsEvent event = addEvent(1);
        gateway.header();
        gateway.rows.add(List.of(event.getEventId()));
        service.synchronize();

        assertThat(gateway.writes).isEmpty();
        assertThat(gateway.capacityRequests).isEmpty();
        assertThat(marked).containsExactly(1L);
    }

    @Test
    void batchRepeatsAndStopsAtConfiguredLimitThenResumes(CapturedOutput output) {
        properties.setBatchSize("2");
        properties.setMaxBatchesPerRun("2");
        for (int id = 1; id <= 5; id++) {
            addEvent(id);
        }
        service.synchronize();
        assertThat(marked).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(gateway.writes).containsExactly("'analytics_events'!A1:AA1", "'analytics_events'!A2:AA3",
                "'analytics_events'!A4:AA5");
        assertThat(output.getAll()).contains("written=4, existing=0, pending=true");

        service.synchronize();
        assertThat(marked).hasSize(5);
        assertThat(gateway.rows).hasSize(6);
        assertThat(gateway.writes.get(3)).isEqualTo("'analytics_events'!A6:AA6");
    }

    @Test
    void growsGridBeforeHeaderAndBeforeDataWrite() {
        gateway.capacity = 0;
        addEvent(1);
        addEvent(2);
        service.synchronize();

        assertThat(gateway.capacityRequests).containsExactly(1, 3);
        assertThat(gateway.capacity).isEqualTo(3);
        assertThat(marked).hasSize(2);
    }

    @Test
    void preservesInteriorBlankRowsAndEscapesQuotedSheetName() {
        properties.setSheetName("team's events");
        gateway.header();
        gateway.rows.add(List.of());
        gateway.rows.add(List.of("existing-event"));
        addEvent(1);
        service.synchronize();

        assertThat(gateway.readRanges).containsExactly("'team''s events'!A:A", "'team''s events'!A1:AA1");
        assertThat(gateway.writes).containsExactly("'team''s events'!A4:AA4");
        assertThat(gateway.rows.get(1)).isEmpty();
        assertThat(gateway.rows.get(2)).containsExactly("existing-event");
    }

    @Test
    void duplicateEventIdsWithinBatchWriteOneRowAndMarkBothRecords() {
        AnalyticsEvent first = addEvent(1);
        AnalyticsEvent second = addEvent(2);
        ReflectionTestUtils.setField(second, "memberId", MEMBER_ID + 1);
        ReflectionTestUtils.setField(second, "eventId", first.getEventId());
        service.synchronize();

        assertThat(gateway.rows).hasSize(2);
        assertThat(marked).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void duplicateEventIdsAcrossBatchesWriteOneRow() {
        properties.setBatchSize("1");
        AnalyticsEvent first = addEvent(1);
        ReflectionTestUtils.setField(addEvent(2), "eventId", first.getEventId());
        service.synchronize();

        assertThat(gateway.rows).hasSize(2);
        assertThat(gateway.writes).hasSize(2);
        assertThat(marked).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void failedWriteDoesNotMarkEvenExistingIdsInSameBatchAndStops(CapturedOutput output) {
        AnalyticsEvent first = addEvent(1);
        addEvent(2);
        gateway.header();
        gateway.rows.add(List.of(first.getEventId()));
        gateway.failDataWrite = true;
        assertThatNoException().isThrownBy(service::synchronize);

        assertThat(marked).isEmpty();
        verifyNoInteractions(transactions);
        verify(repository, times(1)).findPendingForSheet(500);
        assertPrivateFailure(output);
    }

    @Test
    void lostWriteResponseIsRecoveredByReadingEventIdsNextRun() {
        addEvent(1);
        gateway.persistThenFail = true;
        service.synchronize();
        assertThat(marked).isEmpty();
        assertThat(gateway.rows).hasSize(2);

        gateway.persistThenFail = false;
        service.synchronize();
        assertThat(gateway.rows).hasSize(2);
        assertThat(gateway.writes).hasSize(2);
        assertThat(marked).containsExactly(1L);
    }

    @Test
    void databaseMarkFailureAfterSheetWriteRecoversWithoutDuplicateRows(CapturedOutput output) {
        addEvent(1);
        markingFails = true;
        service.synchronize();
        assertThat(marked).isEmpty();
        assertThat(gateway.rows).hasSize(2);
        verify(transactions).rollback(any());
        assertPrivateFailure(output);

        markingFails = false;
        service.synchronize();
        assertThat(marked).containsExactly(1L);
        assertThat(gateway.rows).hasSize(2);
        assertThat(gateway.writes).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"read", "header", "capacity", "columns", "headerRead"})
    void failureBeforeDataWriteStopsWithoutMarking(String failure, CapturedOutput output) {
        addEvent(1);
        gateway.failAt = failure;
        assertThatNoException().isThrownBy(service::synchronize);
        assertThat(marked).isEmpty();
        verifyNoInteractions(repository, transactions);
        assertPrivateFailure(output);
    }

    @ParameterizedTest
    @CsvSource({"spreadsheetId, ''", "sheetName, ' '", "userKeySecret, ''", "userKeySecret, too-short",
            "batchSize, 0", "batchSize, 1001", "batchSize, bad", "maxBatchesPerRun, 0",
            "maxBatchesPerRun, bad", "syncCron, bad", "syncZone, invalid-zone"})
    void invalidConfigurationSkipsWithoutExternalCallsOrPrivateValues(String field, String value,
                                                                                    CapturedOutput output) {
        ReflectionTestUtils.setField(properties, field, value);
        assertThatNoException().isThrownBy(service::synchronize);

        verifyNoInteractions(repository, transactions);
        assertThat(gateway.readRanges).isEmpty();
        assertThat(gateway.columnCapacityRequests).isEmpty();
        assertPrivateFailure(output);
        assertThat(output.getAll()).doesNotContain("too-short", "length=", "invalid-zone", "test-spreadsheet");
    }

    private void assertPrivateFailure(CapturedOutput output) {
        assertThat(output.getAll()).contains("WARN", "type=", "written=", "existing=")
                .doesNotContain(SECRET, Long.toString(MEMBER_ID), AnalyticsUserKey.generate(SECRET, MEMBER_ID),
                        "sensitive", "동기화 완료");
    }

    private AnalyticsEvent addEvent(long id) {
        AnalyticsEvent event = AnalyticsEvent.client(MEMBER_ID, UUID.randomUUID().toString(), null,
                ClientEventName.HOME_VIEW, LocalDateTime.parse("2026-10-12T00:00:00"),
                LocalDateTime.parse("2026-10-12T00:00:01"), null, ClientOs.ANDROID, 1,
                "{\"entrySource\":\"APP_OPEN\"}");
        ReflectionTestUtils.setField(event, "id", id);
        events.put(id, event);
        return event;
    }

    private class MemoryGateway extends GoogleSheetsGateway {
        private final List<List<Object>> rows = new ArrayList<>();
        private final List<String> readRanges = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();
        private final List<Integer> capacityRequests = new ArrayList<>();
        private final List<Integer> columnCapacityRequests = new ArrayList<>();
        private int capacity = 1000;
        private int columns = 26;
        private String failAt = "";
        private boolean failDataWrite;
        private boolean persistThenFail;

        MemoryGateway() {
            super(null);
        }

        void header() {
            rows.add(new ArrayList<>(AnalyticsSheetRowMapper.HEADERS));
        }

        @Override
        public List<List<Object>> readValues(String spreadsheetId, String range) {
            assertThat(inTransaction.get()).isFalse();
            readRanges.add(range);
            if (range.endsWith("A1:AA1")) {
                assertThat(columns).isGreaterThanOrEqualTo(27);
                failIf("headerRead");
                return rows.isEmpty() ? List.of() : List.of(new ArrayList<>(rows.getFirst()));
            }
            assertThat(range).endsWith("A:A");
            failIf("read");
            return rows.stream().map(row -> row.isEmpty() ? List.<Object>of() : List.of(row.get(0))).toList();
        }

        @Override
        public void ensureColumnCapacity(String spreadsheetId, String sheetName, int requiredColumns) {
            assertThat(inTransaction.get()).isFalse();
            failIf("columns");
            columnCapacityRequests.add(requiredColumns);
            columns = Math.max(columns, requiredColumns);
        }

        @Override
        public void ensureRowCapacity(String spreadsheetId, String sheetName, int requiredRows) {
            assertThat(inTransaction.get()).isFalse();
            failIf("capacity");
            capacityRequests.add(requiredRows);
            capacity = Math.max(capacity, requiredRows);
        }

        @Override
        public void batchUpdateValues(String spreadsheetId, Map<String, List<List<Object>>> valuesByRange) {
            assertThat(inTransaction.get()).isFalse();
            assertThat(valuesByRange).hasSize(1);
            valuesByRange.forEach((range, values) -> {
                Matcher matcher = Pattern.compile(".*!A(\\d+):AA(\\d+)").matcher(range);
                assertThat(matcher.matches()).isTrue();
                int start = Integer.parseInt(matcher.group(1));
                int end = Integer.parseInt(matcher.group(2));
                assertThat(values).hasSize(end - start + 1);
                assertThat(end).isLessThanOrEqualTo(capacity);
                assertThat(columns).isGreaterThanOrEqualTo(27);
                if (start == 1) {
                    failIf("header");
                } else if (failDataWrite) {
                    throw failure();
                }
                writes.add(range);
                while (rows.size() < end) {
                    rows.add(List.of());
                }
                for (int i = 0; i < values.size(); i++) {
                    assertThat(values.get(i)).hasSize(27);
                    rows.set(start - 1 + i, new ArrayList<>(values.get(i)));
                }
                if (start != 1 && persistThenFail) {
                    throw failure();
                }
            });
        }

        private void failIf(String stage) {
            if (failAt.equals(stage)) {
                throw failure();
            }
        }

        private RuntimeException failure() {
            return new IllegalStateException("sensitive " + SECRET + " " + MEMBER_ID);
        }
    }
}
