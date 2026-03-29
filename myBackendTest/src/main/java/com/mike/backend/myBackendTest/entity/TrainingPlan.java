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

    /** Manuell gesetzter Aktiv-Status (kann auch durch Ablauf überschrieben werden) */
    private boolean active = true;

    /** Erstellungszeitpunkt — wird nie verändert */
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Zeitpunkt der letzten manuellen Aktivierung.
     * Wird gesetzt bei: Erstellung + jeder manuellen Reaktivierung.
     */
    @Column(name = "last_activated_at")
    private LocalDateTime lastActivatedAt;

    /**
     * Plan ist aktiv bis zu diesem Zeitpunkt.
     * Berechnung: lastActivatedAt + 1 Monat
     * Wird gesetzt bei: Erstellung + jeder manuellen Reaktivierung.
     */
    @Column(name = "active_until")
    private LocalDateTime activeUntil;

    // ---- Constructors ----
    public TrainingPlan() {}

    public TrainingPlan(String planName, AppUser user) {
        this.planName = planName;
        this.user = user;
        // Beim Erstellen: sofort aktivieren für 1 Monat
        this.active = true;
        this.lastActivatedAt = LocalDateTime.now();
        this.activeUntil = this.lastActivatedAt.plusMonths(1);
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

    /**
     * Prüft ob der Plan aktuell wirklich aktiv ist (berücksichtigt Ablaufdatum).
     * Nutze diese Methode für Geschäftslogik statt isActive() direkt.
     */
    public boolean isCurrentlyActive() {
        if (!active) return false;
        if (activeUntil == null) return true; // Kein Ablaufdatum → bleibt aktiv (Rückwärtskompatibilität)
        return LocalDateTime.now().isBefore(activeUntil);
    }
}