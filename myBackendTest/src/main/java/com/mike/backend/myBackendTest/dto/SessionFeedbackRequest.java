package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/**
 * DTO für Trainings-Feedback vom Frontend
 * zentrale Objekt: Benutzer meldet wie anstrengend die Einheit war
 */
public record SessionFeedbackRequest(

    @NotNull
    Long trainingPlanId,

    /** Session-RPE (1–10): Wie anstrengend war die gesamte Einheit? */
    @NotNull @Min(1) @Max(10)
    Integer sessionRpe,

    /** Optionale Notiz des Nutzers */
    String userNote,

    /** RPE-Werte pro Übung (optional, für detailliertere Anpassung) */
    List<ExerciseFeedback> exerciseFeedbacks
) {
    public record ExerciseFeedback(
        @NotNull Long plannedExerciseId,
        @NotNull @Min(1) @Max(10) Integer exerciseRpe,
        Integer setsCompleted,
        Integer repsCompleted,
        Double weightUsed,
        String note
    ) {}
}
