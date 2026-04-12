package com.mike.backend.myBackendTest.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request-DTO zum Speichern eines Body-Scan-Eintrags.
 *
 * Alle Messwerte sind optional — nur das Datum ist Pflicht.
 */
public record BodyScanRequest(

        @NotNull(message = "Messdatum ist erforderlich")
        LocalDate measuredAt,

        //Körperwerte
        Double bmi,
        Double bodyFatPercent,
        Double bodyFatKg,
        Double leanMassPercent,
        Double bodyWaterPercent,
        Double visceralFatKg,

        // Bioimpedanz / Vitalwerte
        Double phaseAngle,
        Double oxygenSaturation,

        // Segmentale Muskelwerte (kg)
        Double muscleKgTorso,
        Double muscleKgArmRight,
        Double muscleKgArmLeft,
        Double muscleKgLegRight,
        Double muscleKgLegLeft
) {}