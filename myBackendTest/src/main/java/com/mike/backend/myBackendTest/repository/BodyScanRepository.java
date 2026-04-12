package com.mike.backend.myBackendTest.repository;

import com.mike.backend.myBackendTest.entity.BodyScanEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BodyScanRepository extends JpaRepository<BodyScanEntry, Long> {

    /** Alle Einträge eines Nutzers, chronologisch absteigend (neueste zuerst). */
    List<BodyScanEntry> findByUserIdOrderByMeasuredAtDesc(Long userId);

    /** Die letzten N Einträge (Spring Data schneidet über Limit). */
    List<BodyScanEntry> findTop10ByUserIdOrderByMeasuredAtDesc(Long userId);

    /** Prüfung auf doppeltes Datum pro Nutzer. */
    Optional<BodyScanEntry> findByUserIdAndMeasuredAt(Long userId, LocalDate measuredAt);

    /** Existenzprüfung: Hat der Nutzer überhaupt Body-Scan-Daten? */
    boolean existsByUserId(Long userId);
}