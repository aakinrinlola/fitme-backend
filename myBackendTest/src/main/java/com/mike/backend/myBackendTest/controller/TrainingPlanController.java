package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.CreateTrainingPlanRequest;
import com.mike.backend.myBackendTest.dto.GeneratePlanRequest;
import com.mike.backend.myBackendTest.entity.TrainingPlan;
import com.mike.backend.myBackendTest.security.SecurityHelper;
import com.mike.backend.myBackendTest.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Trainingsplan-Endpoints (geschützt, JWT erforderlich).
 *
 * POST /api/training-plans          → Manuell erstellen
 * POST /api/training-plans/generate → KI erstellt den Plan (NEU)
 * GET  /api/training-plans          → Alle eigenen Pläne
 * GET  /api/training-plans/{planId} → Einen Plan abrufen
 */
@RestController
@RequestMapping("/api/training-plans")
public class TrainingPlanController {

    private final TrainingService trainingService;
    private final SecurityHelper securityHelper;

    public TrainingPlanController(TrainingService trainingService, SecurityHelper securityHelper) {
        this.trainingService = trainingService;
        this.securityHelper = securityHelper;
    }

    /**
     * Trainingsplan manuell erstellen.
     * Die userId wird aus dem JWT extrahiert.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(
            @Valid @RequestBody CreateTrainingPlanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.createPlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", plan.getId(),
                "planName", plan.getPlanName(),
                "exerciseCount", plan.getExercises().size(),
                "message", "Trainingsplan erfolgreich erstellt"
        ));
    }

    /**
     * Trainingsplan per KI generieren und direkt speichern.
     *
     * POST /api/training-plans/generate
     *
     * Body (JSON):
     * {
     *   "planName":        "Mein Push-Day",               (Pflicht)
     *   "userPrompt":      "Ich will Oberkörper aufbauen", (Pflicht)
     *   "fitnessGoal":     "MUSCLE_GAIN",                 (optional)
     *   "daysPerWeek":     3,                             (optional)
     *   "focusMuscles":    "Brust, Schultern, Trizeps",   (optional)
     *   "experienceLevel": "INTERMEDIATE"                 (optional)
     * }
     *
     * Response: gleiche Struktur wie manuelles Erstellen
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generatePlan(
            @Valid @RequestBody GeneratePlanRequest request) {

        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.generateAndCreatePlan(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", plan.getId(),
                "planName", plan.getPlanName(),
                "exerciseCount", plan.getExercises().size(),
                "message", "KI-Trainingsplan erfolgreich generiert"
        ));
    }

    /**
     * Einen Trainingsplan abrufen (mit Ownership-Check).
     */
    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.getPlanForUser(planId, userId);
        return ResponseEntity.ok(Map.of(
                "id", plan.getId(),
                "planName", plan.getPlanName(),
                "description", plan.getDescription() != null ? plan.getDescription() : "",
                "active", plan.isActive(),
                "exercises", plan.getExercises().stream().map(e -> Map.of(
                        "id", e.getId(),
                        "exerciseName", e.getExerciseName(),
                        "sets", e.getSets(),
                        "reps", e.getReps(),
                        "weightKg", e.getWeightKg(),
                        "restSeconds", e.getRestSeconds(),
                        "targetRpe", e.getTargetRpe() != null ? e.getTargetRpe() : 7,
                        "order", e.getExerciseOrder()
                )).toList()
        ));
    }

    /**
     * Alle eigenen aktiven Trainingspläne abrufen.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyPlans() {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(
                trainingService.getUserPlans(userId).stream().map(plan -> Map.<String, Object>of(
                        "id", plan.getId(),
                        "planName", plan.getPlanName(),
                        "exerciseCount", plan.getExercises().size(),
                        "active", plan.isActive()
                )).toList()
        );
    }
}