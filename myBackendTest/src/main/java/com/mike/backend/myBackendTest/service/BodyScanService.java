package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.BodyScanRequest;
import com.mike.backend.myBackendTest.dto.BodyScanResponse;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.entity.BodyScanEntry;
import com.mike.backend.myBackendTest.exception.ResourceNotFoundException;
import com.mike.backend.myBackendTest.repository.BodyScanRepository;
import com.mike.backend.myBackendTest.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BodyScanService {

    private final BodyScanRepository bodyScanRepository;
    private final UserRepository     userRepository;

    public BodyScanService(BodyScanRepository bodyScanRepository,
                           UserRepository userRepository) {
        this.bodyScanRepository = bodyScanRepository;
        this.userRepository     = userRepository;
    }

    // ── Alle Einträge eines Nutzers (neueste zuerst) ──────────────────────────
    @Transactional(readOnly = true)
    public List<BodyScanResponse> getHistory(Long userId) {
        return bodyScanRepository.findByUserIdOrderByMeasuredAtDesc(userId)
                .stream()
                .map(BodyScanResponse::from)
                .toList();
    }

    // ── Eintrag speichern (neu anlegen oder vorhandenes Datum überschreiben) ──
    public BodyScanResponse save(Long userId, BodyScanRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + userId));

        // Bei gleichem Datum: bestehenden Eintrag überschreiben (Upsert-Logik)
        BodyScanEntry entry = bodyScanRepository
                .findByUserIdAndMeasuredAt(userId, request.measuredAt())
                .orElse(new BodyScanEntry());

        entry.setUser(user);
        entry.setMeasuredAt(request.measuredAt());

        // Nur nicht-null Werte übernehmen (partial update)
        if (request.bmi()              != null) entry.setBmi(request.bmi());
        if (request.bodyFatPercent()   != null) entry.setBodyFatPercent(request.bodyFatPercent());
        if (request.bodyFatKg()        != null) entry.setBodyFatKg(request.bodyFatKg());
        if (request.leanMassPercent()  != null) entry.setLeanMassPercent(request.leanMassPercent());
        if (request.bodyWaterPercent() != null) entry.setBodyWaterPercent(request.bodyWaterPercent());
        if (request.visceralFatKg()    != null) entry.setVisceralFatKg(request.visceralFatKg());
        if (request.phaseAngle()       != null) entry.setPhaseAngle(request.phaseAngle());
        if (request.oxygenSaturation() != null) entry.setOxygenSaturation(request.oxygenSaturation());
        if (request.muscleKgTorso()    != null) entry.setMuscleKgTorso(request.muscleKgTorso());
        if (request.muscleKgArmRight() != null) entry.setMuscleKgArmRight(request.muscleKgArmRight());
        if (request.muscleKgArmLeft()  != null) entry.setMuscleKgArmLeft(request.muscleKgArmLeft());
        if (request.muscleKgLegRight() != null) entry.setMuscleKgLegRight(request.muscleKgLegRight());
        if (request.muscleKgLegLeft()  != null) entry.setMuscleKgLegLeft(request.muscleKgLegLeft());

        return BodyScanResponse.from(bodyScanRepository.save(entry));
    }

    // ── Einzelnen Eintrag löschen ─────────────────────────────────────────────
    public void delete(Long userId, Long entryId) {
        BodyScanEntry entry = bodyScanRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Body-Scan-Eintrag nicht gefunden: " + entryId));

        if (!entry.getUser().getId().equals(userId)) {
            throw new SecurityException("Kein Zugriff auf diesen Eintrag");
        }
        bodyScanRepository.delete(entry);
    }

    // ── Hat der Nutzer mindestens einen Eintrag? ──────────────────────────────
    @Transactional(readOnly = true)
    public boolean hasEntries(Long userId) {
        return bodyScanRepository.existsByUserId(userId);
    }
}