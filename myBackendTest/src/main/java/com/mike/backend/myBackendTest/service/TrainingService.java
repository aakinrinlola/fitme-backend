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
    private final PlanLimitService planLimitService;

    public TrainingService(TrainingPlanRepository planRepo,
                           PlannedExerciseRepository exerciseRepo,
                           TrainingSessionRepository sessionRepo,
                           UserRepository userRepo,
                           RpeAdjustmentService rpeService,
                           AiService aiService,
                           PlanLimitService planLimitService) {
        this.planRepo     = planRepo;
        this.exerciseRepo = exerciseRepo;
        this.sessionRepo  = sessionRepo;
        this.userRepo     = userRepo;
        this.rpeService   = rpeService;
        this.aiService    = aiService;
        this.planLimitService  = planLimitService;
    }

    // ===================== PLAN ERSTELLEN =====================

    public TrainingPlan createPlan(Long userId, CreateTrainingPlanRequest req) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(req.description());
        plan.setPlanDurationWeeks(4);

        int order = 0;
        for (var ex : req.exercises()) {
            plan.getExercises().add(buildExercise(ex, plan, order++, null));
        }
        return planRepo.save(plan);
    }

    // ===================== KI-GENERIERUNG =====================

    public TrainingPlan generateAndCreatePlan(Long userId, GeneratePlanRequest req) {
        log.info("KI-Generierung: User={}, Plan='{}'", userId, req.planName());

        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        // ── Monatliches Limit prüfen ─────────────────────────────────
        planLimitService.checkAiPlanLimit(user);

        AiService.GeneratedPlan generated = aiService.generateTrainingPlan(user, req);
        if (generated.days().isEmpty()) {
            throw new IllegalStateException("KI konnte keinen Plan erstellen.");
        }

        TrainingPlan plan = new TrainingPlan(req.planName(), user);
        plan.setDescription(buildPlanDescription(req, generated));
        plan.setPlanDurationWeeks(4);
        plan.setGeneratedByAi(true);

        int order = 0;
        for (AiService.GeneratedDay day : generated.days()) {
            for (AiService.GeneratedExercise genEx : day.exercises()) {
                var exInput = new CreateTrainingPlanRequest.ExerciseInput(
                        genEx.exerciseName(), genEx.sets(), genEx.reps(),
                        genEx.weightKg(), genEx.restSeconds(), genEx.targetRpe());

                PlannedExercise pe = buildExercise(exInput, plan, order++, day.dayName());
                if (genEx.description() != null && !genEx.description().isBlank()) {
                    pe.setDescription(genEx.description());
                }
                plan.getExercises().add(pe);
            }
        }

        TrainingPlan saved = planRepo.save(plan);
        log.info("KI-Plan gespeichert: id={}, Tage={}", saved.getId(), generated.days().size());
        return saved;
    }
    // ===================== FEEDBACK VERFÜGBARKEIT =====================

    @Transactional(readOnly = true)
    public Map<String, Object> getFeedbackAvailability(Long planId, Long userId) {
        TrainingPlan plan = getPlanForUser(planId, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId",            plan.getId());
        result.put("isActive",          plan.isCurrentlyActive());
        result.put("currentWeek",       plan.getCurrentWeek());
        result.put("planDurationWeeks", plan.getPlanDurationWeeks());

        if (!plan.isCurrentlyActive()) {
            result.put("allowed", false);
            result.put("reason",  "Plan ist inaktiv");
            result.put("nextFeedbackAvailableAt", null);
            return result;
        }

        boolean allowed = plan.isFeedbackAllowedThisWeek();
        result.put("allowed", allowed);
        result.put("nextFeedbackAvailableAt",
                plan.getNextFeedbackAvailableAt() != null
                        ? plan.getNextFeedbackAvailableAt().toString() : null);
        result.put("reason", allowed ? "Feedback ist möglich"
                : "Feedback diese Woche bereits abgegeben");
        return result;
    }

    // ===================== FEEDBACK VERARBEITEN =====================

    public SessionFeedbackResponse processFeedback(Long userId, SessionFeedbackRequest request) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));
        TrainingPlan plan = planRepo.findById(request.trainingPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan nicht gefunden: " + request.trainingPlanId()));

        if (!plan.getUser().getId().equals(userId)) throw new SecurityException("Zugriff verweigert");
        if (!plan.isFeedbackAllowedThisWeek())
            throw new IllegalStateException("Feedback diese Woche bereits abgegeben. Nächstes Feedback ab: "
                    + plan.getNextFeedbackAvailableAt());
        if (!plan.isCurrentlyActive())
            throw new IllegalStateException("Feedback nur für aktive Pläne möglich.");

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
        List<ExerciseAdjustment> adjustments = rpeService.calculateAdjustments(
                exercises, request.sessionRpe(), rpeMap);
        exerciseRepo.saveAll(exercises);

        String aiExplanation = aiService.generateExplanation(
                user, request.sessionRpe(), request.userNote(), adjustments);
        session.setAiResponse(aiExplanation);
        session = sessionRepo.save(session);

        LocalDateTime now = LocalDateTime.now();
        plan.setLastFeedbackAt(now);
        plan.setNextFeedbackAvailableAt(now.plusDays(7));
        planRepo.save(plan);

        long total     = sessionRepo.countByTrainingPlanId(plan.getId());
        long completed = sessionRepo.countByTrainingPlanIdAndCompletedTrue(plan.getId());
        double pct     = total > 0 ? (double) completed / total * 100.0 : 0.0;

        return new SessionFeedbackResponse(session.getId(), request.sessionRpe(), aiExplanation,
                adjustments, new AdherenceStats(total, completed, pct));
    }

    // ===================== STATUS =====================

    public TrainingPlan setActiveStatus(Long planId, Long userId, boolean active) {
        TrainingPlan plan = getPlanForUser(planId, userId);
        if (active) {
            plan.setActive(true);
            plan.setLastActivatedAt(LocalDateTime.now());
            plan.setActiveUntil(LocalDateTime.now().plusMonths(1));
        } else {
            plan.setActive(false);
        }
        return planRepo.save(plan);
    }

    public void deletePlan(Long planId, Long userId) {
        planRepo.delete(getPlanForUser(planId, userId));
    }

    @Transactional(readOnly = true)
    public TrainingPlan getPlanForUser(Long planId, Long userId) {
        TrainingPlan plan = planRepo.findByIdWithExercises(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan nicht gefunden: " + planId));
        if (!plan.getUser().getId().equals(userId)) throw new SecurityException("Zugriff verweigert");
        return plan;
    }

    public List<TrainingPlan> getUserPlans(Long userId) {
        List<TrainingPlan> plans = planRepo.findByUserIdWithExercises(userId); // ← ÄNDERN
        plans.forEach(p -> {
            if (p.isActive() && p.getActiveUntil() != null
                    && LocalDateTime.now().isAfter(p.getActiveUntil())) {
                p.setActive(false);
                planRepo.save(p);
            }
        });
        return plans;
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
        StringBuilder d = new StringBuilder("🤖 KI-generiert: ").append(req.userPrompt());
        if (req.fitnessGoal() != null && !req.fitnessGoal().isBlank())
            d.append(" | Ziel: ").append(translateGoal(req.fitnessGoal()));
        if (req.daysPerWeek() != null)
            d.append(" | ").append(req.daysPerWeek()).append("x/Woche");
        if (req.sessionDurationMinutes() != null)
            d.append(" | ").append(req.sessionDurationMinutes()).append(" min/Einheit");
        if (!generated.days().isEmpty()) {
            List<String> names = generated.days().stream().map(AiService.GeneratedDay::dayName).toList();
            d.append(" | Tage: ").append(String.join(", ", names));
        }
        return d.toString();
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

    @Transactional(readOnly = true)
    public List<TrainingSession> getSessionHistory(Long userId) {
        return sessionRepo.findByUserIdOrderBySessionDateDesc(userId);
    }

}