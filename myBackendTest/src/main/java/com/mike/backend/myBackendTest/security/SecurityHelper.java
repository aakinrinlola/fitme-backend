package com.mike.backend.myBackendTest.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Helper-Klasse für Security-Operationen.
 *
 * Unterstützt zwei Auth-Modi:
 * - Local JWT: Principal ist eine Long (User-ID)
 * - Auth0: Principal ist nach Auth0UserSyncFilter ebenfalls eine Long
 *          (falls Filter nicht lief: Fallback auf Jwt-Subject)
 */
@Component
public class SecurityHelper {

    /**
     * Gibt die User-ID des aktuell authentifizierten Benutzers zurück.
     * Funktioniert für beide Auth-Modi.
     */
    public Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("Kein authentifizierter Benutzer");
        }

        Object principal = auth.getPrincipal();

        // Local JWT Mode: Principal ist direkt die User-ID
        if (principal instanceof Long) {
            return (Long) principal;
        }

        // Auth0 Mode nach Sync-Filter: Principal kann auch Long sein
        if (principal instanceof Number) {
            return ((Number) principal).longValue();
        }

        // Fallback: Wenn principal ein String ist (z.B. User-ID als String)
        if (principal instanceof String) {
            try {
                return Long.parseLong((String) principal);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "Unerwarteter Principal-Typ: String '" + principal + "' ist keine gültige User-ID");
            }
        }

        throw new IllegalStateException(
                "Unerwarteter Principal-Typ: " + principal.getClass().getSimpleName()
                        + ". Erwartet: Long (User-ID).");
    }

    public void ensureOwnerOrAdmin(Long resourceOwnerId) {
        Long currentUserId = getCurrentUserId();
        if (!currentUserId.equals(resourceOwnerId) && !isAdmin()) {
            throw new SecurityException("Zugriff verweigert: Du kannst nur auf deine eigenen Daten zugreifen");
        }
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}