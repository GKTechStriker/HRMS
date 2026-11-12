package com.hrms.config;

import com.hrms.security.AbsoluteSessionTimeoutFilter;
import com.hrms.security.CustomUserDetails;
import com.hrms.security.SessionManagementService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security Configuration (Section 4 & Section 8.3)
 *
 * Enforces declarative path- and method-level authorization:
 * - SUPER_ADMIN: Read-only oversight across HR data + explicit exception for session revocation
 * - HR_ADMIN: Operational read/write access to HR modules
 * - EMPLOYEE: Self-service access only
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final long absoluteTimeoutHours;
    private final List<String> corsAllowedOrigins;

    public SecurityConfig(
            @Value("${hrms.session.absolute-timeout-hours:8}") long absoluteTimeoutHours,
            @Value("${hrms.cors.allowed-origins:}") List<String> corsAllowedOrigins) {
        this.absoluteTimeoutHours = absoluteTimeoutHours;
        this.corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
    }

    /**
     * Configure HTTP security filter chain.
     * Permits public access to /api/health, auth pages, and static assets.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SessionManagementService sessionManagementService,
                                                   Clock clock) throws Exception {
        AbsoluteSessionTimeoutFilter absoluteTimeoutFilter =
                new AbsoluteSessionTimeoutFilter(sessionManagementService, clock, absoluteTimeoutHours);

        http
            .cors(Customizer.withDefaults())
            .addFilterAfter(absoluteTimeoutFilter, SecurityContextHolderFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/health",
                    "/login",
                    "/error",
                    "/favicon.ico",
                    "/sample/**",
                    "/css/**",
                    "/js/**",
                    "/images/**"
                ).permitAll()
                // Self-service account actions (password rotation)
                .requestMatchers("/account/**").authenticated()
                // Explicit Scope Exception (Section 4.1): Any authenticated role may call /sessions/{id}/revoke
                // (Service layer enforces SUPER_ADMIN = any session, others = own session only)
                .requestMatchers(HttpMethod.POST, "/sessions/*/revoke").authenticated()
                // Operational HR write paths restricted strictly to HR_ADMIN (Super Admin is read-only on HR data)
                .requestMatchers(HttpMethod.POST, "/employees/**", "/departments/**", "/audit/verify-dummy").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/employees/new", "/employees/*/edit", "/employees/import", "/departments/new").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/employees", "/departments/**").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
                // Attendance & Daily Operations (Milestone 3)
                .requestMatchers(HttpMethod.POST, "/attendance/correct").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/attendance/manage").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
                .requestMatchers("/attendance/**").authenticated()
                // Leave Management (Milestone 3B)
                .requestMatchers(HttpMethod.POST, "/leaves/*/approve", "/leaves/*/reject").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/leaves/manage").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
                .requestMatchers("/leaves/**").authenticated()
                // Analytics & Reporting (Milestone 4)
                .requestMatchers("/api/analytics/**", "/reports/**").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
                // Audit Trail & Session Oversight (Section 8.2)
                .requestMatchers("/audit/**").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
                .requestMatchers("/sessions/**").hasAnyRole("SUPER_ADMIN", "HR_ADMIN", "EMPLOYEE")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(authenticationSuccessHandler(sessionManagementService))
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .addLogoutHandler((request, response, authentication) -> {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        sessionManagementService.recordLogoutSession(session.getId());
                    }
                })
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "SESSION")
                .permitAll()
            );

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler(SessionManagementService sessionManagementService) {
        return (request, response, authentication) -> {
            if (authentication.getPrincipal() instanceof CustomUserDetails principal) {
                sessionManagementService.recordLoginSession(principal, request);
            }
            response.sendRedirect(request.getContextPath() + "/dashboard");
        };
    }

    /**
     * Role Hierarchy Configuration (Section 4.3):
     * Positioned so a future ROLE_MANAGER can be inserted cleanly in Phase 5 without
     * rewriting endpoint rules. Note that SUPER_ADMIN does NOT inherit HR_ADMIN write
     * permissions because Super Admin is strictly read-only on HR data.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_HR_ADMIN > ROLE_EMPLOYEE
                """);
    }

    /**
     * Configure password encoder for user passwords
     * BCrypt automatically handles salt generation and verification
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Configure CORS using explicit allowlisted origins (GAP-05).
     * When hrms.cors.allowed-origins is empty, cross-origin requests are disallowed by default.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> normalizedOrigins = corsAllowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        configuration.setAllowedOrigins(normalizedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(!normalizedOrigins.isEmpty());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
