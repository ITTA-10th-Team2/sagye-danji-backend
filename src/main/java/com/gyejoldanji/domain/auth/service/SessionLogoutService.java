package com.gyejoldanji.domain.auth.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.enums.SessionRevokeReason;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository;
import com.gyejoldanji.domain.auth.repository.AuthRefreshTokenRepository.OwnerIds;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 현재 세션 종료: 원문 해시로 소유자 ID hint → 트랜잭션(Refresh와 같은 회원 → 세션 → 토큰 행 잠금, 소유 관계 재확인, 미폐기 세션만
 * LOGOUT 폐기) → commit 성공 뒤 반환.
 *
 * <p>Refresh와 달리 소비·세대 불일치·만료 토큰, 만료 세션, 비활성 회원도 종료를 거부하지 않는다. hint가 없거나 잠금 사이 행이 사라졌거나
 * 소유 관계가 달라졌으면 바꾸지 않고 정상 반환한다(이미 없음). DB·commit 실패는 예외로 올라가 503 COMMON_008 또는 500 COMMON_003이며
 * 정상 반환으로 숨기지 않는다. 자동 재시도하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SessionLogoutService {

    private final TransactionTemplate transactionTemplate;
    private final MemberRepository memberRepository;
    private final AuthSessionRepository sessionRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    /** Refresh가 속한 세션 하나만 종료한다. 반환 시점에는 commit이 끝나 있다. */
    public void logout(String refreshToken) {
        byte[] hash = ServiceTokenService.sha256(refreshToken);
        try {
            transactionTemplate.executeWithoutResult(status -> revoke(hash));
        } catch (RuntimeException e) {
            throw GlobalExceptionHandler.translateTransactionFailure("로그아웃", e);
        }
    }

    /** 트랜잭션 안. 각 조회에서 대상이 없으면 그 자리에서 끝낸다(다음 행을 잠그지 않고 시각도 읽지 않는다). */
    private void revoke(byte[] hash) {
        // 해시만 조건인 스칼라 ID라 엔티티를 올리지 않는다. 권한 확정이 아니므로 잠근 뒤 소유 관계를 다시 확인한다.
        Optional<OwnerIds> owner = refreshTokenRepository.findOwnerIdsByHash(hash);
        if (owner.isEmpty()) {
            return;
        }
        Optional<Member> member = memberRepository.findByIdForUpdate(owner.get().getMemberId());
        if (member.isEmpty()) {
            return;
        }
        Optional<AuthSession> session = sessionRepository.findByIdForUpdate(owner.get().getSessionId())
                .filter(s -> s.getMember().getId().equals(member.get().getId()));
        if (session.isEmpty()) {
            return;
        }
        boolean tokenOwned = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .filter(t -> t.getSession().getId().equals(session.get().getId()))
                .isPresent();
        if (!tokenOwned) {
            return;
        }
        // 마지막 잠금 뒤의 시각. DB TIMESTAMP(6)에 맞춰 마이크로초로 자른다. 이미 폐기된 세션은 revoke가 최초 시각·사유를 유지한다.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        session.get().revoke(SessionRevokeReason.LOGOUT, now);
    }
}
