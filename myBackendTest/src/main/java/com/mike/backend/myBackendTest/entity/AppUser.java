package com.mike.backend.myBackendTest.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String username;

    @Email
    private String email;

    private Integer age;

    private Double weightKg;

    private Double heightCm;

    @Enumerated(EnumType.STRING)
    private FitnessLevel fitnessLevel = FitnessLevel.BEGINNER;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TrainingPlan> trainingPlans = new ArrayList<>();

    private LocalDateTime createdAt = LocalDateTime.now();

    public enum FitnessLevel {
        BEGINNER, INTERMEDIATE, ADVANCED
    }

    // ---- Constructors ----
    public AppUser() {}

    public AppUser(String username, String email) {
        this.username = username;
        this.email = email;
    }

    // ---- Getters & Setters ----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public Double getWeightKg() { return weightKg; }
    public void setWeightKg(Double weightKg) { this.weightKg = weightKg; }

    public Double getHeightCm() { return heightCm; }
    public void setHeightCm(Double heightCm) { this.heightCm = heightCm; }

    public FitnessLevel getFitnessLevel() { return fitnessLevel; }
    public void setFitnessLevel(FitnessLevel fitnessLevel) { this.fitnessLevel = fitnessLevel; }

    public List<TrainingPlan> getTrainingPlans() { return trainingPlans; }
    public void setTrainingPlans(List<TrainingPlan> trainingPlans) { this.trainingPlans = trainingPlans; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
