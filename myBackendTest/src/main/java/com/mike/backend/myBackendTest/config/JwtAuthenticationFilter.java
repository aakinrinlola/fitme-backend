package com.mike.backend.myBackendTest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT-Filter: Wird bei jedem Request ausgeführt.
 *
 * Ablauf:
 * 1. Prüft ob ein "Authorization: Bearer <token>" Header existiert
 * 2. Validiert den Token (Signatur + Ablauf)
 * 3. Setzt die Authentication im SecurityContext
 *    → Alle nachfolgenden Filter/Controller wissen, wer der User ist
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 1. Token aus Header extrahieren
        String token = extractToken(request);

        if (token != null && jwtService.validateToken(token)) {
            // 2. User-Infos aus Token lesen
            Long userId = jwtService.getUserIdFromToken(token);
            String username = jwtService.getUsernameFromToken(token);
            String role = jwtService.getRoleFromToken(token);

            // 3. Spring Security Authentication setzen
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

            var authentication = new UsernamePasswordAuthenticationToken(
                    userId,      // Principal = User-ID (wird in Controllern genutzt)
                    null,        // Credentials (nicht nötig nach Authentifizierung)
                    authorities  // Granted Authorities
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT Auth gesetzt: userId={}, username={}, role={}", userId, username, role);
        }

        filterChain.doFilter(request, response);
    }

    /** Extrahiert den Bearer-Token aus dem Authorization-Header */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
