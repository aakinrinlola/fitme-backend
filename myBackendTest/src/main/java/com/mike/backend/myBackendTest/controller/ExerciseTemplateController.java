package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.DayTemplateRequest;
import com.mike.backend.myBackendTest.dto.ExerciseTemplateRequest;
import com.mike.backend.myBackendTest.security.SecurityHelper;
import com.mike.backend.myBackendTest.service.ExerciseTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExerciseTemplateController {

    private final ExerciseTemplateService service;
    private final SecurityHelper          securityHelper;

    public ExerciseTemplateController(ExerciseTemplateService service, SecurityHelper securityHelper) {
        this.service        = service;
        this.securityHelper = securityHelper;
    }

    // ── Übungs-Vorlagen ────────────────────────────────────────────────────────

    @GetMapping("/exercise-templates")
    public ResponseEntity<List<Map<String, Object>>> getExerciseTemplates(
            @RequestParam(required = false) String muscleGroup) {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(service.getExerciseTemplates(userId, muscleGroup));
    }

    @PostMapping("/exercise-templates")
    public ResponseEntity<Map<String, Object>> createExerciseTemplate(
            @Valid @RequestBody ExerciseTemplateRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveExerciseTemplate(userId, request));
    }

    @DeleteMapping("/exercise-templates/{id}")
    public ResponseEntity<Void> deleteExerciseTemplate(@PathVariable Long id) {
        Long userId = securityHelper.getCurrentUserId();
        service.deleteExerciseTemplate(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ── Tag-Vorlagen ───────────────────────────────────────────────────────────

    @GetMapping("/day-templates")
    public ResponseEntity<List<Map<String, Object>>> getDayTemplates(
            @RequestParam(required = false) String muscleGroup) {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(service.getDayTemplates(userId, muscleGroup));
    }

    @PostMapping("/day-templates")
    public ResponseEntity<Map<String, Object>> createDayTemplate(
            @Valid @RequestBody DayTemplateRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveDayTemplate(userId, request));
    }

    @DeleteMapping("/day-templates/{id}")
    public ResponseEntity<Void> deleteDayTemplate(@PathVariable Long id) {
        Long userId = securityHelper.getCurrentUserId();
        service.deleteDayTemplate(userId, id);
        return ResponseEntity.noContent().build();
    }
}
