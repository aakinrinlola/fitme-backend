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

        @Min(15) @Max(180)
        Integer sessionDurationMinutes,

        @Min(3) @Max(12)
        Integer sleepHoursPerNight,

        /** LOW / MODERATE / HIGH */
        String stressLevel,

        String injuries,

        List<String> focusMuscleGroups,

        String focusMusclesFreetext,

        Boolean includeMobilityPlan,

        /**
         * Steuert die Tages-Aufteilung wenn Fokus-Muskelgruppen gewählt sind.
         *
         * "DOUBLE_FOCUS" → Fokus-Muskelgruppe bekommt 2 Trainingstage mit
         *                  unterschiedlichem Schwerpunkt (z.B. Beine-Quad + Beine-Hinge).
         *                  Bei 3 Tagen: 2 Fokus-Tage + 1 anderer Tag.
         *                  Bei 2 Tagen: 2 Fokus-Tage.
         *
         * "BALANCED"     → Klassischer Split (Push/Pull/Legs o.ä.),
         *                  Fokus-Tag kommt als Tag A zuerst.
         *
         * null           → Standard (= BALANCED-Verhalten)
         */
        String focusStrategy

) {}