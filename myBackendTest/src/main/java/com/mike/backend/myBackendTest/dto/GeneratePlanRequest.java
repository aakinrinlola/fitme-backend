package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO für KI-gestützte Trainingsplan-Generierung.
 *
 * Feldnamen MÜSSEN exakt dem Angular-Frontend (training.model.ts → GeneratePlanRequest) entsprechen:
 *   planName, userPrompt, fitnessGoal, daysPerWeek, focusMuscles, experienceLevel
 *
 * POST /api/training-plans/generate
 */
public record GeneratePlanRequest(

        /**
         * Vom Benutzer eingegebener Name des Plans.
         * Frontend-Feld: aiPlanName → planName
         */
        @NotBlank(message = "Bitte gib einen Plannamen ein")
        @Size(min = 2, max = 100, message = "Planname muss zwischen 2 und 100 Zeichen lang sein")
        String planName,

        /**
         * Freitext-Beschreibung des Nutzerwunsches.
         * Frontend-Feld: aiUserPrompt → userPrompt
         */
        @NotBlank(message = "Bitte beschreibe deinen gewünschten Trainingsplan")
        @Size(min = 10, max = 1000, message = "Beschreibung muss zwischen 10 und 1000 Zeichen lang sein")
        String userPrompt,

        /**
         * Trainingsziel (optional).
         * Mögliche Werte: MUSCLE_GAIN, STRENGTH, FAT_LOSS, ENDURANCE, GENERAL_FITNESS
         * Frontend-Feld: aiGoal → fitnessGoal
         */
        String fitnessGoal,

        /**
         * Trainingstage pro Woche (optional, 1–7).
         * Frontend-Feld: aiDaysPerWeek → daysPerWeek
         */
        @Min(value = 1, message = "Mindestens 1 Tag pro Woche")
        @Max(value = 7, message = "Maximal 7 Tage pro Woche")
        Integer daysPerWeek,

        /**
         * Fokus-Muskelgruppen als Freitext (optional).
         * Beispiel: "Brust, Schulter, Trizeps"
         * Frontend-Feld: aiFocusMuscles → focusMuscles
         */
        String focusMuscles,

        /**
         * Erfahrungslevel (optional).
         * Mögliche Werte: BEGINNER, INTERMEDIATE, ADVANCED
         * Frontend-Feld: aiExperienceLevel → experienceLevel
         */
        String experienceLevel

) {}