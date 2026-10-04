package com.gyejoldanji.domain.member.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.gyejoldanji.domain.auth.entity.AuthSession;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.member.dto.MemberResponse;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import com.gyejoldanji.global.security.CurrentMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 회원 조회와 온보딩 완료. 회원·세션은 서버가 인증한 CurrentMember의 ID로만 고른다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final AuthSessionRepository sessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 내 정보와 온보딩 상태. 세션 검사 뒤 회원이 사라졌으면 401 AUTH_003, DB 연결·자원·잠금·timeout은 503 COMMON_008이다.
     */
    public MemberResponse getMe(Long memberId) {
        Optional<Member> member;
        try {
            member = memberRepository.findById(memberId);
        } catch (RuntimeException e) {
            if (GlobalExceptionHandler.isDatabaseUnavailable(e)) {
                log.warn("내 정보 조회 실패(DB 일시 장애): type={}", e.getClass().getName());
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            throw e;
        }
        return member.map(MemberResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
    }

    /**
     * 온보딩 완료. 반환 시점에는 commit이 끝나 있다.
     *
     * <p>{@link TransactionTemplate}으로 commit까지 이 경계 안에서 끝내고, commit을 포함한 실패는 원인 타입으로 503 COMMON_008 또는
     * 500 COMMON_003으로 바꾼다. 다른 도메인의 무결성 409로 넘기지 않으며 이미 분류된 {@link BusinessException}은 그대로 둔다.
     * 자동 재시도하지 않고 세션·토큰도 건드리지 않으므로 실패 뒤 같은 Access로 다시 요청할 수 있다.
     */
    public MemberResponse completeOnboarding(CurrentMember currentMember) {
        try {
            return transactionTemplate.execute(status -> complete(currentMember.memberId(), currentMember.sessionId()));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw GlobalExceptionHandler.translateTransactionFailure("온보딩 완료", e);
        }
    }

    /**
     * 트랜잭션 안: 회원 → 세션 행 잠금 → 잠금 뒤 새 UTC 시각으로 보호 필터의 검사를 다시 한다 → 미완료일 때만 최초 완료 시각 저장.
     * 필터 통과 뒤 잠금을 기다리는 동안 폐기·만료·비활성화·행 삭제가 있을 수 있어 필터 결과를 믿지 않는다. 이미 완료한 회원도 같다.
     */
    private MemberResponse complete(Long memberId, Long sessionId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        AuthSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        // 마지막 잠금 뒤의 시각. 요청 진입·필터 검사 시각을 재사용하지 않는다. DB TIMESTAMP(6)에 맞춰 마이크로초로 자른다.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (!member.getId().equals(session.getMember().getId())
                || member.getStatus() != MemberStatus.ACTIVE
                || session.getRevokedAt() != null
                || !now.isBefore(session.getExpiresAt())) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_INVALID);
        }
        member.completeOnboarding(now);
        return MemberResponse.from(member);
    }
}
