package com.gyejoldanji.domain.jar.repository;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.global.common.enums.SeasonType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

/** 단지 페이지를 회원 소유권과 함께 조회한다. */
public interface JarPageRepository extends JpaRepository<JarPage, Long> {
    @EntityGraph(attributePaths = "stickers")
    Optional<JarPage> findByIdAndMemberId(Long id, Long memberId);

    @EntityGraph(attributePaths = "stickers")
    Optional<JarPage> findByMemberIdAndYearAndSeasonAndPageNumber(
            Long memberId, int year, SeasonType season, int pageNumber);

    /** 기록 배정·재정렬 중 같은 범위의 페이지가 바뀌지 않도록 번호순으로 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<JarPage> findAllByMemberIdAndYearAndSeasonOrderByPageNumberAsc(
            Long memberId, int year, SeasonType season);
}
