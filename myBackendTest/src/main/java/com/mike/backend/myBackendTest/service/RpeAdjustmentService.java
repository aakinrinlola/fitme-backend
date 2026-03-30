package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse.ExerciseAdjustment;
import com.mike.backend.myBackendTest.entity.PlannedExercise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Regelbasierte Trainingsanpassung auf Basis von RPE-Werten.
 *
 * Anpassungslogik (überarbeitet):
 *   RPE 1–4:  Zu leicht      → spürbar intensivieren  (+10% Gewicht, +2 Wdh)
 *   RPE 5–6:  Genau richtig  → KEINE Änderung (Wunsch des Users)
 *   RPE 7–8:  Zielbereich    → minimale Steigerung (+2.5%)
 *   RPE 9:    Zu schwer      → moderat reduzieren (−5%)
 *   RPE 10:   Maximum        → deutlich reduzieren (−10%, −2 Wdh)
 *
 * Gewichte werden auf 0.5 kg gerundet (praxisnah für Hantelscheiben).
 */
@Service
public class RpeAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(RpeAdjustmentService.class);

    public List<ExerciseAdjustment> calculateAdjustments(
            List<PlannedExercise> exercises,
            int sessionRpe,
            Map<Long, Integer> exerciseRpeMap) {

        List<ExerciseAdjustment> adjustments = new ArrayList<>();
        for (PlannedExercise exercise : exercises) {
            int effectiveRpe = exerciseRpeMap.getOrDefault(exercise.getId(), sessionRpe);
            ExerciseAdjustment adj = adjustExercise(exercise, effectiveRpe);
            adjustments.add(adj);
            applyAdjustment(exercise, adj);
        }
        return adjustments;
    }

    private ExerciseAdjustment adjustExercise(PlannedExercise ex, int rpe) {
        double prevWeight = ex.getWeightKg();
        int    prevReps   = ex.getReps();
        int    prevSets   = ex.getSets();

        double newWeight = prevWeight;
        int    newReps   = prevReps;
        int    newSets   = prevSets;
        String reason;

        if (rpe <= 4) {
            // ── Zu leicht: intensivieren ──────────────────────────
            newWeight = round(prevWeight * 1.10);   // +10%
            newReps   = Math.min(prevReps + 2, 20); // +2 Wdh (max 20)
            reason = String.format(
                    "RPE %d: Einheit war sehr leicht. Gewicht +10%%, Wiederholungen +2.", rpe);
            log.info("'{}': RPE={} → stark intensiviert", ex.getExerciseName(), rpe);

        } else if (rpe <= 6) {
            // ── Genau richtig: KEINE Änderung ─────────────────────
            reason = String.format(
                    "RPE %d: Optimale Belastung. Parameter bleiben unverändert.", rpe);
            log.info("'{}': RPE={} → keine Änderung (Zielbereich)", ex.getExerciseName(), rpe);

        } else if (rpe == 7) {
            // ── Leicht über Ziel: minimale Steigerung ─────────────
            newWeight = round(prevWeight * 1.025);  // +2.5%
            reason = String.format(
                    "RPE %d: Guter Bereich. Minimale Steigerung (+2.5%%) für progressive Überladung.", rpe);
            log.info("'{}': RPE={} → leicht intensiviert", ex.getExerciseName(), rpe);

        } else if (rpe == 8) {
            // ── Oberer Zielbereich: unverändert ───────────────────
            reason = String.format(
                    "RPE %d: Oberer Zielbereich. Parameter bleiben stabil.", rpe);
            log.info("'{}': RPE={} → stabil", ex.getExerciseName(), rpe);

        } else if (rpe == 9) {
            // ── Zu schwer: moderat reduzieren ─────────────────────
            newWeight = round(prevWeight * 0.95);   // −5%
            reason = String.format(
                    "RPE %d: Zu anstrengend. Gewicht −5%% zur Erholung und Technikverbesserung.", rpe);
            log.info("'{}': RPE={} → moderat reduziert", ex.getExerciseName(), rpe);

        } else {
            // ── Maximum (RPE 10): deutlich reduzieren ─────────────
            newWeight = round(prevWeight * 0.90);   // −10%
            newReps   = Math.max(prevReps - 2, 3);  // −2 Wdh (min 3)
            reason = String.format(
                    "RPE %d: Maximale Belastung. Gewicht −10%%, Wdh −2. Übertraining vermeiden.", rpe);
            log.info("'{}': RPE={} → stark reduziert", ex.getExerciseName(), rpe);
        }

        // Sicherheitsgrenzen
        newWeight = Math.max(0, newWeight);
        newReps   = Math.max(1, newReps);
        newSets   = Math.max(1, newSets);

        return new ExerciseAdjustment(
                ex.getId(), ex.getExerciseName(),
                prevWeight, newWeight,
                prevReps, newReps,
                prevSets, newSets,
                reason
        );
    }

    private void applyAdjustment(PlannedExercise exercise, ExerciseAdjustment adj) {
        exercise.setWeightKg(adj.newWeight());
        exercise.setReps(adj.newReps());
        exercise.setSets(adj.newSets());
    }

    /** Rundet auf 0.5 kg (praxisnahe Hantelscheiben-Stufen) */
    private double round(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}