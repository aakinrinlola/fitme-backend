package com.mike.backend.myBackendTest.security;

import com.mike.backend.myBackendTest.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security Konfiguration.
 *
 * Prinzipien:
 * - Stateless: Kein Session-Cookie, nur JWT
 * - Public Endpoints: /api/auth/** (Login, Register)
 * - Protected: Alles andere → JWT erforderlich
 * - CORS: Angular Frontend (localhost:4200) erlaubt
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Ermöglicht @PreAuthorize in Controllern
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF deaktivieren (JWT-basiert, kein Cookie)
            .csrf(csrf -> csrf.disable())

            // CORS aktivieren
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Stateless Session (kein Cookie, kein Session-Store)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Endpoint-Regeln
            .authorizeHttpRequests(auth -> auth
                // Öffentliche Endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()

                // Admin-only Endpoints
                .requestMatchers(HttpMethod.GET, "/api/admin/**").hasRole("ADMIN")

                // Alles andere erfordert Authentifizierung
                .anyRequest().authenticated()
            )

            // H2-Console braucht Frames
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))

            // JWT-Filter VOR dem Standard-Auth-Filter einfügen
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "http://localhost:4200",   // Angular Dev
            "http://localhost:3000"    // Alternative Frontend
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
