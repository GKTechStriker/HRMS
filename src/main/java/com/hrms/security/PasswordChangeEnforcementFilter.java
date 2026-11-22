package com.hrms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces mandatory password rotation on first login (P0 Security Requirement).
 * Intercepts any authenticated user whose password has never been changed (passwordResetAt == null)
 * and redirects them to /account/password until they set a new personal password.
 */
public class PasswordChangeEnforcementFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomUserDetails principal) {
            if (principal.isPasswordResetRequired()) {
                String path = getPathWithoutContext(request);
                if (!isAllowedPath(path)) {
                    if (path.startsWith("/api/")) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                "Password change required before accessing API resources.");
                        return;
                    }
                    String redirectUrl = request.getContextPath() + "/account/password?required=true";
                    response.sendRedirect(redirectUrl);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getPathWithoutContext(HttpServletRequest request) {
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        String path = request.getRequestURI();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    private boolean isAllowedPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/account/password")
                || path.equals("/logout")
                || path.equals("/login")
                || path.equals("/error")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.equals("/favicon.ico");
    }
}
