package com.gyejoldanji.domain.jar.repository;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.global.common.enums.SeasonType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 단지 페이지를 회원 소유권과 함께 조회한다. */
public interface JarPageRepository extends JpaRepository<JarPage, Long> {
    @EntityGraph(attributePaths = "stickers")
    Optional<JarPage> findByIdAndMemberId(Long id, Long memberId);

    @EntityGraph(attributePaths = "stickers")
    Optional<JarPage> findByMemberIdAndYearAndSeasonAndPageNumber(
            Long memberId, int year, SeasonType season, int pageNumber);

    Optional<JarPage> findFirstByMemberIdAndYearAndSeasonOrderByPageNumberDesc(
            Long memberId, int year, SeasonType season);
}
