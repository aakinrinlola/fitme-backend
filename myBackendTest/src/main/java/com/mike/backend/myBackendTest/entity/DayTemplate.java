package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "day_template")
public class DayTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String templateName;

    private String muscleGroup;

    @OneToMany(mappedBy = "dayTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("exerciseOrder ASC")
    private List<DayTemplateExercise> exercises = new ArrayList<>();

    private LocalDateTime createdAt = LocalDateTime.now();

    public DayTemplate() {}

    public Long getId()                                   { return id; }
    public AppUser getUser()                              { return user; }
    public void setUser(AppUser user)                     { this.user = user; }
    public String getTemplateName()                       { return templateName; }
    public void setTemplateName(String n)                 { this.templateName = n; }
    public String getMuscleGroup()                        { return muscleGroup; }
    public void setMuscleGroup(String g)                  { this.muscleGroup = g; }
    public List<DayTemplateExercise> getExercises()       { return exercises; }
    public void setExercises(List<DayTemplateExercise> e) { this.exercises = e; }
    public LocalDateTime getCreatedAt()                   { return createdAt; }
}
