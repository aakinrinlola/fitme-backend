package com.mike.backend.myBackendTest.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Login-Request: Username/Email + Passwort.
 */
public record LoginRequest(

    @NotBlank(message = "Username oder E-Mail ist erforderlich")
    String usernameOrEmail,

    @NotBlank(message = "Passwort ist erforderlich")
    String password
) {}
