package com.mike.backend.myBackendTest.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT-Service: Erstellt und validiert JSON Web Tokens.
 *
 * Token-Inhalt:
 * - subject: User-ID (nicht Username, da sich dieser ändern kann)
 * - claim "username": Benutzername
 * - claim "role": Rolle (USER/ADMIN)
 * - iat: Erstellungszeitpunkt
 * - exp: Ablaufzeitpunkt
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms:3600000}") long accessTokenExpirationMs,
            @Value("${jwt.refresh-token-expiration-ms:604800000}") long refreshTokenExpirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    /** Erstellt einen Access-Token (kurze Laufzeit, 1h Standard) */
    public String generateAccessToken(Long userId, String username, String role) {
        return buildToken(userId, username, role, accessTokenExpirationMs);
    }

    /** Erstellt einen Refresh-Token (lange Laufzeit, 7 Tage Standard) */
    public String generateRefreshToken(Long userId, String username, String role) {
        return buildToken(userId, username, role, refreshTokenExpirationMs);
    }

    private String buildToken(Long userId, String username, String role, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /** Extrahiert die User-ID (subject) aus dem Token */
    public Long getUserIdFromToken(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    /** Extrahiert den Username aus dem Token */
    public String getUsernameFromToken(String token) {
        return getClaims(token).get("username", String.class);
    }

    /** Extrahiert die Rolle aus dem Token */
    public String getRoleFromToken(String token) {
        return getClaims(token).get("role", String.class);
    }

    /** Prüft ob der Token gültig ist (Signatur + Ablauf) */
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT abgelaufen: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT nicht unterstützt: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT ungültig: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("JWT Signatur ungültig: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT Claims leer: {}", e.getMessage());
        }
        return false;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
