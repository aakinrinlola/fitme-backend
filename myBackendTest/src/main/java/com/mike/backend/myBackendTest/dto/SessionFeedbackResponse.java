package com.mike.backend.myBackendTest.dto;

import java.util.List;

/**
 * Antwort nach Feedback-Verarbeitung.
 * Enthält die regelbasierten Anpassungen UND die KI-Erklärung.
 */
public record SessionFeedbackResponse(
    Long sessionId,
    Integer sessionRpe,
    String aiExplanation,
    List<ExerciseAdjustment> adjustments,
    AdherenceStats adherenceStats
) {
    public record ExerciseAdjustment(
        Long exerciseId,
        String exerciseName,
        double previousWeight,
        double newWeight,
        int previousReps,
        int newReps,
        int previousSets,
        int newSets,
        String adjustmentReason
    ) {}

    public record AdherenceStats(
        long totalPlanned,
        long totalCompleted,
        double adherencePercent
    ) {}
}
