package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.dto.RecordImageResponse;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordSummaryResponse;
import com.gyejoldanji.domain.record.dto.SeasonRecordCursorPageResponse;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 기록 목록 keyset 페이지와 상세 소유권 조회를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecordQueryServiceTest {

    @Mock
    private RecordRepository recordRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageService imageStorageService;
    @Mock
    private RecordCursorCodec cursorCodec;
    @Mock
    private Clock clock;
    @InjectMocks
    private RecordQueryService service;

    @Test
    void summaryCountsFirstRecordDayInclusivelyInSeoul() {
        RecordRepository.RecordSummaryProjection projection = summary(3, LocalDate.of(2026, 9, 24));
        when(recordRepository.summarizeByMemberId(42L)).thenReturn(projection);
        when(clock.withZone(ZoneId.of("Asia/Seoul")))
                .thenReturn(Clock.fixed(Instant.parse("2026-10-04T15:30:00Z"), ZoneId.of("Asia/Seoul")));

        RecordSummaryResponse response = service.findSummary(42L);

        assertThat(response.recordCount()).isEqualTo(3);
        assertThat(response.recordingDayCount()).isEqualTo(12);
    }

    @Test
    void summaryReturnsOneOnFirstRecordDay() {
        RecordRepository.RecordSummaryProjection projection = summary(1, LocalDate.of(2026, 10, 5));
        when(recordRepository.summarizeByMemberId(42L)).thenReturn(projection);
        when(clock.withZone(ZoneId.of("Asia/Seoul")))
                .thenReturn(Clock.fixed(Instant.parse("2026-10-04T15:30:00Z"), ZoneId.of("Asia/Seoul")));

        assertThat(service.findSummary(42L).recordingDayCount()).isEqualTo(1);
    }

    @Test
    void summaryReturnsZerosWhenMemberHasNoRecord() {
        when(recordRepository.summarizeByMemberId(42L)).thenReturn(summary(0, null));

        assertThat(service.findSummary(42L)).isEqualTo(new RecordSummaryResponse(0, 0,
                java.util.Map.of(SeasonType.SPRING, 0L, SeasonType.SUMMER, 0L,
                        SeasonType.AUTUMN, 0L, SeasonType.WINTER, 0L)));
        verifyNoInteractions(clock);
    }

    @Test
    void seasonPageUsesRequestedSizeAndDecodedCursor() {
        RecordCursorCodec.Cursor cursor = new RecordCursorCodec.Cursor(LocalDate.of(2026, 10, 4), 101L);
        when(cursorCodec.decodeNullable("cursor")).thenReturn(cursor);
        when(recordRepository.findOwnedBySeasonInDateRangeAfter(eq(42L), eq(SeasonType.AUTUMN),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2027, 1, 1)),
                eq(cursor.recordDate()), eq(cursor.recordId()), any(Pageable.class)))
                .thenReturn(List.of());

        SeasonRecordCursorPageResponse response = service.findBySeason(
                42L, 2026, SeasonType.AUTUMN, "cursor", 7);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(recordRepository).findOwnedBySeasonInDateRangeAfter(eq(42L), eq(SeasonType.AUTUMN), any(), any(),
                any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(8);
        verifyNoInteractions(imageRepository);
    }

    @Test
    void seasonPageIncludesSingleImageWithoutAdditionalRecordQueries() {
        Record record = record(101L, LocalDate.of(2026, 10, 4));
        when(recordRepository.findOwnedBySeasonInDateRange(eq(42L), eq(SeasonType.AUTUMN),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2027, 1, 1)), any(Pageable.class)))
                .thenReturn(List.of(record));
        when(imageRepository.findAllByRecordIds(List.of(101L))).thenReturn(List.of(
                image(501L, record, 0, "record-images/501-thumb.jpg")));
        when(imageStorageService.issueViewUrl(any())).thenAnswer(invocation ->
                "https://storage.example/" + invocation.<String>getArgument(0));

        SeasonRecordCursorPageResponse response = service.findBySeason(
                42L, 2026, SeasonType.AUTUMN, null, 5);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().image().id()).isEqualTo(501L);
        assertThat(response.items().getFirst().image().thumbnailUrl())
                .isEqualTo("https://storage.example/record-images/501-thumb.jpg");
        verify(imageRepository).findAllByRecordIds(List.of(101L));
    }

    @Test
    void rejectsSeasonPageSizeOutsideSupportedRange() {
        assertThatThrownBy(() -> service.findBySeason(42L, 2026, SeasonType.AUTUMN, null, 51))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verifyNoInteractions(recordRepository, imageRepository, cursorCodec);
    }

    @Test
    void detailReturnsSingleImage() {
        Record record = record(101L, LocalDate.of(2026, 10, 4));
        when(recordRepository.findOwnedById(101L, 42L)).thenReturn(Optional.of(record));
        when(imageRepository.findAllByRecordIdOrderBySortOrderAsc(101L))
                .thenReturn(List.of(image(502L, record, 0, "record-images/502-thumb.jpg")));
        when(imageStorageService.issueViewUrl(any())).thenAnswer(invocation ->
                "https://storage.example/" + invocation.<String>getArgument(0));

        RecordResponse response = service.findDetail(42L, 101L);

        assertThat(response.image().id()).isEqualTo(502L);
        assertThat(response.image().originalUrl())
                .isEqualTo("https://storage.example/record-images/502.jpg");
        assertThat(response.image().thumbnailUrl())
                .isEqualTo("https://storage.example/record-images/502-thumb.jpg");
        verify(imageRepository).findAllByRecordIdOrderBySortOrderAsc(101L);
    }

    @Test
    void hidesWhetherAnotherMembersRecordExists() {
        when(recordRepository.findOwnedById(101L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDetail(42L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RECORD_NOT_FOUND));

        verify(imageRepository, never()).findAllByRecordIdOrderBySortOrderAsc(any());
    }

    private static Record record(Long id, LocalDate date) {
        Member member = Member.create("TOSS", "provider-user");
        ReflectionTestUtils.setField(member, "id", 42L);
        Record record = Record.create(member, date, SeasonType.AUTUMN, "memo");
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }

    private static Image image(Long id, Record record, int order) {
        return image(id, record, order, null);
    }

    private static Image image(Long id, Record record, int order, String thumbnailKey) {
        Image image = Image.create(record, "record-images/" + id + ".jpg", thumbnailKey,
                PhotoSource.CAMERA, order);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }

    private static RecordRepository.RecordSummaryProjection summary(long count, LocalDate firstDate) {
        return new RecordRepository.RecordSummaryProjection() {
            @Override
            public long getRecordCount() {
                return count;
            }

            @Override
            public LocalDate getFirstRecordDate() {
                return firstDate;
            }

            @Override public long getSpringCount() { return 0; }
            @Override public long getSummerCount() { return 0; }
            @Override public long getAutumnCount() { return count; }
            @Override public long getWinterCount() { return 0; }
        };
    }
}
