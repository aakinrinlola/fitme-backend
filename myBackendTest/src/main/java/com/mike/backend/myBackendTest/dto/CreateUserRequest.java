package com.mike.backend.myBackendTest.dto;

import com.mike.backend.myBackendTest.entity.AppUser;
import jakarta.validation.constraints.*;

public record CreateUserRequest(
    @NotBlank String username,
    @Email String email,
    Integer age,
    Double weightKg,
    Double heightCm,
    AppUser.FitnessLevel fitnessLevel
) {}
