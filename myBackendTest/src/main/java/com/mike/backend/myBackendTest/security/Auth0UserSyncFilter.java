package com.mike.backend.myBackendTest.security;

import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter für Auth0-Modus.
 *
 * Aufgabe: Wenn ein Auth0-JWT validiert wurde, prüfe ob der User bereits
 * in der lokalen Datenbank existiert. Wenn nicht, erstelle ihn automatisch.
 *
 * Setzt anschließend die lokale User-ID als Principal im SecurityContext,
 * damit die bestehenden Controller/Services (die alle mit userId arbeiten)
 * weiterhin funktionieren.
 *
 * Wird NUR aktiviert wenn auth.mode=auth0.
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "auth0")
public class Auth0UserSyncFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(Auth0UserSyncFilter.class);

    private final UserRepository userRepository;

    public Auth0UserSyncFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String auth0Sub = jwt.getSubject();            // z.B. "auth0|abc123"
            String email = jwt.getClaimAsString("email");  // Auth0 liefert Email
            String name = jwt.getClaimAsString("name");    // Auth0 liefert Name
            String nickname = jwt.getClaimAsString("nickname");

            if (auth0Sub != null) {
                // User anhand Email finden oder neu erstellen
                String effectiveEmail = (email != null && !email.isBlank()) ? email : auth0Sub + "@auth0.local";
                String effectiveName = (nickname != null && !nickname.isBlank()) ? nickname
                        : (name != null && !name.isBlank()) ? name : "user_" + auth0Sub.hashCode();

                AppUser user = userRepository.findByEmail(effectiveEmail)
                        .orElseGet(() -> {
                            log.info("Auth0 User erstmalig: sub={}, email={}", auth0Sub, effectiveEmail);
                            AppUser newUser = new AppUser();
                            newUser.setUsername(effectiveName);
                            newUser.setEmail(effectiveEmail);
                            // Kein echtes Passwort nötig — Auth0 verwaltet die Credentials
                            newUser.setPassword("AUTH0_MANAGED");
                            newUser.setRole(AppUser.Role.USER);
                            newUser.setFitnessLevel(AppUser.FitnessLevel.BEGINNER);
                            return userRepository.save(newUser);
                        });

                // Bestehende Authentication mit lokaler User-ID ersetzen
                // Damit funktionieren SecurityHelper.getCurrentUserId() und alle Controller
                var newAuth = new JwtAuthenticationToken(jwt, jwtAuth.getAuthorities()) {
                    @Override
                    public Object getPrincipal() {
                        return user.getId(); // Lokale User-ID als Principal
                    }
                };
                newAuth.setDetails(jwtAuth.getDetails());
                SecurityContextHolder.getContext().setAuthentication(newAuth);
            }
        }

        filterChain.doFilter(request, response);
    }
}