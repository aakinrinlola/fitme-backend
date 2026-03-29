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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trainingsplan-Endpoints (JWT erforderlich).
 *
 * POST   /api/training-plans              → Manuell erstellen
 * POST   /api/training-plans/generate     → KI-generiert
 * GET    /api/training-plans              → Alle Pläne des Users
 * GET    /api/training-plans/{planId}     → Einzelner Plan
 * PATCH  /api/training-plans/{planId}/status → Aktiv/Inaktiv umschalten (NEU)
 * DELETE /api/training-plans/{planId}     → Plan löschen
 *
 * WICHTIG: /generate VOR /{planId} gemappt.
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

    // ── POST /api/training-plans ─────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(
            @Valid @RequestBody CreateTrainingPlanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.createPlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildSummary(plan, "Trainingsplan erfolgreich erstellt"));
    }

    // ── POST /api/training-plans/generate ────────────────────────────────────
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generatePlan(
            @Valid @RequestBody GeneratePlanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.generateAndCreatePlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildSummary(plan, "KI-Trainingsplan erfolgreich generiert"));
    }

    // ── GET /api/training-plans ──────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyPlans() {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(
                trainingService.getUserPlans(userId).stream()
                        .map(plan -> buildSummary(plan, null))
                        .toList()
        );
    }

    // ── GET /api/training-plans/{planId} ─────────────────────────────────────
    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.getPlanForUser(planId, userId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",          plan.getId());
        resp.put("planName",    plan.getPlanName());
        resp.put("description", plan.getDescription() != null ? plan.getDescription() : "");
        resp.put("active",      plan.isActive());
        resp.put("activeUntil", plan.getActiveUntil() != null ? plan.getActiveUntil().toString() : null);
        resp.put("exercises",   plan.getExercises().stream().map(e -> {
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("id",           e.getId());
            ex.put("exerciseName", e.getExerciseName());
            ex.put("sets",         e.getSets());
            ex.put("reps",         e.getReps());
            ex.put("weightKg",     e.getWeightKg());
            ex.put("restSeconds",  e.getRestSeconds());
            ex.put("targetRpe",    e.getTargetRpe() != null ? e.getTargetRpe() : 7);
            ex.put("order",        e.getExerciseOrder());
            ex.put("trainingDay",  e.getTrainingDay() != null ? e.getTrainingDay() : "");
            return ex;
        }).toList());

        return ResponseEntity.ok(resp);
    }

    // ── PATCH /api/training-plans/{planId}/status ─────────────────────────────
    /**
     * Ändert den Aktiv-Status eines Plans.
     *
     * Request-Body: { "active": true } oder { "active": false }
     * Response:     { id, active, activeUntil, message }
     *
     * Aktivierung:   active=true, activeUntil wird auf jetzt+1Monat gesetzt.
     * Deaktivierung: active=false, activeUntil bleibt unverändert.
     */
    @PatchMapping("/{planId}/status")
    public ResponseEntity<Map<String, Object>> setStatus(
            @PathVariable Long planId,
            @RequestBody Map<String, Object> request) {

        Object activeObj = request.get("active");
        if (activeObj == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Feld 'active' ist erforderlich"));
        }
        boolean active = Boolean.parseBoolean(activeObj.toString());
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.setActiveStatus(planId, userId, active);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",          plan.getId());
        resp.put("active",      plan.isActive());
        resp.put("activeUntil", plan.getActiveUntil() != null ? plan.getActiveUntil().toString() : null);
        resp.put("message",     active ? "Plan wurde aktiviert (aktiv für 1 Monat)" : "Plan wurde deaktiviert");
        return ResponseEntity.ok(resp);
    }

    // ── DELETE /api/training-plans/{planId} ──────────────────────────────────
    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        trainingService.deletePlan(planId, userId);
        return ResponseEntity.noContent().build();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /** Baut eine einheitliche Plan-Summary-Response (inkl. activeUntil) */
    private Map<String, Object> buildSummary(TrainingPlan plan, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",            plan.getId());
        map.put("planName",      plan.getPlanName());
        map.put("exerciseCount", plan.getExercises().size());
        map.put("active",        plan.isActive());
        map.put("activeUntil",   plan.getActiveUntil() != null ? plan.getActiveUntil().toString() : null);
        if (message != null) map.put("message", message);
        return map;
    }
}