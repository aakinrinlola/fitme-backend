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

/**
 * KI-Service — zwei Aufgaben:
 *
 * 1) generateExplanation()  — erklaert RPE-Anpassungen nach dem Training
 * 2) generateTrainingPlan() — generiert einen strukturierten mehrtaegigen Plan
 *
 * Tag-Anzahl-Logik (exakt nach Vorgabe):
 *   1-2 Tage/Woche  -> 1 Trainingstag-Plan  (Tag A)
 *   3-5 Tage/Woche  -> 2 verschiedene Tage  (Tag A + Tag B), rotierend genutzt
 *   6+  Tage/Woche  -> 3 verschiedene Tage  (Tag A + Tag B + Tag C), rotierend genutzt
 *
 * Uebungen pro Tag (exakt nach Vorgabe):
 *   BEGINNER     -> genau 5 Uebungen
 *   INTERMEDIATE -> genau 6 Uebungen
 *   ADVANCED     -> genau 7 Uebungen
 */
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
                default -> { log.warn("Unbekannter AI-Provider: {}", aiConfig.getProvider()); yield null; }
            };
            if (response != null && !response.isBlank()) return response;
        } catch (Exception e) {
            log.error("Fehler bei KI-Erklaerung: {}", e.getMessage());
        }
        return buildFallbackExplanation(sessionRpe, adjustments);
    }

    // =========================================================================
    // METHODE 2: Mehrtaegiger Trainingsplan
    // =========================================================================

    public GeneratedPlan generateTrainingPlan(AppUser user, GeneratePlanRequest request) {
        String userPrompt = buildPlanUserPrompt(user, request);
        try {
            String rawJson = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllamaJson(userPrompt);
                case "openai" -> callOpenAiText(userPrompt, buildPlanSystemPrompt());
                default -> { log.warn("Unbekannter AI-Provider: {}", aiConfig.getProvider()); yield null; }
            };
            if (rawJson != null && !rawJson.isBlank()) {
                GeneratedPlan parsed = parseGeneratedPlan(rawJson);
                if (parsed != null && !parsed.days().isEmpty()) {
                    long total = parsed.days().stream().mapToLong(d -> d.exercises().size()).sum();
                    log.info("KI-Plan: '{}' mit {} Tagen, {} Uebungen", parsed.planName(), parsed.days().size(), total);
                    return parsed;
                }
                log.warn("KI-Antwort nicht verwertbar -> Fallback");
            } else {
                log.warn("KI lieferte leere Antwort -> Fallback");
            }
        } catch (Exception e) {
            log.error("Fehler bei KI-Plan-Generierung: {}", e.getMessage());
        }
        return buildFallbackPlan(request);
    }

    // =========================================================================
    // Datenstrukturen
    // =========================================================================

    public record GeneratedDay(String dayName, String focus, List<GeneratedExercise> exercises) {}
    public record GeneratedPlan(String planName, String description, List<GeneratedDay> days) {}
    public record GeneratedExercise(String exerciseName, int sets, int reps,
                                    double weightKg, int restSeconds, int targetRpe) {}

    // =========================================================================
    // Tag-Anzahl-Logik (zentrale Hilfsmethode)
    // =========================================================================

    /**
     * Berechnet die Anzahl der zu erstellenden Trainingstag-Plaene.
     *   1-2 Tage -> 1 Plan
     *   3-5 Tage -> 2 Plaene
     *   6+  Tage -> 3 Plaene
     */
    private int calcNumDayPlans(int daysPerWeek) {
        if (daysPerWeek <= 2) return 1;
        if (daysPerWeek <= 5) return 2;
        return 3;
    }

    /**
     * Berechnet die exakte Anzahl der Uebungen pro Tag.
     *   BEGINNER     -> 5
     *   INTERMEDIATE -> 6
     *   ADVANCED     -> 7
     */
    private int calcExercisesPerDay(String level) {
        if (level == null) return 5;
        return switch (level.toUpperCase()) {
            case "INTERMEDIATE" -> 6;
            case "ADVANCED"     -> 7;
            default             -> 5; // BEGINNER
        };
    }

    /**
     * Baut die Rotations-Beschreibung fuer die Plan-Beschreibung.
     * Beispiel: "Rotation: Tag A, Tag B, Tag A, Tag B, ..."
     */
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

            REGEL 1 — ANZAHL DER TRAININGSTAGE:
              1–2 Trainingstage/Woche  → genau 1 Tag  (Tag A)
              3–5 Trainingstage/Woche  → genau 2 Tage (Tag A, Tag B)
              6+  Trainingstage/Woche  → genau 3 Tage (Tag A, Tag B, Tag C)

            REGEL 2 — ÜBUNGSANZAHL PRO TAG:
              BEGINNER     → genau 5 Übungen pro Tag
              INTERMEDIATE → genau 6 Übungen pro Tag
              ADVANCED     → genau 7 Übungen pro Tag

            REGEL 3 — DUPLIKAT-VERBOT (KRITISCH):
              ✗ VERBOTEN: Dieselbe Übung in mehreren Trainingstagen.
              ✗ VERBOTEN: Fast identische Trainingstage.
              ✓ PFLICHT:  Jeder Tag hat klar andere Übungen als alle anderen Tage.

            REGEL 4 — FOKUS-MUSKELGRUPPEN (wenn angegeben):
              Die angegebenen Fokus-Muskelgruppen bestimmen den CHARAKTER der Trainingstage.
              Sie dürfen nicht nur beiläufig vorkommen, sondern müssen klar priorisiert werden.

              FOKUS BEINE (bei 3 Trainingstagen):
                → 2 der 3 Tage müssen beinintensiv sein (Hauptübungen: Quadrizeps, Beinbeuger, Gesäß).
                → Der 3. Tag darf andere Muskelgruppen ergänzen (z.B. Oberkörper).
                → Die beiden Bein-Tage müssen sich trotzdem voneinander unterscheiden
                  (z.B. Tag A: Kniebeuge-fokussiert, Tag B: Kreuzheben/Hamstring-fokussiert).

              FOKUS SCHULTER (bei 3 Trainingstagen):
                → 2 der 3 Tage müssen schulter-/oberkörperintensiv sein
                  (Hauptübungen: Schulter, oberer Rücken, Brust oder ergänzende Oberkörperübungen).
                → Der 3. Tag darf andere Muskelgruppen ergänzen (z.B. Beine, Core).
                → Die beiden Schulter-Tage müssen sich voneinander unterscheiden.

              ALLGEMEINE FOKUS-LOGIK (für jeden Fokus):
                → Fokus-Muskelgruppen erscheinen häufiger und früher im Tag.
                → Mindestens 60% der Übungen eines Tages beziehen sich auf die Fokus-Muskelgruppe.
                → Die Fokus-Priorisierung darf nicht dazu führen, dass alle Tage fast identisch werden.
                → Auch bei Fokus gilt: sinnvolle Variation zwischen den Tagen.

            REGEL 5 — REIHENFOLGE INNERHALB EINES TAGES:
              ✓ Große Muskelgruppen zuerst (Brust/Rücken/Beine), dann kleine (Schulter, Arme).
              ✓ Muskelgruppen blockweise trainieren — alle Übungen einer Gruppe zusammen.
              ✗ VERBOTEN: Schulter oder Arme vor Brust, Rücken oder Beinen platzieren.
              Korrekte Reihenfolge (Beispiele):
                Push-Tag:  Brust → Brust-Variation → Schulter → Schulter-Variation → Trizeps
                Pull-Tag:  Rücken → Rücken-Variation → Rücken → Bizeps → Bizeps-Variation
                Bein-Tag:  Quadrizeps → Quadrizeps-Variation → Hamstrings → Gesäß → Waden

            REGEL 6 — ÜBUNGSNAMEN:
              ✗ VERBOTEN: "Beine", "Brust", "Rücken", "Arme", "Core", "Ganzkörper"
              ✓ PFLICHT:  Nur konkrete Übungsnamen auf Deutsch:
                "Bankdrücken mit Langhantel", "Schrägbankdrücken mit Kurzhanteln",
                "Schulterdrücken mit Kurzhanteln", "Seitheben mit Kurzhanteln",
                "Klimmzüge", "Latzug am Kabelzug", "Langhantelrudern",
                "Kniebeuge mit Langhantel", "Beinpresse", "Rumänisches Kreuzheben",
                "Bizeps-Curl mit Kurzhanteln", "Trizeps Pushdown am Kabelzug"

            REGEL 7 — DOSIS:
              sets: 2–5, reps: 5–15, restSeconds: 45–180, targetRpe: 6–9
              weightKg: realistisch; Körpergewichtsübungen = 0, alle anderen > 0

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
                    {"exerciseName": "string", "sets": 3, "reps": 10, "weightKg": 60.0, "restSeconds": 90, "targetRpe": 7}
                  ]
                },
                {
                  "dayName": "Tag B",
                  "focus": "Rücken, Bizeps",
                  "exercises": [...]
                }
              ]
            }

            Gib NUR das JSON zurück. Kein Text davor oder danach.
            """;
    }
    // =========================================================================
    // User-Prompt (mit exakten Vorgaben aus der Anforderung)
    // =========================================================================

    private String buildPlanUserPrompt(AppUser user, GeneratePlanRequest request) {
        // ── Werte aufbereiten ────────────────────────────────────────────────
        String planName     = request.planName();
        String userPrompt   = request.userPrompt();
        String fitnessGoal  = (request.fitnessGoal() != null && !request.fitnessGoal().isBlank())
                ? translateGoal(request.fitnessGoal()) : "Nicht angegeben";
        int    daysPerWeek  = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        String focusMuscles = (request.focusMuscles() != null && !request.focusMuscles().isBlank())
                ? request.focusMuscles().trim() : "";
        String rawLevel     = resolveRawLevel(request, user);
        String levelLabel   = translateLevel(rawLevel);
        int    numDayPlans  = calcNumDayPlans(daysPerWeek);
        int    exPerDay     = calcExercisesPerDay(rawLevel);

        // ── Tag-Namen ────────────────────────────────────────────────────────
        String dayNames = switch (numDayPlans) {
            case 1  -> "Tag A";
            case 2  -> "Tag A und Tag B";
            default -> "Tag A, Tag B und Tag C";
        };

        // ── Muskelgruppen-Aufteilung (Standard, ohne Fokus) ──────────────────
        String standardDistribution = switch (numDayPlans) {
            case 1  -> "Tag A: Ausgewogener Ganzkörpertag — Brust → Rücken → Beine → Schulter → Arme.";
            case 2  -> """
                    Tag A (Push):       Brust → Schulter → Trizeps
                    Tag B (Pull+Legs):  Rücken → Bizeps → Beine
                    PFLICHT: Keine Übung darf in beiden Tagen vorkommen.""";
            default -> """
                    Tag A (Push):  Brust → Schulter → Trizeps
                    Tag B (Pull):  Rücken → Bizeps
                    Tag C (Legs):  Quadrizeps → Hamstrings → Gesäß → Waden
                    PFLICHT: Keine Übung darf in mehr als einem Tag vorkommen.""";
        };

        // ── Fokus-spezifische Muskelgruppen-Aufteilung ───────────────────────
        String focusSection = "";
        if (!focusMuscles.isBlank()) {
            String focusLower = focusMuscles.toLowerCase();
            boolean isBeinFokus    = focusLower.contains("bein") || focusLower.contains("quad")
                    || focusLower.contains("hamstring") || focusLower.contains("gesäß");
            boolean isSchulterFokus= focusLower.contains("schulter") || focusLower.contains("deltoid");
            boolean isBrustFokus   = focusLower.contains("brust") || focusLower.contains("pecto");
            boolean isRueckenFokus = focusLower.contains("rücken") || focusLower.contains("latiss");

            if (numDayPlans == 3) {
                if (isBeinFokus) {
                    focusSection = """

                    FOKUS-REGEL BEINE (3 Trainingstage):
                    → 2 der 3 Tage müssen beinintensiv sein — Beinübungen bestimmen den Charakter.
                    → Empfohlene Aufteilung:
                        Tag A (Beine – Quadrizeps):  Kniebeuge, Beinpresse, Ausfallschritte, Wadenheben, [1–2 Oberkörper optional]
                        Tag B (Beine – Hamstrings):  Rumänisches Kreuzheben, Beinbeuger, Kreuzheben-Variante, Gesäß, [1–2 Oberkörper optional]
                        Tag C (Oberkörper):          Brust, Rücken, Schulter, Arme — keine Beinübungen.
                    → Die beiden Bein-Tage müssen sich unterscheiden:
                        Tag A betont Quadrizeps (Kniebeuge-Muster).
                        Tag B betont Hamstrings/Gesäß (Kreuzheben-Muster).
                    → Keine Beinübung darf in beiden Bein-Tagen (Tag A und Tag B) identisch vorkommen.""";
                } else if (isSchulterFokus) {
                    focusSection = """

                    FOKUS-REGEL SCHULTER (3 Trainingstage):
                    → 2 der 3 Tage müssen schulter-/oberkörperintensiv sein.
                    → Empfohlene Aufteilung:
                        Tag A (Schulter + Brust):    Schulterdrücken, Seitheben, Frontdrücken, Bankdrücken, Fliegende, [Trizeps]
                        Tag B (Schulter + Rücken):   Seitheben, Schulterdrücken, Latzug, Rudern, Face Pulls, [Bizeps]
                        Tag C (Beine):               Kniebeuge, Beinpresse, Rumänisches Kreuzheben, Ausfallschritte, Wadenheben.
                    → Die beiden Schulter-Tage müssen sich unterscheiden:
                        Tag A betont drückende Schulterübungen (mit Brustbezug).
                        Tag B betont ziehende Schulterübungen (mit Rückenbezug).
                    → Keine Schulterübung darf in beiden Schulter-Tagen identisch vorkommen.""";
                } else if (isBrustFokus) {
                    focusSection = """

                    FOKUS-REGEL BRUST (3 Trainingstage):
                    → 2 der 3 Tage müssen brustintensiv sein.
                    → Empfohlene Aufteilung:
                        Tag A (Brust + Schulter):  Bankdrücken, Schrägdrücken, Fliegende, Schulterdrücken, Seitheben
                        Tag B (Brust + Trizeps):   Schrägbankdrücken, Flachdrücken mit Kurzhanteln, Trizeps-Übungen
                        Tag C (Rücken + Beine):    Klimmzüge, Rudern, Kniebeuge, Rumänisches Kreuzheben.
                    → Keine Brustübung darf in beiden Brust-Tagen identisch vorkommen.""";
                } else if (isRueckenFokus) {
                    focusSection = """

                    FOKUS-REGEL RÜCKEN (3 Trainingstage):
                    → 2 der 3 Tage müssen rückenintensiv sein.
                    → Empfohlene Aufteilung:
                        Tag A (Rücken – Breite):   Klimmzüge, Latzug am Kabelzug, einarmiges Rudern, Bizeps
                        Tag B (Rücken – Dicke):    Langhantelrudern, T-Bar-Rudern, Kreuzheben, Face Pulls, Bizeps-Variation
                        Tag C (Brust + Beine):     Bankdrücken, Schrägdrücken, Kniebeuge, Rumänisches Kreuzheben.
                    → Keine Rückenübung darf in beiden Rücken-Tagen identisch vorkommen.""";
                } else {
                    // Allgemeiner Fokus bei 3 Tagen
                    focusSection = "\nFOKUS-REGEL ALLGEMEIN: Die Muskelgruppe(n) '"
                            + focusMuscles + "' sollen in mindestens 2 der 3 Trainingstage "
                            + "klar priorisiert werden. Sie bestimmen den Charakter des jeweiligen Tages.";
                }
            } else if (numDayPlans == 2) {
                if (isBeinFokus) {
                    focusSection = """

                    FOKUS-REGEL BEINE (2 Trainingstage):
                    → Beide Tage sollen Beinübungen enthalten, aber unterschiedlich:
                        Tag A: Schwerpunkt Quadrizeps (Kniebeuge, Beinpresse, Ausfallschritte) + Oberkörper ergänzend
                        Tag B: Schwerpunkt Hamstrings/Gesäß (Rumänisches Kreuzheben, Beinbeuger) + Oberkörper ergänzend
                    → Keine Beinübung darf in beiden Tagen identisch sein.""";
                } else if (isSchulterFokus) {
                    focusSection = """

                    FOKUS-REGEL SCHULTER (2 Trainingstage):
                    → Beide Tage sollen Schulterübungen enthalten, aber unterschiedlich:
                        Tag A: Drückende Schulterübungen (Schulterdrücken, Seitheben) + Brust
                        Tag B: Ziehende Schulterübungen (Face Pulls, Seitheben hinten) + Rücken
                    → Keine Schulterübung darf in beiden Tagen identisch sein.""";
                } else {
                    focusSection = "\nFOKUS-REGEL: Die Muskelgruppe(n) '"
                            + focusMuscles + "' sollen in beiden Trainingstagen "
                            + "priorisiert werden, dabei unterschiedliche Uebungen pro Tag.";
                }
            }
        }

        // ── Rotations-Erklärung ──────────────────────────────────────────────
        String rotationLine = "";
        if (numDayPlans < daysPerWeek) {
            rotationLine = switch (numDayPlans) {
                case 2  -> daysPerWeek + " Tage/Woche: Rotation Tag A → Tag B → Tag A → Tag B → ...";
                case 3  -> daysPerWeek + " Tage/Woche: Rotation Tag A → Tag B → Tag C → Tag A → ...";
                default -> "";
            };
        }

        // ── Prompt zusammenbauen ─────────────────────────────────────────────
        return "Erstelle einen strukturierten Trainingsplan anhand der folgenden Angaben:\n\n"
                + "Planname: "                + planName                                             + "\n"
                + "Benutzerwunsch: "          + userPrompt                                           + "\n"
                + "Trainingsziel: "           + fitnessGoal                                          + "\n"
                + "Trainingstage pro Woche: " + daysPerWeek                                          + "\n"
                + "Fokus-Muskelgruppen: "     + (focusMuscles.isBlank() ? "Keine" : focusMuscles)   + "\n"
                + "Erfahrungslevel: "         + levelLabel                                           + "\n"
                + "Alter: "   + (user.getAge()      != null ? user.getAge()      + " Jahre" : "k.A.") + "\n"
                + "Gewicht: " + (user.getWeightKg() != null ? user.getWeightKg() + " kg"    : "k.A.") + "\n"
                + "\n"
                + "══════════════════════════════════════════\n"
                + "PFLICHTREGELN FÜR DIESEN PLAN:\n"
                + "══════════════════════════════════════════\n"
                + "1. Erstelle EXAKT " + numDayPlans + " Trainingstag-Plan(e): " + dayNames + ".\n"
                + "   Weder mehr noch weniger Trainingstage.\n"
                + "\n"
                + "2. Jeder Trainingstag enthält EXAKT " + exPerDay + " Übungen (" + levelLabel + ").\n"
                + "   Niemals weniger, niemals mehr Übungen pro Tag.\n"
                + "\n"
                + "3. STANDARD-MUSKELGRUPPEN-AUFTEILUNG:\n"
                + standardDistribution + "\n"
                + focusSection + "\n"
                + "\n"
                + "4. REIHENFOLGE INNERHALB JEDES TAGES:\n"
                + "   - Erst große Muskelgruppen (Brust/Rücken/Beine), dann kleine (Schulter/Arme).\n"
                + "   - Schulter und Arme NIEMALS vor Brust, Rücken oder Beinen.\n"
                + "   - Übungen derselben Muskelgruppe zusammenhalten (blockweise).\n"
                + "\n"
                + "5. DUPLIKAT-VERBOT:\n"
                + "   - Jede Übung darf im gesamten Plan nur 1x vorkommen.\n"
                + "   - Zwischen den Tagen darf es KEINE gleichen Übungen geben.\n"
                + "   - Die Tage müssen sich inhaltlich klar unterscheiden.\n"
                + (rotationLine.isBlank() ? "" : "\n6. ROTATION: " + rotationLine + "\n")
                + "\n"
                + "Gib ausschließlich das JSON zurück.";
    }
    // =========================================================================
    // Hilfsmethoden Übersetzung
    // =========================================================================

    /** Liefert den rohen Level-String (BEGINNER/INTERMEDIATE/ADVANCED) für interne Logik */
    private String resolveRawLevel(GeneratePlanRequest request, AppUser user) {
        if (request.experienceLevel() != null && !request.experienceLevel().isBlank())
            return request.experienceLevel().toUpperCase();
        // null-safe: user kann im Fallback-Kontext null sein
        if (user != null && user.getFitnessLevel() != null)
            return user.getFitnessLevel().name().toUpperCase();
        return "BEGINNER";
    }

    private String resolveExperienceLevel(GeneratePlanRequest request, AppUser user) {
        return translateLevel(resolveRawLevel(request, user));
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
            default                -> goal;
        };
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
            if (start == -1 || end == -1 || end <= start) {
                log.warn("Kein JSON in KI-Antwort gefunden");
                return null;
            }
            cleaned = cleaned.substring(start, end + 1);

            JsonNode root = objectMapper.readTree(cleaned);
            String planName    = root.path("planName").asText("KI-Trainingsplan");
            String description = root.path("description").asText("");

            // Neues Format: days[]
            JsonNode daysArray = root.path("days");
            if (daysArray.isArray() && daysArray.size() > 0) {
                List<GeneratedDay> days = new ArrayList<>();
                for (JsonNode dayNode : daysArray) {
                    String dayName = dayNode.path("dayName").asText("Tag " + (days.size() + 1));
                    String focus   = dayNode.path("focus").asText("");
                    List<GeneratedExercise> exercises = parseExercises(dayNode.path("exercises"));
                    if (!exercises.isEmpty()) {
                        days.add(new GeneratedDay(dayName, focus, exercises));
                    }
                }
                if (!days.isEmpty()) return new GeneratedPlan(planName, description, days);
            }

            // Fallback: altes Format exercises[]
            JsonNode exArray = root.path("exercises");
            if (exArray.isArray() && exArray.size() > 0) {
                log.warn("KI nutzte altes Format (exercises[]) -> aufteilen");
                List<GeneratedExercise> all = parseExercises(exArray);
                if (!all.isEmpty()) {
                    return new GeneratedPlan(planName, description, splitExercisesIntoDays(all));
                }
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
            if (name.isBlank() || isGenericName(name)) {
                log.warn("Uebungsname abgelehnt: '{}'", name);
                continue;
            }
            list.add(new GeneratedExercise(name,
                    clamp(n.path("sets").asInt(3), 1, 10),
                    clamp(n.path("reps").asInt(10), 1, 50),
                    Math.max(0, n.path("weightKg").asDouble(20.0)),
                    clamp(n.path("restSeconds").asInt(90), 30, 300),
                    clamp(n.path("targetRpe").asInt(7), 1, 10)));
        }
        return list;
    }

    private List<GeneratedDay> splitExercisesIntoDays(List<GeneratedExercise> all) {
        int half = Math.max(1, all.size() / 2);
        List<GeneratedDay> days = new ArrayList<>();
        days.add(new GeneratedDay("Tag A", "Oberkörper", new ArrayList<>(all.subList(0, half))));
        if (all.size() > half) {
            days.add(new GeneratedDay("Tag B", "Unterkörper & Core",
                    new ArrayList<>(all.subList(half, all.size()))));
        }
        return days;
    }

    private boolean isGenericName(String name) {
        String l = name.toLowerCase();
        return List.of("beine", "brust", "rücken", "arme", "core",
                        "ganzkörper", "übung", "exercise", "training", "workout")
                .stream().anyMatch(t -> l.equals(t) || l.startsWith(t + " "));
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // =========================================================================
    // Fallback-Plan (mehrtätig, exakt nach Anzahl-Logik)
    // =========================================================================

    private GeneratedPlan buildFallbackPlan(GeneratePlanRequest request) {
        String planName    = request.planName() != null ? request.planName() : "Trainingsplan";
        // Direkte Level-Aufloesung — kein AppUser verfuegbar im Fallback-Kontext
        String level       = (request.experienceLevel() != null && !request.experienceLevel().isBlank())
                ? request.experienceLevel().toUpperCase() : "BEGINNER";
        int    daysPerWeek = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        int    numDayPlans = calcNumDayPlans(daysPerWeek);

        // Rotationshinweis fuer Beschreibung
        List<String> dayNames = List.of("Tag A", "Tag B", "Tag C");
        String rotation = buildRotationHint(daysPerWeek, numDayPlans,
                dayNames.subList(0, numDayPlans));
        String description = "Fallback-Plan: " + request.userPrompt()
                + (rotation.isBlank() ? "" : " | " + rotation);

        List<GeneratedDay> allDays = switch (level) {
            case "ADVANCED"     -> buildAdvancedDays();
            case "INTERMEDIATE" -> buildIntermediateDays();
            default             -> buildBeginnerDays();
        };

        // Nur so viele Tage wie numDayPlans
        List<GeneratedDay> days = allDays.subList(0, Math.min(numDayPlans, allDays.size()));
        return new GeneratedPlan(planName, description, new ArrayList<>(days));
    }

    // ── Anfänger-Fallback: Push / Pull+Legs / Core+Ganzkörper — je 5 Übungen ──

    private List<GeneratedDay> buildBeginnerDays() {
        // Tag A: Push (Brust → Schulter → Trizeps)
        var tagA = new GeneratedDay("Tag A", "Brust, Schulter, Trizeps", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",       3, 10, 30.0, 90, 6),
                new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",3, 12, 10.0, 90, 6),
                new GeneratedExercise("Schulterdrücken mit Kurzhanteln",  3, 12,  8.0, 90, 6),
                new GeneratedExercise("Seitheben mit Kurzhanteln",        3, 12,  5.0, 60, 6),
                new GeneratedExercise("Trizeps Pushdown am Kabelzug",     3, 12, 12.0, 60, 6)
        ));
        // Tag B: Pull + Beine (Rücken → Bizeps → Beine)
        var tagB = new GeneratedDay("Tag B", "Rücken, Bizeps, Beine", List.of(
                new GeneratedExercise("Latzug am Kabelzug",               3, 10, 25.0, 90, 6),
                new GeneratedExercise("Rudern am Kabelzug",               3, 12, 20.0, 90, 6),
                new GeneratedExercise("Bizeps-Curl mit Kurzhanteln",      3, 12,  6.0, 60, 6),
                new GeneratedExercise("Kniebeuge mit Körpergewicht",       3, 12,  0.0, 90, 6),
                new GeneratedExercise("Rumänisches Kreuzheben",            3, 12, 20.0, 90, 6)
        ));
        // Tag C: Beine + Core (Quadrizeps → Hamstrings → Core)
        var tagC = new GeneratedDay("Tag C", "Beine, Core", List.of(
                new GeneratedExercise("Ausfallschritte mit Körpergewicht", 3, 10,  0.0, 90, 6),
                new GeneratedExercise("Goblet Squat mit Kurzhantel",       3, 12, 10.0, 90, 6),
                new GeneratedExercise("Beinbeuger an der Maschine",        3, 12, 25.0, 60, 6),
                new GeneratedExercise("Plank",                             3, 30,  0.0, 60, 6),
                new GeneratedExercise("Crunches",                          3, 15,  0.0, 60, 6)
        ));
        return List.of(tagA, tagB, tagC);
    }

    // ── Fortgeschritten-Fallback: Push / Pull / Legs — je 6 Übungen ─────────

    private List<GeneratedDay> buildIntermediateDays() {
        // Tag A: Push (Brust → Schulter → Trizeps)
        var push = new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",        4,  8, 60.0, 120, 7),
                new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln", 3, 10, 22.0,  90, 7),
                new GeneratedExercise("Schulterdrücken mit Langhantel",    3, 10, 30.0,  90, 7),
                new GeneratedExercise("Seitheben mit Kurzhanteln",         3, 12,  8.0,  60, 7),
                new GeneratedExercise("Trizeps Dips",                      3, 10,  0.0,  90, 7),
                new GeneratedExercise("Vorgebeugtes Seitheben",            3, 12,  6.0,  60, 6)
        ));
        // Tag B: Pull (Rücken → Bizeps)
        var pull = new GeneratedDay("Tag B – Pull", "Rücken, Bizeps", List.of(
                new GeneratedExercise("Klimmzüge",                         4,  6,  0.0, 120, 7),
                new GeneratedExercise("Langhantelrudern",                  4,  8, 50.0, 120, 7),
                new GeneratedExercise("Latzug am Kabelzug",               3, 10, 45.0,  90, 7),
                new GeneratedExercise("Einarmiges Kurzhantelrudern",       3, 10, 20.0,  90, 7),
                new GeneratedExercise("Bizeps-Curl mit Langhantel",        3, 10, 20.0,  60, 7),
                new GeneratedExercise("Hammer Curls mit Kurzhanteln",      3, 12, 12.0,  60, 6)
        ));
        // Tag C: Legs (Quadrizeps → Hamstrings → Gesäß → Waden)
        var legs = new GeneratedDay("Tag C – Beine", "Quadrizeps, Hamstrings, Gesäß, Waden", List.of(
                new GeneratedExercise("Kniebeuge mit Langhantel",          4,  8, 70.0, 120, 7),
                new GeneratedExercise("Beinpresse",                        3, 10, 80.0,  90, 7),
                new GeneratedExercise("Rumänisches Kreuzheben",            3, 10, 60.0,  90, 7),
                new GeneratedExercise("Ausfallschritte mit Kurzhanteln",   3, 12, 16.0,  90, 7),
                new GeneratedExercise("Beinbeuger an der Maschine",        3, 12, 35.0,  60, 6),
                new GeneratedExercise("Wadenheben an der Maschine",        3, 15, 40.0,  60, 6)
        ));
        return List.of(push, pull, legs);
    }

    // ── Experte-Fallback: Push / Pull / Legs — je 7 Übungen ─────────────────

    private List<GeneratedDay> buildAdvancedDays() {
        // Tag A: Push (Brust → Schulter → Trizeps)
        var push = new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps – Kraft", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",          5,  5,  90.0, 180, 8),
                new GeneratedExercise("Schrägbankdrücken mit Langhantel",    4,  6,  75.0, 150, 8),
                new GeneratedExercise("Kurzhantel-Fliegenschlagen",          3, 10,  24.0,  90, 7),
                new GeneratedExercise("Schulterdrücken mit Langhantel",      4,  6,  60.0, 150, 8),
                new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12,  12.0,  60, 7),
                new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",      3, 10,  20.0, 120, 7),
                new GeneratedExercise("Schädelbrechter mit Langhantel",      3, 10,  25.0,  90, 7)
        ));
        // Tag B: Pull (Rücken → Bizeps)
        var pull = new GeneratedDay("Tag B – Pull", "Rücken, Bizeps – Kraft", List.of(
                new GeneratedExercise("Kreuzheben",                          5,  5, 120.0, 180, 9),
                new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         4,  6,  15.0, 150, 8),
                new GeneratedExercise("Langhantelrudern",                    4,  6,  80.0, 150, 8),
                new GeneratedExercise("Einarmiges Kurzhantelrudern",         3,  8,  32.0,  90, 7),
                new GeneratedExercise("Face Pulls am Kabelzug",              3, 15,  20.0,  60, 6),
                new GeneratedExercise("Bizeps-Curl mit Langhantel",          4,  8,  30.0,  90, 7),
                new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12,  18.0,  60, 6)
        ));
        // Tag C: Legs + Core (Quadrizeps → Hamstrings → Gesäß → Waden → Core)
        var legs = new GeneratedDay("Tag C – Beine & Core", "Quadrizeps, Hamstrings, Gesäß, Waden, Core", List.of(
                new GeneratedExercise("Kniebeuge mit Langhantel",            5,  5, 100.0, 180, 8),
                new GeneratedExercise("Beinpresse",                          4,  8, 120.0, 120, 7),
                new GeneratedExercise("Rumänisches Kreuzheben",              4,  6,  90.0, 150, 8),
                new GeneratedExercise("Bulgarische Split Kniebeuge",         3,  8,  30.0, 120, 7),
                new GeneratedExercise("Beinbeuger an der Maschine",          3, 10,  45.0,  90, 7),
                new GeneratedExercise("Wadenheben an der Maschine",          4, 15,  60.0,  60, 7),
                new GeneratedExercise("Hängendes Beinheben",                 3, 12,   0.0,  60, 7)
        ));
        return List.of(push, pull, legs);
    }
    // =========================================================================
    // HTTP-Calls — 3-Ebenen-Fehlerbehandlung
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
                "options", Map.of("temperature", 0.15, "num_predict", 3000));
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
                        Map.of("role", "user",   "content", userPrompt)),
                "temperature", 0.3, "max_tokens", 3000);
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
    // Erklärung-Hilfsmethoden
    // =========================================================================

    private String buildExplanationPrompt(AppUser user, int sessionRpe, String userNote,
                                          List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder("Du bist ein erfahrener Fitness-Coach. Einheit bewertet:\n\n");
        if (user.getFitnessLevel() != null) sb.append("Level=").append(user.getFitnessLevel()).append("\n");
        if (user.getAge() != null) sb.append("Alter=").append(user.getAge()).append("\n");
        sb.append("Session-RPE: ").append(sessionRpe).append("/10\n");
        if (userNote != null && !userNote.isBlank()) sb.append("Notiz: ").append(userNote).append("\n");
        sb.append("\nAnpassungen:\n");
        for (var adj : adjustments)
            sb.append(String.format("- %s: %.1f->%.1f kg, %d->%d Wdh\n",
                    adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                    adj.previousReps(), adj.newReps()));
        sb.append("\nKurze motivierende Erklaerung auf Deutsch, du-Form, max 200 Woerter.");
        return sb.toString();
    }

    private String buildFallbackExplanation(int sessionRpe, List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();
        if      (sessionRpe <= 4) sb.append("Deine Einheit war sehr leicht — wir erhoehen die Last.");
        else if (sessionRpe <= 6) sb.append("Moderate Belastung — leichte Steigerung fuer Fortschritt.");
        else if (sessionRpe <= 8) sb.append("Optimaler Bereich! Parameter bleiben stabil.");
        else                       sb.append("Sehr anstrengend — wir reduzieren etwas zur Erholung.");
        sb.append("\n\nAnpassungen:\n");
        for (var adj : adjustments)
            if (adj.previousWeight() != adj.newWeight() || adj.previousReps() != adj.newReps())
                sb.append(String.format("- %s: %.1f -> %.1f kg, %d -> %d Wdh\n",
                        adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                        adj.previousReps(), adj.newReps()));
        return sb.toString();
    }
}