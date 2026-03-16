package com.mike.backend.myBackendTest.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
    @NotBlank(message = "Refresh-Token ist erforderlich")
    String refreshToken
) {}
