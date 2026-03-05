package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "training_plan")
public class TrainingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String planName;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @OneToMany(mappedBy = "trainingPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("exerciseOrder ASC")
    private List<PlannedExercise> exercises = new ArrayList<>();

    @OneToMany(mappedBy = "trainingPlan", cascade = CascadeType.ALL)
    @OrderBy("sessionDate DESC")
    private List<TrainingSession> sessions = new ArrayList<>();

    private boolean active = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    // ---- Constructors ----
    public TrainingPlan() {}

    public TrainingPlan(String planName, AppUser user) {
        this.planName = planName;
        this.user = user;
    }

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public List<PlannedExercise> getExercises() { return exercises; }
    public void setExercises(List<PlannedExercise> exercises) { this.exercises = exercises; }

    public List<TrainingSession> getSessions() { return sessions; }
    public void setSessions(List<TrainingSession> sessions) { this.sessions = sessions; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
