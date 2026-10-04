package com.gyejoldanji.global.security;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.domain.auth.repository.AuthSessionRepository.AuthenticationView;
import com.gyejoldanji.domain.member.enums.MemberStatus;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.common.exception.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서명·claim이 검증된 Access JWT의 sub·sid로 회원·세션을 조회해 존재·소유 회원 일치·ACTIVE·미폐기·절대 만료 전인지 확인하고,
 * 통과하면 principal을 {@link CurrentMember}로 바꾼다. Bearer 인증이 없는 요청은 그대로 넘겨 인가 단계가 처리한다.
 *
 * <p>실패하면 SecurityContext를 비우고 직접 JSON으로 응답한 뒤 끝낸다(뒤 단계·ControllerAdvice로 넘기지 않음). 무효 세션은 401
 * AUTH_003, DB 연결·자원·잠금·timeout은 503 COMMON_008, 그 밖의 조회 오류는 500 COMMON_003이며 어느 경우도 인증을 통과시키지
 * 않는다. 조회만 하며 세션 만료를 연장하거나 행을 잠그지 않는다.
 *
 * <p>Spring Bean으로 만들지 않고 보호 체인에서만 생성하므로 Servlet 필터로 자동 등록되지 않는다.
 */
@Slf4j
public class SessionValidationFilter extends OncePerRequestFilter {

    private final AuthSessionRepository sessionRepository;
    private final Clock clock;
    private final JsonSecurityErrorHandler errorHandler;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    /** BearerTokenAuthenticationFilter와 같은 요청 속성 저장소. 같은 요청의 다른 dispatch도 교체된 principal을 본다. */
    private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();

    public SessionValidationFilter(AuthSessionRepository sessionRepository, Clock clock,
                                   JsonSecurityErrorHandler errorHandler) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.errorHandler = errorHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(contextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = authentication.getToken();
        long memberId = Long.parseLong(jwt.getSubject());
        Optional<AuthenticationView> view;
        try {
            view = sessionRepository.findAuthenticationView(jwt.getClaimAsString("sid"), memberId);
        } catch (RuntimeException e) {
            if (GlobalExceptionHandler.isDatabaseUnavailable(e)) {
                log.warn("세션 검사 실패(DB 일시 장애): type={}", e.getClass().getName());
                reject(response, ErrorCode.SERVICE_UNAVAILABLE);
            } else {
                log.error("세션 검사 실패: ", GlobalExceptionHandler.withoutMessages(e));
                reject(response, ErrorCode.INTERNAL_SERVER_ERROR);
            }
            return;
        }
        if (view.isEmpty() || !isUsable(view.get(), memberId)) {
            reject(response, ErrorCode.AUTH_SESSION_INVALID);
            return;
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new CurrentMember(memberId, view.get().getSessionId()), null, authentication.getAuthorities()));
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }

    /** 조회는 sid·sub가 함께 맞을 때만 결과가 있지만 소유 회원을 한 번 더 확인한다. DB 시각은 UTC다. */
    private boolean isUsable(AuthenticationView view, long memberId) {
        return view.getMemberId() == memberId
                && view.getMemberStatus() == MemberStatus.ACTIVE
                && view.getRevokedAt() == null
                && LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).isBefore(view.getExpiresAt());
    }

    private void reject(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        contextHolder.clearContext();
        errorHandler.write(response, errorCode);
    }
}
