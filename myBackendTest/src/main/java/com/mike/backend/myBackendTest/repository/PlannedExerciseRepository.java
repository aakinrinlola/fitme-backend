package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.PlannedExercise;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlannedExerciseRepository extends JpaRepository<PlannedExercise, Long> {
    List<PlannedExercise> findByTrainingPlanIdOrderByExerciseOrder(Long trainingPlanId);
}
