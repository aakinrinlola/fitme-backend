package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercise_template")
public class ExerciseTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String exerciseName;

    private String muscleGroup;

    private String muscleFocus;

    private int defaultSets        = 3;
    private int defaultReps        = 10;
    private double defaultWeightKg = 0.0;
    private int defaultRestSeconds = 90;
    private Integer defaultTargetRpe;

    @Column(columnDefinition = "TEXT")
    private String description;

    private LocalDateTime createdAt = LocalDateTime.now();

    public ExerciseTemplate() {}

    public Long getId()                           { return id; }
    public AppUser getUser()                      { return user; }
    public void setUser(AppUser user)             { this.user = user; }
    public String getExerciseName()               { return exerciseName; }
    public void setExerciseName(String n)         { this.exerciseName = n; }
    public String getMuscleGroup()                { return muscleGroup; }
    public void setMuscleGroup(String g)          { this.muscleGroup = g; }
    public String getMuscleFocus()                { return muscleFocus; }
    public void setMuscleFocus(String f)          { this.muscleFocus = f; }
    public int getDefaultSets()                   { return defaultSets; }
    public void setDefaultSets(int s)             { this.defaultSets = s; }
    public int getDefaultReps()                   { return defaultReps; }
    public void setDefaultReps(int r)             { this.defaultReps = r; }
    public double getDefaultWeightKg()            { return defaultWeightKg; }
    public void setDefaultWeightKg(double w)      { this.defaultWeightKg = w; }
    public int getDefaultRestSeconds()            { return defaultRestSeconds; }
    public void setDefaultRestSeconds(int r)      { this.defaultRestSeconds = r; }
    public Integer getDefaultTargetRpe()          { return defaultTargetRpe; }
    public void setDefaultTargetRpe(Integer rpe)  { this.defaultTargetRpe = rpe; }
    public String getDescription()                { return description; }
    public void setDescription(String d)          { this.description = d; }
    public LocalDateTime getCreatedAt()           { return createdAt; }
}
