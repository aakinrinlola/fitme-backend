package com.mike.backend.myBackendTest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mike.backend.myBackendTest.config.AiConfig;
import com.mike.backend.myBackendTest.dto.GeneratePlanRequest;
import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse.ExerciseAdjustment;
import com.mike.backend.myBackendTest.entity.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiConfig aiConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AiService(AiConfig aiConfig, WebClient webClient, ObjectMapper objectMapper) {
        this.aiConfig     = aiConfig;
        this.webClient    = webClient;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Datenstrukturen
    // =========================================================================

    public record GeneratedDay(String dayName, String focus, List<GeneratedExercise> exercises) {}
    public record GeneratedPlan(String planName, String description, List<GeneratedDay> days) {}

    public record GeneratedExercise(String exerciseName, int sets, int reps,
                                    double weightKg, int restSeconds, int targetRpe,
                                    String description) {}

    // =========================================================================
    // METHODE 1: Erklärung nach Feedback
    // =========================================================================

    public String generateExplanation(AppUser user, int sessionRpe, String userNote,
                                      List<ExerciseAdjustment> adjustments) {
        String prompt = buildExplanationPrompt(user, sessionRpe, userNote, adjustments);
        try {
            String response = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllamaText(prompt);
                case "openai" -> callOpenAiText(prompt,
                        "Du bist ein erfahrener Fitness-Coach. Antworte auf Deutsch, kurz und motivierend.");
                default -> null;
            };
            if (response != null && !response.isBlank()) return response;
        } catch (Exception e) {
            log.error("Fehler bei KI-Erklaerung: {}", e.getMessage());
        }
        return buildFallbackExplanation(sessionRpe, adjustments);
    }

    // =========================================================================
    // METHODE 2: Trainingsplan generieren
    // =========================================================================

    public GeneratedPlan generateTrainingPlan(AppUser user, GeneratePlanRequest request) {
        String userPrompt = buildPlanUserPrompt(user, request);
        try {
            String rawJson = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllamaJson(userPrompt);
                case "openai" -> callOpenAiText(userPrompt, buildPlanSystemPrompt());
                default -> null;
            };
            if (rawJson != null && !rawJson.isBlank()) {
                GeneratedPlan parsed = parseGeneratedPlan(rawJson);
                if (parsed != null && !parsed.days().isEmpty()) {
                    // ── NEU: Tagesreihenfolge nach Fokus korrigieren ──────────
                    GeneratedPlan reordered = reorderGeneratedPlanByFocus(parsed, request);
                    long total = reordered.days().stream().mapToLong(d -> d.exercises().size()).sum();
                    log.info("KI-Plan: '{}' mit {} Tagen, {} Uebungen", reordered.planName(), reordered.days().size(), total);
                    return reordered;
                }
            }
            log.warn("KI lieferte keinen verwertbaren Plan -> Fallback");
        } catch (Exception e) {
            log.error("Fehler bei KI-Plan-Generierung: {}", e.getMessage());
        }
        return buildFallbackPlan(request);
    }

    // =========================================================================
    // NEU: Tagesreihenfolge nach Fokus korrigieren (KI-Output + Fallback)
    // =========================================================================

    /**
     * Stellt sicher, dass Tag A immer der Fokus-Tag ist.
     * Verschiebt den passenden Tag an Position 0, behält Mobilitätsblock am Ende.
     */
    private GeneratedPlan reorderGeneratedPlanByFocus(GeneratedPlan plan, GeneratePlanRequest request) {
        if (plan.days().size() <= 1) return plan;

        String focusMuscles = resolveFocusMuscles(request);
        if (focusMuscles.isBlank()) return plan; // kein Fokus → keine Umordnung

        List<GeneratedDay> days = new ArrayList<>(plan.days());

        // Mobilitätsblock immer ans Ende (wird zuerst rausgenommen)
        GeneratedDay mobilityDay = null;
        List<GeneratedDay> trainingDays = new ArrayList<>();
        for (GeneratedDay day : days) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                mobilityDay = day;
            } else {
                trainingDays.add(day);
            }
        }

        int priorityIndex = findFocusDayIndex(trainingDays, focusMuscles);

        if (priorityIndex > 0) {
            // Fokus-Tag immer an den Anfang (Tag A)
            GeneratedDay focusDay = trainingDays.remove(priorityIndex);
            trainingDays.add(0, focusDay);
            log.info("Tagesreihenfolge angepasst: '{}' → Tag A (Fokus: {})",
                    focusDay.dayName(), focusMuscles);
        }

        // Tage nach dem Umsortieren zu Tag A / Tag B / Tag C umbenennen
        List<GeneratedDay> renamed = renameDaysSequentially(trainingDays);

        // Mobilitätsblock wieder ans Ende
        if (mobilityDay != null) renamed.add(mobilityDay);

        return new GeneratedPlan(plan.planName(), plan.description(), renamed);
    }

    /**
     * Gibt den Index des Trainingstages zurück, der am besten zum Fokus passt.
     * Gibt 0 zurück wenn der Fokus-Tag bereits an erster Stelle ist oder nicht erkannt wird.
     */
    private int findFocusDayIndex(List<GeneratedDay> days, String focusMuscles) {
        if (days.isEmpty()) return 0;

        String fl = focusMuscles.toLowerCase();
        FocusType focus = detectFocusType(fl);

        for (int i = 0; i < days.size(); i++) {
            String dayFocus = (days.get(i).focus() + " " + days.get(i).dayName()).toLowerCase();
            if (matchesFocusType(dayFocus, focus)) {
                return i;
            }
        }

        // Sekundärer Scan: Übungsnamen prüfen
        for (int i = 0; i < days.size(); i++) {
            boolean exerciseMatch = days.get(i).exercises().stream()
                    .anyMatch(ex -> exerciseMatchesFocus(ex.exerciseName().toLowerCase(), focus));
            if (exerciseMatch) return i;
        }

        return 0; // kein Match → unverändert lassen
    }

    private enum FocusType { BEINE, BRUST, RUECKEN, SCHULTER, ARME, CORE, UNKNOWN }

    private FocusType detectFocusType(String focusLower) {
        if (focusLower.contains("bein") || focusLower.contains("quad") ||
                focusLower.contains("hamstring") || focusLower.contains("gesäß") ||
                focusLower.contains("glute") || focusLower.contains("legs") ||
                focusLower.contains("wade")) {
            return FocusType.BEINE;
        }
        if (focusLower.contains("brust") || focusLower.contains("pecto") ||
                focusLower.contains("chest") || focusLower.contains("push")) {
            return FocusType.BRUST;
        }
        if (focusLower.contains("rücken") || focusLower.contains("back") ||
                focusLower.contains("latiss") || focusLower.contains("rudern") ||
                focusLower.contains("pull")) {
            return FocusType.RUECKEN;
        }
        if (focusLower.contains("schulter") || focusLower.contains("deltoid") ||
                focusLower.contains("shoulder")) {
            return FocusType.SCHULTER;
        }
        if (focusLower.contains("arm") || focusLower.contains("bizeps") ||
                focusLower.contains("trizeps") || focusLower.contains("bicep") ||
                focusLower.contains("tricep")) {
            return FocusType.ARME;
        }
        if (focusLower.contains("core") || focusLower.contains("bauch") ||
                focusLower.contains("abs")) {
            return FocusType.CORE;
        }
        return FocusType.UNKNOWN;
    }

    private boolean matchesFocusType(String dayFocusLower, FocusType focus) {
        return switch (focus) {
            case BEINE    -> dayFocusLower.contains("bein") || dayFocusLower.contains("quad") ||
                    dayFocusLower.contains("hamstring") || dayFocusLower.contains("gesäß") ||
                    dayFocusLower.contains("glute") || dayFocusLower.contains("leg") ||
                    dayFocusLower.contains("unterkörper");
            case BRUST    -> dayFocusLower.contains("brust") || dayFocusLower.contains("push") ||
                    dayFocusLower.contains("chest") || dayFocusLower.contains("pecto");
            case RUECKEN  -> dayFocusLower.contains("rücken") || dayFocusLower.contains("pull") ||
                    dayFocusLower.contains("back") || dayFocusLower.contains("latiss");
            case SCHULTER -> dayFocusLower.contains("schulter") || dayFocusLower.contains("shoulder") ||
                    dayFocusLower.contains("deltoid");
            case ARME     -> dayFocusLower.contains("arm") || dayFocusLower.contains("bizeps") ||
                    dayFocusLower.contains("trizeps");
            case CORE     -> dayFocusLower.contains("core") || dayFocusLower.contains("bauch") ||
                    dayFocusLower.contains("abs");
            case UNKNOWN  -> false;
        };
    }

    private boolean exerciseMatchesFocus(String exerciseLower, FocusType focus) {
        return switch (focus) {
            case BEINE    -> exerciseLower.contains("kniebeuge") || exerciseLower.contains("beinpresse") ||
                    exerciseLower.contains("ausfallschritt") || exerciseLower.contains("kreuzheben") ||
                    exerciseLower.contains("beinbeuger") || exerciseLower.contains("wadenheben");
            case BRUST    -> exerciseLower.contains("bankdrücken") || exerciseLower.contains("fliegenschlagen") ||
                    exerciseLower.contains("dips") || exerciseLower.contains("schrägbank");
            case RUECKEN  -> exerciseLower.contains("klimmzüge") || exerciseLower.contains("latzug") ||
                    exerciseLower.contains("rudern") || exerciseLower.contains("face pull");
            case SCHULTER -> exerciseLower.contains("schulterdrücken") || exerciseLower.contains("seitheben") ||
                    exerciseLower.contains("military press");
            case ARME     -> exerciseLower.contains("curl") || exerciseLower.contains("pushdown") ||
                    exerciseLower.contains("trizeps") || exerciseLower.contains("hammer");
            case CORE     -> exerciseLower.contains("plank") || exerciseLower.contains("crunch") ||
                    exerciseLower.contains("sit-up") || exerciseLower.contains("beinheben");
            case UNKNOWN  -> false;
        };
    }

    /** Alle Fokus-Quellen zusammenführen (gleiche Logik wie im User-Prompt) */
    private String resolveFocusMuscles(GeneratePlanRequest request) {
        List<String> parts = new ArrayList<>();
        if (request.focusMuscleGroups() != null) parts.addAll(request.focusMuscleGroups());
        if (request.focusMusclesFreetext() != null && !request.focusMusclesFreetext().isBlank())
            parts.add(request.focusMusclesFreetext().trim());
        if (request.focusMuscles() != null && !request.focusMuscles().isBlank())
            parts.add(request.focusMuscles().trim());
        return String.join(", ", parts.stream().filter(s -> s != null && !s.isBlank()).toList());
    }

    // =========================================================================
    // Tag-Anzahl-Logik
    // =========================================================================

    private int calcNumDayPlans(int daysPerWeek) {
        if (daysPerWeek <= 1) return 1;
        if (daysPerWeek <= 2) return 2;
        return 3; // 3+ Tage → immer 3 distinkte Tagespläne (A/B/C mit Rotation)
    }

    private int calcExercisesPerDay(String level) {
        if (level == null) return 5;
        return switch (level.toUpperCase()) {
            case "INTERMEDIATE" -> 6;
            case "ADVANCED"     -> 7;
            default             -> 5;
        };
    }

    private int calcExercisesPerDayWithDuration(String level, Integer durationMinutes) {
        if (durationMinutes == null) return calcExercisesPerDay(level);
        if (durationMinutes <= 30) return 3;
        if (durationMinutes <= 45) return 4;
        return calcExercisesPerDay(level);
    }

    private String buildRotationHint(int daysPerWeek, int numDayPlans, List<String> dayNames) {
        if (numDayPlans == 1 || daysPerWeek <= numDayPlans) return "";
        StringBuilder sb = new StringBuilder("Wochenrotation: ");
        for (int i = 0; i < daysPerWeek; i++) {
            if (i > 0) sb.append(", ");
            sb.append(dayNames.get(i % numDayPlans));
        }
        sb.append(", ...");
        return sb.toString();
    }

    // =========================================================================
    // System-Prompt
    // =========================================================================

    private String buildPlanSystemPrompt() {
        return """
            Du bist ein erfahrener Personal Trainer. Erstelle einen strukturierten Trainingsplan.
            Antworte AUSSCHLIESSLICH mit einem validen JSON-Objekt.
            Kein Markdown, keine Codeblöcke, kein Text vor oder nach dem JSON.

            ═══════════════════════════════════════════════════════
            PFLICHTREGELN — ALLE REGELN SIND VERBINDLICH
            ═══════════════════════════════════════════════════════

            REGEL 1 — ANZAHL DER TRAININGSTAGE (DISTINCT TEMPLATES):
              1 Tag/Woche  → genau 1 Trainingsplan-Tag (Tag A, jeden Tag gleich)
              2 Tage/Woche → genau 2 Trainingsplan-Tage (Tag A, Tag B — Rotation A/B)
              3+ Tage/Woche → genau 3 Trainingsplan-Tage (Tag A, Tag B, Tag C — Rotation)
              Jeder Trainingstag MUSS sich klar von den anderen unterscheiden.
              Der Mobilitätsblock zählt NICHT als Trainingstag.

            REGEL 2 — ÜBUNGSANZAHL PRO TAG (Standard):
              BEGINNER     → genau 5 Übungen
              INTERMEDIATE → genau 6 Übungen
              ADVANCED     → genau 7 Übungen
              Wird durch REGEL 8 (Trainingsdauer) überschrieben, falls angegeben.

            REGEL 3 — DUPLIKAT-VERBOT:
              ✗ Keine Übung darf in mehreren Trainingstagen vorkommen.
              ✓ Jeder Tag hat klar andere Übungen als alle anderen.

            REGEL 4 — FOKUS-MUSKELGRUPPEN:
              Fokus-Muskelgruppen bestimmen den Charakter der Trainingstage.
              Sie müssen klar priorisiert werden (≥60% der Übungen pro Tag).

            REGEL 5 — REIHENFOLGE PRO TAG:
              Große Muskelgruppen zuerst (Brust/Rücken/Beine), dann kleine (Schulter, Arme).
              ✗ Schulter und Arme niemals vor Brust, Rücken oder Beinen.

            REGEL 6 — ÜBUNGSNAMEN:
              ✗ VERBOTEN: "Beine", "Brust", "Rücken", "Arme", "Core", "Ganzkörper"
              ✓ NUR konkrete Namen: "Bankdrücken mit Langhantel", "Kniebeuge mit Langhantel" usw.

            REGEL 7 — DOSIS:
              sets: 2–5 | reps: 5–15 | restSeconds: 45–180 | targetRpe: 6–9
              weightKg: realistisch; Körpergewichtsübungen = 0, alle anderen > 0

            REGEL 8 — TRAININGSDAUER (überschreibt REGEL 2 bei ≤45 min):
              30 min → EXAKT 3 Übungen. Nur Grundübungen (Compound). Pausen 45–60s.
              45 min → EXAKT 4 Übungen. Max. 1 Isolation. Pausen 60–90s.
              60 min → Standard nach REGEL 2. Pausen 90–120s.
              75 min → Standard + 1 Zusatzübung erlaubt. Pausen bis 120s.
              90 min → Standard + 2 Zusatzübungen oder mehr Sätze. Pausen bis 180s.

            REGEL 9 — REGENERATION / VOLUMEN-TOLERANZ:
              ≥8h Schlaf + Niedriger Stress   → 4–5 Sätze pro Übung möglich.
              6–7h Schlaf + Moderater Stress  → Standard 3–4 Sätze.
              ≤5h Schlaf ODER Hoher Stress    → Max. 2–3 Sätze. Gewichte −10%. Kein Training bis Versagen.
              Schlechter Schlaf UND hoher Stress zusammen → max. 2 Sätze pro Übung.

            REGEL 10 — VERLETZUNGEN / EINSCHRÄNKUNGEN:
              ✗ VERBOTEN: Übungen, die betroffene Regionen direkt belasten.
              ✓ PFLICHT: Sichere Alternativen wählen.

            REGEL 11 — MOBILITÄTSBLOCK (nur wenn "includeMobilityPlan: true"):
              → Als LETZTEN Tag einen zusätzlichen Tag hinzufügen: "dayName": "Mobilitätsblock"
              → Genau 5–7 Mobility-Übungen.
              → sets: 1–2 | reps: 20–60 | weightKg: 0.0 | restSeconds: 30 | targetRpe: 3
              → JEDE Mobilitätsübung MUSS ein gefülltes "description"-Feld haben.
              → Normale Trainingstag-Übungen haben "description": "".

            REGEL 12 — TAGESREIHENFOLGE NACH FOKUS (KRITISCH):
              ✓ PFLICHT: Der Fokus-Tag MUSS als "Tag A" (erstes Element im 'days'-Array) stehen.
              Nach der Umsortierung werden alle Tage sequenziell als Tag A, Tag B, Tag C benannt.

              Fokus-Mapping:
                BEINE / QUAD / HAMSTRING / GLUTES / GESÄSS
                  → "dayName": "Tag A – Beine" kommt ZUERST im JSON
                BRUST / PUSH
                  → "dayName": "Tag A – Push" mit Brust-Schwerpunkt kommt ZUERST
                RÜCKEN / PULL / LATISSIMUS
                  → "dayName": "Tag A – Pull" mit Rücken-Schwerpunkt kommt ZUERST
                SCHULTER
                  → "dayName": "Tag A – Schulter" kommt ZUERST
                ARME
                  → "dayName": "Tag A – Arme" kommt ZUERST
                KEIN FOKUS
                  → Standardreihenfolge: Push → Pull → Legs

            REGEL 13 — EXPLOSIVE ÜBUNGEN FÜR FORTGESCHRITTENE & EXPERTEN:
              ✓ PFLICHT bei INTERMEDIATE und ADVANCED: Jeder Bein-Trainingstag MUSS
                mindestens eine explosive Übung enthalten.
              Erlaubte explosive Übungen (Auswahl):
                Box Jumps | Jump Squats | Broad Jumps | Depth Jumps | Bounding
                Power Clean | Hang Clean | Kettlebell Swing | Medball Slam
                Sprünge auf Kasten | Laterale Sprünge | Einbeinige Box Jumps
              ✗ VERBOTEN: Bein-Tag ohne explosive Komponente bei INTERMEDIATE/ADVANCED.
              ✓ Explosive Übung immer ZUERST im Bein-Tag (wenn Muskeln noch frisch).
              weightKg: 0.0 für Körpergewichtssprünge | sets: 3–4 | reps: 5–8 | RPE: 8–9

            REGEL 14 — VARIABILITÄT & ANTI-STATIK (KRITISCH):
              ✗ VERBOTEN: Immer dieselben Standardübungen zu verwenden.
              ✓ PFLICHT: Jede Generierung MUSS sichtbar anders sein als ein typischer Plan.
              Variiere aktiv:
                • Übungsauswahl: Nutze verschiedene Varianten (Langhantel / KH / Kabel /
                  Maschine / Körpergewicht / unilateral) und wechsle zwischen ihnen.
                • Trainingsstil: Wähle PRO TAG einen dominanten Stil:
                    "Kraft" → 4–5 Sätze, 3–6 Wdh, hohe Last, lange Pausen (120–180s)
                    "Hypertrophie" → 3–4 Sätze, 8–12 Wdh, mittlere Last, 60–90s Pause
                    "Explosiv/Athletic" → 3–4 Sätze, 4–8 Wdh, schnelle Ausführung
                    "Kraft-Ausdauer" → 2–3 Sätze, 12–20 Wdh, kurze Pausen 30–60s
                • Reihenfolge: Variiere die Übungsreihenfolge (z.B. auch mal Isolation vor
                  Compound wenn der Fokus darauf liegt).
                • Perspektive: Nimm jedes Mal eine andere Trainer-Perspektive ein
                  (z.B. Powerlifter, Bodybuilder, Athletik-Coach, Functional Trainer).

            REGEL 15 — KLARE TAGES-UNTERSCHIEDE:
              ✓ PFLICHT: Jeder Trainingstag (A, B, C) MUSS sich in ALLEN drei Dimensionen
                klar unterscheiden:
                  1. ÜBUNGEN: Keine einzige Übung darf in zwei verschiedenen Tagen vorkommen.
                  2. FOKUS: Jeder Tag hat eine andere primäre Muskelgruppe / Bewegungsmuster.
                  3. INTENSITÄT: Variiere den Trainingsstil pro Tag.
                Beispiel für 3 Tage:
                  Tag A – Kraft (5×5, hohe Last)
                  Tag B – Hypertrophie (4×10, mittlere Last)
                  Tag C – Explosiv/Athletik (3×6, schnell + Sprünge bei Bein-Tag)

            ═══════════════════════════════════════════════════════
            AUSGABEFORMAT (exakt einhalten):
            ═══════════════════════════════════════════════════════
            {
              "planName": "string",
              "description": "string",
              "days": [
                {
                  "dayName": "Tag A",
                  "focus": "Brust, Schulter, Trizeps",
                  "exercises": [
                    {
                      "exerciseName": "Bankdrücken mit Langhantel",
                      "sets": 4,
                      "reps": 8,
                      "weightKg": 60.0,
                      "restSeconds": 120,
                      "targetRpe": 7,
                      "description": ""
                    }
                  ]
                }
              ]
            }

            Gib NUR das JSON zurück. Kein Text davor oder danach.
            """;
    }

    // =========================================================================
    // User-Prompt
    // =========================================================================

    private String buildPlanUserPrompt(AppUser user, GeneratePlanRequest request) {
        List<String> focusParts = new ArrayList<>();
        if (request.focusMuscleGroups() != null)
            focusParts.addAll(request.focusMuscleGroups());
        if (request.focusMusclesFreetext() != null && !request.focusMusclesFreetext().isBlank())
            focusParts.add(request.focusMusclesFreetext().trim());
        if (request.focusMuscles() != null && !request.focusMuscles().isBlank())
            focusParts.add(request.focusMuscles().trim());
        String focusMuscles = String.join(", ", focusParts.stream()
                .filter(s -> s != null && !s.isBlank()).toList());

        String planName    = request.planName();
        String userPrompt  = request.userPrompt();
        String fitnessGoal = (request.fitnessGoal() != null && !request.fitnessGoal().isBlank())
                ? translateGoal(request.fitnessGoal()) : "Nicht angegeben";
        int daysPerWeek    = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        String rawLevel    = resolveRawLevel(request, user);
        String levelLabel  = translateLevel(rawLevel);
        int numDayPlans    = calcNumDayPlans(daysPerWeek);
        int exPerDay       = calcExercisesPerDayWithDuration(rawLevel, request.sessionDurationMinutes());

        String durationStr = request.sessionDurationMinutes() != null
                ? request.sessionDurationMinutes() + " Minuten" : "Nicht angegeben";
        String sleepStr = request.sleepHoursPerNight() != null
                ? request.sleepHoursPerNight() + " Stunden" : "Nicht angegeben";
        String stressStr  = translateStress(request.stressLevel());
        String injuriesStr = (request.injuries() != null && !request.injuries().isBlank())
                ? request.injuries().trim() : "Keine";
        boolean mobility  = Boolean.TRUE.equals(request.includeMobilityPlan());

        int    sleepH     = request.sleepHoursPerNight() != null ? request.sleepHoursPerNight() : 7;
        String stress     = request.stressLevel() != null ? request.stressLevel().toUpperCase() : "MODERATE";
        String recoveryAdvice;
        if (sleepH <= 5 || "HIGH".equals(stress)) {
            recoveryAdvice = "Schlechte Regeneration → max. 2–3 Sätze. Gewichte −10%. Kein Training bis Versagen.";
        } else if (sleepH >= 8 && "LOW".equals(stress)) {
            recoveryAdvice = "Gute Regeneration → 4–5 Sätze pro Übung möglich.";
        } else {
            recoveryAdvice = "Moderate Regeneration → Standard 3–4 Sätze pro Übung.";
        }

        String dayNamesStr = switch (numDayPlans) {
            case 1  -> "Tag A";
            case 2  -> "Tag A und Tag B";
            default -> "Tag A, Tag B und Tag C";
        };

        String standardDist = switch (numDayPlans) {
            case 1  -> "Tag A: Ausgewogener Ganzkörpertag — Brust → Rücken → Beine → Schulter → Arme.";
            case 2  -> "Tag A (Push): Brust → Schulter → Trizeps\nTag B (Pull+Legs): Rücken → Bizeps → Beine";
            default -> "Tag A (Push): Brust → Schulter → Trizeps\nTag B (Pull): Rücken → Bizeps\nTag C (Legs): Beine";
        };

        String focusSection = buildFocusSection(focusMuscles, numDayPlans);

        // ── NEU: Fokus-Tagesreihenfolge bestimmen ─────────────────────────────
        String focusOrderingRule = buildFocusOrderingRule(focusMuscles);

        String rotationLine = buildRotationHint(daysPerWeek, numDayPlans,
                List.of("Tag A", "Tag B", "Tag C"));

        return "Erstelle einen strukturierten Trainingsplan anhand der folgenden Angaben:\n\n"
                + "Planname: "                 + planName     + "\n"
                + "Benutzerwunsch: "           + userPrompt   + "\n"
                + "Trainingsziel: "            + fitnessGoal  + "\n"
                + "Trainingstage/Woche: "      + daysPerWeek  + "\n"
                + "Fokus-Muskelgruppen: "      + (focusMuscles.isBlank() ? "Keine" : focusMuscles) + "\n"
                + "Erfahrungslevel: "          + levelLabel   + "\n"
                + "Alter: "   + (user.getAge()      != null ? user.getAge()      + " Jahre" : "k.A.") + "\n"
                + "Gewicht: " + (user.getWeightKg() != null ? user.getWeightKg() + " kg"    : "k.A.") + "\n"
                + "── Neue Parameter ──────────────────────────────\n"
                + "Trainingsdauer pro Einheit: " + durationStr  + "\n"
                + "Schlaf pro Nacht: "           + sleepStr     + "\n"
                + "Stresslevel: "                + stressStr    + "\n"
                + "Verletzungen/Einschränkungen: "+ injuriesStr  + "\n"
                + "Mobilitätsblock gewünscht: "  + (mobility ? "Ja" : "Nein") + "\n"
                + "\n"
                + "══════════════════════════════════════════\n"
                + "PFLICHTREGELN FÜR DIESEN PLAN:\n"
                + "══════════════════════════════════════════\n"
                + "1. Erstelle EXAKT " + numDayPlans + " Trainingstag-Plan(e): " + dayNamesStr + ".\n"
                + "\n"
                + "2. Jeder Trainingstag enthält EXAKT " + exPerDay + " Übungen.\n"
                + (request.sessionDurationMinutes() != null && request.sessionDurationMinutes() <= 45
                ? "   (Reduziert wegen Trainingsdauer " + durationStr + " → REGEL 8)\n"
                : "   (Erfahrungslevel: " + levelLabel + " → REGEL 2)\n")
                + "\n"
                + "3. MUSKELGRUPPEN-AUFTEILUNG:\n"
                + standardDist + "\n"
                + focusSection + "\n"
                + "\n"
                // ── NEU: explizite Reihenfolge-Regel ──────────────────────────
                + "4. TAGESREIHENFOLGE (KRITISCH — REGEL 12):\n"
                + focusOrderingRule + "\n"
                + "\n"
                + "5. ÜBUNGSREIHENFOLGE PRO TAG: Große Muskelgruppen zuerst. Schulter/Arme niemals vor Brust/Rücken/Beinen.\n"
                + "\n"
                + "6. DUPLIKAT-VERBOT: Keine Übung in mehr als einem Tag.\n"
                + (rotationLine.isBlank() ? "" : "\n7. ROTATION: " + rotationLine + "\n")
                + "\n"
                + "8. TRAININGSDAUER: " + durationStr + "\n"
                + "   Pausen und Übungsanzahl entsprechend REGEL 8 anpassen.\n"
                + "\n"
                + "9. REGENERATION: Schlaf " + sleepStr + " | Stress " + stressStr + "\n"
                + "   " + recoveryAdvice + "\n"
                + "\n"
                + (!"Keine".equals(injuriesStr)
                ? "10. VERLETZUNGEN / EINSCHRÄNKUNGEN (KRITISCH):\n"
                + "   " + injuriesStr + "\n"
                + "   → Alle betroffenen Übungen KOMPLETT vermeiden und ersetzen (REGEL 10).\n\n"
                : "")
                + (mobility
                ? "11. MOBILITÄTSBLOCK (PFLICHT):\n"
                + "    Als LETZTEN Tag 'Mobilitätsblock' hinzufügen (Zusatz zu den " + numDayPlans + " Trainingstagen).\n"
                + "    5–7 Mobility-Übungen, jede mit 'description' (1–2 Sätze Ausführung auf Deutsch).\n"
                + "    weightKg: 0.0 | sets: 1–2 | reps: 20–60 | restSeconds: 30 | targetRpe: 3\n\n"
                : "")
                + buildExplosiveRule(rawLevel, numDayPlans)
                + buildVariabilityHint(rawLevel, numDayPlans)
                + "Gib ausschließlich das JSON zurück.";
    }

    /**
     * NEU: Erzeugt eine explizite Reihenfolge-Anweisung basierend auf dem Fokus.
     */
    private String buildFocusOrderingRule(String focusMuscles) {
        if (focusMuscles == null || focusMuscles.isBlank()) {
            return "   Kein Fokus → Standardreihenfolge beibehalten: Push (Tag A) → Pull (Tag B) → Legs (Tag C).";
        }

        String fl = focusMuscles.toLowerCase();
        FocusType focus = detectFocusType(fl);

        return switch (focus) {
            case BEINE    -> "   Fokus BEINE → Tag A MUSS ein Bein-Workout sein (Kniebeuge, Beinpresse, RDL usw.).\n"
                    + "   Tag A – Beine kommt als ERSTES Element im 'days'-Array.\n"
                    + "   Danach folgen die Oberkörper-Tage als Tag B, Tag C.";
            case BRUST    -> "   Fokus BRUST → Tag A MUSS ein Push-Workout (Brust-Schwerpunkt) sein.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.\n"
                    + "   Bankdrücken, Schrägdrücken etc. kommen ZUERST.";
            case RUECKEN  -> "   Fokus RÜCKEN → Tag A MUSS ein Pull-Workout (Rücken-Schwerpunkt) sein.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.\n"
                    + "   Klimmzüge, Rudern, Latzug etc. kommen ZUERST.";
            case SCHULTER -> "   Fokus SCHULTER → Tag A MUSS ein Schulter-Workout sein.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.\n"
                    + "   Schulterdrücken, Seitheben etc. kommen ZUERST.";
            case ARME     -> "   Fokus ARME → Tag A MUSS einen Arm-Schwerpunkt haben.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.";
            case CORE     -> "   Fokus CORE → Tag A MUSS einen Core-Schwerpunkt haben.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.";
            default       -> "   Fokus '" + focusMuscles + "' → Tag A soll diesen Fokus priorisieren.\n"
                    + "   Tag A MUSS als erstes Element im 'days'-Array stehen.";
        };
    }

    /** Fokus-spezifische Muskelgruppen-Aufteilung */
    private String buildFocusSection(String focusMuscles, int numDayPlans) {
        if (focusMuscles.isBlank()) return "";
        String fl = focusMuscles.toLowerCase();
        boolean isBein     = fl.contains("bein") || fl.contains("quad") || fl.contains("hamstring")
                || fl.contains("gesäß") || fl.contains("glute");
        boolean isSchulter = fl.contains("schulter") || fl.contains("deltoid");
        boolean isBrust    = fl.contains("brust") || fl.contains("pecto");
        boolean isRuecken  = fl.contains("rücken") || fl.contains("latiss");

        if (numDayPlans == 3) {
            if (isBein) return """

                    FOKUS-REGEL BEINE (3 Tage — BEINE ZUERST als Tag A):
                    Tag A (Beine – Quad, ERSTER TAG): Kniebeuge, Beinpresse, Ausfallschritte, Wadenheben + ggf. 1 Oberkörper.
                    Tag B (Beine – Hamstring): Rumänisches Kreuzheben, Beinbeuger, Gesäßübung + ggf. 1 Oberkörper.
                    Tag C (Oberkörper): Brust, Rücken, Schulter, Arme.""";
            if (isSchulter) return """

                    FOKUS-REGEL SCHULTER (3 Tage — SCHULTER ZUERST):
                    Tag A (Schulter+Brust, ERSTER TAG): Schulterdrücken, Seitheben, Bankdrücken, Fliegenschlagen, Trizeps.
                    Tag B (Schulter+Rücken): Seitheben hinten, Face Pulls, Latzug, Rudern, Bizeps.
                    Tag C (Beine): Kniebeuge, Beinpresse, Rumänisches KH, Ausfallschritte, Waden.""";
            if (isBrust) return """

                    FOKUS-REGEL BRUST (3 Tage — BRUST/PUSH ZUERST):
                    Tag A (Brust+Schulter, ERSTER TAG): Bankdrücken, Schrägdrücken, Schulterdrücken, Seitheben, Trizeps.
                    Tag B (Brust+Trizeps): Schrägbankdrücken KH, Fliegenschlagen, Trizeps-Übungen.
                    Tag C (Rücken+Beine): Klimmzüge, Rudern, Kniebeuge, Rumänisches KH.""";
            if (isRuecken) return """

                    FOKUS-REGEL RÜCKEN (3 Tage — RÜCKEN/PULL ZUERST):
                    Tag A (Rücken-Breite, ERSTER TAG): Klimmzüge, Latzug, einarmiges Rudern, Bizeps.
                    Tag B (Rücken-Dicke): Langhantelrudern, T-Bar-Rudern, Kreuzheben, Face Pulls, Bizeps-Variation.
                    Tag C (Brust+Beine): Bankdrücken, Schrägdrücken, Kniebeuge, Rumänisches KH.""";
        } else if (numDayPlans == 2) {
            if (isBein) return """

                    FOKUS-REGEL BEINE (2 Tage — BEINE ZUERST als Tag A):
                    Tag A (Beine – Quad, ERSTER TAG): Kniebeuge, Beinpresse, Ausfallschritte + Oberkörper ergänzend.
                    Tag B (Beine – Hamstring + Oberkörper): Rumänisches KH, Beinbeuger + Oberkörper ergänzend.""";
            if (isSchulter) return """

                    FOKUS-REGEL SCHULTER (2 Tage — SCHULTER ZUERST):
                    Tag A (Schulter+Brust, ERSTER TAG): Drückende Schulterübungen + Brust.
                    Tag B (Schulter+Rücken): Ziehende Schulterübungen + Rücken.""";
            if (isBrust) return """

                    FOKUS-REGEL BRUST (2 Tage — BRUST ZUERST):
                    Tag A (Brust+Push, ERSTER TAG): Bankdrücken, Schrägdrücken, Schulter, Trizeps.
                    Tag B (Rücken+Beine): Klimmzüge, Rudern, Kniebeuge, Rumänisches KH.""";
            if (isRuecken) return """

                    FOKUS-REGEL RÜCKEN (2 Tage — RÜCKEN ZUERST):
                    Tag A (Rücken+Pull, ERSTER TAG): Klimmzüge, Latzug, Rudern, Bizeps.
                    Tag B (Brust+Beine): Bankdrücken, Kniebeuge, Beinpresse, Trizeps.""";
        }
        return "\nFOKUS '" + focusMuscles + "': Tag A MUSS diesen Fokus priorisieren — klar stärker gewichten als andere Tage.";
    }

    /**
     * REGEL 13: Explosive Übungen für INTERMEDIATE und ADVANCED auf Bein-Tagen.
     */
    private String buildExplosiveRule(String level, int numDayPlans) {
        if (level == null) return "";
        String l = level.toUpperCase();
        if (!l.equals("INTERMEDIATE") && !l.equals("ADVANCED")) return "";
        if (numDayPlans < 1) return "";
        return "12. EXPLOSIVE ÜBUNG AUF BEIN-TAGEN (PFLICHT für " + level + " — REGEL 13):\n"
                + "    Jeder Bein-Trainingstag MUSS als ERSTE Übung eine explosive Übung enthalten.\n"
                + "    Erlaubt: Box Jumps | Jump Squats | Broad Jumps | Power Clean | Kettlebell Swing\n"
                + "             Medball Slam | Laterale Sprünge | Depth Jumps | Bounding\n"
                + "    weightKg: 0.0 | sets: 3–4 | reps: 5–8 | restSeconds: 90–120 | targetRpe: 8–9\n\n";
    }

    /**
     * REGEL 14 + 15: Variabilität und klare Tages-Unterschiede.
     */
    private String buildVariabilityHint(String level, int numDayPlans) {
        String styleHint;
        if (numDayPlans == 1) {
            styleHint = "    Wähle einen klaren Trainingsstil: Kraft (5×5) ODER Hypertrophie (4×10) ODER Explosiv.\n";
        } else if (numDayPlans == 2) {
            styleHint = "    Tag A und Tag B MÜSSEN verschiedene Trainingsstile haben:\n"
                    + "      z.B. Tag A = Kraft (5×5, hohe Last) | Tag B = Hypertrophie (4×10, mittlere Last)\n"
                    + "      ODER Tag A = Hypertrophie | Tag B = Explosiv/Athletik\n";
        } else {
            styleHint = "    Jeder Tag MUSS einen anderen dominanten Trainingsstil haben:\n"
                    + "      Tag A = Kraft (4–5×3–6, hohe Last, 120–180s Pause)\n"
                    + "      Tag B = Hypertrophie (3–4×8–12, mittlere Last, 60–90s Pause)\n"
                    + "      Tag C = Explosiv/Athletik (3–4×4–8, schnelle Ausführung, dynamische Übungen)\n";
        }
        return "13. VARIABILITÄT & TAGES-UNTERSCHIEDE (KRITISCH — REGEL 14 + 15):\n"
                + styleHint
                + "    ✗ VERBOTEN: Immer dieselben Standardübungen (Bankdrücken/Klimmzüge/Kniebeuge als Einheitsbrei).\n"
                + "    ✓ PFLICHT: Nutze verschiedene Übungsvarianten (KH / LH / Kabel / unilateral / Körpergewicht).\n"
                + "    ✓ PFLICHT: Keine einzige Übung darf in zwei verschiedenen Trainingstagen vorkommen.\n\n";
    }

    // =========================================================================
    // JSON-Parser
    // =========================================================================

    private GeneratedPlan parseGeneratedPlan(String rawResponse) {
        try {
            String cleaned = rawResponse
                    .replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) { log.warn("Kein JSON gefunden"); return null; }
            cleaned = cleaned.substring(start, end + 1);

            JsonNode root = objectMapper.readTree(cleaned);
            String planName    = root.path("planName").asText("KI-Trainingsplan");
            String description = root.path("description").asText("");

            JsonNode daysArray = root.path("days");
            if (daysArray.isArray() && daysArray.size() > 0) {
                List<GeneratedDay> days = new ArrayList<>();
                for (JsonNode dayNode : daysArray) {
                    String dayName = dayNode.path("dayName").asText("Tag " + (days.size() + 1));
                    String focus   = dayNode.path("focus").asText("");
                    List<GeneratedExercise> exercises = parseExercises(dayNode.path("exercises"));
                    if (!exercises.isEmpty()) days.add(new GeneratedDay(dayName, focus, exercises));
                }
                if (!days.isEmpty()) return new GeneratedPlan(planName, description, days);
            }

            JsonNode exArray = root.path("exercises");
            if (exArray.isArray() && exArray.size() > 0) {
                List<GeneratedExercise> all = parseExercises(exArray);
                if (!all.isEmpty())
                    return new GeneratedPlan(planName, description, splitExercisesIntoDays(all));
            }
            return null;
        } catch (Exception e) {
            log.error("JSON-Parse-Fehler: {}", e.getMessage());
            return null;
        }
    }

    private List<GeneratedExercise> parseExercises(JsonNode exArray) {
        List<GeneratedExercise> list = new ArrayList<>();
        if (!exArray.isArray()) return list;
        for (JsonNode n : exArray) {
            String name = n.path("exerciseName").asText("").trim();
            if (name.isBlank() || isGenericName(name)) { log.warn("Name abgelehnt: '{}'", name); continue; }
            String desc = n.path("description").asText("");
            list.add(new GeneratedExercise(name,
                    clamp(n.path("sets").asInt(3),        1, 10),
                    clamp(n.path("reps").asInt(10),       1, 60),
                    Math.max(0, n.path("weightKg").asDouble(20.0)),
                    clamp(n.path("restSeconds").asInt(90), 15, 300),
                    clamp(n.path("targetRpe").asInt(7),    1, 10),
                    desc));
        }
        return list;
    }

    private List<GeneratedDay> splitExercisesIntoDays(List<GeneratedExercise> all) {
        int half = Math.max(1, all.size() / 2);
        List<GeneratedDay> days = new ArrayList<>();
        days.add(new GeneratedDay("Tag A", "Oberkörper", new ArrayList<>(all.subList(0, half))));
        if (all.size() > half)
            days.add(new GeneratedDay("Tag B", "Unterkörper", new ArrayList<>(all.subList(half, all.size()))));
        return days;
    }

    private boolean isGenericName(String name) {
        String l = name.toLowerCase();
        return List.of("beine","brust","rücken","arme","core","ganzkörper","übung","exercise","training","workout")
                .stream().anyMatch(t -> l.equals(t) || l.startsWith(t + " "));
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // =========================================================================
    // Fallback-Plan (mit fokusbasierter Tagesreihenfolge)
    // =========================================================================

    private GeneratedPlan buildFallbackPlan(GeneratePlanRequest request) {
        String planName   = request.planName() != null ? request.planName() : "Trainingsplan";
        String level      = resolveRawLevel(request, null);
        int daysPerWeek   = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        int numDayPlans   = calcNumDayPlans(daysPerWeek);

        // Variant: 0, 1 oder 2 — sorgt für sichtbare Variation beim Fallback
        int variant = (int) (System.currentTimeMillis() % 3);

        List<String> dayNames = List.of("Tag A", "Tag B", "Tag C");
        String rotation   = buildRotationHint(daysPerWeek, numDayPlans, dayNames);
        String description = "Fallback-Plan | " + request.userPrompt()
                + (rotation.isBlank() ? "" : " | " + rotation);

        List<GeneratedDay> allDays = switch (level) {
            case "ADVANCED"     -> buildAdvancedDays(variant);
            case "INTERMEDIATE" -> buildIntermediateDays(variant);
            default             -> buildBeginnerDays();
        };

        // Sätze anhand Regeneration anpassen
        int sets = calcFallbackSets(request);
        if (sets != 3) {
            allDays = adjustSets(allDays, sets);
        }

        // ── NEU: Tagesreihenfolge nach Fokus anpassen ──────────────────────
        String focusMuscles = resolveFocusMuscles(request);
        if (!focusMuscles.isBlank()) {
            allDays = reorderFallbackDaysByFocus(allDays, focusMuscles);
        }

        List<GeneratedDay> days = new ArrayList<>(allDays.subList(0, Math.min(numDayPlans, allDays.size())));

        // Mobilitätsblock hinzufügen wenn gewünscht
        if (Boolean.TRUE.equals(request.includeMobilityPlan())) {
            days.add(buildFallbackMobilityDay());
        }

        return new GeneratedPlan(planName, description, days);
    }

    /**
     * Ordnet die Fallback-Tage nach dem gewählten Fokus.
     * Fokus-Tag wird immer Tag A (erste Position + Umbenennung).
     */
    private List<GeneratedDay> reorderFallbackDaysByFocus(List<GeneratedDay> days, String focusMuscles) {
        if (days.size() <= 1) return days;

        FocusType focus = detectFocusType(focusMuscles.toLowerCase());
        if (focus == FocusType.UNKNOWN) return days;

        int focusIndex = -1;
        for (int i = 0; i < days.size(); i++) {
            String dayFocus = (days.get(i).focus() + " " + days.get(i).dayName()).toLowerCase();
            if (matchesFocusType(dayFocus, focus)) { focusIndex = i; break; }
        }
        if (focusIndex < 0) {
            for (int i = 0; i < days.size(); i++) {
                boolean match = days.get(i).exercises().stream()
                        .anyMatch(ex -> exerciseMatchesFocus(ex.exerciseName().toLowerCase(), focus));
                if (match) { focusIndex = i; break; }
            }
        }
        if (focusIndex <= 0) return renameDaysSequentially(days); // schon vorne oder nicht gefunden

        List<GeneratedDay> reordered = new ArrayList<>(days);
        GeneratedDay focusDay = reordered.remove(focusIndex);
        reordered.add(0, focusDay);
        log.info("Fallback-Tage umgeordnet: '{}' → Tag A (Fokus: {})", focusDay.dayName(), focusMuscles);

        return renameDaysSequentially(reordered);
    }

    /**
     * Benennt die Trainingstage nach der Umsortierung sequenziell um:
     * Position 0 → "Tag A – <Inhalt>", Position 1 → "Tag B – <Inhalt>", usw.
     * Der Suffix hinter " – " (z.B. "Beine", "Push", "Pull") bleibt erhalten.
     * Der Mobilitätsblock wird nicht umbenannt.
     */
    private List<GeneratedDay> renameDaysSequentially(List<GeneratedDay> days) {
        String[] labels = {"A", "B", "C", "D"};
        List<GeneratedDay> result = new ArrayList<>();
        int labelIndex = 0;
        for (GeneratedDay day : days) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                result.add(day); // Mobility bleibt unverändert
                continue;
            }
            if (labelIndex >= labels.length) {
                result.add(day);
                continue;
            }
            // Suffix nach " – " extrahieren (z.B. "Beine & Core", "Push", "Pull")
            String suffix = "";
            String name = day.dayName();
            int dashIdx = name.indexOf(" – ");
            if (dashIdx >= 0) {
                suffix = " – " + name.substring(dashIdx + 3);
            }
            String newName = "Tag " + labels[labelIndex] + suffix;
            result.add(new GeneratedDay(newName, day.focus(), day.exercises()));
            labelIndex++;
        }
        return result;
    }

    private int calcFallbackSets(GeneratePlanRequest request) {
        int sleepH = request.sleepHoursPerNight() != null ? request.sleepHoursPerNight() : 7;
        String stress = request.stressLevel() != null ? request.stressLevel().toUpperCase() : "MODERATE";
        if (sleepH <= 5 || "HIGH".equals(stress)) return 2;
        if (sleepH >= 8 && "LOW".equals(stress))  return 4;
        return 3;
    }

    private List<GeneratedDay> adjustSets(List<GeneratedDay> days, int targetSets) {
        return days.stream().map(day -> {
            List<GeneratedExercise> adjusted = day.exercises().stream()
                    .map(e -> new GeneratedExercise(e.exerciseName(), targetSets, e.reps(),
                            e.weightKg(), e.restSeconds(), e.targetRpe(), e.description()))
                    .toList();
            return new GeneratedDay(day.dayName(), day.focus(), new ArrayList<>(adjusted));
        }).toList();
    }

    // ── Mobilitäts-Fallback ───────────────────────────────────────────────────

    private GeneratedDay buildFallbackMobilityDay() {
        return new GeneratedDay("Mobilitätsblock", "Mobility & Dehnung", List.of(
                new GeneratedExercise("Hip Flexor Stretch", 2, 45, 0.0, 30, 3,
                        "Knie auf dem Boden, hinteres Knie auf Matte. Hüfte nach vorne schieben bis du eine Dehnung in der Leiste spürst. 30–45 Sekunden halten, dann Seite wechseln."),
                new GeneratedExercise("Thoracic Rotation", 2, 10, 0.0, 30, 3,
                        "Seitlich auf dem Boden liegen, Knie 90° gebeugt. Oberen Arm langsam nach hinten rotieren, Schulterblatt folgt. 10 langsame Wiederholungen pro Seite."),
                new GeneratedExercise("World's Greatest Stretch", 2, 8, 0.0, 30, 3,
                        "Ausfallschritt nach vorne, vordere Hand neben Fuß auf den Boden. Hinteren Arm nach oben drehen und dem Blick folgen. 8 Wiederholungen pro Seite."),
                new GeneratedExercise("Cat-Cow", 2, 15, 0.0, 30, 3,
                        "Im Vierfüßlerstand abwechselnd Rücken nach oben wölben (Katze) und nach unten hängen lassen (Kuh). Bewegung mit dem Atem synchronisieren."),
                new GeneratedExercise("90/90 Hip Stretch", 2, 45, 0.0, 30, 3,
                        "Beide Beine je in einem 90°-Winkel auf dem Boden positionieren. Aufrecht sitzen und 30–45 Sekunden halten. Dann Beinstellung wechseln."),
                new GeneratedExercise("Schulterkreisen mit Band", 2, 15, 0.0, 30, 3,
                        "Band schulterbreit vor dem Körper halten. Arme gestreckt langsam über den Kopf und hinter den Körper führen. Griffbreite nach Komfort anpassen."),
                new GeneratedExercise("Pigeon Pose", 2, 45, 0.0, 30, 3,
                        "Aus dem Vierfüßlerstand ein Knie nach vorne bringen und seitlich ablegen. Körper langsam nach vorne absenken. 30–45 Sekunden pro Seite halten.")
        ));
    }

    // ── Fallback-Tage: Anfänger (je 5 Übungen) ───────────────────────────────

    private List<GeneratedDay> buildBeginnerDays() {
        var tagA = new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",        3, 10, 30.0, 90, 6, ""),
                new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln", 3, 12, 10.0, 90, 6, ""),
                new GeneratedExercise("Schulterdrücken mit Kurzhanteln",   3, 12,  8.0, 90, 6, ""),
                new GeneratedExercise("Seitheben mit Kurzhanteln",         3, 12,  5.0, 60, 6, ""),
                new GeneratedExercise("Trizeps Pushdown am Kabelzug",      3, 12, 12.0, 60, 6, "")
        ));
        var tagB = new GeneratedDay("Tag B – Pull", "Rücken, Bizeps", List.of(
                new GeneratedExercise("Latzug am Kabelzug",                3, 10, 25.0, 90, 6, ""),
                new GeneratedExercise("Rudern am Kabelzug",                3, 12, 20.0, 90, 6, ""),
                new GeneratedExercise("Bizeps-Curl mit Kurzhanteln",       3, 12,  6.0, 60, 6, ""),
                new GeneratedExercise("Kniebeuge mit Körpergewicht",        3, 12,  0.0, 90, 6, ""),
                new GeneratedExercise("Rumänisches Kreuzheben",             3, 12, 20.0, 90, 6, "")
        ));
        var tagC = new GeneratedDay("Tag C – Beine", "Quadrizeps, Hamstrings, Core", List.of(
                new GeneratedExercise("Ausfallschritte mit Körpergewicht",  3, 10,  0.0, 90, 6, ""),
                new GeneratedExercise("Goblet Squat mit Kurzhantel",        3, 12, 10.0, 90, 6, ""),
                new GeneratedExercise("Beinbeuger an der Maschine",         3, 12, 25.0, 60, 6, ""),
                new GeneratedExercise("Plank",                              3, 30,  0.0, 60, 6, ""),
                new GeneratedExercise("Crunches",                           3, 15,  0.0, 60, 6, "")
        ));
        return new ArrayList<>(List.of(tagA, tagB, tagC));
    }

    // ── Fallback-Tage: Fortgeschritten (je 6 Übungen, 3 Varianten) ──────────

    private List<GeneratedDay> buildIntermediateDays(int variant) {
        // ── Bein-Tag Varianten (explosive Übung immer ZUERST) ─────────────────
        GeneratedDay legs = switch (variant) {
            case 1 -> new GeneratedDay("Tag C – Beine", "Hamstring-Fokus, explosiv", List.of(
                    new GeneratedExercise("Broad Jumps",                        3,  6,  0.0,  90, 8, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",             4,  8, 65.0, 120, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",         3, 10, 40.0,  90, 7, ""),
                    new GeneratedExercise("Goblet Squat mit Kurzhantel",        3, 10, 24.0,  90, 7, ""),
                    new GeneratedExercise("Ausfallschritte mit Kurzhanteln",    3, 12, 18.0,  90, 6, ""),
                    new GeneratedExercise("Wadenheben an der Maschine",         3, 15, 40.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag C – Beine", "Kraft-Ausdauer, explosiv", List.of(
                    new GeneratedExercise("Kettlebell Swing",                   4,  8,  24.0, 90, 8, ""),
                    new GeneratedExercise("Beinpresse",                         4, 12,  80.0, 90, 7, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",        3,  8,  20.0, 90, 7, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel",           3, 10,  60.0, 90, 7, ""),
                    new GeneratedExercise("Beinbeuger liegend",                 3, 12,  35.0, 60, 6, ""),
                    new GeneratedExercise("Einbeiniges Wadenheben",             3, 15,   0.0, 60, 6, "")
            ));
            default -> new GeneratedDay("Tag C – Beine", "Quad-Fokus, explosiv", List.of(
                    new GeneratedExercise("Jump Squats",                        3,  8,  0.0,  90, 8, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel",           4,  8, 70.0, 120, 7, ""),
                    new GeneratedExercise("Beinpresse",                         3, 10, 80.0,  90, 7, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",             3, 10, 60.0,  90, 7, ""),
                    new GeneratedExercise("Ausfallschritte mit Kurzhanteln",    3, 12, 16.0,  90, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",         3, 12, 35.0,  60, 6, "")
            ));
        };

        // ── Push-Tag Varianten ────────────────────────────────────────────────
        GeneratedDay push = switch (variant) {
            case 1 -> new GeneratedDay("Tag A – Push", "Schulter-Fokus, Hypertrophie", List.of(
                    new GeneratedExercise("Schulterdrücken mit Langhantel",      4, 10, 35.0,  90, 7, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",   3, 10, 22.0,  90, 7, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12,  8.0,  60, 7, ""),
                    new GeneratedExercise("Vorgebeugtes Seitheben",              3, 12,  6.0,  60, 6, ""),
                    new GeneratedExercise("Trizeps Pushdown am Kabelzug",        3, 12, 20.0,  60, 6, ""),
                    new GeneratedExercise("Overhead Trizeps Extension KH",      3, 12, 12.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag A – Push", "Brust-Fokus, Kraft-Ausdauer", List.of(
                    new GeneratedExercise("Bankdrücken mit Kurzhanteln",         4, 10, 28.0,  90, 7, ""),
                    new GeneratedExercise("Kabelzug-Fliegenschlagen",            3, 15, 15.0,  60, 7, ""),
                    new GeneratedExercise("Dips",                                3, 12,  0.0,  90, 7, ""),
                    new GeneratedExercise("Frontheben mit Kurzhanteln",          3, 12,  6.0,  60, 6, ""),
                    new GeneratedExercise("Seitheben am Kabelzug",               3, 15, 10.0,  60, 6, ""),
                    new GeneratedExercise("Schädelbrechter mit Kurzhanteln",     3, 12, 10.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps", List.of(
                    new GeneratedExercise("Bankdrücken mit Langhantel",          4,  8, 60.0, 120, 7, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",   3, 10, 22.0,  90, 7, ""),
                    new GeneratedExercise("Schulterdrücken mit Langhantel",      3, 10, 30.0,  90, 7, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln",           3, 12,  8.0,  60, 7, ""),
                    new GeneratedExercise("Trizeps Dips",                        3, 10,  0.0,  90, 7, ""),
                    new GeneratedExercise("Vorgebeugtes Seitheben",              3, 12,  6.0,  60, 6, "")
            ));
        };

        // ── Pull-Tag Varianten ────────────────────────────────────────────────
        GeneratedDay pull = switch (variant) {
            case 1 -> new GeneratedDay("Tag B – Pull", "Rücken-Breite, Hypertrophie", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         4,  8, 10.0, 120, 7, ""),
                    new GeneratedExercise("Latzug eng Griff",                    3, 10, 50.0,  90, 7, ""),
                    new GeneratedExercise("Seilzug-Rudern sitzend",              3, 12, 45.0,  90, 7, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",              3, 15, 20.0,  60, 6, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12, 14.0,  60, 6, ""),
                    new GeneratedExercise("Konzentrations Curls",                3, 12, 10.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag B – Pull", "Rücken-Dicke, Kraft", List.of(
                    new GeneratedExercise("Langhantelrudern",                    5,  6, 55.0, 150, 8, ""),
                    new GeneratedExercise("T-Bar Rudern",                        4,  8, 40.0, 120, 7, ""),
                    new GeneratedExercise("Klimmzüge",                           3,  8,  0.0, 120, 7, ""),
                    new GeneratedExercise("Einarmiges Kurzhantelrudern",         3, 10, 24.0,  90, 7, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",          3, 10, 22.0,  60, 7, ""),
                    new GeneratedExercise("Reverse Curls",                       3, 12, 10.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag B – Pull", "Rücken, Bizeps", List.of(
                    new GeneratedExercise("Klimmzüge",                           4,  6,  0.0, 120, 7, ""),
                    new GeneratedExercise("Langhantelrudern",                    4,  8, 50.0, 120, 7, ""),
                    new GeneratedExercise("Latzug am Kabelzug",                  3, 10, 45.0,  90, 7, ""),
                    new GeneratedExercise("Einarmiges Kurzhantelrudern",         3, 10, 20.0,  90, 7, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",          3, 10, 20.0,  60, 7, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12, 12.0,  60, 6, "")
            ));
        };

        return new ArrayList<>(List.of(push, pull, legs));
    }

    // ── Fallback-Tage: Experte (je 7 Übungen, 3 Varianten) ───────────────────

    private List<GeneratedDay> buildAdvancedDays(int variant) {
        // ── Bein-Tag Varianten (explosive Übung ZUERST, PFLICHT) ──────────────
        GeneratedDay legs = switch (variant) {
            case 1 -> new GeneratedDay("Tag C – Beine", "Hamstring & Kraft, explosiv", List.of(
                    new GeneratedExercise("Depth Jumps",                         4,  5,  0.0, 120, 9, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              5,  5, 95.0, 180, 8, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",          4,  8, 50.0, 120, 8, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",         3,  8, 32.0, 120, 7, ""),
                    new GeneratedExercise("Beinpresse enge Fußstellung",         3, 10, 110.0, 90, 7, ""),
                    new GeneratedExercise("Nordische Hamstring Curls",           3,  6,  0.0, 120, 9, ""),
                    new GeneratedExercise("Einbeiniges Wadenheben",              4, 12,  0.0,  60, 7, "")
            ));
            case 2 -> new GeneratedDay("Tag C – Beine", "Athletik & Power", List.of(
                    new GeneratedExercise("Power Clean",                         4,  4, 60.0, 150, 9, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel",            4,  6, 95.0, 150, 8, ""),
                    new GeneratedExercise("Beinpresse",                          3,  8, 130.0,120, 7, ""),
                    new GeneratedExercise("Ausfallschritte laufend",             3, 10, 20.0,  90, 7, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              3,  8, 80.0, 120, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",          3, 10, 45.0,  90, 7, ""),
                    new GeneratedExercise("Wadenheben stehend",                  4, 15, 70.0,  60, 7, "")
            ));
            default -> new GeneratedDay("Tag C – Beine", "Quad-Fokus & Kraft, explosiv", List.of(
                    new GeneratedExercise("Box Jumps",                           4,  5,  0.0, 120, 9, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel",            5,  5, 100.0,180, 8, ""),
                    new GeneratedExercise("Beinpresse",                          4,  8, 120.0,120, 7, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              4,  6,  90.0,150, 8, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",         3,  8,  30.0,120, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",          3, 10,  45.0, 90, 7, ""),
                    new GeneratedExercise("Wadenheben an der Maschine",          4, 15,  60.0, 60, 7, "")
            ));
        };

        // ── Push-Tag Varianten ────────────────────────────────────────────────
        GeneratedDay push = switch (variant) {
            case 1 -> new GeneratedDay("Tag A – Push", "Schulter-Fokus, Hypertrophie", List.of(
                    new GeneratedExercise("Schulterdrücken mit Kurzhanteln",     5,  8, 32.0, 150, 8, ""),
                    new GeneratedExercise("Bankdrücken mit Langhantel",          4,  8, 80.0, 120, 7, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12, 12.0,  60, 7, ""),
                    new GeneratedExercise("Vorgebeugtes Seitheben",              4, 12,  8.0,  60, 7, ""),
                    new GeneratedExercise("Arnold Press",                        3, 10, 22.0,  90, 7, ""),
                    new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",      3, 10, 20.0, 120, 7, ""),
                    new GeneratedExercise("Schädelbrechter mit Langhantel",      3, 10, 25.0,  90, 7, "")
            ));
            case 2 -> new GeneratedDay("Tag A – Push", "Brust-Fokus, Kraft-Ausdauer", List.of(
                    new GeneratedExercise("Schrägbankdrücken mit Langhantel",    4,  8, 70.0, 120, 8, ""),
                    new GeneratedExercise("Bankdrücken mit Kurzhanteln",         4, 10, 36.0,  90, 7, ""),
                    new GeneratedExercise("Kabelzug-Fliegenschlagen",            3, 15, 18.0,  60, 7, ""),
                    new GeneratedExercise("Dips",                                4, 12,  0.0,  90, 7, ""),
                    new GeneratedExercise("Frontdrücken stehend",                3,  8, 50.0, 120, 8, ""),
                    new GeneratedExercise("Seitheben am Kabelzug",               3, 15, 12.0,  60, 6, ""),
                    new GeneratedExercise("Overhead Trizeps Extension",          3, 12, 20.0,  90, 7, "")
            ));
            default -> new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps – Kraft", List.of(
                    new GeneratedExercise("Bankdrücken mit Langhantel",          5,  5,  90.0, 180, 8, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Langhantel",    4,  6,  75.0, 150, 8, ""),
                    new GeneratedExercise("Kurzhantel-Fliegenschlagen",          3, 10,  24.0,  90, 7, ""),
                    new GeneratedExercise("Schulterdrücken mit Langhantel",      4,  6,  60.0, 150, 8, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12,  12.0,  60, 7, ""),
                    new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",      3, 10,  20.0, 120, 7, ""),
                    new GeneratedExercise("Schädelbrechter mit Langhantel",      3, 10,  25.0,  90, 7, "")
            ));
        };

        // ── Pull-Tag Varianten ────────────────────────────────────────────────
        GeneratedDay pull = switch (variant) {
            case 1 -> new GeneratedDay("Tag B – Pull", "Rücken-Breite, Hypertrophie", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         5,  6, 20.0, 150, 8, ""),
                    new GeneratedExercise("Latzug weit Griff",                   4, 10, 60.0,  90, 7, ""),
                    new GeneratedExercise("Seilzug-Rudern sitzend",              4, 10, 55.0,  90, 7, ""),
                    new GeneratedExercise("Einarmiges KH-Rudern",                3,  8, 36.0,  90, 7, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",              3, 15, 22.0,  60, 6, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",        4, 10, 18.0,  60, 7, ""),
                    new GeneratedExercise("Konzentrations Curls",                3, 12, 14.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag B – Pull", "Rücken-Dicke, Athletik", List.of(
                    new GeneratedExercise("Kreuzheben",                          5,  3, 130.0, 180, 9, ""),
                    new GeneratedExercise("T-Bar Rudern",                        4,  8,  50.0, 120, 8, ""),
                    new GeneratedExercise("Klimmzüge",                           4,  8,   0.0, 120, 8, ""),
                    new GeneratedExercise("Einarmiges KH-Rudern",                3,  8,  34.0,  90, 7, ""),
                    new GeneratedExercise("Kabelzug-Rudern weit",                3, 12,  40.0,  90, 7, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",          4,  8,  32.0,  90, 7, ""),
                    new GeneratedExercise("Reverse Curls",                       3, 12,  12.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag B – Pull", "Rücken, Bizeps – Kraft", List.of(
                    new GeneratedExercise("Kreuzheben",                          5,  5, 120.0, 180, 9, ""),
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         4,  6,  15.0, 150, 8, ""),
                    new GeneratedExercise("Langhantelrudern",                    4,  6,  80.0, 150, 8, ""),
                    new GeneratedExercise("Einarmiges Kurzhantelrudern",         3,  8,  32.0,  90, 7, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",              3, 15,  20.0,  60, 6, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",          4,  8,  30.0,  90, 7, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12,  18.0,  60, 6, "")
            ));
        };

        return new ArrayList<>(List.of(push, pull, legs));
    }

    // =========================================================================
    // HTTP-Calls
    // =========================================================================

    private String callOllamaText(String prompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";
        Map<String, Object> body = Map.of("model", aiConfig.getOllama().getModel(),
                "prompt", prompt, "stream", false,
                "options", Map.of("temperature", 0.7, "num_predict", 500));
        try {
            return webClient.post().uri(url).header("Content-Type", "application/json").bodyValue(body)
                    .retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), r -> r.bodyToMono(String.class)
                            .doOnNext(e -> log.warn("Ollama Text HTTP-Fehler: {}", e)).flatMap(e -> Mono.empty()))
                    .bodyToMono(Map.class)
                    .map(m -> (m != null && m.get("response") != null) ? (String) m.get("response") : null)
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume(e -> { log.warn("Ollama Text: {}", e.getMessage()); return Mono.empty(); })
                    .block();
        } catch (Exception e) { log.error("Ollama Text Fehler: {}", e.getMessage()); return null; }
    }

    private String callOllamaJson(String userPrompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";
        String fullPrompt = buildPlanSystemPrompt() + "\n\n" + userPrompt;
        Map<String, Object> body = Map.of("model", aiConfig.getOllama().getModel(),
                "prompt", fullPrompt, "stream", false, "format", "json",
                "options", Map.of("temperature", 0.15, "num_predict", 4000));
        try {
            return webClient.post().uri(url).header("Content-Type", "application/json").bodyValue(body)
                    .retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), r -> r.bodyToMono(String.class)
                            .doOnNext(e -> log.warn("Ollama JSON HTTP-Fehler: {}", e)).flatMap(e -> Mono.empty()))
                    .bodyToMono(Map.class)
                    .map(m -> (m != null && m.get("response") != null) ? (String) m.get("response") : null)
                    .timeout(Duration.ofSeconds(120))
                    .onErrorResume(e -> { log.warn("Ollama JSON: {}", e.getMessage()); return Mono.empty(); })
                    .block();
        } catch (Exception e) { log.error("Ollama JSON Fehler: {}", e.getMessage()); return null; }
    }

    @SuppressWarnings("unchecked")
    private String callOpenAiText(String userPrompt, String systemPrompt) {
        String url = aiConfig.getOpenai().getBaseUrl() + "/chat/completions";
        Map<String, Object> body = Map.of("model", aiConfig.getOpenai().getModel(),
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.3, "max_tokens", 4000);
        try {
            return webClient.post().uri(url).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getOpenai().getApiKey())
                    .bodyValue(body).retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), r -> r.bodyToMono(String.class)
                            .doOnNext(e -> log.warn("OpenAI HTTP-Fehler: {}", e)).flatMap(e -> Mono.empty()))
                    .bodyToMono(Map.class)
                    .map(m -> {
                        if (m == null) return null;
                        var choices = (List<Map<String, Object>>) m.get("choices");
                        if (choices == null || choices.isEmpty()) return null;
                        var msg = (Map<String, Object>) choices.get(0).get("message");
                        return msg != null ? (String) msg.get("content") : null;
                    })
                    .timeout(Duration.ofSeconds(60))
                    .onErrorResume(e -> { log.warn("OpenAI: {}", e.getMessage()); return Mono.empty(); })
                    .block();
        } catch (Exception e) { log.error("OpenAI Fehler: {}", e.getMessage()); return null; }
    }

    // =========================================================================
    // Hilfsmethoden
    // =========================================================================

    private String resolveRawLevel(GeneratePlanRequest request, AppUser user) {
        if (request != null && request.experienceLevel() != null && !request.experienceLevel().isBlank())
            return request.experienceLevel().toUpperCase();
        if (user != null && user.getFitnessLevel() != null)
            return user.getFitnessLevel().name().toUpperCase();
        return "BEGINNER";
    }

    private String translateLevel(String level) {
        if (level == null) return "Anfänger (BEGINNER)";
        return switch (level.toUpperCase()) {
            case "INTERMEDIATE" -> "Fortgeschritten (INTERMEDIATE)";
            case "ADVANCED"     -> "Experte (ADVANCED)";
            default             -> "Anfänger (BEGINNER)";
        };
    }

    private String translateGoal(String goal) {
        if (goal == null) return "Nicht angegeben";
        return switch (goal.toUpperCase()) {
            case "MUSCLE_GAIN"     -> "Muskelaufbau";
            case "STRENGTH"        -> "Kraftaufbau";
            case "FAT_LOSS"        -> "Fettabbau";
            case "ENDURANCE"       -> "Ausdauer";
            case "GENERAL_FITNESS" -> "Allgemeine Fitness";
            default -> goal;
        };
    }

    private String translateStress(String stress) {
        if (stress == null) return "Nicht angegeben";
        return switch (stress.toUpperCase()) {
            case "LOW"      -> "Niedrig";
            case "MODERATE" -> "Moderat";
            case "HIGH"     -> "Hoch";
            default         -> stress;
        };
    }

    private String buildExplanationPrompt(AppUser user, int sessionRpe, String userNote,
                                          List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder("Fitness-Coach Feedback:\n");
        if (user.getFitnessLevel() != null) sb.append("Level=").append(user.getFitnessLevel()).append("\n");
        sb.append("Session-RPE: ").append(sessionRpe).append("/10\n");
        if (userNote != null && !userNote.isBlank()) sb.append("Notiz: ").append(userNote).append("\n");
        sb.append("\nAnpassungen:\n");
        for (var adj : adjustments)
            sb.append(String.format("- %s: %.1f→%.1f kg, %d→%d Wdh\n",
                    adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                    adj.previousReps(), adj.newReps()));
        sb.append("\nKurze motivierende Erklärung auf Deutsch, max 150 Wörter.");
        return sb.toString();
    }

    private String buildFallbackExplanation(int sessionRpe, List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();
        if      (sessionRpe <= 4) sb.append("Sehr leichte Einheit — wir erhöhen die Last.");
        else if (sessionRpe <= 6) sb.append("Optimale Belastung — weiter so!");
        else if (sessionRpe <= 8) sb.append("Guter Zielbereich — minimale Anpassungen.");
        else                       sb.append("Sehr anstrengend — wir reduzieren etwas.");
        sb.append("\n\nAnpassungen:\n");
        for (var adj : adjustments)
            if (adj.previousWeight() != adj.newWeight() || adj.previousReps() != adj.newReps())
                sb.append(String.format("- %s: %.1f → %.1f kg, %d → %d Wdh\n",
                        adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                        adj.previousReps(), adj.newReps()));
        return sb.toString();
    }
}