package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Speichert einen einzelnen Body-Scan-Messwert-Satz pro Nutzer und Datum.
 * Alle Felder sind optional (nullable), da nicht jedes Gerät alle Werte liefert.
 */
@Entity
@Table(name = "body_scan_entry",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "measured_at"}))
public class BodyScanEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relation ──────────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "measured_at", nullable = false)
    private LocalDate measuredAt;

    // ── Körperkomposition ─────────────────────────────────────────────────────
    private Double bmi;

    @Column(name = "body_fat_percent")
    private Double bodyFatPercent;          // Fettmasse %

    @Column(name = "body_fat_kg")
    private Double bodyFatKg;              // Fettmasse kg

    @Column(name = "lean_mass_percent")
    private Double leanMassPercent;         // Fettfreie Masse %

    @Column(name = "body_water_percent")
    private Double bodyWaterPercent;        // Körperwasser %

    @Column(name = "visceral_fat_kg")
    private Double visceralFatKg;           // Viszerales Fett kg

    // ── Bioimpedanz / Vitalwerte ──────────────────────────────────────────────
    @Column(name = "phase_angle")
    private Double phaseAngle;             // Phasenwinkel

    @Column(name = "oxygen_saturation")
    private Double oxygenSaturation;       // Sauerstoffsättigung %

    // ── Segmentale Muskelwerte (kg) ───────────────────────────────────────────
    @Column(name = "muscle_kg_torso")
    private Double muscleKgTorso;

    @Column(name = "muscle_kg_arm_right")
    private Double muscleKgArmRight;

    @Column(name = "muscle_kg_arm_left")
    private Double muscleKgArmLeft;

    @Column(name = "muscle_kg_leg_right")
    private Double muscleKgLegRight;

    @Column(name = "muscle_kg_leg_left")
    private Double muscleKgLegLeft;

    // ── Konstruktoren ─────────────────────────────────────────────────────────
    public BodyScanEntry() {}

    // ── Getter & Setter ───────────────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public LocalDate getMeasuredAt() { return measuredAt; }
    public void setMeasuredAt(LocalDate measuredAt) { this.measuredAt = measuredAt; }

    public Double getBmi() { return bmi; }
    public void setBmi(Double bmi) { this.bmi = bmi; }

    public Double getBodyFatPercent() { return bodyFatPercent; }
    public void setBodyFatPercent(Double bodyFatPercent) { this.bodyFatPercent = bodyFatPercent; }

    public Double getBodyFatKg() { return bodyFatKg; }
    public void setBodyFatKg(Double bodyFatKg) { this.bodyFatKg = bodyFatKg; }

    public Double getLeanMassPercent() { return leanMassPercent; }
    public void setLeanMassPercent(Double leanMassPercent) { this.leanMassPercent = leanMassPercent; }

    public Double getBodyWaterPercent() { return bodyWaterPercent; }
    public void setBodyWaterPercent(Double bodyWaterPercent) { this.bodyWaterPercent = bodyWaterPercent; }

    public Double getVisceralFatKg() { return visceralFatKg; }
    public void setVisceralFatKg(Double visceralFatKg) { this.visceralFatKg = visceralFatKg; }

    public Double getPhaseAngle() { return phaseAngle; }
    public void setPhaseAngle(Double phaseAngle) { this.phaseAngle = phaseAngle; }

    public Double getOxygenSaturation() { return oxygenSaturation; }
    public void setOxygenSaturation(Double oxygenSaturation) { this.oxygenSaturation = oxygenSaturation; }

    public Double getMuscleKgTorso() { return muscleKgTorso; }
    public void setMuscleKgTorso(Double muscleKgTorso) { this.muscleKgTorso = muscleKgTorso; }

    public Double getMuscleKgArmRight() { return muscleKgArmRight; }
    public void setMuscleKgArmRight(Double muscleKgArmRight) { this.muscleKgArmRight = muscleKgArmRight; }

    public Double getMuscleKgArmLeft() { return muscleKgArmLeft; }
    public void setMuscleKgArmLeft(Double muscleKgArmLeft) { this.muscleKgArmLeft = muscleKgArmLeft; }

    public Double getMuscleKgLegRight() { return muscleKgLegRight; }
    public void setMuscleKgLegRight(Double muscleKgLegRight) { this.muscleKgLegRight = muscleKgLegRight; }

    public Double getMuscleKgLegLeft() { return muscleKgLegLeft; }
    public void setMuscleKgLegLeft(Double muscleKgLegLeft) { this.muscleKgLegLeft = muscleKgLegLeft; }
}