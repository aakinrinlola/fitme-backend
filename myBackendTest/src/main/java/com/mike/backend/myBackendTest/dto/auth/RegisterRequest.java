package com.mike.backend.myBackendTest.dto.auth;

import jakarta.validation.constraints.*;

/**
 * Registrierungs-Request.
 * Enthält alle Pflichtfelder + optionale Profildaten.
 */
public record RegisterRequest(

    @NotBlank(message = "Username ist erforderlich")
    @Size(min = 3, max = 50, message = "Username muss zwischen 3 und 50 Zeichen lang sein")
    String username,

    @NotBlank(message = "E-Mail ist erforderlich")
    @Email(message = "Ungültige E-Mail-Adresse")
    String email,

    @NotBlank(message = "Passwort ist erforderlich")
    @Size(min = 8, max = 100, message = "Passwort muss mindestens 8 Zeichen lang sein")
    String password,

    // Optionale Profildaten (können auch später ergänzt werden)
    Integer age,
    Double weightKg,
    Double heightCm,
    String fitnessLevel
) {}
