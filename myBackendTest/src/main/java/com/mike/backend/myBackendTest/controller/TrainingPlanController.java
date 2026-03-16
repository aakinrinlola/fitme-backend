package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.CreateTrainingPlanRequest;
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
 * Trainingsplan-Endpoints (geschützt).
 * User können nur ihre eigenen Pläne erstellen/sehen.
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
     * Trainingsplan erstellen.
     * Die userId im Request wird ignoriert → stattdessen JWT-User-ID verwendet.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(@Valid @RequestBody CreateTrainingPlanRequest request) {
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
     * Einen Trainingsplan abrufen.
     * Prüft ob der Plan dem aktuellen User gehört.
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
     * Alle eigenen Trainingspläne abrufen.
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
