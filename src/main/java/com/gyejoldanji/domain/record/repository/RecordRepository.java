package com.gyejoldanji.domain.record.repository;

import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 기록 저장과 회원 소유권을 포함한 command용 잠금 조회를 제공한다. */
public interface RecordRepository extends JpaRepository<Record, Long> {

    long countByJarPageId(Long jarPageId);

    List<Record> findAllByJarPageIdOrderByRecordDateDescIdDesc(Long jarPageId);

    /** 회원의 전체 기록 수·가장 오래된 기록일·계절별 개수를 단일 집계 쿼리로 조회한다. */
    @Query(value = """
            select count(*) as recordCount,
                   min(record_date) as firstRecordDate,
                   coalesce(sum(case when season = 'SPRING' then 1 else 0 end), 0) as springCount,
                   coalesce(sum(case when season = 'SUMMER' then 1 else 0 end), 0) as summerCount,
                   coalesce(sum(case when season = 'AUTUMN' then 1 else 0 end), 0) as autumnCount,
                   coalesce(sum(case when season = 'WINTER' then 1 else 0 end), 0) as winterCount
            from records
            where member_id = :memberId
            """, nativeQuery = true)
    RecordSummaryProjection summarizeByMemberId(@Param("memberId") Long memberId);

    /** 다른 회원의 기록 존재 여부를 노출하지 않도록 ID와 회원 ID를 함께 조건화해 행 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Record r where r.id = :recordId and r.member.id = :memberId")
    Optional<Record> findOwnedByIdForUpdate(@Param("recordId") Long recordId, @Param("memberId") Long memberId);

    /** 잠금 없이 ID와 회원 ID를 함께 조건화해 상세 조회한다. */
    @Query("select r from Record r where r.id = :recordId and r.member.id = :memberId")
    Optional<Record> findOwnedById(@Param("recordId") Long recordId, @Param("memberId") Long memberId);

    /** 회원의 연도·계절 범위 첫 페이지를 최신 기록부터 조회한다. */
    @Query("""
            select r from Record r
            where r.member.id = :memberId and r.season = :season
              and r.recordDate >= :startDate and r.recordDate < :endDate
            order by r.recordDate desc, r.id desc
            """)
    List<Record> findOwnedBySeasonInDateRange(@Param("memberId") Long memberId,
                                              @Param("season") SeasonType season,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate,
                                              Pageable pageable);

    /** 회원의 연도·계절 범위에서 복합 cursor 뒤 기록을 최신순으로 조회한다. */
    @Query("""
            select r from Record r
            where r.member.id = :memberId and r.season = :season
              and r.recordDate >= :startDate and r.recordDate < :endDate
              and (r.recordDate < :cursorDate
                   or (r.recordDate = :cursorDate and r.id < :cursorId))
            order by r.recordDate desc, r.id desc
            """)
    List<Record> findOwnedBySeasonInDateRangeAfter(@Param("memberId") Long memberId,
                                                   @Param("season") SeasonType season,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate,
                                                   @Param("cursorDate") LocalDate cursorDate,
                                                   @Param("cursorId") Long cursorId,
                                                   Pageable pageable);

    /** 홈 기록 요약 계산에 필요한 집계 결과만 노출한다. */
    interface RecordSummaryProjection {
        long getRecordCount();

        LocalDate getFirstRecordDate();

        long getSpringCount();

        long getSummerCount();

        long getAutumnCount();

        long getWinterCount();
    }
}
