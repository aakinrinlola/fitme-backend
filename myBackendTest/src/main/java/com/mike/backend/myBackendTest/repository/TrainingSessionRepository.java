package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.TrainingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {
    List<TrainingSession> findByUserIdOrderBySessionDateDesc(Long userId);
    List<TrainingSession> findByTrainingPlanIdOrderBySessionDateDesc(Long planId);
    long countByUserIdAndCompletedTrue(Long userId);
    long countByTrainingPlanId(Long planId);
    long countByTrainingPlanIdAndCompletedTrue(Long planId);
}
