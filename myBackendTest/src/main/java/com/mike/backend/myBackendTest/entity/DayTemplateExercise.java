package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "day_template_exercise")
public class DayTemplateExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_template_id", nullable = false)
    private DayTemplate dayTemplate;

    @Column(nullable = false)
    private String exerciseName;

    private int sets         = 3;
    private int reps         = 10;
    private double weightKg  = 0.0;
    private int restSeconds  = 90;
    private Integer targetRpe;
    private int exerciseOrder = 0;

    @Column(columnDefinition = "TEXT")
    private String description;

    public DayTemplateExercise() {}

    public Long getId()                         { return id; }
    public DayTemplate getDayTemplate()         { return dayTemplate; }
    public void setDayTemplate(DayTemplate d)   { this.dayTemplate = d; }
    public String getExerciseName()             { return exerciseName; }
    public void setExerciseName(String n)       { this.exerciseName = n; }
    public int getSets()                        { return sets; }
    public void setSets(int s)                  { this.sets = s; }
    public int getReps()                        { return reps; }
    public void setReps(int r)                  { this.reps = r; }
    public double getWeightKg()                 { return weightKg; }
    public void setWeightKg(double w)           { this.weightKg = w; }
    public int getRestSeconds()                 { return restSeconds; }
    public void setRestSeconds(int r)           { this.restSeconds = r; }
    public Integer getTargetRpe()               { return targetRpe; }
    public void setTargetRpe(Integer rpe)       { this.targetRpe = rpe; }
    public int getExerciseOrder()               { return exerciseOrder; }
    public void setExerciseOrder(int o)         { this.exerciseOrder = o; }
    public String getDescription()              { return description; }
    public void setDescription(String d)        { this.description = d; }
}
