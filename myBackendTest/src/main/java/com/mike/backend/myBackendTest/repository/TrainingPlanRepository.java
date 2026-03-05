package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.TrainingPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrainingPlanRepository extends JpaRepository<TrainingPlan, Long> {
    List<TrainingPlan> findByUserId(Long userId);
    List<TrainingPlan> findByUserIdAndActiveTrue(Long userId);
}
