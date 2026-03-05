package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "training_session")
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_plan_id", nullable = false)
    private TrainingPlan trainingPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    private LocalDateTime sessionDate = LocalDateTime.now();

    private boolean completed = false;

    /** Session-RPE (1–10): Gesamtbelastung der Einheit */
    private Integer sessionRpe;

    /** Optionale Notiz des Nutzers */
    @Column(columnDefinition = "TEXT")
    private String userNote;

    /** Generierte Antwort/Empfehlung der KI */
    @Column(columnDefinition = "TEXT")
    private String aiResponse;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SessionExercise> exercises = new ArrayList<>();

    // ---- Constructors ----
    public TrainingSession() {}

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TrainingPlan getTrainingPlan() { return trainingPlan; }
    public void setTrainingPlan(TrainingPlan trainingPlan) { this.trainingPlan = trainingPlan; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public LocalDateTime getSessionDate() { return sessionDate; }
    public void setSessionDate(LocalDateTime sessionDate) { this.sessionDate = sessionDate; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public Integer getSessionRpe() { return sessionRpe; }
    public void setSessionRpe(Integer sessionRpe) { this.sessionRpe = sessionRpe; }

    public String getUserNote() { return userNote; }
    public void setUserNote(String userNote) { this.userNote = userNote; }

    public String getAiResponse() { return aiResponse; }
    public void setAiResponse(String aiResponse) { this.aiResponse = aiResponse; }

    public List<SessionExercise> getExercises() { return exercises; }
    public void setExercises(List<SessionExercise> exercises) { this.exercises = exercises; }
}
