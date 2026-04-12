package com.mike.backend.myBackendTest.dto;

import com.mike.backend.myBackendTest.entity.BodyScanEntry;
import java.time.LocalDate;

/**
 * Response-DTO für einen Body-Scan-Eintrag.
 * Wird sowohl für Einzeleinträge als auch in der Historienliste verwendet.
 */
public record BodyScanResponse(
        Long      id,
        LocalDate measuredAt,

        // ── Körperkomposition ──────────────────────────────────────────────
        Double bmi,
        Double bodyFatPercent,
        Double bodyFatKg,
        Double leanMassPercent,
        Double bodyWaterPercent,
        Double visceralFatKg,

        // ── Bioimpedanz / Vitalwerte ───────────────────────────────────────
        Double phaseAngle,
        Double oxygenSaturation,

        // ── Segmentale Muskelwerte (kg) ────────────────────────────────────
        Double muscleKgTorso,
        Double muscleKgArmRight,
        Double muscleKgArmLeft,
        Double muscleKgLegRight,
        Double muscleKgLegLeft
) {
    /** Statische Factory-Methode: Entity → Response. */
    public static BodyScanResponse from(BodyScanEntry e) {
        return new BodyScanResponse(
                e.getId(),
                e.getMeasuredAt(),
                e.getBmi(),
                e.getBodyFatPercent(),
                e.getBodyFatKg(),
                e.getLeanMassPercent(),
                e.getBodyWaterPercent(),
                e.getVisceralFatKg(),
                e.getPhaseAngle(),
                e.getOxygenSaturation(),
                e.getMuscleKgTorso(),
                e.getMuscleKgArmRight(),
                e.getMuscleKgArmLeft(),
                e.getMuscleKgLegRight(),
                e.getMuscleKgLegLeft()
        );
    }
}