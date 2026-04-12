package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.NotBlank;

public record ExerciseTemplateRequest(
    @NotBlank String exerciseName,
    String muscleGroup,
    String muscleFocus,
    int defaultSets,
    int defaultReps,
    double defaultWeightKg,
    int defaultRestSeconds,
    Integer defaultTargetRpe,
    String description
) {}
