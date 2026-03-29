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

import java.time.LocalDateTime;
import java.util.*;

/**
 * Zentraler Service für Trainingsplan-Verwaltung und Feedback.
 *
 * NEU:
 * - setActiveStatus() — manuelles Aktivieren/Deaktivieren mit 1-Monats-Logik
 * - checkAndApplyExpiry() — lazy Ablaufprüfung beim Laden
 * - activeUntil wird bei Erstellung gesetzt (now + 1 Monat)
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

        // TrainingPlan-Konstruktor setzt activeUntil = now + 1 Monat
        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(req.description());

        int order = 0;
        for (var exInput : req.exercises()) {
            plan.getExercises().add(buildExercise(exInput, plan, order++, null));
        }
        return planRepo.save(plan);
    }

    // ===================== KI-GENERIERUNG =====================

    public TrainingPlan generateAndCreatePlan(Long userId, GeneratePlanRequest req) {
        log.info("KI-Plan-Generierung: User={}, Plan='{}'", userId, req.planName());

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        AiService.GeneratedPlan generated = aiService.generateTrainingPlan(user, req);
        if (generated.days().isEmpty()) {
            throw new IllegalStateException("KI konnte keinen Plan erstellen.");
        }

        // TrainingPlan-Konstruktor setzt activeUntil = now + 1 Monat
        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(buildPlanDescription(req, generated));

        int globalOrder = 0;
        for (AiService.GeneratedDay day : generated.days()) {
            for (AiService.GeneratedExercise genEx : day.exercises()) {
                var exInput = new CreateTrainingPlanRequest.ExerciseInput(
                        genEx.exerciseName(), genEx.sets(), genEx.reps(),
                        genEx.weightKg(), genEx.restSeconds(), genEx.targetRpe()
                );
                plan.getExercises().add(buildExercise(exInput, plan, globalOrder++, day.dayName()));
            }
        }

        TrainingPlan saved = planRepo.save(plan);
        log.info("KI-Plan gespeichert: planId={}, activeUntil={}", saved.getId(), saved.getActiveUntil());
        return saved;
    }

    // ===================== STATUS ÄNDERN (NEU) =====================

    /**
     * Ändert den Aktiv-Status eines Plans manuell.
     *
     * Bei Aktivierung:
     *   - active = true
     *   - lastActivatedAt = jetzt
     *   - activeUntil = jetzt + 1 Monat  ← neuer Zeitraum startet
     *
     * Bei Deaktivierung:
     *   - active = false
     *   - activeUntil und lastActivatedAt bleiben unverändert
     */
    public TrainingPlan setActiveStatus(Long planId, Long userId, boolean active) {
        TrainingPlan plan = getPlanForUser(planId, userId);

        if (active) {
            plan.setActive(true);
            plan.setLastActivatedAt(LocalDateTime.now());
            plan.setActiveUntil(LocalDateTime.now().plusMonths(1));
            log.info("Plan {} aktiviert. Aktiv bis: {}", planId, plan.getActiveUntil());
        } else {
            plan.setActive(false);
            log.info("Plan {} manuell deaktiviert.", planId);
        }

        return planRepo.save(plan);
    }

    // ===================== PLAN LÖSCHEN =====================

    public void deletePlan(Long planId, Long userId) {
        TrainingPlan plan = getPlanForUser(planId, userId);
        planRepo.delete(plan);
        log.info("Plan gelöscht: planId={}, userId={}", planId, userId);
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

    /**
     * Gibt alle aktiven Pläne des Users zurück.
     * Prüft dabei lazy, ob Pläne abgelaufen sind, und deaktiviert sie ggf.
     */
    public List<TrainingPlan> getUserPlans(Long userId) {
        List<TrainingPlan> plans = planRepo.findByUserId(userId);
        plans.forEach(this::checkAndApplyExpiry);
        return plans;
    }

    /**
     * Lazy Ablaufprüfung: Falls ein Plan aktiv ist, aber activeUntil in der Vergangenheit liegt,
     * wird er automatisch als inaktiv markiert und gespeichert.
     */
    private void checkAndApplyExpiry(TrainingPlan plan) {
        if (plan.isActive()
                && plan.getActiveUntil() != null
                && LocalDateTime.now().isAfter(plan.getActiveUntil())) {
            plan.setActive(false);
            planRepo.save(plan);
            log.info("Plan {} automatisch deaktiviert (activeUntil: {} liegt in der Vergangenheit)",
                    plan.getId(), plan.getActiveUntil());
        }
    }

    // ===================== FEEDBACK VERARBEITEN =====================

    public SessionFeedbackResponse processFeedback(Long userId, SessionFeedbackRequest request) {
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
            request.exerciseFeedbacks().forEach(ef -> rpeMap.put(ef.plannedExerciseId(), ef.exerciseRpe()));
        }

        List<PlannedExercise> exercises = plan.getExercises();
        List<ExerciseAdjustment> adjustments = rpeService.calculateAdjustments(exercises, request.sessionRpe(), rpeMap);
        exerciseRepo.saveAll(exercises);

        String aiExplanation = aiService.generateExplanation(user, request.sessionRpe(), request.userNote(), adjustments);
        session.setAiResponse(aiExplanation);
        session = sessionRepo.save(session);

        long total     = sessionRepo.countByTrainingPlanId(plan.getId());
        long completed = sessionRepo.countByTrainingPlanIdAndCompletedTrue(plan.getId());
        double pct     = total > 0 ? (double) completed / total * 100.0 : 0.0;

        return new SessionFeedbackResponse(session.getId(), request.sessionRpe(), aiExplanation,
                adjustments, new AdherenceStats(total, completed, pct));
    }

    // ===================== SESSION HISTORY =====================

    @Transactional(readOnly = true)
    public List<TrainingSession> getSessionHistory(Long userId) {
        return sessionRepo.findByUserIdOrderBySessionDateDesc(userId);
    }

    // ===================== HILFSMETHODEN =====================

    private PlannedExercise buildExercise(CreateTrainingPlanRequest.ExerciseInput input,
                                          TrainingPlan plan, int order, String trainingDay) {
        PlannedExercise ex = new PlannedExercise(
                input.exerciseName(), input.sets(), input.reps(), input.weightKg());
        ex.setRestSeconds(input.restSeconds());
        ex.setTargetRpe(input.targetRpe());
        ex.setExerciseOrder(order);
        ex.setTrainingPlan(plan);
        ex.setTrainingDay(trainingDay);
        return ex;
    }

    private String buildPlanDescription(GeneratePlanRequest req, AiService.GeneratedPlan generated) {
        StringBuilder desc = new StringBuilder("🤖 KI-generiert: ").append(req.userPrompt());
        if (req.fitnessGoal() != null && !req.fitnessGoal().isBlank())
            desc.append(" | Ziel: ").append(translateGoal(req.fitnessGoal()));
        if (req.daysPerWeek() != null)
            desc.append(" | ").append(req.daysPerWeek()).append("x/Woche");

        int numDays = generated.days().size();
        int dpw     = req.daysPerWeek() != null ? req.daysPerWeek() : 3;
        if (numDays > 0) {
            List<String> names = generated.days().stream().map(AiService.GeneratedDay::dayName).toList();
            desc.append(" | Tage: ").append(String.join(", ", names));
            if (numDays > 1 && dpw > numDays) {
                desc.append(" | Rotation: ");
                for (int i = 0; i < dpw; i++) {
                    if (i > 0) desc.append(" → ");
                    desc.append(names.get(i % numDays));
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
            default -> goal;
        };
    }
}