package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse.ExerciseAdjustment;
import com.mike.backend.myBackendTest.entity.PlannedExercise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Regelbasierte Trainingsanpassung auf Basis der RPE-Werte.
 *
 * Gemäß Proposal: "Die numerische Trainingsanpassung erfolgt primär regelbasiert.
 * Grundlage bildet eine autoregulatorische Logik auf Basis der Rate of Perceived Exertion (RPE)."
 *
 * RPE-Skala (1–10):
 *   1–4:  Sehr leicht → Gewicht/Volumen deutlich erhöhen
 *   5–6:  Leicht      → Gewicht moderat erhöhen
 *   7–8:  Zielbereich → Minimal anpassen oder beibehalten
 *   9:    Schwer      → Gewicht leicht senken
 *   10:   Maximum     → Gewicht/Volumen deutlich senken
 */
@Service
public class RpeAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(RpeAdjustmentService.class);

    /**
     * Berechnet Anpassungen für alle Übungen basierend auf Session-RPE
     * und (wenn vorhanden) individuellen Übungs-RPE-Werten.
     */
    public List<ExerciseAdjustment> calculateAdjustments(
            List<PlannedExercise> exercises,
            int sessionRpe,
            java.util.Map<Long, Integer> exerciseRpeMap) {

        List<ExerciseAdjustment> adjustments = new ArrayList<>();

        for (PlannedExercise exercise : exercises) {
            // Wenn ein individueller RPE-Wert für diese Übung existiert, nutze diesen;
            // ansonsten fällt auf den Session-RPE zurück
            int effectiveRpe = exerciseRpeMap.getOrDefault(exercise.getId(), sessionRpe);

            ExerciseAdjustment adjustment = adjustExercise(exercise, effectiveRpe);
            adjustments.add(adjustment);

            // Anpassungen sofort in die geplante Übung übernehmen
            applyAdjustment(exercise, adjustment);
        }

        return adjustments;
    }

    private ExerciseAdjustment adjustExercise(PlannedExercise ex, int rpe) {
        double prevWeight = ex.getWeightKg();
        int prevReps = ex.getReps();
        int prevSets = ex.getSets();

        double newWeight = prevWeight;
        int newReps = prevReps;
        int newSets = prevSets;
        String reason;

        if (rpe <= 4) {
            // Sehr leicht → deutliche Steigerung
            newWeight = round(prevWeight * 1.10); // +10%
            if (prevReps < 12) newReps = prevReps + 2;
            reason = String.format("RPE %d: Einheit war sehr leicht. Gewicht um 10%% erhöht, Wiederholungen angepasst.", rpe);
            log.info("Übung '{}': RPE={}, deutliche Steigerung", ex.getExerciseName(), rpe);

        } else if (rpe <= 6) {
            // Leicht → moderate Steigerung
            newWeight = round(prevWeight * 1.05); // +5%
            reason = String.format("RPE %d: Belastung war moderat. Gewicht um 5%% erhöht für nächste Einheit.", rpe);
            log.info("Übung '{}': RPE={}, moderate Steigerung", ex.getExerciseName(), rpe);

        } else if (rpe <= 8) {
            // Zielbereich → beibehalten oder minimale Anpassung
            if (rpe == 7) {
                newWeight = round(prevWeight * 1.025); // +2.5%
                reason = String.format("RPE %d: Guter Zielbereich. Minimale Steigerung (+2.5%%) für progressive Overload.", rpe);
            } else {
                reason = String.format("RPE %d: Optimaler Belastungsbereich. Parameter beibehalten.", rpe);
            }
            log.info("Übung '{}': RPE={}, Zielbereich", ex.getExerciseName(), rpe);

        } else if (rpe == 9) {
            // Schwer → leichte Reduktion
            newWeight = round(prevWeight * 0.95); // -5%
            reason = String.format("RPE %d: Einheit war sehr anstrengend. Gewicht um 5%% reduziert zur Erholung.", rpe);
            log.info("Übung '{}': RPE={}, leichte Reduktion", ex.getExerciseName(), rpe);

        } else {
            // RPE 10 → deutliche Reduktion
            newWeight = round(prevWeight * 0.90); // -10%
            if (prevReps > 6) newReps = prevReps - 2;
            reason = String.format("RPE %d: Maximale Belastung erreicht. Gewicht -10%%, Wiederholungen reduziert. Überbelastung vermeiden.", rpe);
            log.info("Übung '{}': RPE={}, deutliche Reduktion", ex.getExerciseName(), rpe);
        }

        // Sicherheitsgrenzen
        newWeight = Math.max(0, newWeight);
        newReps = Math.max(1, newReps);
        newSets = Math.max(1, newSets);

        return new ExerciseAdjustment(
            ex.getId(),
            ex.getExerciseName(),
            prevWeight,
            newWeight,
            prevReps,
            newReps,
            prevSets,
            newSets,
            reason
        );
    }

    private void applyAdjustment(PlannedExercise exercise, ExerciseAdjustment adj) {
        exercise.setWeightKg(adj.newWeight());
        exercise.setReps(adj.newReps());
        exercise.setSets(adj.newSets());
    }

    private double round(double value) {
        // Auf 0.5 kg runden (praxisnah)
        return Math.round(value * 2.0) / 2.0;
    }
}
