package com.hrms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Enforces an absolute maximum session lifetime (hrms.session.absolute-timeout-hours)
 * regardless of continuous sliding activity (GAP-01).
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final SessionManagementService sessionManagementService;
    private final Clock clock;
    private final long maxAgeMillis;

    public AbsoluteSessionTimeoutFilter(SessionManagementService sessionManagementService,
                                        Clock clock,
                                        long absoluteTimeoutHours) {
        this.sessionManagementService = sessionManagementService;
        this.clock = clock;
        this.maxAgeMillis = absoluteTimeoutHours > 0 ? Math.multiplyExact(absoluteTimeoutHours, 3_600_000L) : 0L;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (maxAgeMillis > 0) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                long elapsedMillis = clock.millis() - session.getCreationTime();
                if (elapsedMillis >= maxAgeMillis) {
                    String sessionId = session.getId();
                    sessionManagementService.recordExpiredSession(sessionId);
                    try {
                        session.invalidate();
                    } catch (IllegalStateException ignored) {
                        // Session already invalidated
                    }
                    SecurityContextHolder.clearContext();

                    String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
                    String path = request.getRequestURI();
                    if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
                        path = path.substring(contextPath.length());
                    }

                    if (!isPublicOrStaticPath(path)) {
                        if (path.startsWith("/api/")) {
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                                    "Session expired due to maximum session duration.");
                            return;
                        }
                        response.sendRedirect(contextPath + "/login?expired");
                        return;
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicOrStaticPath(String path) {
        return "/login".equals(path)
                || "/logout".equals(path)
                || "/error".equals(path)
                || "/favicon.ico".equals(path)
                || "/api/health".equals(path)
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/sample/");
    }
}
