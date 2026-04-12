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


        String focusMuscles,

        String experienceLevel,

        @Min(15) @Max(180)
        Integer sessionDurationMinutes,

        @Min(3) @Max(12)
        Integer sleepHoursPerNight,


        String stressLevel,

        String injuries,

        List<String> focusMuscleGroups,

        String focusMusclesFreetext,

        Boolean includeMobilityPlan,

        String focusStrategy

) {}