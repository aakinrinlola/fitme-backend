package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.config.AiConfig;
import com.mike.backend.myBackendTest.dto.SessionFeedbackResponse.ExerciseAdjustment;
import com.mike.backend.myBackendTest.entity.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * KI-Service: Generiert verständliche Texterklärungen zu Trainingsanpassungen.
 *
 * Gemäß Proposal: "Das LLM übernimmt keine eigenständige numerische Steuerung,
 * sondern generiert ergänzend verständliche textliche Erläuterungen zu den Anpassungen."
 *
 * Unterstützt zwei Provider:
 * 1. Ollama (Self-Hosted) → Datenschutz-konform, keine Daten verlassen die Infrastruktur
 * 2. OpenAI API → Nur pseudonymisierte Daten werden übertragen
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiConfig aiConfig;
    private final WebClient webClient;

    public AiService(AiConfig aiConfig, WebClient webClient) {
        this.aiConfig = aiConfig;
        this.webClient = webClient;
    }

    /**
     * Generiert eine personalisierte KI-Erklärung für die Trainingsanpassungen.
     * Fallback: Standardtext, falls die KI nicht erreichbar ist.
     */
    public String generateExplanation(
            AppUser user,
            int sessionRpe,
            String userNote,
            List<ExerciseAdjustment> adjustments) {

        String prompt = buildPrompt(user, sessionRpe, userNote, adjustments);

        try {
            String response = switch (aiConfig.getProvider().toLowerCase()) {
                case "ollama" -> callOllama(prompt);
                case "openai" -> callOpenAi(prompt);
                default -> {
                    log.warn("Unbekannter AI-Provider: {}", aiConfig.getProvider());
                    yield null;
                }
            };

            if (response != null && !response.isBlank()) {
                log.info("KI-Erklärung erfolgreich generiert ({} Zeichen)", response.length());
                return response;
            }
        } catch (Exception e) {
            log.error("Fehler bei KI-Aufruf: {}", e.getMessage());
        }

        // Fallback: Standarderklärung (gemäß Proposal)
        return buildFallbackExplanation(sessionRpe, adjustments);
    }

    /**
     * Baut den Prompt für das LLM.
     * Nur pseudonymisierte Daten werden verwendet (kein Name, keine E-Mail).
     */
    private String buildPrompt(AppUser user, int sessionRpe, String userNote,
                                List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Du bist ein erfahrener Fitness-Coach. Ein Trainierender hat gerade seine Einheit bewertet.\n\n");

        // Pseudonymisierte Nutzerdaten
        sb.append("Profil: ");
        if (user.getFitnessLevel() != null) sb.append("Level=").append(user.getFitnessLevel()).append(", ");
        if (user.getAge() != null) sb.append("Alter=").append(user.getAge()).append(", ");
        sb.append("\n");

        sb.append("Session-RPE: ").append(sessionRpe).append("/10\n");
        if (userNote != null && !userNote.isBlank()) {
            sb.append("Notiz des Trainierenden: ").append(userNote).append("\n");
        }

        sb.append("\nFolgende Anpassungen wurden regelbasiert vorgenommen:\n");
        for (var adj : adjustments) {
            sb.append(String.format("- %s: Gewicht %.1f→%.1f kg, Wdh %d→%d, Sätze %d→%d (%s)\n",
                adj.exerciseName(),
                adj.previousWeight(), adj.newWeight(),
                adj.previousReps(), adj.newReps(),
                adj.previousSets(), adj.newSets(),
                adj.adjustmentReason()));
        }

        sb.append("\nBitte erstelle eine kurze, motivierende und verständliche Erklärung (max 200 Wörter) ");
        sb.append("auf Deutsch, die dem Trainierenden erklärt:\n");
        sb.append("1. Warum diese Anpassungen gemacht wurden\n");
        sb.append("2. Was das für das nächste Training bedeutet\n");
        sb.append("3. Einen kurzen motivierenden Tipp\n");
        sb.append("\nAntworte direkt und persönlich (du-Form). Keine Überschriften oder Aufzählungszeichen.");

        return sb.toString();
    }

    // ===================== OLLAMA (Self-Hosted) =====================

    private String callOllama(String prompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";

        log.info("Sende Anfrage an Ollama: {}, Model: {}", url, aiConfig.getOllama().getModel());

        Map<String, Object> body = Map.of(
            "model", aiConfig.getOllama().getModel(),
            "prompt", prompt,
            "stream", false,
            "options", Map.of(
                "temperature", 0.7,
                "num_predict", 500
            )
        );

        String response = webClient.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .map(map -> (String) map.get("response"))
            .timeout(Duration.ofSeconds(30))
            .block();

        return response;
    }

    // ===================== OPENAI-KOMPATIBLE API =====================

    private String callOpenAi(String prompt) {
        String url = aiConfig.getOpenai().getBaseUrl() + "/chat/completions";

        log.info("Sende Anfrage an OpenAI: {}, Model: {}", url, aiConfig.getOpenai().getModel());

        Map<String, Object> body = Map.of(
            "model", aiConfig.getOpenai().getModel(),
            "messages", List.of(
                Map.of("role", "system", "content",
                    "Du bist ein erfahrener Fitness-Coach. Antworte auf Deutsch, kurz und motivierend."),
                Map.of("role", "user", "content", prompt)
            ),
            "temperature", 0.7,
            "max_tokens", 500
        );

        @SuppressWarnings("unchecked")
        String response = webClient.post()
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
            .timeout(Duration.ofSeconds(30))
            .block();

        return response;
    }

    // ===================== FALLBACK =====================

    /**
     * Standarderklärung, wenn die KI nicht erreichbar ist.
     * Gemäß Proposal: "Sollte das LLM keine valide Antwort liefern,
     * wird ausschließlich die regelbasierte Anpassung mit einer standardisierten Erklärung ausgegeben."
     */
    private String buildFallbackExplanation(int sessionRpe, List<ExerciseAdjustment> adjustments) {
        StringBuilder sb = new StringBuilder();

        if (sessionRpe <= 4) {
            sb.append("Deine letzte Einheit war relativ leicht für dich. ");
            sb.append("Deshalb haben wir die Gewichte und/oder Wiederholungen etwas erhöht, ");
            sb.append("damit du weiterhin Fortschritte machst.");
        } else if (sessionRpe <= 6) {
            sb.append("Die Belastung war moderat. ");
            sb.append("Wir haben die Gewichte leicht gesteigert, um progressive Overload sicherzustellen.");
        } else if (sessionRpe <= 8) {
            sb.append("Du warst im optimalen Trainingsbereich! ");
            sb.append("Die Parameter bleiben weitgehend gleich oder wurden minimal angepasst.");
        } else {
            sb.append("Die letzte Einheit war sehr anstrengend. ");
            sb.append("Wir haben die Belastung etwas reduziert, damit du dich gut erholen kannst ");
            sb.append("und Übertraining vermeidest.");
        }

        sb.append("\n\nAnpassungen im Detail:\n");
        for (var adj : adjustments) {
            if (adj.previousWeight() != adj.newWeight() || adj.previousReps() != adj.newReps()) {
                sb.append(String.format("• %s: %.1f kg → %.1f kg, %d Wdh → %d Wdh\n",
                    adj.exerciseName(),
                    adj.previousWeight(), adj.newWeight(),
                    adj.previousReps(), adj.newReps()));
            }
        }

        return sb.toString();
    }
}
