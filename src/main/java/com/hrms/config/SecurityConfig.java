package com.hrms.config;

import com.hrms.security.CustomUserDetails;
import com.hrms.security.SessionManagementService;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

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

    /**
     * Configure HTTP security filter chain.
     * Permits public access to /api/health, auth pages, and static assets.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SessionManagementService sessionManagementService) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/health",
                    "/login",
                    "/password/forgot",
                    "/password/reset",
                    "/error",
                    "/favicon.ico",
                    "/sample/**",
                    "/css/**",
                    "/js/**",
                    "/images/**"
                ).permitAll()
                // Explicit Scope Exception (Section 4.1): Any authenticated role may call /sessions/{id}/revoke
                // (Service layer enforces SUPER_ADMIN = any session, others = own session only)
                .requestMatchers(HttpMethod.POST, "/sessions/*/revoke").authenticated()
                // Operational HR write paths restricted strictly to HR_ADMIN (Super Admin is read-only on HR data)
                .requestMatchers(HttpMethod.POST, "/employees/**", "/departments/**", "/audit/verify-dummy").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/employees/new", "/employees/*/edit", "/employees/import", "/departments/new").hasRole("HR_ADMIN")
                .requestMatchers(HttpMethod.GET, "/employees", "/departments/**").hasAnyRole("SUPER_ADMIN", "HR_ADMIN")
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
     * Configure CORS for API endpoints
     * Allows frontend applications to make cross-origin requests
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Allow requests from any origin (for hackathon; restrict in production)
        configuration.setAllowedOrigins(Arrays.asList("*"));

        // Allow HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // Allow all headers
        configuration.setAllowedHeaders(Arrays.asList("*"));

        // Allow credentials (cookies, auth headers)
        configuration.setAllowCredentials(false); // Set to true if using credentials

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
