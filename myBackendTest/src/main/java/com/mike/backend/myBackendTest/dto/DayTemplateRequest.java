package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DayTemplateRequest(
    @NotBlank String templateName,
    String muscleGroup,
    @NotEmpty List<ExerciseEntry> exercises
) {
    public record ExerciseEntry(
        @NotBlank String exerciseName,
        int sets,
        int reps,
        double weightKg,
        int restSeconds,
        Integer targetRpe,
        String description
    ) {}
}
