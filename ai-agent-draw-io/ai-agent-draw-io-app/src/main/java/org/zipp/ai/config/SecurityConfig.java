package org.zipp.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Server-side session auth for #3. The filter chain is intentionally permissive because request-level
 * ownership is enforced downstream by {@code CurrentOwnerHttpResolver} — Spring Security here exists
 * so a session cookie can bind a real user, not to gate individual endpoints. Session lifetime and
 * cookie flags live in {@code application*.yml}; production overrides {@code cookie.secure=true}.
 */
@Configuration
public class SecurityConfig {

    @Value("${app.security.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/v1/auth/register", "POST"),
                                new AntPathRequestMatcher("/api/v1/auth/resend-verification", "POST"),
                                new AntPathRequestMatcher("/api/v1/auth/password-reset/request", "POST"),
                                new AntPathRequestMatcher("/api/v1/auth/password-reset/confirm", "POST"),
                                new AntPathRequestMatcher("/api/v1/auth/login", "POST")))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * CORS with credentials so the SPA can send its session cookie. Credentialed CORS must be
     * origin-whitelisted; wildcard origins would let untrusted sites call session-backed APIs.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(parseAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Admin-Token",
                "X-Requested-With",
                "X-Workspace-Id",
                "X-XSRF-TOKEN"));
        cfg.setExposedHeaders(List.of("X-CSRF-TOKEN"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private List<String> parseAllowedOrigins() {
        return Arrays.stream((allowedOrigins == null ? "" : allowedOrigins).split("[,;\\s]+"))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .distinct()
                .toList();
    }
}
