package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    /** Erstellungszeitpunkt — nie verändern */
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Letztes manuelles Aktivieren */
    @Column(name = "last_activated_at")
    private LocalDateTime lastActivatedAt;

    /**
     * Aktiv bis (createdAt + 1 Monat bei Erstellung,
     * lastActivatedAt + 1 Monat bei Reaktivierung)
     */
    @Column(name = "active_until")
    private LocalDateTime activeUntil;

    // ── Wochenstruktur ───────────────────────────────────────────

    /** Feste Laufzeit in Wochen (Standard: 4) */
    @Column(name = "plan_duration_weeks")
    private int planDurationWeeks = 4;

    /**
     * Zeitpunkt des letzten abgesendeten Feedbacks.
     * Dient zur wöchentlichen Sperr-Logik.
     */
    @Column(name = "last_feedback_at")
    private LocalDateTime lastFeedbackAt;

    /**
     * Ab diesem Zeitpunkt ist das nächste Feedback wieder möglich
     * (= lastFeedbackAt + 7 Tage).
     */
    @Column(name = "next_feedback_available_at")
    private LocalDateTime nextFeedbackAvailableAt;

    // ---- Constructors ----
    public TrainingPlan() {}

    public TrainingPlan(String planName, AppUser user) {
        this.planName = planName;
        this.user = user;
        this.active = true;
        this.lastActivatedAt = LocalDateTime.now();
        this.activeUntil = this.lastActivatedAt.plusMonths(1);
    }

    // ---- Business logic ----

    /** True wenn Plan gerade aktiv UND activeUntil noch nicht abgelaufen */
    public boolean isCurrentlyActive() {
        if (!active) return false;
        if (activeUntil == null) return true;
        return LocalDateTime.now().isBefore(activeUntil);
    }

    /**
     * Aktuelle Planwoche (1–planDurationWeeks).
     * Basiert auf Erstellungsdatum.
     */
    public int getCurrentWeek() {
        long daysSinceCreation = ChronoUnit.DAYS.between(createdAt.toLocalDate(),
                LocalDateTime.now().toLocalDate());
        int week = (int) (daysSinceCreation / 7) + 1;
        return Math.min(week, planDurationWeeks);
    }

    /**
     * Gibt an, ob für diese Woche bereits Feedback gegeben wurde.
     * Wird anhand von nextFeedbackAvailableAt geprüft.
     */
    public boolean isFeedbackAllowedThisWeek() {
        if (nextFeedbackAvailableAt == null) return true;
        return LocalDateTime.now().isAfter(nextFeedbackAvailableAt);
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

    public LocalDateTime getLastActivatedAt() { return lastActivatedAt; }
    public void setLastActivatedAt(LocalDateTime lastActivatedAt) { this.lastActivatedAt = lastActivatedAt; }

    public LocalDateTime getActiveUntil() { return activeUntil; }
    public void setActiveUntil(LocalDateTime activeUntil) { this.activeUntil = activeUntil; }

    public int getPlanDurationWeeks() { return planDurationWeeks; }
    public void setPlanDurationWeeks(int planDurationWeeks) { this.planDurationWeeks = planDurationWeeks; }

    public LocalDateTime getLastFeedbackAt() { return lastFeedbackAt; }
    public void setLastFeedbackAt(LocalDateTime lastFeedbackAt) { this.lastFeedbackAt = lastFeedbackAt; }

    public LocalDateTime getNextFeedbackAvailableAt() { return nextFeedbackAvailableAt; }
    public void setNextFeedbackAvailableAt(LocalDateTime nextFeedbackAvailableAt) {
        this.nextFeedbackAvailableAt = nextFeedbackAvailableAt;
    }
}