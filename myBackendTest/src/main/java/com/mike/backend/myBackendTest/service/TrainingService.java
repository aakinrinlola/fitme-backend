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
 * Zentraler Service für Trainingsplan-Verwaltung und Feedback-Verarbeitung.
 *
 * NEU: generateAndCreatePlan() iteriert über AiService.GeneratedDay-Objekte
 * und setzt das Feld trainingDay auf jeder PlannedExercise, damit das
 * Frontend die Übungen nach Tag gruppieren kann.
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
        this.planRepo     = planRepo;
        this.exerciseRepo = exerciseRepo;
        this.sessionRepo  = sessionRepo;
        this.userRepo     = userRepo;
        this.rpeService   = rpeService;
        this.aiService    = aiService;
    }

    // ===================== MANUELL ERSTELLEN =====================

    public TrainingPlan createPlan(Long userId, CreateTrainingPlanRequest req) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(req.description());

        int order = 0;
        for (var exInput : req.exercises()) {
            PlannedExercise exercise = buildExercise(exInput, plan, order++, null);
            plan.getExercises().add(exercise);
        }
        return planRepo.save(plan);
    }

    // ===================== KI-GENERIERUNG (MEHRTÄGIG) =====================

    /**
     * Lässt die KI einen mehrtägigen Trainingsplan generieren und speichert ihn.
     *
     * Jede Übung bekommt das Feld trainingDay (z.B. "Tag A", "Tag B"), sodass
     * das Frontend die Übungen nach Tag gruppieren und anzeigen kann.
     */
    public TrainingPlan generateAndCreatePlan(Long userId, GeneratePlanRequest req) {
        log.info("KI-Plan-Generierung: User={}, Plan='{}'", userId, req.planName());

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        AiService.GeneratedPlan generated = aiService.generateTrainingPlan(user, req);

        if (generated.days().isEmpty()) {
            throw new IllegalStateException("KI konnte keinen Plan erstellen. Bitte Beschreibung präzisieren.");
        }

        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(buildPlanDescription(req, generated));

        int globalOrder = 0;
        for (AiService.GeneratedDay day : generated.days()) {
            for (AiService.GeneratedExercise genEx : day.exercises()) {
                var exInput = new CreateTrainingPlanRequest.ExerciseInput(
                        genEx.exerciseName(), genEx.sets(), genEx.reps(),
                        genEx.weightKg(), genEx.restSeconds(), genEx.targetRpe()
                );
                // trainingDay wird gesetzt, damit das Frontend nach Tag gruppieren kann
                PlannedExercise exercise = buildExercise(exInput, plan, globalOrder++, day.dayName());
                plan.getExercises().add(exercise);
            }
        }

        TrainingPlan saved = planRepo.save(plan);
        log.info("KI-Plan gespeichert: planId={}, {} Tage, {} Übungen gesamt",
                saved.getId(), generated.days().size(), saved.getExercises().size());
        return saved;
    }

    /**
     * Baut eine Plan-Beschreibung die auch die Tage-Struktur widerspiegelt.
     */
    private String buildPlanDescription(GeneratePlanRequest req, AiService.GeneratedPlan generated) {
        StringBuilder desc = new StringBuilder();
        desc.append("🤖 KI-generiert: ").append(req.userPrompt());

        if (req.fitnessGoal() != null && !req.fitnessGoal().isBlank()) {
            desc.append(" | Ziel: ").append(translateGoal(req.fitnessGoal()));
        }
        if (req.daysPerWeek() != null) {
            desc.append(" | ").append(req.daysPerWeek()).append("x/Woche");
        }

        int numDays    = generated.days().size();
        int daysPerWeek = req.daysPerWeek() != null ? req.daysPerWeek() : 3;

        // Tage-Struktur + Rotationshinweis
        if (numDays > 0) {
            List<String> dayNames = generated.days().stream().map(AiService.GeneratedDay::dayName).toList();
            desc.append(" | Tage: ").append(String.join(", ", dayNames));

            // Rotationshinweis nur wenn mehr Trainingstage als Planvarianten
            if (numDays > 1 && daysPerWeek > numDays) {
                desc.append(" | Rotation: ");
                for (int i = 0; i < daysPerWeek; i++) {
                    if (i > 0) desc.append(" → ");
                    desc.append(dayNames.get(i % numDays));
                }
            }
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
            default                -> goal;
        };
    }

    // ===================== PLAN ABFRAGEN =====================

    @Transactional(readOnly = true)
    public TrainingPlan getPlanForUser(Long planId, Long userId) {
        TrainingPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan nicht gefunden: " + planId));
        if (!plan.getUser().getId().equals(userId)) {
            throw new SecurityException("Zugriff verweigert: Dieser Plan gehört dir nicht");
        }
        return plan;
    }

    @Transactional(readOnly = true)
    public List<TrainingPlan> getUserPlans(Long userId) {
        return planRepo.findByUserIdAndActiveTrue(userId);
    }

    // ===================== FEEDBACK VERARBEITEN =====================

    public SessionFeedbackResponse processFeedback(Long userId, SessionFeedbackRequest request) {
        log.info("Feedback: User={}, Plan={}, RPE={}", userId, request.trainingPlanId(), request.sessionRpe());

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));
        TrainingPlan plan = planRepo.findById(request.trainingPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan nicht gefunden: " + request.trainingPlanId()));

        if (!plan.getUser().getId().equals(userId)) {
            throw new SecurityException("Zugriff verweigert");
        }

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
                if (ef.weightUsed()    != null) se.setWeightUsed(ef.weightUsed());
                se.setNote(ef.note());
                exerciseRepo.findById(ef.plannedExerciseId()).ifPresent(pe -> {
                    se.setPlannedExercise(pe);
                    se.setExerciseName(pe.getExerciseName());
                });
                session.getExercises().add(se);
            }
        }

        Map<Long, Integer> rpeMap = new HashMap<>();
        if (request.exerciseFeedbacks() != null) {
            for (var ef : request.exerciseFeedbacks())
                rpeMap.put(ef.plannedExerciseId(), ef.exerciseRpe());
        }

        List<PlannedExercise> exercises = plan.getExercises();
        List<ExerciseAdjustment> adjustments = rpeService.calculateAdjustments(
                exercises, request.sessionRpe(), rpeMap);
        exerciseRepo.saveAll(exercises);

        String aiExplanation = aiService.generateExplanation(
                user, request.sessionRpe(), request.userNote(), adjustments);
        session.setAiResponse(aiExplanation);
        session = sessionRepo.save(session);

        long totalPlanned   = sessionRepo.countByTrainingPlanId(plan.getId());
        long totalCompleted = sessionRepo.countByTrainingPlanIdAndCompletedTrue(plan.getId());
        double adherence    = totalPlanned > 0 ? (double) totalCompleted / totalPlanned * 100.0 : 0.0;

        log.info("Feedback verarbeitet: Session={}, {} Anpassungen", session.getId(), adjustments.size());
        return new SessionFeedbackResponse(session.getId(), request.sessionRpe(), aiExplanation,
                adjustments, new AdherenceStats(totalPlanned, totalCompleted, adherence));
    }

    // ===================== SESSION HISTORY =====================

    @Transactional(readOnly = true)
    public List<TrainingSession> getSessionHistory(Long userId) {
        return sessionRepo.findByUserIdOrderBySessionDateDesc(userId);
    }

    // ===================== HILFSMETHODEN =====================

    /**
     * Baut eine PlannedExercise-Entity.
     *
     * @param trainingDay  "Tag A", "Tag B", … oder null für manuelle Pläne
     */
    private PlannedExercise buildExercise(CreateTrainingPlanRequest.ExerciseInput input,
                                          TrainingPlan plan, int order, String trainingDay) {
        PlannedExercise exercise = new PlannedExercise(
                input.exerciseName(), input.sets(), input.reps(), input.weightKg());
        exercise.setRestSeconds(input.restSeconds());
        exercise.setTargetRpe(input.targetRpe());
        exercise.setExerciseOrder(order);
        exercise.setTrainingPlan(plan);
        exercise.setTrainingDay(trainingDay); // null für manuelle Pläne
        return exercise;
    }
}