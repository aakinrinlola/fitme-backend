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
 * Trainingsplan-Endpoints.
 *
 * POST   /api/training-plans              → Manuell erstellen
 * POST   /api/training-plans/generate     → KI-generiert
 * GET    /api/training-plans              → Alle eigenen Pläne
 * GET    /api/training-plans/{id}         → Einzelplan
 * GET    /api/training-plans/{id}/feedback-availability → Feedback-Verfügbarkeit (NEU)
 * PATCH  /api/training-plans/{id}/status  → Aktiv/Inaktiv
 * DELETE /api/training-plans/{id}         → Löschen
 *
 * WICHTIG: /generate und /{id}/feedback-availability VOR /{id}
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

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(
            @Valid @RequestBody CreateTrainingPlanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.createPlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildSummary(plan, "Trainingsplan erfolgreich erstellt"));
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generatePlan(
            @Valid @RequestBody GeneratePlanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.generateAndCreatePlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildSummary(plan, "KI-Trainingsplan erfolgreich generiert"));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyPlans() {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(
                trainingService.getUserPlans(userId).stream()
                        .map(p -> buildSummary(p, null))
                        .toList()
        );
    }

    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        TrainingPlan plan = trainingService.getPlanForUser(planId, userId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id",                      plan.getId());
        resp.put("planName",                plan.getPlanName());
        resp.put("description",             plan.getDescription() != null ? plan.getDescription() : "");
        resp.put("active",                  plan.isActive());
        resp.put("activeUntil",             plan.getActiveUntil() != null ? plan.getActiveUntil().toString() : null);
        resp.put("planDurationWeeks",       plan.getPlanDurationWeeks());
        resp.put("currentWeek",             plan.getCurrentWeek());
        resp.put("feedbackAllowedThisWeek", plan.isFeedbackAllowedThisWeek());
        resp.put("nextFeedbackAvailableAt", plan.getNextFeedbackAvailableAt() != null
                ? plan.getNextFeedbackAvailableAt().toString() : null);
        resp.put("exercises", plan.getExercises().stream().map(e -> {
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

    /**
     * GET /api/training-plans/{planId}/feedback-availability
     * Prüft ob Feedback für diese Woche möglich ist.
     * Frontend fragt das beim Laden der Feedback-Seite ab.
     */
    @GetMapping("/{planId}/feedback-availability")
    public ResponseEntity<Map<String, Object>> getFeedbackAvailability(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        Map<String, Object> availability = trainingService.getFeedbackAvailability(planId, userId);
        return ResponseEntity.ok(availability);
    }

    /**
     * PATCH /api/training-plans/{planId}/status
     * Body: { "active": true } oder { "active": false }
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
        resp.put("message",     active ? "Plan aktiviert (1 Monat)" : "Plan deaktiviert");
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long planId) {
        Long userId = securityHelper.getCurrentUserId();
        trainingService.deletePlan(planId, userId);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> buildSummary(TrainingPlan plan, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",                      plan.getId());
        map.put("planName",                plan.getPlanName());
        map.put("exerciseCount",           plan.getExercises().size());
        map.put("active",                  plan.isActive());
        map.put("activeUntil",             plan.getActiveUntil() != null ? plan.getActiveUntil().toString() : null);
        map.put("currentWeek",             plan.getCurrentWeek());
        map.put("planDurationWeeks",       plan.getPlanDurationWeeks());
        map.put("feedbackAllowedThisWeek", plan.isFeedbackAllowedThisWeek());
        map.put("nextFeedbackAvailableAt", plan.getNextFeedbackAvailableAt() != null
                ? plan.getNextFeedbackAvailableAt().toString() : null);
        if (message != null) map.put("message", message);
        return map;
    }
}