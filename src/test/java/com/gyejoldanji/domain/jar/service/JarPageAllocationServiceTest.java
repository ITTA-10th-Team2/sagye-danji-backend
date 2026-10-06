package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

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
    void reusesLatestPageUntilItContainsFiveRecords() {
        Member member = member();
        JarPage page = page(member, 2, 31L);
        when(pageRepository.findFirstByMemberIdAndYearAndSeasonOrderByPageNumberDesc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(Optional.of(page));
        when(recordRepository.countByJarPageId(31L)).thenReturn(4L);

        assertThat(service.allocate(member, 2026, SeasonType.AUTUMN)).isSameAs(page);
        verify(pageRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsNextPageWhenLatestPageIsFull() {
        Member member = member();
        JarPage page = page(member, 2, 31L);
        when(pageRepository.findFirstByMemberIdAndYearAndSeasonOrderByPageNumberDesc(
                42L, 2026, SeasonType.AUTUMN)).thenReturn(Optional.of(page));
        when(recordRepository.countByJarPageId(31L)).thenReturn(5L);
        when(pageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JarPage allocated = service.allocate(member, 2026, SeasonType.AUTUMN);

        assertThat(allocated.getPageNumber()).isEqualTo(3);
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
}
