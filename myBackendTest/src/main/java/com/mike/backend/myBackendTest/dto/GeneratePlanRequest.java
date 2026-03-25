package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO für KI-gestützte Trainingsplan-Generierung.
 * Feldnamen entsprechen exakt dem Angular-Frontend (training-plan-create.ts).
 *
 * POST /api/training-plans/generate
 */
public record GeneratePlanRequest(

        /** Name des zu erstellenden Plans */
        @NotBlank(message = "Bitte gib einen Plannamen ein")
        String planName,

        /** Freitext-Beschreibung: "Ich will 3x/Woche trainieren, Fokus Oberkörper" */
        @NotBlank(message = "Bitte beschreibe deinen gewünschten Trainingsplan")
        @Size(min = 10, max = 1000, message = "Beschreibung muss zwischen 10 und 1000 Zeichen lang sein")
        String userPrompt,

        /** Trainingsziel: MUSCLE_GAIN, STRENGTH, FAT_LOSS, ENDURANCE, GENERAL_FITNESS (optional) */
        String fitnessGoal,

        /** Trainingstage pro Woche (optional, 1–7) */
        @Min(value = 1, message = "Mindestens 1 Tag pro Woche")
        @Max(value = 7, message = "Maximal 7 Tage pro Woche")
        Integer daysPerWeek,

        /** Fokus-Muskelgruppen: z.B. "Brust, Schulter, Trizeps" (optional) */
        String focusMuscles,

        /** Erfahrungslevel: BEGINNER, INTERMEDIATE, ADVANCED (optional) */
        String experienceLevel
) {}