package com.gyejoldanji.domain.analytics.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 행동 이벤트 저장. */
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {

    /**
     * 회원이 이미 저장한 eventId 중 주어진 것을 찾는다. 잠금 없는 일반 SELECT다. 같은 회원의 동시 배치는 호출 쪽 회원 행 잠금이 줄을 세우고,
     * READ COMMITTED 트랜잭션이라 앞 배치가 commit한 행을 본다. 없는 키에 FOR UPDATE를 걸면 REPEATABLE READ에서 갭 락이 생겨 서로 다른
     * 회원의 첫 배치끼리 데드락이 날 수 있다. 시각 파라미터가 없어 native로 쓴다.
     */
    @Query(value = """
            SELECT event_id FROM analytics_events
            WHERE member_id = :memberId AND event_id IN (:eventIds)
            """, nativeQuery = true)
    List<String> findExistingEventIds(@Param("memberId") Long memberId, @Param("eventIds") Collection<String> eventIds);

    /** 대기 인덱스(sheet_synced_at, id) 순서로 이번 배치만 읽는다. */
    @Query(value = """
            SELECT * FROM analytics_events
            WHERE sheet_synced_at IS NULL
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<AnalyticsEvent> findPendingForSheet(@Param("limit") int limit);

    /** native 시각 바인딩은 JVM 시간대 영향을 피하도록 Instant를 받는다. 호출자가 짧은 트랜잭션을 연다. */
    @Modifying
    @Query(value = """
            UPDATE analytics_events SET sheet_synced_at = :syncedAt
            WHERE id IN (:ids) AND sheet_synced_at IS NULL
            """, nativeQuery = true)
    int markSheetSynced(@Param("ids") Collection<Long> ids, @Param("syncedAt") Instant syncedAt);

    boolean existsBySheetSyncedAtIsNull();
}
