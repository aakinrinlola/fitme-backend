package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.UpdateProfileRequest;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.exception.ResourceNotFoundException;
import com.mike.backend.myBackendTest.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public AppUser getUser(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + id));
    }

    @Transactional(readOnly = true)
    public List<AppUser> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Profil aktualisieren (ohne Passwort).
     * Nur die mitgeschickten Felder werden übernommen.
     */
    public AppUser updateProfile(Long userId, UpdateProfileRequest req) {
        AppUser user = getUser(userId);

        if (req.username() != null && !req.username().isBlank()) {
            // Prüfen ob Username schon vergeben ist
            userRepository.findByUsername(req.username()).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new IllegalArgumentException("Username '" + req.username() + "' ist bereits vergeben");
                }
            });
            user.setUsername(req.username());
        }

        if (req.email() != null && !req.email().isBlank()) {
            userRepository.findByEmail(req.email()).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new IllegalArgumentException("E-Mail '" + req.email() + "' ist bereits registriert");
                }
            });
            user.setEmail(req.email());
        }

        if (req.age() != null) user.setAge(req.age());
        if (req.weightKg() != null) user.setWeightKg(req.weightKg());
        if (req.heightCm() != null) user.setHeightCm(req.heightCm());

        if (req.fitnessLevel() != null) {
            try {
                user.setFitnessLevel(AppUser.FitnessLevel.valueOf(req.fitnessLevel().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Ungültiges Level → ignorieren
            }
        }

        return userRepository.save(user);
    }

    /**
     * Passwort ändern: altes Passwort prüfen, neues setzen.
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        AppUser user = getUser(userId);

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("Aktuelles Passwort ist falsch");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User nicht gefunden: " + id);
        }
        userRepository.deleteById(id);
    }
}
