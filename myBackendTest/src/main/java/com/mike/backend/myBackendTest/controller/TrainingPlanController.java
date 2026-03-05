package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.CreateTrainingPlanRequest;
import com.mike.backend.myBackendTest.entity.TrainingPlan;
import com.mike.backend.myBackendTest.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/training-plans")
public class TrainingPlanController {

    private final TrainingService trainingService;

    public TrainingPlanController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlan(@Valid @RequestBody CreateTrainingPlanRequest request) {
        TrainingPlan plan = trainingService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", plan.getId(),
            "planName", plan.getPlanName(),
            "exerciseCount", plan.getExercises().size(),
            "message", "Trainingsplan erfolgreich erstellt"
        ));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable Long planId) {
        TrainingPlan plan = trainingService.getPlan(planId);
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

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getUserPlans(@PathVariable Long userId) {
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
