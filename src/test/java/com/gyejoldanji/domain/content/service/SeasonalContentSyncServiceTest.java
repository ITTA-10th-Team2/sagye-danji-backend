package com.gyejoldanji.domain.content.service;

import com.gyejoldanji.domain.content.client.SeasonalContentSheetClient;
import com.gyejoldanji.domain.content.client.model.SeasonalContentSheetRow;
import com.gyejoldanji.domain.content.client.model.SheetSyncStatus;
import com.gyejoldanji.domain.content.config.SeasonalContentSyncProperties;
import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.enums.ContentCategory;
import com.gyejoldanji.domain.content.enums.OptimalPeriod;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeasonalContentSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");
    private static final String CONTENT_CODE = "ffb930c2-ac8a-409e-b0d6-1aaf30216161";

    @Mock
    private SeasonalContentSheetClient sheetClient;

    @Mock
    private SeasonalContentRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private SeasonalContentSyncService service;

    @BeforeEach
    void setUp() {
        SeasonalContentSyncProperties properties = new SeasonalContentSyncProperties();
        properties.setProcessingTimeoutMinutes(10);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        service = new SeasonalContentSyncService(
                sheetClient,
                repository,
                new TransactionTemplate(transactionManager),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesCodeAndCreatesContentForReadyRow() {
        when(sheetClient.readRows()).thenReturn(List.of(row(2, "", "AUTUMN", "FOOD", "TRUE", SheetSyncStatus.READY, null)));
        when(repository.findByContentCode(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SeasonalContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.synchronize();

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SeasonalContent> contentCaptor = ArgumentCaptor.forClass(SeasonalContent.class);
        verify(sheetClient).writeContentCode(eq(2), codeCaptor.capture());
        verify(repository).save(contentCaptor.capture());
        verify(sheetClient).markProcessing(2, NOW);
        verify(sheetClient).markSynced(eq(2), isNull(), eq(NOW));
        assertThat(contentCaptor.getValue().getContentCode()).isEqualTo(codeCaptor.getValue());
        assertThat(contentCaptor.getValue().isActive()).isTrue();
    }

    @Test
    void updatesAndDeactivatesExistingContent() {
        SeasonalContent content = SeasonalContent.create(
                CONTENT_CODE, SeasonType.SPRING, ContentCategory.SCENERY,
                "단풍", "20261001", "20261130",
                OptimalPeriod.PEAK, "전국", "산림청", "20260918",
                "기존 제목", "기존 설명");
        when(sheetClient.readRows()).thenReturn(List.of(
                row(3, CONTENT_CODE, "WINTER", "ACTIVITY_LIFESTYLE", "FALSE", SheetSyncStatus.READY, null)));
        when(repository.findByContentCode(CONTENT_CODE)).thenReturn(Optional.of(content));

        service.synchronize();

        assertThat(content.getSeason()).isEqualTo(SeasonType.WINTER);
        assertThat(content.getCategory()).isEqualTo(ContentCategory.ACTIVITY_LIFESTYLE);
        assertThat(content.getTitle()).isEqualTo("새 제목");
        assertThat(content.isActive()).isFalse();
        verify(repository, never()).save(any());
        verify(sheetClient).markSynced(eq(3), isNull(), eq(NOW));
    }

    @Test
    void marksInvalidRowFailedAndContinuesWithNextRow() {
        SeasonalContent validContent = SeasonalContent.create(
                CONTENT_CODE, SeasonType.AUTUMN, ContentCategory.FOOD,
                "단감", "20261001", "20261130",
                OptimalPeriod.PEAK, "전국", "농촌진흥청", "20260918",
                "기존", "기존 설명");
        SeasonalContentSheetRow invalid = row(
                4, "a6dd261b-a7af-43ec-9321-e41916943657", "RAINY", "FOOD", "TRUE", SheetSyncStatus.READY, null);
        SeasonalContentSheetRow valid = row(
                5, CONTENT_CODE, "AUTUMN", "FOOD", "TRUE", SheetSyncStatus.READY, null);
        when(sheetClient.readRows()).thenReturn(List.of(invalid, valid));
        when(repository.findByContentCode(CONTENT_CODE)).thenReturn(Optional.of(validContent));

        service.synchronize();

        verify(sheetClient).markFailed(4, "계절 값이 지원되지 않습니다.");
        verify(sheetClient).markSynced(eq(5), isNull(), eq(NOW));
    }

    @Test
    void recoversExpiredProcessingRowWithoutWaiting() {
        SeasonalContent content = SeasonalContent.create(
                CONTENT_CODE, SeasonType.AUTUMN, ContentCategory.FOOD,
                "단감", "20261001", "20261130",
                OptimalPeriod.PEAK, "전국", "농촌진흥청", "20260918",
                "기존", "기존 설명");
        when(sheetClient.readRows()).thenReturn(List.of(row(
                6, CONTENT_CODE, "AUTUMN", "FOOD", "TRUE",
                SheetSyncStatus.PROCESSING, NOW.minusSeconds(601))));
        when(repository.findByContentCode(CONTENT_CODE)).thenReturn(Optional.of(content));

        service.synchronize();

        verify(sheetClient).markReady(6);
        verify(sheetClient).markProcessing(6, NOW);
        verify(sheetClient).markSynced(eq(6), isNull(), eq(NOW));
    }

    @Test
    void doesNotProcessRecentProcessingRow() {
        when(sheetClient.readRows()).thenReturn(List.of(row(
                7, CONTENT_CODE, "AUTUMN", "FOOD", "TRUE",
                SheetSyncStatus.PROCESSING, NOW.minusSeconds(60))));

        service.synchronize();

        verify(sheetClient, never()).markReady(7);
        verify(repository, never()).findByContentCode(anyString());
    }

    @Test
    void rejectsInvalidAvailableDateBeforeDatabaseAccess() {
        SeasonalContentSheetRow invalidDateRow = new SeasonalContentSheetRow(
                9, CONTENT_CODE, "AUTUMN", "SCENERY", "단풍",
                "20261301", "20261130", "PEAK", "전국", "산림청",
                "20260918", "단풍 보기", "단풍을 감상해요.", "TRUE",
                SheetSyncStatus.READY, null, null, null, "");
        when(sheetClient.readRows()).thenReturn(List.of(invalidDateRow));

        service.synchronize();

        verify(sheetClient).markFailed(9, "사용 가능 시작일은 yyyy-MM-dd 또는 yyyyMMdd 형식이어야 합니다.");
        verify(repository, never()).findByContentCode(anyString());
    }

    @Test
    void acceptsCalendarDatesAndNormalizesThemForDatabase() {
        SeasonalContentSheetRow calendarDateRow = new SeasonalContentSheetRow(
                10, CONTENT_CODE, "AUTUMN", "FOOD", "단감",
                "2026-10-01", "2026-11-30", "PEAK", "전국", "농촌진흥청",
                "2026-09-18", "단감 맛보기", "제철 단감을 맛봐요.", "TRUE",
                SheetSyncStatus.READY, null, null, null, "");
        when(sheetClient.readRows()).thenReturn(List.of(calendarDateRow));
        when(repository.findByContentCode(CONTENT_CODE)).thenReturn(Optional.empty());
        when(repository.save(any(SeasonalContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.synchronize();

        ArgumentCaptor<SeasonalContent> contentCaptor = ArgumentCaptor.forClass(SeasonalContent.class);
        verify(repository).save(contentCaptor.capture());
        assertThat(contentCaptor.getValue().getAvailableStartDate()).isEqualTo("20261001");
        assertThat(contentCaptor.getValue().getAvailableEndDate()).isEqualTo("20261130");
        assertThat(contentCaptor.getValue().getSourceCheckedDate()).isEqualTo("20260918");
    }

    @Test
    void acceptsBlankOptionalDates() {
        SeasonalContentSheetRow blankDateRow = new SeasonalContentSheetRow(
                11, CONTENT_CODE, "AUTUMN", "FOOD", "단감",
                "", "", "PEAK", "전국", "농촌진흥청",
                "", "단감 맛보기", "제철 단감을 맛봐요.", "TRUE",
                SheetSyncStatus.READY, null, null, null, "");
        when(sheetClient.readRows()).thenReturn(List.of(blankDateRow));
        when(repository.findByContentCode(CONTENT_CODE)).thenReturn(Optional.empty());
        when(repository.save(any(SeasonalContent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.synchronize();

        ArgumentCaptor<SeasonalContent> contentCaptor = ArgumentCaptor.forClass(SeasonalContent.class);
        verify(repository).save(contentCaptor.capture());
        assertThat(contentCaptor.getValue().getAvailableStartDate()).isNull();
        assertThat(contentCaptor.getValue().getAvailableEndDate()).isNull();
        assertThat(contentCaptor.getValue().getSourceCheckedDate()).isNull();
    }

    @Test
    void retryAfterSheetResultFailureDoesNotInsertDuplicate() {
        AtomicReference<String> issuedCode = new AtomicReference<>();
        AtomicReference<SeasonalContent> storedContent = new AtomicReference<>();
        AtomicInteger readCount = new AtomicInteger();
        SeasonalContentSheetRow firstRow = row(8, "", "AUTUMN", "SCENERY", "TRUE", SheetSyncStatus.READY, null);

        when(sheetClient.readRows()).thenAnswer(invocation -> {
            if (readCount.getAndIncrement() == 0) {
                return List.of(firstRow);
            }
            return List.of(row(8, issuedCode.get(), "AUTUMN", "SCENERY", "TRUE", SheetSyncStatus.READY, null));
        });
        doAnswer(invocation -> {
            issuedCode.set(invocation.getArgument(1));
            return null;
        }).when(sheetClient).writeContentCode(eq(8), anyString());
        when(repository.findByContentCode(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(storedContent.get()));
        when(repository.save(any(SeasonalContent.class))).thenAnswer(invocation -> {
            SeasonalContent content = invocation.getArgument(0);
            storedContent.set(content);
            return content;
        });
        doThrow(new IllegalStateException("sheet unavailable"))
                .doNothing()
                .when(sheetClient).markSynced(eq(8), isNull(), eq(NOW));

        service.synchronize();
        service.synchronize();

        verify(repository, times(1)).save(any(SeasonalContent.class));
        verify(sheetClient, times(2)).markSynced(eq(8), isNull(), eq(NOW));
        verify(sheetClient).markFailed(8, "동기화 처리 중 오류가 발생했습니다.");
    }

    private SeasonalContentSheetRow row(
            int rowNumber,
            String contentCode,
            String season,
            String category,
            String active,
            SheetSyncStatus status,
            Instant processingStartedAt
    ) {
        return new SeasonalContentSheetRow(
                rowNumber,
                contentCode,
                season,
                category,
                "단풍",
                "20261001",
                "20261130",
                "PEAK",
                "전국",
                "산림청",
                "20260918",
                "새 제목",
                "새 설명",
                active,
                status,
                processingStartedAt,
                null,
                null,
                "");
    }
}
