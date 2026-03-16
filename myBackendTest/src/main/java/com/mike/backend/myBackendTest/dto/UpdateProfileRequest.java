package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * DTO für Profil-Updates.
 * Alle Felder sind optional — nur die mitgeschickten Felder werden aktualisiert.
 */
public record UpdateProfileRequest(
    @Size(min = 3, max = 50) String username,
    @Email String email,
    Integer age,
    Double weightKg,
    Double heightCm,
    String fitnessLevel
) {}
