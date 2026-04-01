package com.mike.backend.myBackendTest.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Health-Check Endpoint — öffentlich, kein JWT erforderlich.
 * Wird von Railway für Health-Checks und zum Verifizieren des Deployments verwendet.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${auth.mode:local}")
    private String authMode;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "profile", activeProfile,
                "authMode", authMode,
                "version", "1.0.0"
        ));
    }
}