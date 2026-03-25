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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * KI-Service: Zwei Aufgaben —
 *
 * 1) generateExplanation()   → erklärt RPE-Anpassungen nach dem Training (bestehend)
 * 2) generateTrainingPlan()  → generiert einen kompletten Trainingsplan als JSON (neu)
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiConfig aiConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AiService(AiConfig aiConfig, WebClient webClient, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // METHODE 1: Erklärung nach Feedback (bestehend, unverändert)
    // =====================================================================

    public String generateExplanation(
            AppUser user,
            int sessionRpe,
            String userNote,
            List<ExerciseAdjustment> adjustments) {

        String prompt = buildExplanationPrompt(user, sessionRpe, userNote, adjustments);

        try {
            String response = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllamaText(prompt);
                case "openai" -> callOpenAiText(prompt,
                        "Du bist ein erfahrener Fitness-Coach. Antworte auf Deutsch, kurz und motivierend.");
                default -> {
                    log.warn("Unbekannter AI-Provider: {}", aiConfig.getProvider());
                    yield null;
                }
            };

            if (response != null && !response.isBlank()) {
                log.info("KI-Erklärung generiert ({} Zeichen)", response.length());
                return response;
            }
        } catch (Exception e) {
            log.error("Fehler bei KI-Erklärung: {}", e.getMessage());
        }

        return buildFallbackExplanation(sessionRpe, adjustments);
    }

    // =====================================================================
    // METHODE 2: Trainingsplan-Generierung (neu)
    // =====================================================================

    /**
     * Generiert einen vollständigen Trainingsplan via KI.
     * Feldnamen aus GeneratePlanRequest: planName, userPrompt, fitnessGoal,
     * daysPerWeek, focusMuscles, experienceLevel
     */
    public GeneratedPlan generateTrainingPlan(AppUser user, GeneratePlanRequest request) {

        String systemPrompt = buildPlanSystemPrompt();
        String userPrompt   = buildPlanUserPrompt(user, request);

        try {
            String rawJson = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllamaJson(userPrompt);
                case "openai" -> callOpenAiText(userPrompt, systemPrompt);
                default -> {
                    log.warn("Unbekannter AI-Provider: {}", aiConfig.getProvider());
                    yield null;
                }
            };

            if (rawJson != null && !rawJson.isBlank()) {
                GeneratedPlan parsed = parseGeneratedPlan(rawJson);
                if (parsed != null && !parsed.exercises().isEmpty()) {
                    log.info("KI-Plan generiert: '{}' mit {} Übungen",
                            parsed.planName(), parsed.exercises().size());
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.error("Fehler bei KI-Plan-Generierung: {}", e.getMessage());
        }

        log.warn("KI nicht erreichbar → Fallback-Plan wird verwendet");
        return buildFallbackPlan(request);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Datenstrukturen
    // ─────────────────────────────────────────────────────────────────────

    public record GeneratedPlan(
            String planName,
            String description,
            List<GeneratedExercise> exercises
    ) {}

    public record GeneratedExercise(
            String exerciseName,
            int sets,
            int reps,
            double weightKg,
            int restSeconds,
            int targetRpe
    ) {}

    // ─────────────────────────────────────────────────────────────────────
    // Prompt-Builder für Plan-Generierung
    // ─────────────────────────────────────────────────────────────────────

    private String buildPlanSystemPrompt() {
        return """
            Du bist ein erfahrener Personal Trainer. Erstelle einen Trainingsplan als JSON.
            Antworte AUSSCHLIESSLICH mit einem validen JSON-Objekt.
            Kein Markdown, keine Codeblöcke, kein Text vor oder nach dem JSON.
            Das JSON muss exakt diesem Schema folgen:
            {
              "planName": "string",
              "description": "string",
              "exercises": [
                {
                  "exerciseName": "string",
                  "sets": number,
                  "reps": number,
                  "weightKg": number,
                  "restSeconds": number,
                  "targetRpe": number
                }
              ]
            }
            """;
    }

    /**
     * Baut den User-Prompt aus GeneratePlanRequest.
     * Verwendet: userPrompt, fitnessGoal, experienceLevel, daysPerWeek, focusMuscles
     * + Profildaten des Users
     */
    private String buildPlanUserPrompt(AppUser user, GeneratePlanRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("Erstelle einen Trainingsplan mit folgenden Anforderungen:\n\n");

        // Pflichtfeld: freier Wunsch-Text des Users
        sb.append("Wunsch: ").append(request.userPrompt()).append("\n");

        // Erfahrungslevel (aus Request oder Profil)
        if (request.experienceLevel() != null && !request.experienceLevel().isBlank()) {
            sb.append("Erfahrungslevel: ").append(translateLevel(request.experienceLevel())).append("\n");
        } else if (user.getFitnessLevel() != null) {
            sb.append("Erfahrungslevel (aus Profil): ").append(user.getFitnessLevel()).append("\n");
        }

        // Trainingsziel
        if (request.fitnessGoal() != null && !request.fitnessGoal().isBlank()) {
            sb.append("Trainingsziel: ").append(translateGoal(request.fitnessGoal())).append("\n");
        }

        // Trainingstage
        if (request.daysPerWeek() != null) {
            sb.append("Trainingstage pro Woche: ").append(request.daysPerWeek()).append("\n");
        }

        // Fokus-Muskelgruppen
        if (request.focusMuscles() != null && !request.focusMuscles().isBlank()) {
            sb.append("Fokus-Muskelgruppen: ").append(request.focusMuscles()).append("\n");
        }

        // Nutzerprofil-Daten
        if (user.getAge() != null)      sb.append("Alter: ").append(user.getAge()).append(" Jahre\n");
        if (user.getWeightKg() != null) sb.append("Körpergewicht: ").append(user.getWeightKg()).append(" kg\n");

        sb.append("""

            Regeln:
            - 4 bis 8 Übungen
            - sets: 2–5, reps: 5–15
            - weightKg: realistische Anfängerwerte
            - restSeconds: 60–180
            - targetRpe: 6–8
            - exerciseName auf Deutsch
            - Gib NUR das JSON zurück.
            """);

        return sb.toString();
    }

    /** Übersetzt Frontend-Enum-Werte in lesbare Strings für den Prompt */
    private String translateLevel(String level) {
        return switch (level.toUpperCase()) {
            case "BEGINNER"     -> "Anfänger";
            case "INTERMEDIATE" -> "Fortgeschritten";
            case "ADVANCED"     -> "Experte";
            default             -> level;
        };
    }

    /** Übersetzt Frontend-Enum-Werte für Trainingsziele */
    private String translateGoal(String goal) {
        return switch (goal.toUpperCase()) {
            case "MUSCLE_GAIN"     -> "Muskelaufbau";
            case "STRENGTH"        -> "Kraftaufbau";
            case "FAT_LOSS"        -> "Fettabbau";
            case "ENDURANCE"       -> "Ausdauer";
            case "GENERAL_FITNESS" -> "Allgemeine Fitness";
            default                -> goal;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // JSON-Parser für KI-Antwort
    // ─────────────────────────────────────────────────────────────────────

    private GeneratedPlan parseGeneratedPlan(String rawResponse) {
        try {
            // Markdown-Fences entfernen falls vorhanden
            String cleaned = rawResponse
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            // Nur den JSON-Teil extrahieren
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) {
                log.warn("Kein gültiges JSON in KI-Antwort");
                return null;
            }
            cleaned = cleaned.substring(start, end + 1);

            JsonNode root = objectMapper.readTree(cleaned);

            String planName    = root.path("planName").asText("KI-Trainingsplan");
            String description = root.path("description").asText("");

            List<GeneratedExercise> exercises = new ArrayList<>();
            JsonNode exArray = root.path("exercises");

            if (exArray.isArray()) {
                for (JsonNode exNode : exArray) {
                    String name     = exNode.path("exerciseName").asText("Übung");
                    int sets        = clamp(exNode.path("sets").asInt(3), 1, 10);
                    int reps        = clamp(exNode.path("reps").asInt(10), 1, 50);
                    double weightKg = Math.max(0, exNode.path("weightKg").asDouble(20.0));
                    int restSeconds = clamp(exNode.path("restSeconds").asInt(90), 30, 300);
                    int targetRpe   = clamp(exNode.path("targetRpe").asInt(7), 1, 10);

                    if (!name.isBlank()) {
                        exercises.add(new GeneratedExercise(
                                name, sets, reps, weightKg, restSeconds, targetRpe));
                    }
                }
            }

            if (exercises.isEmpty()) {
                log.warn("KI hat keine Übungen geliefert");
                return null;
            }

            return new GeneratedPlan(planName, description, exercises);

        } catch (Exception e) {
            log.error("JSON-Parse-Fehler: {}", e.getMessage());
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fallback-Plan
    // ─────────────────────────────────────────────────────────────────────

    private GeneratedPlan buildFallbackPlan(GeneratePlanRequest request) {
        // planName kommt jetzt direkt aus dem Request
        String planName    = request.planName() != null ? request.planName() : "Trainingsplan";
        String description = "Automatisch erstellt — " + request.userPrompt();

        String level = request.experienceLevel() != null
                ? request.experienceLevel().toUpperCase() : "BEGINNER";

        List<GeneratedExercise> exercises = switch (level) {
            case "ADVANCED" -> List.of(
                    new GeneratedExercise("Kniebeuge",        5, 5,  100.0, 180, 8),
                    new GeneratedExercise("Bankdrücken",      5, 5,  90.0,  180, 8),
                    new GeneratedExercise("Kreuzheben",       4, 5,  120.0, 180, 8),
                    new GeneratedExercise("Schulterdrücken",  4, 8,  60.0,  120, 7),
                    new GeneratedExercise("Klimmzüge",        4, 8,  0.0,   90,  7),
                    new GeneratedExercise("Trizeps Dips",     3, 10, 0.0,   90,  7)
            );
            case "INTERMEDIATE" -> List.of(
                    new GeneratedExercise("Kniebeuge",            4, 8,  70.0, 120, 7),
                    new GeneratedExercise("Bankdrücken",          4, 8,  60.0, 120, 7),
                    new GeneratedExercise("Latzug",               3, 10, 50.0, 90,  7),
                    new GeneratedExercise("Schulterdrücken",      3, 10, 30.0, 90,  7),
                    new GeneratedExercise("Rumänisches Kreuzheben", 3, 10, 60.0, 90, 7),
                    new GeneratedExercise("Bizeps-Curl",          3, 12, 15.0, 60,  6)
            );
            default -> List.of(
                    new GeneratedExercise("Kniebeuge",        3, 10, 40.0, 90, 6),
                    new GeneratedExercise("Bankdrücken",      3, 10, 30.0, 90, 6),
                    new GeneratedExercise("Latzug",           3, 10, 30.0, 90, 6),
                    new GeneratedExercise("Schulterdrücken",  3, 12, 15.0, 90, 6),
                    new GeneratedExercise("Bizeps-Curl",      3, 12, 8.0,  60, 6),
                    new GeneratedExercise("Trizeps Pushdown", 3, 12, 10.0, 60, 6)
            );
        };

        return new GeneratedPlan(planName, description, exercises);
    }

    // =====================================================================
    // HTTP-Calls
    // =====================================================================

    private String callOllamaText(String prompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";
        log.info("Ollama Text: model={}", aiConfig.getOllama().getModel());

        Map<String, Object> body = Map.of(
                "model",   aiConfig.getOllama().getModel(),
                "prompt",  prompt,
                "stream",  false,
                "options", Map.of("temperature", 0.7, "num_predict", 500)
        );

        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (String) map.get("response"))
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    private String callOllamaJson(String prompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";
        log.info("Ollama JSON: model={}", aiConfig.getOllama().getModel());

        String fullPrompt = buildPlanSystemPrompt() + "\n\n" + prompt;

        Map<String, Object> body = Map.of(
                "model",   aiConfig.getOllama().getModel(),
                "prompt",  fullPrompt,
                "stream",  false,
                "format",  "json",
                "options", Map.of("temperature", 0.2, "num_predict", 1000)
        );

        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (String) map.get("response"))
                .timeout(Duration.ofSeconds(60))
                .block();
    }

    @SuppressWarnings("unchecked")
    private String callOpenAiText(String userPrompt, String systemPrompt) {
        String url = aiConfig.getOpenai().getBaseUrl() + "/chat/completions";
        log.info("OpenAI: model={}", aiConfig.getOpenai().getModel());

        Map<String, Object> body = Map.of(
                "model", aiConfig.getOpenai().getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "temperature", 0.3,
                "max_tokens", 1000
        );

        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiConfig.getOpenai().getApiKey())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> {
                    var choices = (List<Map<String, Object>>) map.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        var message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    }
                    return "";
                })
                .timeout(Duration.ofSeconds(60))
                .block();
    }

    // =====================================================================
    // Erklärung-Hilfsmethoden (bestehend)
    // =====================================================================

    private String buildExplanationPrompt(AppUser user, int sessionRpe, String userNote,
                                          List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Du bist ein erfahrener Fitness-Coach. Einheit bewertet:\n\n");
        if (user.getFitnessLevel() != null) sb.append("Level=").append(user.getFitnessLevel()).append("\n");
        if (user.getAge() != null) sb.append("Alter=").append(user.getAge()).append("\n");
        sb.append("Session-RPE: ").append(sessionRpe).append("/10\n");
        if (userNote != null && !userNote.isBlank()) sb.append("Notiz: ").append(userNote).append("\n");
        sb.append("\nAnpassungen:\n");
        for (var adj : adjustments) {
            sb.append(String.format("- %s: %.1f→%.1f kg, %d→%d Wdh\n",
                    adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                    adj.previousReps(), adj.newReps()));
        }
        sb.append("\nKurze motivierende Erklärung auf Deutsch, du-Form, max 200 Wörter.");
        return sb.toString();
    }

    private String buildFallbackExplanation(int sessionRpe, List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();
        if (sessionRpe <= 4)      sb.append("Deine Einheit war leicht — wir haben die Last erhöht.");
        else if (sessionRpe <= 6) sb.append("Moderate Belastung — leichte Steigerung für Fortschritt.");
        else if (sessionRpe <= 8) sb.append("Optimaler Bereich! Parameter bleiben stabil.");
        else                      sb.append("Sehr anstrengend — wir reduzieren etwas zur Erholung.");
        sb.append("\n\nAnpassungen:\n");
        for (var adj : adjustments) {
            if (adj.previousWeight() != adj.newWeight() || adj.previousReps() != adj.newReps()) {
                sb.append(String.format("• %s: %.1f → %.1f kg, %d → %d Wdh\n",
                        adj.exerciseName(), adj.previousWeight(), adj.newWeight(),
                        adj.previousReps(), adj.newReps()));
            }
        }
        return sb.toString();
    }
}