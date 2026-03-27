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
 * POST /api/training-plans              → Manuell erstellen
 * POST /api/training-plans/generate    → KI-Trainingsplan generieren und speichern
 * GET  /api/training-plans             → Alle eigenen aktiven Pläne
 * GET  /api/training-plans/{planId}    → Einzelnen Plan abrufen
 *
 * WICHTIG: /generate muss VOR /{planId} gemappt sein (Spring-Routing-Reihenfolge).
 */
@RestController
@RequestMapping("/api/training-plans")
public class TrainingPlanController {

    private final TrainingService trainingService;
    private final SecurityHelper  securityHelper;

    public TrainingPlanController(TrainingService trainingService, SecurityHelper securityHelper) {
        this.trainingService = trainingService;
        this.securityHelper  = securityHelper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/training-plans
    // Trainingsplan manuell erstellen.
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(
            @Valid @RequestBody CreateTrainingPlanRequest request) {

        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.createPlan(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id",            plan.getId(),
                "planName",      plan.getPlanName(),
                "exerciseCount", plan.getExercises().size(),
                "message",       "Trainingsplan erfolgreich erstellt"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/training-plans/generate
    // KI generiert Übungen und speichert den Plan direkt.
    //
    // Request-Body (GeneratePlanRequest):
    //   planName        – vom User eingegebener Planname (Pflicht)
    //   userPrompt      – Freitext-Wunsch des Users (Pflicht, min 10 Zeichen)
    //   fitnessGoal     – MUSCLE_GAIN | STRENGTH | FAT_LOSS | ENDURANCE | GENERAL_FITNESS (optional)
    //   daysPerWeek     – 1–7 (optional)
    //   focusMuscles    – z.B. "Brust, Schulter, Trizeps" (optional)
    //   experienceLevel – BEGINNER | INTERMEDIATE | ADVANCED (optional)
    //
    // Response: { id, planName, exerciseCount, message }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generatePlan(
            @Valid @RequestBody GeneratePlanRequest request) {

        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.generateAndCreatePlan(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id",            plan.getId(),
                "planName",      plan.getPlanName(),
                "exerciseCount", plan.getExercises().size(),
                "message",       "KI-Trainingsplan erfolgreich generiert"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/training-plans/{planId}
    // Einzelnen Plan abrufen (mit Ownership-Check).
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.getPlanForUser(planId, userId);

        return ResponseEntity.ok(Map.of(
                "id",          plan.getId(),
                "planName",    plan.getPlanName(),
                "description", plan.getDescription() != null ? plan.getDescription() : "",
                "active",      plan.isActive(),
                "exercises",   plan.getExercises().stream().map(e -> Map.of(
                        "id",           e.getId(),
                        "exerciseName", e.getExerciseName(),
                        "sets",         e.getSets(),
                        "reps",         e.getReps(),
                        "weightKg",     e.getWeightKg(),
                        "restSeconds",  e.getRestSeconds(),
                        "targetRpe",    e.getTargetRpe() != null ? e.getTargetRpe() : 7,
                        "order",        e.getExerciseOrder()
                )).toList()
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/training-plans
    // Alle eigenen aktiven Pläne abrufen.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyPlans() {
        Long userId = securityHelper.getCurrentUserId();

        return ResponseEntity.ok(
                trainingService.getUserPlans(userId).stream()
                        .map(plan -> Map.<String, Object>of(
                                "id",            plan.getId(),
                                "planName",      plan.getPlanName(),
                                "exerciseCount", plan.getExercises().size(),
                                "active",        plan.isActive()
                        ))
                        .toList()
        );
    }
}