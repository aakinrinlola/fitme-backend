package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.repository.TrainingPlanRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class PlanLimitService {

    private static final int FREE_LIMIT    = 5;
    private static final int PREMIUM_LIMIT = 20;

    private final TrainingPlanRepository planRepo;

    public PlanLimitService(TrainingPlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    public void checkAiPlanLimit(AppUser user) {
        AppUser.Role role = user.getRole() != null ? user.getRole() : AppUser.Role.USER;

        int limit = switch (role) {
            case ADMIN   -> Integer.MAX_VALUE;
            case PREMIUM -> PREMIUM_LIMIT;
            default      -> FREE_LIMIT;
        };

        LocalDateTime monthStart = LocalDateTime.now()
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0);

        long usedThisMonth = planRepo.countAiPlansThisMonth(user.getId(), monthStart);

        if (usedThisMonth >= limit) {
            String roleLabel = user.getRole() == AppUser.Role.PREMIUM ? "Premium" : "Free";
            throw new IllegalStateException(String.format(
                    "Monatliches Limit erreicht: %s-Nutzer können %d KI-Pläne pro Monat erstellen. " +
                            "Du hast diesen Monat bereits %d erstellt. " +
                            "Limit wird am 1. des nächsten Monats zurückgesetzt.",
                    roleLabel, limit, usedThisMonth
            ));
        }
    }

    public Map<String, Object> getLimitInfo(AppUser user) {
        AppUser.Role role = user.getRole() != null ? user.getRole() : AppUser.Role.USER;

        int limit = switch (role) {
            case ADMIN   -> -1;
            case PREMIUM -> PREMIUM_LIMIT;
            default      -> FREE_LIMIT;
        };

        LocalDateTime monthStart = LocalDateTime.now()
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0);

        long used = planRepo.countAiPlansThisMonth(user.getId(), monthStart);

        return Map.of(
                "used",       used,
                "limit",      limit,
                "remaining",  limit == -1 ? -1 : limit - used,
                "role",       user.getRole().name(),
                "resetsAt",   monthStart.plusMonths(1).toString()
        );
    }
}
