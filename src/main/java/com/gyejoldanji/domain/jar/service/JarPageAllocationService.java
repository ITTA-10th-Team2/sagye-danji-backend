package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 같은 회원·연도·계절의 기록을 최대 다섯 개씩 안정적인 단지 페이지에 배정한다. */
@Service
@RequiredArgsConstructor
public class JarPageAllocationService {
    private static final int PAGE_CAPACITY = 5;
    private final JarPageRepository pageRepository;
    private final RecordRepository recordRepository;

    public JarPage allocate(Member lockedMember, int year, SeasonType season) {
        Optional<JarPage> latest = pageRepository.findFirstByMemberIdAndYearAndSeasonOrderByPageNumberDesc(
                lockedMember.getId(), year, season);
        if (latest.isPresent() && recordRepository.countByJarPageId(latest.get().getId()) < PAGE_CAPACITY) {
            return latest.get();
        }
        int nextPageNumber = latest.map(page -> page.getPageNumber() + 1).orElse(0);
        return pageRepository.saveAndFlush(JarPage.create(lockedMember, year, season, nextPageNumber));
    }
}
