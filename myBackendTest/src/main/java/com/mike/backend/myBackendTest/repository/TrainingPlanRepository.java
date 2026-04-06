package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.TrainingPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TrainingPlanRepository extends JpaRepository<TrainingPlan, Long> {

    List<TrainingPlan> findByUserId(Long userId);
    List<TrainingPlan> findByUserIdAndActiveTrue(Long userId);

    // Dashboard — alle Pläne mit Exercises laden
    @Query("SELECT p FROM TrainingPlan p LEFT JOIN FETCH p.exercises WHERE p.user.id = :userId")
    List<TrainingPlan> findByUserIdWithExercises(@Param("userId") Long userId);

    // Einzelner Plan mit Exercises laden
    @Query("SELECT p FROM TrainingPlan p LEFT JOIN FETCH p.exercises WHERE p.id = :planId")
    Optional<TrainingPlan> findByIdWithExercises(@Param("planId") Long planId);

    // KI-Pläne zählen für Limit
    @Query("SELECT COUNT(p) FROM TrainingPlan p WHERE p.user.id = :userId " +
            "AND p.generatedByAi = true " +
            "AND p.createdAt >= :monthStart")
    long countAiPlansThisMonth(@Param("userId") Long userId,
                               @Param("monthStart") LocalDateTime monthStart);

    // Letzten KI-Plan finden — LIMIT 1 nicht in JPQL, daher List + im Code .stream().findFirst()
    @Query("SELECT p FROM TrainingPlan p WHERE p.user.id = :userId " +
            "AND p.generatedByAi = true ORDER BY p.createdAt DESC")
    List<TrainingPlan> findLastAiPlansByUserId(@Param("userId") Long userId);
}