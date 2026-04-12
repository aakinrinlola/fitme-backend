package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.ExerciseTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExerciseTemplateRepository extends JpaRepository<ExerciseTemplate, Long> {
    List<ExerciseTemplate> findByUserIdOrderByExerciseNameAsc(Long userId);
    List<ExerciseTemplate> findByUserIdAndMuscleGroupOrderByExerciseNameAsc(Long userId, String muscleGroup);
    boolean existsByUserIdAndId(Long userId, Long id);
}
