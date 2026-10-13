package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JarPageAllocationServiceTest {
    @Mock JarPageRepository pageRepository;
    @Mock RecordRepository recordRepository;
    @InjectMocks JarPageAllocationService service;

    @Test
    void reusesLowestAvailablePageInsteadOfLatestPage() {
        Member member = member();
        JarPage first = page(member, 0, 30L);
        JarPage second = page(member, 1, 31L);
        when(pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(List.of(first, second));
        when(recordRepository.countByJarPageId(30L)).thenReturn(0L);

        assertThat(service.allocate(member, 2026, SeasonType.AUTUMN)).isSameAs(first);
        verify(pageRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsNextPageWhenLatestPageIsFull() {
        Member member = member();
        JarPage page = page(member, 2, 31L);
        when(pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(List.of(page));
        when(recordRepository.countByJarPageId(31L)).thenReturn(5L);
        when(pageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JarPage allocated = service.allocate(member, 2026, SeasonType.AUTUMN);

        assertThat(allocated.getPageNumber()).isEqualTo(3);
    }

    @Test
    void compactsFiveRemainingRecordsIntoFirstPageAfterDeletion() {
        Member member = member();
        JarPage first = page(member, 0, 30L);
        JarPage second = page(member, 1, 31L);
        List<Record> firstRecords = List.of(
                record(member, first, 100L), record(member, first, 101L),
                record(member, first, 102L), record(member, first, 103L));
        Record moved = record(member, second, 104L);
        when(pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(List.of(first, second));
        when(recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(30L)).thenReturn(firstRecords);
        when(recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(31L))
                .thenReturn(List.of(moved));

        service.compact(member, 2026, SeasonType.AUTUMN);

        assertThat(moved.getJarPage()).isSameAs(first);
        assertThat(firstRecords).allSatisfy(record -> assertThat(record.getJarPage()).isSameAs(first));
        verify(pageRepository, never()).delete(any());
    }

    @Test
    void movesAllRecordsFromSecondPageWhenFirstPageIsEmpty() {
        Member member = member();
        JarPage first = page(member, 0, 30L);
        JarPage second = page(member, 1, 31L);
        List<Record> records = List.of(
                record(member, second, 100L),
                record(member, second, 101L),
                record(member, second, 102L));
        when(pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(List.of(first, second));
        when(recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(30L)).thenReturn(List.of());
        when(recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(31L)).thenReturn(records);

        service.compact(member, 2026, SeasonType.AUTUMN);

        assertThat(records).allSatisfy(record -> {
            assertThat(record.getJarPage().getPageNumber()).isZero();
            assertThat(record.getMemo()).isNull();
        });
    }

    private static Member member() {
        Member member = Member.create("TOSS", "member-42");
        ReflectionTestUtils.setField(member, "id", 42L);
        return member;
    }

    private static JarPage page(Member member, int number, long id) {
        JarPage page = JarPage.create(member, 2026, SeasonType.AUTUMN, number);
        ReflectionTestUtils.setField(page, "id", id);
        return page;
    }

    private static Record record(Member member, JarPage page, long id) {
        Record record = Record.create(member, page, LocalDate.of(2026, 10, 1), SeasonType.AUTUMN, null);
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
