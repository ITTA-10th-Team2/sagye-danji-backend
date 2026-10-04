package com.gyejoldanji.domain.auth.repository;

import com.gyejoldanji.domain.auth.entity.AuthRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Refresh 토큰 이력 조회·저장. ForUpdate 메서드는 회원→세션을 먼저 잠근 트랜잭션 안에서만 호출한다. */
public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, Long> {

    /** 잠금 순서를 지키기 위해 먼저 알아내는 소유자 ID. 권한 확정이 아니라 잠글 대상을 찾는 힌트다. */
    interface OwnerIds {
        /** 토큰이 속한 세션의 회원 식별자. */
        Long getMemberId();

        /** 토큰이 속한 세션 식별자. */
        Long getSessionId();
    }

    /**
     * 해시만 조건으로 소유자 ID를 조회한다. 사용·세대·만료·상태 조건을 붙이면
     * 이미 사용한 토큰의 재사용을 탐지하지 못하므로 붙이지 않는다.
     */
    @Query("""
            select s.member.id as memberId, s.id as sessionId
            from AuthRefreshToken t join t.session s
            where t.tokenHash = :hash
            """)
    Optional<OwnerIds> findOwnerIdsByHash(@Param("hash") byte[] hash);

    /** 토큰을 해시로 행 잠금(FOR UPDATE) 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuthRefreshToken t where t.tokenHash = :hash")
    Optional<AuthRefreshToken> findByTokenHashForUpdate(@Param("hash") byte[] hash);

    /**
     * 정리할 세션 하나의 Refresh 이력(현재·소비 모두)을 삭제하고 삭제 행 수를 반환한다. 그 세션을 먼저 잠근 정리 트랜잭션에서만 호출한다.
     * 세션 단위로 지워 (session_id, generation) 인덱스로 그 세션의 이력만 잠근다.
     */
    @Modifying
    @Query(value = "DELETE FROM auth_refresh_tokens WHERE session_id = :sessionId", nativeQuery = true)
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
