package com.mike.backend.myBackendTest.controller;

import com.mike.backend.myBackendTest.dto.UpdateProfileRequest;
import com.mike.backend.myBackendTest.dto.auth.AuthResponse;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.security.SecurityHelper;
import com.mike.backend.myBackendTest.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * User-Profil-Endpoints (geschützt).
 * Jeder User kann nur sein eigenes Profil sehen und bearbeiten.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final SecurityHelper securityHelper;

    public UserController(UserService userService, SecurityHelper securityHelper) {
        this.userService = userService;
        this.securityHelper = securityHelper;
    }

    /**
     * Eigenes Profil abrufen.
     * GET /api/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyProfile() {
        Long userId = securityHelper.getCurrentUserId();
        AppUser user = userService.getUser(userId);
        return ResponseEntity.ok(buildProfileResponse(user));
    }

    /**
     * Eigenes Profil aktualisieren.
     * PUT /api/users/me
     */
    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = securityHelper.getCurrentUserId();
        AppUser user = userService.updateProfile(userId, request);
        return ResponseEntity.ok(buildProfileResponse(user));
    }

    /**
     * Eigenes Passwort ändern.
     * PUT /api/users/me/password
     */
    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody Map<String, String> request) {
        Long userId = securityHelper.getCurrentUserId();
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");

        if (oldPassword == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "oldPassword und newPassword sind erforderlich"));
        }
        if (newPassword.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Neues Passwort muss mindestens 8 Zeichen lang sein"));
        }

        userService.changePassword(userId, oldPassword, newPassword);
        return ResponseEntity.ok(Map.of("message", "Passwort erfolgreich geändert"));
    }

    /**
     * Eigenes Konto löschen.
     * DELETE /api/users/me
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMyAccount() {
        Long userId = securityHelper.getCurrentUserId();
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> buildProfileResponse(AppUser user) {
        return Map.of(
            "id", user.getId(),
            "username", user.getUsername(),
            "email", user.getEmail(),
            "role", user.getRole().name(),
            "fitnessLevel", user.getFitnessLevel() != null ? user.getFitnessLevel().name() : "BEGINNER",
            "age", user.getAge() != null ? user.getAge() : 0,
            "weightKg", user.getWeightKg() != null ? user.getWeightKg() : 0.0,
            "heightCm", user.getHeightCm() != null ? user.getHeightCm() : 0.0,
            "createdAt", user.getCreatedAt().toString()
        );
    }
}
