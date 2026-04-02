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

    /**
     * description: leer für normale Kraftübungen,
     * gefüllt für Mobilitätsübungen (1-2 Sätze Ausführung auf Deutsch).
     */
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
                    long total = parsed.days().stream().mapToLong(d -> d.exercises().size()).sum();
                    log.info("KI-Plan: '{}' mit {} Tagen, {} Uebungen", parsed.planName(), parsed.days().size(), total);
                    return parsed;
                }
            }
            log.warn("KI lieferte keinen verwertbaren Plan -> Fallback");
        } catch (Exception e) {
            log.error("Fehler bei KI-Plan-Generierung: {}", e.getMessage());
        }
        return buildFallbackPlan(request);
    }

    // =========================================================================
    // Tag-Anzahl-Logik
    // =========================================================================

    private int calcNumDayPlans(int daysPerWeek) {
        if (daysPerWeek <= 2) return 1;
        if (daysPerWeek <= 5) return 2;
        return 3;
    }

    private int calcExercisesPerDay(String level) {
        if (level == null) return 5;
        return switch (level.toUpperCase()) {
            case "INTERMEDIATE" -> 6;
            case "ADVANCED"     -> 7;
            default             -> 5;
        };
    }

    /** Überschreibt Level-basierte Übungsanzahl bei kurzer Trainingsdauer */
    private int calcExercisesPerDayWithDuration(String level, Integer durationMinutes) {
        if (durationMinutes == null) return calcExercisesPerDay(level);
        if (durationMinutes <= 30) return 3;
        if (durationMinutes <= 45) return 4;
        return calcExercisesPerDay(level); // 60+ min: Level-Standard
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
    // System-Prompt (mit allen 11 Regeln)
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
              1–2 Tage/Woche → genau 1 Tag  (Tag A)
              3–5 Tage/Woche → genau 2 Tage (Tag A, Tag B)
              6+  Tage/Woche → genau 3 Tage (Tag A, Tag B, Tag C)
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
              Fokus BEINE bei 3 Tagen: 2 Bein-Tage (Quad / Hamstring jeweils anders), 1 Oberkörper-Tag.
              Fokus SCHULTER bei 3 Tagen: 2 Schulter-Tage (Push / Pull-Schulter jeweils anders), 1 Bein-Tag.

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
              Die Satzanzahl (sets) — NICHT die Übungsanzahl — wird angepasst:
              ≥8h Schlaf + Niedriger Stress   → 4–5 Sätze pro Übung möglich.
              6–7h Schlaf + Moderater Stress  → Standard 3–4 Sätze.
              ≤5h Schlaf ODER Hoher Stress    → Max. 2–3 Sätze. Gewichte −10%. Kein Training bis Versagen.
              Schlechter Schlaf UND hoher Stress zusammen → max. 2 Sätze pro Übung.

            REGEL 10 — VERLETZUNGEN / EINSCHRÄNKUNGEN:
              ✗ VERBOTEN: Übungen, die betroffene Regionen direkt belasten.
              ✓ PFLICHT: Sichere Alternativen wählen. Beispiele:
                Knieschmerzen       → Beinpresse, Goblet Squat (statt Kniebeuge)
                Schulterschmerzen   → Kabelzug flach, Liegestütz (statt Bankdrücken)
                Rückenschmerzen     → Rumänisches KH leicht, Beinpresse (statt Kreuzheben)
                Handgelenksprobleme → Hammer Curl, Kabelzug-Curl (statt Barbell-Curl)

            REGEL 11 — MOBILITÄTSBLOCK (nur wenn "includeMobilityPlan: true"):
              → Als LETZTEN Tag einen zusätzlichen Tag hinzufügen: "dayName": "Mobilitätsblock"
              → Genau 5–7 Mobility-Übungen.
              → sets: 1–2 | reps: 20–60 (Sekunden-Angabe) | weightKg: 0.0
                restSeconds: 30 | targetRpe: 3
              → JEDE Mobilitätsübung MUSS ein gefülltes "description"-Feld haben:
                Kurze Ausführung auf Deutsch, 1–2 Sätze, verständlich für Anfänger.
                Beispiel: "Knie auf dem Boden, Hüfte nach vorne schieben und 30 Sek. halten."
              → Normale Trainingstag-Übungen haben "description": "".

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
    // User-Prompt (alle 6 neuen Parameter eingebaut)
    // =========================================================================

    private String buildPlanUserPrompt(AppUser user, GeneratePlanRequest request) {
        // ── Fokus aus allen Quellen zusammenführen ───────────────────────────
        List<String> focusParts = new ArrayList<>();
        if (request.focusMuscleGroups() != null)
            focusParts.addAll(request.focusMuscleGroups());
        if (request.focusMusclesFreetext() != null && !request.focusMusclesFreetext().isBlank())
            focusParts.add(request.focusMusclesFreetext().trim());
        if (request.focusMuscles() != null && !request.focusMuscles().isBlank())
            focusParts.add(request.focusMuscles().trim());
        String focusMuscles = String.join(", ", focusParts.stream()
                .filter(s -> s != null && !s.isBlank()).toList());

        // ── Standard-Werte ────────────────────────────────────────────────────
        String planName    = request.planName();
        String userPrompt  = request.userPrompt();
        String fitnessGoal = (request.fitnessGoal() != null && !request.fitnessGoal().isBlank())
                ? translateGoal(request.fitnessGoal()) : "Nicht angegeben";
        int daysPerWeek    = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        String rawLevel    = resolveRawLevel(request, user);
        String levelLabel  = translateLevel(rawLevel);
        int numDayPlans    = calcNumDayPlans(daysPerWeek);
        int exPerDay       = calcExercisesPerDayWithDuration(rawLevel, request.sessionDurationMinutes());

        // ── Neue Parameter ────────────────────────────────────────────────────
        String durationStr = request.sessionDurationMinutes() != null
                ? request.sessionDurationMinutes() + " Minuten" : "Nicht angegeben";
        String sleepStr = request.sleepHoursPerNight() != null
                ? request.sleepHoursPerNight() + " Stunden" : "Nicht angegeben";
        String stressStr  = translateStress(request.stressLevel());
        String injuriesStr = (request.injuries() != null && !request.injuries().isBlank())
                ? request.injuries().trim() : "Keine";
        boolean mobility  = Boolean.TRUE.equals(request.includeMobilityPlan());

        // ── Regenerations-Empfehlung ──────────────────────────────────────────
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

        // ── Tag-Namen & Rotation ─────────────────────────────────────────────
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

        // ── Fokus-spezifische Aufteilung (bestehende Logik, nutzt merged focusMuscles) ──
        String focusSection = buildFocusSection(focusMuscles, numDayPlans);

        String rotationLine = buildRotationHint(daysPerWeek, numDayPlans,
                List.of("Tag A", "Tag B", "Tag C"));

        // ── Prompt zusammenbauen ─────────────────────────────────────────────
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
                + "4. REIHENFOLGE: Große Muskelgruppen zuerst. Schulter/Arme niemals vor Brust/Rücken/Beinen.\n"
                + "\n"
                + "5. DUPLIKAT-VERBOT: Keine Übung in mehr als einem Tag.\n"
                + (rotationLine.isBlank() ? "" : "\n6. ROTATION: " + rotationLine + "\n")
                + "\n"
                + "7. TRAININGSDAUER: " + durationStr + "\n"
                + "   Pausen und Übungsanzahl entsprechend REGEL 8 anpassen.\n"
                + "\n"
                + "8. REGENERATION: Schlaf " + sleepStr + " | Stress " + stressStr + "\n"
                + "   " + recoveryAdvice + "\n"
                + "\n"
                + (!"Keine".equals(injuriesStr)
                ? "9. VERLETZUNGEN / EINSCHRÄNKUNGEN (KRITISCH):\n"
                + "   " + injuriesStr + "\n"
                + "   → Alle betroffenen Übungen KOMPLETT vermeiden und ersetzen (REGEL 10).\n\n"
                : "")
                + (mobility
                ? "10. MOBILITÄTSBLOCK (PFLICHT):\n"
                + "    Als LETZTEN Tag 'Mobilitätsblock' hinzufügen (Zusatz zu den " + numDayPlans + " Trainingstagen).\n"
                + "    5–7 Mobility-Übungen, jede mit 'description' (1–2 Sätze Ausführung auf Deutsch).\n"
                + "    weightKg: 0.0 | sets: 1–2 | reps: 20–60 | restSeconds: 30 | targetRpe: 3\n\n"
                : "")
                + "Gib ausschließlich das JSON zurück.";
    }

    /** Fokus-spezifische Muskelgruppen-Aufteilung (aus bestehender Logik extrahiert) */
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

                    FOKUS-REGEL BEINE (3 Tage):
                    Tag A (Beine – Quad): Kniebeuge, Beinpresse, Ausfallschritte, Wadenheben, [optional 1 Oberkörper]
                    Tag B (Beine – Hamstring): Rumänisches Kreuzheben, Beinbeuger, Gesäßübung, [optional 1 Oberkörper]
                    Tag C (Oberkörper): Brust, Rücken, Schulter, Arme.
                    Kein Bein-Duplikat zwischen Tag A und B.""";
            if (isSchulter) return """

                    FOKUS-REGEL SCHULTER (3 Tage):
                    Tag A (Schulter+Brust): Schulterdrücken, Seitheben, Bankdrücken, Fliegenschlagen, Trizeps.
                    Tag B (Schulter+Rücken): Seitheben hinten, Face Pulls, Latzug, Rudern, Bizeps.
                    Tag C (Beine): Kniebeuge, Beinpresse, Rumänisches KH, Ausfallschritte, Waden.""";
            if (isBrust) return """

                    FOKUS-REGEL BRUST (3 Tage):
                    Tag A (Brust+Schulter): Bankdrücken, Schrägdrücken, Schulterdrücken, Seitheben, Trizeps.
                    Tag B (Brust+Trizeps): Schrägbankdrücken KH, Fliegenschlagen, Trizeps-Übungen.
                    Tag C (Rücken+Beine): Klimmzüge, Rudern, Kniebeuge, Rumänisches KH.""";
            if (isRuecken) return """

                    FOKUS-REGEL RÜCKEN (3 Tage):
                    Tag A (Rücken-Breite): Klimmzüge, Latzug, einarmiges Rudern, Bizeps.
                    Tag B (Rücken-Dicke): Langhantelrudern, T-Bar-Rudern, Kreuzheben, Face Pulls, Bizeps-Variation.
                    Tag C (Brust+Beine): Bankdrücken, Schrägdrücken, Kniebeuge, Rumänisches KH.""";
        } else if (numDayPlans == 2) {
            if (isBein) return """

                    FOKUS-REGEL BEINE (2 Tage):
                    Tag A: Quad-Fokus (Kniebeuge, Beinpresse) + Oberkörper ergänzend.
                    Tag B: Hamstring-Fokus (Rumänisches KH, Beinbeuger) + Oberkörper ergänzend.""";
            if (isSchulter) return """

                    FOKUS-REGEL SCHULTER (2 Tage):
                    Tag A: Drückende Schulterübungen + Brust.
                    Tag B: Ziehende Schulterübungen + Rücken.""";
        }
        return "\nFOKUS '" + focusMuscles + "': In den Tagen priorisieren, klare Variation zwischen den Tagen.";
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

            // Fallback: altes Format
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
    // Fallback-Plan (respektiert alle neuen Parameter)
    // =========================================================================

    private GeneratedPlan buildFallbackPlan(GeneratePlanRequest request) {
        String planName   = request.planName() != null ? request.planName() : "Trainingsplan";
        String level      = resolveRawLevel(request, null);
        int daysPerWeek   = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        int numDayPlans   = calcNumDayPlans(daysPerWeek);

        List<String> dayNames = List.of("Tag A", "Tag B", "Tag C");
        String rotation   = buildRotationHint(daysPerWeek, numDayPlans, dayNames);
        String description = "Fallback-Plan | " + request.userPrompt()
                + (rotation.isBlank() ? "" : " | " + rotation);

        List<GeneratedDay> allDays = switch (level) {
            case "ADVANCED"     -> buildAdvancedDays();
            case "INTERMEDIATE" -> buildIntermediateDays();
            default             -> buildBeginnerDays();
        };

        // Sätze anhand Regeneration anpassen
        int sets = calcFallbackSets(request);
        if (sets != 3) {
            allDays = adjustSets(allDays, sets);
        }

        List<GeneratedDay> days = new ArrayList<>(allDays.subList(0, Math.min(numDayPlans, allDays.size())));

        // Mobilitätsblock hinzufügen wenn gewünscht
        if (Boolean.TRUE.equals(request.includeMobilityPlan())) {
            days.add(buildFallbackMobilityDay());
        }

        return new GeneratedPlan(planName, description, days);
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

    // ── Mobilitäts-Fallback (mit echten Beschreibungen) ───────────────────────

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

    // ── Anfänger-Fallback (je 5 Übungen, mit leeren Beschreibungen) ──────────

    private List<GeneratedDay> buildBeginnerDays() {
        var tagA = new GeneratedDay("Tag A", "Brust, Schulter, Trizeps", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",        3, 10, 30.0, 90, 6, ""),
                new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln", 3, 12, 10.0, 90, 6, ""),
                new GeneratedExercise("Schulterdrücken mit Kurzhanteln",   3, 12,  8.0, 90, 6, ""),
                new GeneratedExercise("Seitheben mit Kurzhanteln",         3, 12,  5.0, 60, 6, ""),
                new GeneratedExercise("Trizeps Pushdown am Kabelzug",      3, 12, 12.0, 60, 6, "")
        ));
        var tagB = new GeneratedDay("Tag B", "Rücken, Bizeps, Beine", List.of(
                new GeneratedExercise("Latzug am Kabelzug",                3, 10, 25.0, 90, 6, ""),
                new GeneratedExercise("Rudern am Kabelzug",                3, 12, 20.0, 90, 6, ""),
                new GeneratedExercise("Bizeps-Curl mit Kurzhanteln",       3, 12,  6.0, 60, 6, ""),
                new GeneratedExercise("Kniebeuge mit Körpergewicht",        3, 12,  0.0, 90, 6, ""),
                new GeneratedExercise("Rumänisches Kreuzheben",             3, 12, 20.0, 90, 6, "")
        ));
        var tagC = new GeneratedDay("Tag C", "Beine, Core", List.of(
                new GeneratedExercise("Ausfallschritte mit Körpergewicht",  3, 10,  0.0, 90, 6, ""),
                new GeneratedExercise("Goblet Squat mit Kurzhantel",        3, 12, 10.0, 90, 6, ""),
                new GeneratedExercise("Beinbeuger an der Maschine",         3, 12, 25.0, 60, 6, ""),
                new GeneratedExercise("Plank",                              3, 30,  0.0, 60, 6, ""),
                new GeneratedExercise("Crunches",                           3, 15,  0.0, 60, 6, "")
        ));
        return List.of(tagA, tagB, tagC);
    }

    // ── Fortgeschritten-Fallback (je 6 Übungen) ───────────────────────────────

    private List<GeneratedDay> buildIntermediateDays() {
        var push = new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",         4,  8, 60.0, 120, 7, ""),
                new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",  3, 10, 22.0,  90, 7, ""),
                new GeneratedExercise("Schulterdrücken mit Langhantel",     3, 10, 30.0,  90, 7, ""),
                new GeneratedExercise("Seitheben mit Kurzhanteln",          3, 12,  8.0,  60, 7, ""),
                new GeneratedExercise("Trizeps Dips",                       3, 10,  0.0,  90, 7, ""),
                new GeneratedExercise("Vorgebeugtes Seitheben",             3, 12,  6.0,  60, 6, "")
        ));
        var pull = new GeneratedDay("Tag B – Pull", "Rücken, Bizeps", List.of(
                new GeneratedExercise("Klimmzüge",                          4,  6,  0.0, 120, 7, ""),
                new GeneratedExercise("Langhantelrudern",                   4,  8, 50.0, 120, 7, ""),
                new GeneratedExercise("Latzug am Kabelzug",                3, 10, 45.0,  90, 7, ""),
                new GeneratedExercise("Einarmiges Kurzhantelrudern",        3, 10, 20.0,  90, 7, ""),
                new GeneratedExercise("Bizeps-Curl mit Langhantel",         3, 10, 20.0,  60, 7, ""),
                new GeneratedExercise("Hammer Curls mit Kurzhanteln",       3, 12, 12.0,  60, 6, "")
        ));
        var legs = new GeneratedDay("Tag C – Beine", "Quadrizeps, Hamstrings, Gesäß", List.of(
                new GeneratedExercise("Kniebeuge mit Langhantel",           4,  8, 70.0, 120, 7, ""),
                new GeneratedExercise("Beinpresse",                         3, 10, 80.0,  90, 7, ""),
                new GeneratedExercise("Rumänisches Kreuzheben",             3, 10, 60.0,  90, 7, ""),
                new GeneratedExercise("Ausfallschritte mit Kurzhanteln",    3, 12, 16.0,  90, 7, ""),
                new GeneratedExercise("Beinbeuger an der Maschine",         3, 12, 35.0,  60, 6, ""),
                new GeneratedExercise("Wadenheben an der Maschine",         3, 15, 40.0,  60, 6, "")
        ));
        return List.of(push, pull, legs);
    }

    // ── Experte-Fallback (je 7 Übungen) ───────────────────────────────────────

    private List<GeneratedDay> buildAdvancedDays() {
        var push = new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps – Kraft", List.of(
                new GeneratedExercise("Bankdrücken mit Langhantel",          5,  5,  90.0, 180, 8, ""),
                new GeneratedExercise("Schrägbankdrücken mit Langhantel",    4,  6,  75.0, 150, 8, ""),
                new GeneratedExercise("Kurzhantel-Fliegenschlagen",          3, 10,  24.0,  90, 7, ""),
                new GeneratedExercise("Schulterdrücken mit Langhantel",      4,  6,  60.0, 150, 8, ""),
                new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12,  12.0,  60, 7, ""),
                new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",      3, 10,  20.0, 120, 7, ""),
                new GeneratedExercise("Schädelbrechter mit Langhantel",      3, 10,  25.0,  90, 7, "")
        ));
        var pull = new GeneratedDay("Tag B – Pull", "Rücken, Bizeps – Kraft", List.of(
                new GeneratedExercise("Kreuzheben",                          5,  5, 120.0, 180, 9, ""),
                new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         4,  6,  15.0, 150, 8, ""),
                new GeneratedExercise("Langhantelrudern",                    4,  6,  80.0, 150, 8, ""),
                new GeneratedExercise("Einarmiges Kurzhantelrudern",         3,  8,  32.0,  90, 7, ""),
                new GeneratedExercise("Face Pulls am Kabelzug",              3, 15,  20.0,  60, 6, ""),
                new GeneratedExercise("Bizeps-Curl mit Langhantel",          4,  8,  30.0,  90, 7, ""),
                new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12,  18.0,  60, 6, "")
        ));
        var legs = new GeneratedDay("Tag C – Beine & Core", "Quadrizeps, Hamstrings, Core", List.of(
                new GeneratedExercise("Kniebeuge mit Langhantel",            5,  5, 100.0, 180, 8, ""),
                new GeneratedExercise("Beinpresse",                          4,  8, 120.0, 120, 7, ""),
                new GeneratedExercise("Rumänisches Kreuzheben",              4,  6,  90.0, 150, 8, ""),
                new GeneratedExercise("Bulgarische Split Kniebeuge",         3,  8,  30.0, 120, 7, ""),
                new GeneratedExercise("Beinbeuger an der Maschine",          3, 10,  45.0,  90, 7, ""),
                new GeneratedExercise("Wadenheben an der Maschine",          4, 15,  60.0,  60, 7, ""),
                new GeneratedExercise("Hängendes Beinheben",                 3, 12,   0.0,  60, 7, "")
        ));
        return List.of(push, pull, legs);
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