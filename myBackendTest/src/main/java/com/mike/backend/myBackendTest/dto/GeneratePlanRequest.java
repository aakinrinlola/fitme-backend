package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record GeneratePlanRequest(

        @NotBlank(message = "Bitte gib einen Plannamen ein")
        @Size(min = 2, max = 100)
        String planName,

        @NotBlank(message = "Bitte beschreibe deinen gewünschten Trainingsplan")
        @Size(min = 10, max = 1000)
        String userPrompt,

        String fitnessGoal,

        @Min(1) @Max(7)
        Integer daysPerWeek,

        /** Legacy-Freitext, intern mit focusMuscleGroups gemergt */
        String focusMuscles,

        String experienceLevel,

        // ── Neu: Trainingsdauer ──────────────────────────────────────────
        /** Minuten pro Einheit: 30 / 45 / 60 / 75 / 90 */
        @Min(15) @Max(180)
        Integer sessionDurationMinutes,

        // ── Neu: Regeneration ────────────────────────────────────────────
        /** Schlafstunden pro Nacht (Volumen-Anpassung) */
        @Min(3) @Max(12)
        Integer sleepHoursPerNight,

        /** Stresslevel: LOW / MODERATE / HIGH */
        String stressLevel,

        // ── Neu: Verletzungen ────────────────────────────────────────────
        /** Freitext für Schmerzen / Einschränkungen */
        String injuries,

        // ── Neu: Fokus (Chips + Freitext) ────────────────────────────────
        /** Vordefinierte Chip-Auswahl, z.B. ["Beine","Glutes"] */
        List<String> focusMuscleGroups,

        /** Zusätzlicher Freitext-Fokus */
        String focusMusclesFreetext,

        // ── Neu: Mobilitätsplan ──────────────────────────────────────────
        /** true → KI erstellt zusätzlichen Mobilitätsblock */
        Boolean includeMobilityPlan

) {}