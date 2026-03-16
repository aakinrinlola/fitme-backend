package com.mike.backend.myBackendTest.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Helper-Klasse für Security-Operationen in Controllern/Services.
 *
 * Verwendung:
 *   Long myId = securityHelper.getCurrentUserId();
 *   securityHelper.ensureOwnerOrAdmin(resourceOwnerId);
 */
@Component
public class SecurityHelper {

    /**
     * Gibt die User-ID des aktuell authentifizierten Benutzers zurück.
     * @throws IllegalStateException wenn kein User authentifiziert ist
     */
    public Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("Kein authentifizierter Benutzer");
        }
        return (Long) auth.getPrincipal();
    }

    /**
     * Prüft ob der aktuelle User der Besitzer der Ressource ist oder ADMIN-Rolle hat.
     * @throws SecurityException wenn der User nicht berechtigt ist
     */
    public void ensureOwnerOrAdmin(Long resourceOwnerId) {
        Long currentUserId = getCurrentUserId();
        if (!currentUserId.equals(resourceOwnerId) && !isAdmin()) {
            throw new SecurityException("Zugriff verweigert: Du kannst nur auf deine eigenen Daten zugreifen");
        }
    }

    /**
     * Prüft ob der aktuelle User die ADMIN-Rolle hat.
     */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
