package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.DayTemplate;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DayTemplateRepository extends JpaRepository<DayTemplate, Long> {

    @EntityGraph(attributePaths = "exercises")
    List<DayTemplate> findByUserIdOrderByTemplateNameAsc(Long userId);

    @EntityGraph(attributePaths = "exercises")
    List<DayTemplate> findByUserIdAndMuscleGroupOrderByTemplateNameAsc(Long userId, String muscleGroup);

    @EntityGraph(attributePaths = "exercises")
    Optional<DayTemplate> findByIdAndUserId(Long id, Long userId);
}
