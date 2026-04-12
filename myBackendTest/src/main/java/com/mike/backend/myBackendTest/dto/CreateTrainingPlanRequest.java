package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateTrainingPlanRequest(
    @NotBlank String planName,
    String description,
    @NotEmpty List<ExerciseInput> exercises
) {
    public record ExerciseInput(
        @NotBlank String exerciseName,
        @Positive int sets,
        @Positive int reps,
        double weightKg,
        int restSeconds,
        Integer targetRpe,
        String trainingDay,
        String description
    ) {}
}
