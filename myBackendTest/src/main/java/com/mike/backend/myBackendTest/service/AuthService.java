package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.auth.*;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.repository.UserRepository;
import com.mike.backend.myBackendTest.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentifizierungs-Service.
 *
 * Verantwortlich für:
 * - Registrierung (Passwort hashen, User anlegen)
 * - Login (Passwort prüfen, JWT erstellen)
 * - Token-Refresh (neuen Access-Token ausstellen)
 */
@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Registriert einen neuen Benutzer.
     *
     * @throws IllegalArgumentException wenn Username oder E-Mail bereits vergeben
     */
    public AuthResponse register(RegisterRequest request) {
        // Prüfen ob Username/Email bereits existieren
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new IllegalArgumentException("Username '" + request.username() + "' ist bereits vergeben");
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("E-Mail '" + request.email() + "' ist bereits registriert");
        }

        // User erstellen mit gehashtem Passwort
        AppUser user = new AppUser(
            request.username(),
            request.email(),
            passwordEncoder.encode(request.password())
        );
        user.setRole(AppUser.Role.USER);

        // Optionale Profildaten setzen
        if (request.age() != null) user.setAge(request.age());
        if (request.weightKg() != null) user.setWeightKg(request.weightKg());
        if (request.heightCm() != null) user.setHeightCm(request.heightCm());
        if (request.fitnessLevel() != null) {
            try {
                user.setFitnessLevel(AppUser.FitnessLevel.valueOf(request.fitnessLevel().toUpperCase()));
            } catch (IllegalArgumentException e) {
                user.setFitnessLevel(AppUser.FitnessLevel.BEGINNER);
            }
        }

        user = userRepository.save(user);
        log.info("Neuer Benutzer registriert: id={}, username={}", user.getId(), user.getUsername());

        return buildAuthResponse(user);
    }

    /**
     * Login mit Username/E-Mail + Passwort.
     *
     * @throws IllegalArgumentException wenn Credentials ungültig
     */
    public AuthResponse login(LoginRequest request) {
        // User suchen (Username ODER E-Mail)
        AppUser user = userRepository.findByUsername(request.usernameOrEmail())
            .or(() -> userRepository.findByEmail(request.usernameOrEmail()))
            .orElseThrow(() -> new IllegalArgumentException("Ungültige Anmeldedaten"));

        // Passwort prüfen
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Fehlgeschlagener Login-Versuch für: {}", request.usernameOrEmail());
            throw new IllegalArgumentException("Ungültige Anmeldedaten");
        }

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Benutzerkonto ist deaktiviert");
        }

        log.info("Erfolgreicher Login: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    /**
     * Erstellt ein neues Access-Token aus einem gültigen Refresh-Token.
     */
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.refreshToken();

        if (!jwtService.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Ungültiger oder abgelaufener Refresh-Token");
        }

        Long userId = jwtService.getUserIdFromToken(refreshToken);
        AppUser user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("Benutzer nicht gefunden"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Benutzerkonto ist deaktiviert");
        }

        log.info("Token erneuert für: userId={}", userId);
        return buildAuthResponse(user);
    }

    /** Erstellt die AuthResponse mit Tokens und UserInfo */
    private AuthResponse buildAuthResponse(AppUser user) {
        String accessToken = jwtService.generateAccessToken(
            user.getId(), user.getUsername(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(
            user.getId(), user.getUsername(), user.getRole().name());

        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getRole().name(),
            user.getFitnessLevel() != null ? user.getFitnessLevel().name() : null,
            user.getAge(),
            user.getWeightKg(),
            user.getHeightCm()
        );

        return new AuthResponse(accessToken, refreshToken, userInfo);
    }
}
