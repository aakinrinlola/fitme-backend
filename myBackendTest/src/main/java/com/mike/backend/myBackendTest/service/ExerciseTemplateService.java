package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.DayTemplateRequest;
import com.mike.backend.myBackendTest.dto.ExerciseTemplateRequest;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.entity.DayTemplate;
import com.mike.backend.myBackendTest.entity.DayTemplateExercise;
import com.mike.backend.myBackendTest.entity.ExerciseTemplate;
import com.mike.backend.myBackendTest.exception.ResourceNotFoundException;
import com.mike.backend.myBackendTest.repository.DayTemplateRepository;
import com.mike.backend.myBackendTest.repository.ExerciseTemplateRepository;
import com.mike.backend.myBackendTest.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@Transactional
public class ExerciseTemplateService {

    private final ExerciseTemplateRepository exerciseRepo;
    private final DayTemplateRepository      dayRepo;
    private final UserRepository             userRepo;

    public ExerciseTemplateService(ExerciseTemplateRepository exerciseRepo,
                                   DayTemplateRepository dayRepo,
                                   UserRepository userRepo) {
        this.exerciseRepo = exerciseRepo;
        this.dayRepo      = dayRepo;
        this.userRepo     = userRepo;
    }

    // ── Übungs-Vorlagen ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getExerciseTemplates(Long userId, String muscleGroup) {
        List<ExerciseTemplate> list = muscleGroup != null && !muscleGroup.isBlank()
            ? exerciseRepo.findByUserIdAndMuscleGroupOrderByExerciseNameAsc(userId, muscleGroup)
            : exerciseRepo.findByUserIdOrderByExerciseNameAsc(userId);
        return list.stream().map(this::toExerciseMap).toList();
    }

    public Map<String, Object> saveExerciseTemplate(Long userId, ExerciseTemplateRequest req) {
        AppUser user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        ExerciseTemplate t = new ExerciseTemplate();
        t.setUser(user);
        t.setExerciseName(req.exerciseName());
        t.setMuscleGroup(req.muscleGroup());
        t.setMuscleFocus(req.muscleFocus());
        t.setDefaultSets(req.defaultSets() > 0 ? req.defaultSets() : 3);
        t.setDefaultReps(req.defaultReps() > 0 ? req.defaultReps() : 10);
        t.setDefaultWeightKg(req.defaultWeightKg());
        t.setDefaultRestSeconds(req.defaultRestSeconds() > 0 ? req.defaultRestSeconds() : 90);
        t.setDefaultTargetRpe(req.defaultTargetRpe());
        t.setDescription(req.description());
        return toExerciseMap(exerciseRepo.save(t));
    }

    public void deleteExerciseTemplate(Long userId, Long templateId) {
        if (!exerciseRepo.existsByUserIdAndId(userId, templateId)) {
            throw new ResourceNotFoundException("Übungs-Vorlage nicht gefunden: " + templateId);
        }
        exerciseRepo.deleteById(templateId);
    }

    // ── Tag-Vorlagen ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDayTemplates(Long userId, String muscleGroup) {
        List<DayTemplate> list = muscleGroup != null && !muscleGroup.isBlank()
            ? dayRepo.findByUserIdAndMuscleGroupOrderByTemplateNameAsc(userId, muscleGroup)
            : dayRepo.findByUserIdOrderByTemplateNameAsc(userId);
        return list.stream().map(this::toDayMap).toList();
    }

    public Map<String, Object> saveDayTemplate(Long userId, DayTemplateRequest req) {
        AppUser user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        DayTemplate dt = new DayTemplate();
        dt.setUser(user);
        dt.setTemplateName(req.templateName());
        dt.setMuscleGroup(req.muscleGroup());

        int order = 0;
        for (DayTemplateRequest.ExerciseEntry e : req.exercises()) {
            DayTemplateExercise dte = new DayTemplateExercise();
            dte.setDayTemplate(dt);
            dte.setExerciseName(e.exerciseName());
            dte.setSets(e.sets() > 0 ? e.sets() : 3);
            dte.setReps(e.reps() > 0 ? e.reps() : 10);
            dte.setWeightKg(e.weightKg());
            dte.setRestSeconds(e.restSeconds() > 0 ? e.restSeconds() : 90);
            dte.setTargetRpe(e.targetRpe());
            dte.setDescription(e.description());
            dte.setExerciseOrder(order++);
            dt.getExercises().add(dte);
        }
        return toDayMap(dayRepo.save(dt));
    }

    public void deleteDayTemplate(Long userId, Long templateId) {
        DayTemplate dt = dayRepo.findByIdAndUserId(templateId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Tag-Vorlage nicht gefunden: " + templateId));
        dayRepo.delete(dt);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private Map<String, Object> toExerciseMap(ExerciseTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                t.getId());
        m.put("exerciseName",      t.getExerciseName());
        m.put("muscleGroup",       t.getMuscleGroup() != null ? t.getMuscleGroup() : "");
        m.put("muscleFocus",       t.getMuscleFocus() != null ? t.getMuscleFocus() : "");
        m.put("defaultSets",       t.getDefaultSets());
        m.put("defaultReps",       t.getDefaultReps());
        m.put("defaultWeightKg",   t.getDefaultWeightKg());
        m.put("defaultRestSeconds",t.getDefaultRestSeconds());
        m.put("defaultTargetRpe",  t.getDefaultTargetRpe() != null ? t.getDefaultTargetRpe() : 7);
        m.put("description",       t.getDescription() != null ? t.getDescription() : "");
        return m;
    }

    private Map<String, Object> toDayMap(DayTemplate dt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           dt.getId());
        m.put("templateName", dt.getTemplateName());
        m.put("muscleGroup",  dt.getMuscleGroup() != null ? dt.getMuscleGroup() : "");
        m.put("exercises", dt.getExercises().stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("exerciseName", e.getExerciseName());
            em.put("sets",         e.getSets());
            em.put("reps",         e.getReps());
            em.put("weightKg",     e.getWeightKg());
            em.put("restSeconds",  e.getRestSeconds());
            em.put("targetRpe",    e.getTargetRpe() != null ? e.getTargetRpe() : 7);
            em.put("description",  e.getDescription() != null ? e.getDescription() : "");
            return em;
        }).toList());
        return m;
    }
}
