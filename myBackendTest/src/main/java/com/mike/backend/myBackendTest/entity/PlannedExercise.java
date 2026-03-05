package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "planned_exercise")
public class PlannedExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String exerciseName;

    @Positive
    private int sets = 3;

    @Positive
    private int reps = 10;

    private double weightKg = 0.0;

    private int restSeconds = 90;

    private int exerciseOrder;

    /** Ziel-RPE für diese Übung (z.B. 7 = "2–3 Wiederholungen in Reserve") */
    private Integer targetRpe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_plan_id", nullable = false)
    private TrainingPlan trainingPlan;

    // ---- Constructors ----
    public PlannedExercise() {}

    public PlannedExercise(String exerciseName, int sets, int reps, double weightKg) {
        this.exerciseName = exerciseName;
        this.sets = sets;
        this.reps = reps;
        this.weightKg = weightKg;
    }

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExerciseName() { return exerciseName; }
    public void setExerciseName(String exerciseName) { this.exerciseName = exerciseName; }

    public int getSets() { return sets; }
    public void setSets(int sets) { this.sets = sets; }

    public int getReps() { return reps; }
    public void setReps(int reps) { this.reps = reps; }

    public double getWeightKg() { return weightKg; }
    public void setWeightKg(double weightKg) { this.weightKg = weightKg; }

    public int getRestSeconds() { return restSeconds; }
    public void setRestSeconds(int restSeconds) { this.restSeconds = restSeconds; }

    public int getExerciseOrder() { return exerciseOrder; }
    public void setExerciseOrder(int exerciseOrder) { this.exerciseOrder = exerciseOrder; }

    public Integer getTargetRpe() { return targetRpe; }
    public void setTargetRpe(Integer targetRpe) { this.targetRpe = targetRpe; }

    public TrainingPlan getTrainingPlan() { return trainingPlan; }
    public void setTrainingPlan(TrainingPlan trainingPlan) { this.trainingPlan = trainingPlan; }
}
