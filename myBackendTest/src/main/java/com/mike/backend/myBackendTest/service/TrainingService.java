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

/**
 * Zentraler Service: Verarbeitet Trainings-Feedback und orchestriert
 * die regelbasierte Anpassung + KI-Erklärung.
 *
 * Neu: generateAndCreatePlan() — lässt die KI einen Plan generieren und speichert ihn direkt.
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

    // ===================== TRAININGSPLAN MANUELL ERSTELLEN =====================

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
            PlannedExercise exercise = buildExercise(exInput, plan, order++);
            plan.getExercises().add(exercise);
        }

        return planRepo.save(plan);
    }

    // ===================== TRAININGSPLAN PER KI GENERIEREN (NEU) =====================

    /**
     * Lässt die KI einen Trainingsplan generieren und speichert ihn direkt.
     *
     * Ablauf:
     * 1. User-Profil laden
     * 2. KI-Prompt mit Profil + Nutzerwunsch aufbauen
     * 3. KI generiert Übungen als JSON
     * 4. Plan wird gespeichert und zurückgegeben
     *
     * Bei KI-Fehler: Sinnvoller Fallback-Plan (kein Fehler für den User).
     *
     * @param userId  Aus JWT extrahierte User-ID
     * @param req     Generierungs-Request (Planname, Prompt, optionale Parameter)
     * @return        Gespeicherter TrainingPlan mit KI-generierten Übungen
     */
    public TrainingPlan generateAndCreatePlan(Long userId, GeneratePlanRequest req) {
        log.info("KI-Plan-Generierung gestartet: User={}, Planname='{}'", userId, req.planName());

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        // KI generiert den Plan (gibt GeneratedPlan zurück, nicht List)
        AiService.GeneratedPlan generated = aiService.generateTrainingPlan(user, req);

        if (generated.exercises().isEmpty()) {
            throw new IllegalStateException(
                    "KI konnte keinen Plan erstellen. Bitte beschreibe dein Training genauer.");
        }

// Plan aufbauen — planName kommt aus dem Request (vom User eingegeben)
        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        String description = buildPlanDescription(req);
        plan.setDescription(description);

        int order = 0;
        for (var genEx : generated.exercises()) {
            // GeneratedExercise → ExerciseInput konvertieren
            var exInput = new CreateTrainingPlanRequest.ExerciseInput(
                    genEx.exerciseName(),
                    genEx.sets(),
                    genEx.reps(),
                    genEx.weightKg(),
                    genEx.restSeconds(),
                    genEx.targetRpe()
            );
            PlannedExercise exercise = buildExercise(exInput, plan, order++);
            plan.getExercises().add(exercise);
        }

        TrainingPlan saved = planRepo.save(plan);
        log.info("KI-Plan gespeichert: planId={}, {} Übungen", saved.getId(), saved.getExercises().size());

        return saved;
    }

    /**
     * Baut die Plan-Beschreibung aus dem GeneratePlanRequest.
     */
    private String buildPlanDescription(GeneratePlanRequest req) {
        StringBuilder desc = new StringBuilder();
        desc.append("🤖 KI-generiert: ").append(req.userPrompt());

        if (req.fitnessGoal() != null && !req.fitnessGoal().isBlank()) {
            desc.append(" | Ziel: ").append(translateGoal(req.fitnessGoal()));
        }
        if (req.daysPerWeek() != null) {
            desc.append(" | ").append(req.daysPerWeek()).append("x/Woche");
        }
        if (req.focusMuscles() != null && !req.focusMuscles().isBlank()) {
            desc.append(" | Fokus: ").append(req.focusMuscles());
        }

        return desc.toString();
    }

    private String translateGoal(String goal) {
        return switch (goal.toUpperCase()) {
            case "MUSCLE_GAIN"     -> "Muskelaufbau";
            case "FAT_LOSS"        -> "Fettabbau";
            case "STRENGTH"        -> "Kraftaufbau";
            case "ENDURANCE"       -> "Ausdauer";
            case "GENERAL_FITNESS" -> "Allgemeine Fitness";
            default -> goal;
        };
    }

    // ===================== PLAN ABFRAGEN =====================

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

    // ===================== HILFSMETHODEN =====================

    /** Baut eine PlannedExercise-Entity aus dem Input-DTO. */
    private PlannedExercise buildExercise(CreateTrainingPlanRequest.ExerciseInput input,
                                          TrainingPlan plan, int order) {
        PlannedExercise exercise = new PlannedExercise(
                input.exerciseName(),
                input.sets(),
                input.reps(),
                input.weightKg()
        );
        exercise.setRestSeconds(input.restSeconds());
        exercise.setTargetRpe(input.targetRpe());
        exercise.setExerciseOrder(order);
        exercise.setTrainingPlan(plan);
        return exercise;
    }
}