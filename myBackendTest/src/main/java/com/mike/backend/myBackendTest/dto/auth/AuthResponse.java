package com.mike.backend.myBackendTest.dto.auth;

/**
 * Antwort nach erfolgreichem Login/Register.
 * Enthält Access-Token, Refresh-Token und Benutzer-Profil.
 */
public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    UserInfo user
) {
    public AuthResponse(String accessToken, String refreshToken, UserInfo user) {
        this(accessToken, refreshToken, "Bearer", user);
    }

    public record UserInfo(
        Long id,
        String username,
        String email,
        String role,
        String fitnessLevel,
        Integer age,
        Double weightKg,
        Double heightCm
    ) {}
}
