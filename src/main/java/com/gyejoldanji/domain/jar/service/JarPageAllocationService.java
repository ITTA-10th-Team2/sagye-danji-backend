package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 같은 회원·연도·계절의 기록을 최대 다섯 개씩 안정적인 단지 페이지에 배정한다. */
@Service
@RequiredArgsConstructor
public class JarPageAllocationService {
    private static final int PAGE_CAPACITY = 5;
    private final JarPageRepository pageRepository;
    private final RecordRepository recordRepository;

    public JarPage allocate(Member lockedMember, int year, SeasonType season) {
        List<JarPage> pages = pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                lockedMember.getId(), year, season);
        for (JarPage page : pages) {
            if (recordRepository.countByJarPageId(page.getId()) < PAGE_CAPACITY) {
                return page;
            }
        }

        int nextPageNumber = pages.isEmpty() ? 0 : pages.getLast().getPageNumber() + 1;
        return pageRepository.saveAndFlush(JarPage.create(lockedMember, year, season, nextPageNumber));
    }

    /**
     * 삭제·범위 이동으로 생긴 앞 페이지의 빈칸을 뒤 페이지 기록으로 채운다.
     * 페이지 엔티티는 유지하므로 사용자가 배치한 스티커는 삭제되지 않는다.
     */
    public void compact(Member lockedMember, int year, SeasonType season) {
        List<JarPage> pages = pageRepository.findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
                lockedMember.getId(), year, season);
        if (pages.size() < 2) {
            return;
        }

        List<Record> records = new ArrayList<>();
        for (JarPage page : pages) {
            records.addAll(recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(page.getId()));
        }

        for (int index = 0; index < records.size(); index++) {
            JarPage target = pages.get(index / PAGE_CAPACITY);
            Record record = records.get(index);
            if (!target.getId().equals(record.getJarPage().getId())) {
                record.moveToJarPage(target);
            }
        }
    }
}
