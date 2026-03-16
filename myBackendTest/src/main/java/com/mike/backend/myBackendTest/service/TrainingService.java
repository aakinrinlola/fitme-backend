package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.*;
import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse.*;
import com.mike.backend.myBackendTest.entity.*;
import com.mike.backend.myBackendTest.exception.ResourceNotFoundException;
import com.mike.backend.myBackendTest.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Zentraler Service: Verarbeitet Trainings-Feedback und orchestriert
 * die regelbasierte Anpassung + KI-Erklärung.
 *
 * Sicherheit: Alle Methoden prüfen, dass der User nur auf seine eigenen Daten zugreift.
 */
@Service
@Transactional
public class TrainingService {

    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    private final TrainingPlanRepository planRepo;
    private final PlannedExerciseRepository exerciseRepo;
    private final TrainingSessionRepository sessionRepo;
    private final UserRepository userRepo;
    private final RpeAdjustmentService rpeService;
    private final AiService aiService;

    public TrainingService(TrainingPlanRepository planRepo,
                           PlannedExerciseRepository exerciseRepo,
                           TrainingSessionRepository sessionRepo,
                           UserRepository userRepo,
                           RpeAdjustmentService rpeService,
                           AiService aiService) {
        this.planRepo = planRepo;
        this.exerciseRepo = exerciseRepo;
        this.sessionRepo = sessionRepo;
        this.userRepo = userRepo;
        this.rpeService = rpeService;
        this.aiService = aiService;
    }

    // ===================== TRAININGSPLAN ERSTELLEN =====================

    /**
     * Erstellt einen Trainingsplan für den authentifizierten User.
     * Die userId kommt aus dem JWT, nicht aus dem Request.
     */
    public TrainingPlan createPlan(Long userId, CreateTrainingPlanRequest req) {
        AppUser user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(req.description());

        int order = 0;
        for (var exInput : req.exercises()) {
            PlannedExercise exercise = new PlannedExercise(
                exInput.exerciseName(),
                exInput.sets(),
                exInput.reps(),
                exInput.weightKg()
            );
            exercise.setRestSeconds(exInput.restSeconds());
            exercise.setTargetRpe(exInput.targetRpe());
            exercise.setExerciseOrder(order++);
            exercise.setTrainingPlan(plan);
            plan.getExercises().add(exercise);
        }

        return planRepo.save(plan);
    }

    /**
     * Trainingsplan abrufen MIT Ownership-Check.
     * @throws SecurityException wenn der Plan nicht dem User gehört
     */
    @Transactional(readOnly = true)
    public TrainingPlan getPlanForUser(Long planId, Long userId) {
        TrainingPlan plan = planRepo.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("Trainingsplan nicht gefunden: " + planId));

        if (!plan.getUser().getId().equals(userId)) {
            throw new SecurityException("Zugriff verweigert: Dieser Trainingsplan gehört dir nicht");
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public List<TrainingPlan> getUserPlans(Long userId) {
        return planRepo.findByUserIdAndActiveTrue(userId);
    }

    // ===================== FEEDBACK VERARBEITEN (KERNLOGIK) =====================

    /**
     * Verarbeitet Trainings-Feedback.
     * Prüft dass der Plan dem User gehört.
     */
    public SessionFeedbackResponse processFeedback(Long userId, SessionFeedbackRequest request) {
        log.info("Verarbeite Feedback: User={}, Plan={}, Session-RPE={}",
            userId, request.trainingPlanId(), request.sessionRpe());

        // 1. Entities laden + Ownership prüfen
        AppUser user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));
        TrainingPlan plan = planRepo.findById(request.trainingPlanId())
            .orElseThrow(() -> new ResourceNotFoundException("Plan nicht gefunden: " + request.trainingPlanId()));

        // Sicherheitscheck: Plan gehört dem User?
        if (!plan.getUser().getId().equals(userId)) {
            throw new SecurityException("Zugriff verweigert: Dieser Trainingsplan gehört dir nicht");
        }

        // 2. TrainingSession speichern
        TrainingSession session = new TrainingSession();
        session.setUser(user);
        session.setTrainingPlan(plan);
        session.setSessionRpe(request.sessionRpe());
        session.setUserNote(request.userNote());
        session.setCompleted(true);

        // Übungsdaten speichern (wenn vorhanden)
        if (request.exerciseFeedbacks() != null) {
            for (var ef : request.exerciseFeedbacks()) {
                SessionExercise se = new SessionExercise();
                se.setSession(session);
                se.setExerciseRpe(ef.exerciseRpe());
                if (ef.setsCompleted() != null) se.setSetsCompleted(ef.setsCompleted());
                if (ef.repsCompleted() != null) se.setRepsCompleted(ef.repsCompleted());
                if (ef.weightUsed() != null) se.setWeightUsed(ef.weightUsed());
                se.setNote(ef.note());

                exerciseRepo.findById(ef.plannedExerciseId()).ifPresent(pe -> {
                    se.setPlannedExercise(pe);
                    se.setExerciseName(pe.getExerciseName());
                });

                session.getExercises().add(se);
            }
        }

        // 3. Regelbasierte Anpassung berechnen
        Map<Long, Integer> exerciseRpeMap = new HashMap<>();
        if (request.exerciseFeedbacks() != null) {
            for (var ef : request.exerciseFeedbacks()) {
                exerciseRpeMap.put(ef.plannedExerciseId(), ef.exerciseRpe());
            }
        }

        List<PlannedExercise> exercises = plan.getExercises();
        List<ExerciseAdjustment> adjustments = rpeService.calculateAdjustments(
            exercises, request.sessionRpe(), exerciseRpeMap);

        exerciseRepo.saveAll(exercises);

        // 4. KI-Erklärung generieren
        String aiExplanation = aiService.generateExplanation(
            user, request.sessionRpe(), request.userNote(), adjustments);

        // 5. Session mit KI-Antwort speichern
        session.setAiResponse(aiExplanation);
        session = sessionRepo.save(session);

        // 6. Adhärenz berechnen
        long totalPlanned = sessionRepo.countByTrainingPlanId(plan.getId());
        long totalCompleted = sessionRepo.countByTrainingPlanIdAndCompletedTrue(plan.getId());
        double adherencePercent = totalPlanned > 0
            ? (double) totalCompleted / totalPlanned * 100.0 : 0.0;

        log.info("Feedback verarbeitet: Session={}, {} Anpassungen, Adhärenz={}%",
            session.getId(), adjustments.size(), String.format("%.1f", adherencePercent));

        return new SessionFeedbackResponse(
            session.getId(),
            request.sessionRpe(),
            aiExplanation,
            adjustments,
            new AdherenceStats(totalPlanned, totalCompleted, adherencePercent)
        );
    }

    // ===================== SESSION HISTORY =====================

    @Transactional(readOnly = true)
    public List<TrainingSession> getSessionHistory(Long userId) {
        return sessionRepo.findByUserIdOrderBySessionDateDesc(userId);
    }
}
