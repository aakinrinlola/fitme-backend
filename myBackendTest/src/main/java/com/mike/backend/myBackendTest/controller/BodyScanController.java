package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.BodyScanRequest;
import com.mike.backend.myBackendTest.dto.BodyScanResponse;
import com.mike.backend.myBackendTest.security.SecurityHelper;
import com.mike.backend.myBackendTest.service.BodyScanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST-Endpunkte für die Body-Scan-Historie.
 *
 * GET    /api/body-scan          → gesamte Messhistorie des eingeloggten Nutzers
 * POST   /api/body-scan          → neuen Eintrag anlegen (oder Datum überschreiben)
 * DELETE /api/body-scan/{id}     → einzelnen Eintrag löschen
 */
@RestController
@RequestMapping("/api/body-scan")
public class BodyScanController {

    private final BodyScanService bodyScanService;
    private final SecurityHelper  securityHelper;

    public BodyScanController(BodyScanService bodyScanService,
                              SecurityHelper securityHelper) {
        this.bodyScanService = bodyScanService;
        this.securityHelper  = securityHelper;
    }

    /** Gesamte Body-Scan-Historie (neueste zuerst). */
    @GetMapping
    public ResponseEntity<List<BodyScanResponse>> getHistory() {
        Long userId = securityHelper.getCurrentUserId();
        return ResponseEntity.ok(bodyScanService.getHistory(userId));
    }

    /** Neuen Eintrag speichern (Upsert per Datum). */
    @PostMapping
    public ResponseEntity<BodyScanResponse> save(@Valid @RequestBody BodyScanRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        BodyScanResponse response = bodyScanService.save(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Einzelnen Eintrag löschen. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        Long userId = securityHelper.getCurrentUserId();
        bodyScanService.delete(userId, id);
        return ResponseEntity.ok(Map.of("message", "Eintrag gelöscht"));
    }
}