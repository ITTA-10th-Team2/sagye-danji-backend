package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.jar.service.JarPageAllocationService;
import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.record.dto.RecordCreateRequest;
import com.gyejoldanji.domain.record.dto.RecordImageResponse;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordUpdateRequest;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 기록 command의 소유권·계절·이미지 최종 상태와 사전 검증을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecordCommandServiceTest {

    @Mock
    private RecordRepository recordRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private JarPageAllocationService jarPageAllocationService;
    @Mock
    private JarPage jarPage;
    @Mock
    private ImageStorageService imageStorageService;

    private RecordCommandService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-03T16:30:00Z"), ZoneOffset.UTC);
        service = new RecordCommandService(
                recordRepository, imageRepository, memberRepository, jarPageAllocationService,
                imageStorageService, new SeasonResolver(), clock);
    }

    @Test
    void createsRecordWithKoreanDefaultDateAndServerSeason() {
        Member member = member(42L);
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(imageRepository.existsByOriginalKeyIn(any())).thenReturn(false);
        when(recordRepository.save(any())).thenAnswer(invocation -> {
            Record record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 101L);
            return record;
        });
        when(imageRepository.save(any())).thenAnswer(invocation -> {
            Image image = invocation.getArgument(0);
            ReflectionTestUtils.setField(image, "id", 501L);
            return image;
        });
        when(imageStorageService.issueViewUrl(any())).thenAnswer(invocation ->
                "https://storage.example/" + invocation.<String>getArgument(0));
        RecordCreateRequest request = new RecordCreateRequest(null, "가을밤 🍂", "record-images/42/a.jpg");

        RecordResponse response = service.create(42L, request);

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.recordDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(response.season()).isEqualTo(SeasonType.AUTUMN);
        assertThat(response.image().id()).isEqualTo(501L);
        assertThat(response.image().originalUrl())
                .isEqualTo("https://storage.example/record-images/42/a.jpg");
        assertThat(response.image().thumbnailUrl()).isEqualTo(response.image().originalUrl());
        verify(imageStorageService).validateUploadedObjects(42L, List.of("record-images/42/a.jpg"));
        verify(imageRepository).flush();
        verify(recordRepository).flush();
    }

    @Test
    void rejectsUnverifiedUploadBeforeDatabaseWrites() {
        when(imageRepository.existsByOriginalKeyIn(any())).thenReturn(false);
        doThrow(new BusinessException(ErrorCode.IMAGE_OWNERSHIP_MISMATCH))
                .when(imageStorageService).validateUploadedObjects(42L, List.of("record-images/43/a.jpg"));
        RecordCreateRequest request = new RecordCreateRequest(
                LocalDate.of(2026, 10, 4), null, "record-images/43/a.jpg");

        assertThatThrownBy(() -> service.create(42L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_OWNERSHIP_MISMATCH));

        verifyNoInteractions(memberRepository, recordRepository);
        verify(imageRepository, never()).save(any());
    }

    @Test
    void validatesReplacementKeyAndSchedulesOldImageDeletionOnUpdate() {
        Member member = member(42L);
        Record record = record(member, 100L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN);
        Image removed = image(record, 501L, "record-images/42/a.jpg", 0);
        when(recordRepository.findOwnedByIdForUpdate(100L, 42L)).thenReturn(Optional.of(record));
        when(imageRepository.findAllByRecordIdForUpdate(100L)).thenReturn(List.of(removed));
        when(imageRepository.existsByOriginalKeyIn(any())).thenReturn(false);
        when(imageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RecordUpdateRequest request = new RecordUpdateRequest(
                LocalDate.of(2026, 10, 4), null, "record-images/42/c.jpg");

        service.update(42L, 100L, request);

        verify(imageStorageService).validateUploadedObjects(42L, List.of("record-images/42/c.jpg"));
        verify(imageStorageService).scheduleDeletionAfterCommit(List.of(removed));
    }

    @Test
    void rejectsBlankObjectKeyBeforeDatabaseWrites() {
        RecordCreateRequest request = new RecordCreateRequest(LocalDate.of(2026, 10, 4), null, " ");

        assertThatThrownBy(() -> service.create(42L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT));

        verifyNoInteractions(memberRepository, recordRepository);
        verify(imageRepository, never()).save(any());
    }

    @Test
    void rejectsNullObjectKeyAsInvalidInput() {
        RecordCreateRequest request = new RecordCreateRequest(LocalDate.of(2026, 10, 4), null, null);

        assertThatThrownBy(() -> service.create(42L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verifyNoInteractions(memberRepository, recordRepository, imageRepository);
    }

    @Test
    void returnsSameNotFoundForMissingOrOtherMembersRecord() {
        when(recordRepository.findOwnedByIdForUpdate(100L, 42L)).thenReturn(Optional.empty());
        RecordUpdateRequest request = new RecordUpdateRequest(LocalDate.of(2026, 10, 4), null, null);

        assertThatThrownBy(() -> service.update(42L, 100L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RECORD_NOT_FOUND));

        verifyNoInteractions(imageRepository);
    }

    @Test
    void keepsImagesWhenUpdateImagesAreOmittedAndRecalculatesSeason() {
        Member member = member(42L);
        Record record = record(member, 100L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN);
        Image image = image(record, 501L, "record-images/42/a.jpg", 0);
        when(recordRepository.findOwnedByIdForUpdate(100L, 42L)).thenReturn(Optional.of(record));
        when(imageRepository.findAllByRecordIdForUpdate(100L)).thenReturn(List.of(image));
        when(memberRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(jarPageAllocationService.allocate(member, 2026, SeasonType.WINTER)).thenReturn(jarPage);

        RecordResponse response = service.update(42L, 100L,
                new RecordUpdateRequest(LocalDate.of(2026, 12, 1), "겨울", null));

        assertThat(response.season()).isEqualTo(SeasonType.WINTER);
        assertThat(response.image().id()).isEqualTo(501L);
        verify(imageRepository, never()).deleteAll(anyList());
        verify(recordRepository).flush();
    }

    @Test
    void rejectsLegacyRecordWithMultipleImages() {
        Member member = member(42L);
        Record record = record(member, 100L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN);
        Image first = image(record, 501L, "record-images/42/a.jpg", 0);
        Image second = image(record, 502L, "record-images/42/b.jpg", 1);
        when(recordRepository.findOwnedByIdForUpdate(100L, 42L)).thenReturn(Optional.of(record));
        when(imageRepository.findAllByRecordIdForUpdate(100L)).thenReturn(List.of(first, second));
        RecordUpdateRequest request = new RecordUpdateRequest(LocalDate.of(2026, 10, 4), null, null);

        assertThatThrownBy(() -> service.update(42L, 100L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RECORD_IMAGE_INTEGRITY_VIOLATION));
    }

    @Test
    void deletesImagesBeforeOwnedRecord() {
        Member member = member(42L);
        Record record = record(member, 100L, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN);
        List<Image> images = List.of(image(record, 501L, "record-images/42/a.jpg", 0));
        when(recordRepository.findOwnedByIdForUpdate(100L, 42L)).thenReturn(Optional.of(record));
        when(imageRepository.findAllByRecordIdForUpdate(100L)).thenReturn(images);

        service.delete(42L, 100L);

        verify(imageRepository).deleteAll(images);
        verify(imageRepository).flush();
        verify(recordRepository).delete(record);
        verify(recordRepository).flush();
        verify(imageStorageService).scheduleDeletionAfterCommit(images);
    }

    private static Member member(Long id) {
        Member member = Member.create("TOSS_ANON", "member-" + id);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private static Record record(Member member, Long id, LocalDate date, SeasonType season) {
        Record record = Record.create(member, date, season, null);
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }

    private static Image image(Record record, Long id, String key, int order) {
        Image image = Image.create(record, key, null, PhotoSource.CAMERA, order);
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }
}
