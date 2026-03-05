package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.SessionFeedbackRequest;
import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse;
import com.mike.backend.myBackendTest.entity.TrainingSession;
import com.mike.backend.myBackendTest.service.TrainingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Der zentrale Endpoint für den Trainings-Feedback-Flow:
 *
 *   Frontend (Angular)
 *       ↓ POST /api/feedback/{userId}
 *   FeedbackController
 *       ↓
 *   TrainingService.processFeedback()
 *       ├── RpeAdjustmentService  (regelbasierte Anpassung)
 *       └── AiService             (KI-Erklärung via LLM)
 *       ↓
 *   SessionFeedbackResponse (Anpassungen + KI-Text)
 *       ↓
 *   Frontend zeigt dem User die Erklärung an
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final TrainingService trainingService;

    public FeedbackController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    /**
     * Trainings-Feedback einreichen.
     *
     * Beispiel-Request:
     * POST /api/feedback/1
     * {
     *   "trainingPlanId": 1,
     *   "sessionRpe": 7,
     *   "userNote": "Bankdrücken fühlte sich heute schwer an",
     *   "exerciseFeedbacks": [
     *     { "plannedExerciseId": 1, "exerciseRpe": 8, "setsCompleted": 3, "repsCompleted": 10, "weightUsed": 60.0 },
     *     { "plannedExerciseId": 2, "exerciseRpe": 6, "setsCompleted": 3, "repsCompleted": 12, "weightUsed": 40.0 }
     *   ]
     * }
     *
     * Antwort enthält:
     * - KI-generierte Erklärung (oder Fallback-Text)
     * - Regelbasierte Anpassungen pro Übung
     * - Adhärenz-Statistiken
     */
    @PostMapping("/{userId}")
    public ResponseEntity<SessionFeedbackResponse> submitFeedback(
            @PathVariable Long userId,
            @Valid @RequestBody SessionFeedbackRequest request) {

        SessionFeedbackResponse response = trainingService.processFeedback(userId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Trainingshistorie eines Benutzers abrufen.
     */
    @GetMapping("/history/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@PathVariable Long userId) {
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
