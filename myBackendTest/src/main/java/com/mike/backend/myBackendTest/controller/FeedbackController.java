package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.SessionFeedbackRequest;
import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse;
import com.mike.backend.myBackendTest.entity.TrainingSession;
import com.mike.backend.myBackendTest.security.SecurityHelper;
import com.mike.backend.myBackendTest.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Trainings-Feedback-Endpoints (geschützt, JWT erforderlich).
 *
 * WICHTIG: Die User-ID kommt jetzt aus dem JWT-Token,
 * nicht mehr aus der URL → verhindert IDOR-Angriffe.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final TrainingService trainingService;
    private final SecurityHelper securityHelper;

    public FeedbackController(TrainingService trainingService, SecurityHelper securityHelper) {
        this.trainingService = trainingService;
        this.securityHelper = securityHelper;
    }

    /**
     * Trainings-Feedback einreichen.
     * Die User-ID wird aus dem JWT extrahiert → der User kann nur für sich selbst Feedback abgeben.
     */
    @PostMapping
    public ResponseEntity<SessionFeedbackResponse> submitFeedback(
            @Valid @RequestBody SessionFeedbackRequest request) {

        Long userId = securityHelper.getCurrentUserId();
        SessionFeedbackResponse response = trainingService.processFeedback(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Eigene Trainingshistorie abrufen.
     * Gibt nur die Sessions des authentifizierten Users zurück.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getMyHistory() {
        Long userId = securityHelper.getCurrentUserId();
        List<TrainingSession> sessions = trainingService.getSessionHistory(userId);
        return ResponseEntity.ok(sessions.stream().map(s -> Map.<String, Object>of(
            "sessionId", s.getId(),
            "date", s.getSessionDate().toString(),
            "sessionRpe", s.getSessionRpe() != null ? s.getSessionRpe() : 0,
            "completed", s.isCompleted(),
            "aiResponse", s.getAiResponse() != null ? s.getAiResponse() : "",
            "userNote", s.getUserNote() != null ? s.getUserNote() : ""
        )).toList());
    }
}
