package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "session_exercise")
public class SessionExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private TrainingSession session;

    /** Referenz auf die geplante Übung */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_exercise_id")
    private PlannedExercise plannedExercise;

    private String exerciseName;

    private int setsCompleted;
    private int repsCompleted;
    private double weightUsed;

    /** RPE für diese spezifische Übung (1–10) */
    private Integer exerciseRpe;

    private String note;

    // ---- Constructors ----
    public SessionExercise() {}

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TrainingSession getSession() { return session; }
    public void setSession(TrainingSession session) { this.session = session; }

    public PlannedExercise getPlannedExercise() { return plannedExercise; }
    public void setPlannedExercise(PlannedExercise plannedExercise) { this.plannedExercise = plannedExercise; }

    public String getExerciseName() { return exerciseName; }
    public void setExerciseName(String exerciseName) { this.exerciseName = exerciseName; }

    public int getSetsCompleted() { return setsCompleted; }
    public void setSetsCompleted(int setsCompleted) { this.setsCompleted = setsCompleted; }

    public int getRepsCompleted() { return repsCompleted; }
    public void setRepsCompleted(int repsCompleted) { this.repsCompleted = repsCompleted; }

    public double getWeightUsed() { return weightUsed; }
    public void setWeightUsed(double weightUsed) { this.weightUsed = weightUsed; }

    public Integer getExerciseRpe() { return exerciseRpe; }
    public void setExerciseRpe(Integer exerciseRpe) { this.exerciseRpe = exerciseRpe; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
