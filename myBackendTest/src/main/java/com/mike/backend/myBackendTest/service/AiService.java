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
import java.util.*;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    //welcher KI Provider
    private final AiConfig aiConfig;
    //für die HTTP Requests
    private final WebClient webClient;
    //JSON zu parsen
    private final ObjectMapper objectMapper;

    public AiService(AiConfig aiConfig, WebClient webClient, ObjectMapper objectMapper) {
        this.aiConfig     = aiConfig;
        this.webClient    = webClient;
        this.objectMapper = objectMapper;
    }


    // Datenstrukturen


    public record GeneratedDay(String dayName, String focus, List<GeneratedExercise> exercises) {}
    public record GeneratedPlan(String planName, String description, List<GeneratedDay> days) {}
    public record GeneratedExercise(String exerciseName, int sets, int reps,
                                    double weightKg, int restSeconds, int targetRpe,
                                    String description) {}

    // EXERCISE POOL — strukturierte Übungsdatenbank nach Bewegungsmuster

    private static final Map<String, List<String>> EXERCISE_POOL;
    static {
        Map<String, List<String>> pool = new LinkedHashMap<>();
        // ── Unterkörper ───────────────────────────────────────────────────────
        pool.put("squat", List.of(
                "Kniebeuge mit Langhantel", "Front Squat mit Langhantel", "Goblet Squat mit Kurzhantel",
                "Box Squat", "Hackenschmidt-Kniebeuge", "Zercher Kniebeuge", "Sumo Kniebeuge",
                "Pause Kniebeuge", "High Bar Kniebeuge", "Low Bar Kniebeuge",
                "Kniebeuge mit Kettlebell", "Anderson Kniebeuge"));
        pool.put("hinge", List.of(
                "Rumänisches Kreuzheben", "Kreuzheben", "Sumo Kreuzheben", "Hip Thrust mit Langhantel",
                "Good Morning", "Single-Leg RDL", "Trap Bar Kreuzheben", "Stiff-Leg Kreuzheben",
                "45°-Rückenstrecker", "Hyperextension", "Snatch Grip Kreuzheben", "Defizit Kreuzheben"));
        pool.put("lunge", List.of(
                "Ausfallschritte mit Kurzhanteln", "Bulgarische Split Kniebeuge",
                "Ausfallschritte laufend", "Reverse Lunge mit Kurzhanteln",
                "Seitliche Ausfallschritte", "Step-ups auf Kasten",
                "Schrittkniebeuge mit Langhantel", "Walking Lunges mit Kurzhanteln",
                "Einbeinige Kniebeuge auf Kasten", "Reverse Lunge mit Langhantel"));
        pool.put("explosive_legs", List.of(
                "Box Jumps", "Jump Squats", "Broad Jumps", "Depth Jumps",
                "Power Clean", "Hang Clean", "Kettlebell Swing", "Medball Slam",
                "Laterale Sprünge", "Einbeinige Box Jumps", "Bounding", "Trap Bar Jump",
                "Hang Snatch", "Split Jumps"));
        pool.put("quad_machine", List.of(
                "Beinpresse", "Beinstrecker an der Maschine", "Beinpresse enge Fußstellung",
                "Beinpresse weite Fußstellung", "Beinpresse hohe Fußstellung", "Pendulum Kniebeuge"));
        pool.put("hamstring_iso", List.of(
                "Beinbeuger an der Maschine", "Beinbeuger liegend", "Nordische Hamstring Curls",
                "Beinbeuger sitzend", "Stability Ball Leg Curl", "Beinbeuger stehend"));
        pool.put("glute", List.of(
                "Hip Thrust", "Glute Bridge", "Donkey Kicks am Kabelzug",
                "Abduktor Maschine", "Clamshells mit Band", "Cable Kickbacks"));
        pool.put("calf", List.of(
                "Wadenheben stehend", "Wadenheben sitzend", "Einbeiniges Wadenheben",
                "Wadenheben an der Maschine", "Donkey Calf Raises", "Seated Calf Raises",
                "Wadenheben auf Treppe"));
        // ── Drücken / Push ────────────────────────────────────────────────────
        pool.put("horizontal_push", List.of(
                "Bankdrücken mit Langhantel", "Bankdrücken mit Kurzhanteln",
                "Schrägbankdrücken mit Langhantel", "Schrägbankdrücken mit Kurzhanteln",
                "Flachbankdrücken am Kabelzug", "Push-ups auf Ringen",
                "Guillotine Press", "Reverse Grip Bankdrücken",
                "Close Grip Bankdrücken", "Decline Bankdrücken"));
        pool.put("vertical_push", List.of(
                "Schulterdrücken mit Langhantel", "Schulterdrücken mit Kurzhanteln",
                "Arnold Press", "Push Press", "Z-Press sitzend", "Landmine Press",
                "Frontdrücken stehend", "Bradford Press", "Dumbbell Shoulder Press alternierend"));
        pool.put("chest_iso", List.of(
                "Kurzhantel-Fliegenschlagen", "Kabelzug-Fliegenschlagen",
                "Pec Deck Maschine", "Kabelzug Cross-over", "Low-to-High Kabelzug",
                "High-to-Low Kabelzug", "Svend Press"));
        pool.put("dips_push", List.of(
                "Dips", "Trizeps Dips mit Zusatzgewicht", "Ring Dips", "Chest Dips"));
        pool.put("side_delt", List.of(
                "Seitheben mit Kurzhanteln", "Seitheben am Kabelzug",
                "Vorgebeugtes Seitheben", "Maschinen-Seitheben", "Upright Row",
                "Seitheben liegend", "Kabelzug-Seitheben einseitig"));
        pool.put("triceps", List.of(
                "Trizeps Pushdown am Kabelzug", "Overhead Trizeps Extension KH",
                "Schädelbrechter mit Langhantel", "Schädelbrechter mit Kurzhanteln",
                "Kickbacks mit Kurzhantel", "Diamond Push-ups",
                "Trizeps Pushdown eng", "Trizeps-Extension am Kabelzug überkopf",
                "JM Press", "Nahgriff Bankdrücken"));
        // ── Ziehen / Pull ─────────────────────────────────────────────────────
        pool.put("vertical_pull", List.of(
                "Klimmzüge", "Klimmzüge mit Zusatzgewicht", "Latzug am Kabelzug",
                "Latzug eng Griff", "Latzug weit Griff", "Latzug Untergriff",
                "Straight-Arm Pulldown", "Kneeling Pulldown", "Ringklimmzüge",
                "Assistierte Klimmzüge", "Latzug Untergriff eng"));
        pool.put("horizontal_pull", List.of(
                "Langhantelrudern", "Einarmiges Kurzhantelrudern", "Seilzug-Rudern sitzend",
                "T-Bar Rudern", "Maschinenrudern", "Pendlay Rudern", "Meadows Row",
                "Kabelzug-Rudern weit", "Chest Supported Row", "Seal Row"));
        pool.put("rear_delt", List.of(
                "Face Pulls am Kabelzug", "Vorgebeugtes Seitheben", "Reverse Flyes",
                "Band Pull-Aparts", "Rear Delt Maschine", "W-Raises", "Y-T-W Übung"));
        pool.put("biceps", List.of(
                "Bizeps-Curl mit Langhantel", "Bizeps-Curl mit Kurzhanteln",
                "Hammer Curls mit Kurzhanteln", "Konzentrations Curls",
                "Kabelzug-Curl", "Reverse Curls", "Preacher Curl",
                "Spider Curls", "Incline Dumbbell Curl", "Zottman Curl"));
        // ── Core ──────────────────────────────────────────────────────────────
        pool.put("core_stability", List.of(
                "Plank", "Ab Wheel Rollout", "Dead Bug", "Hollow Hold",
                "Pallof Press", "Suitcase Carry", "Farmers Walk"));
        pool.put("core_flexion", List.of(
                "Crunches", "Sit-ups", "Kabelzug-Crunch", "Beinheben hängend",
                "V-Ups", "Toe Touches", "Dragon Flag"));
        pool.put("core_rotation", List.of(
                "Russian Twists", "Woodchopper am Kabelzug", "Landmine Rotation",
                "Medball Rotational Throw", "Anti-Rotation Press"));
        EXERCISE_POOL = Collections.unmodifiableMap(pool);
    }

    private static final List<Map<String, Object>> EXPLOSIVE_EXERCISE_CONFIG;
    static {
        EXPLOSIVE_EXERCISE_CONFIG = List.of(
                Map.of("name", "Box Jumps",           "sets", 4, "reps", 5, "rest", 120, "rpe", 8, "weight", 0.0),
                Map.of("name", "Jump Squats",          "sets", 4, "reps", 6, "rest", 90,  "rpe", 8, "weight", 0.0),
                Map.of("name", "Broad Jumps",          "sets", 3, "reps", 5, "rest", 120, "rpe", 8, "weight", 0.0),
                Map.of("name", "Depth Jumps",          "sets", 4, "reps", 5, "rest", 120, "rpe", 9, "weight", 0.0),
                Map.of("name", "Kettlebell Swing",     "sets", 4, "reps", 8, "rest", 90,  "rpe", 8, "weight", 24.0),
                Map.of("name", "Medball Slam",         "sets", 4, "reps", 6, "rest", 90,  "rpe", 8, "weight", 0.0),
                Map.of("name", "Laterale Sprünge",     "sets", 3, "reps", 8, "rest", 90,  "rpe", 7, "weight", 0.0),
                Map.of("name", "Einbeinige Box Jumps", "sets", 3, "reps", 5, "rest", 120, "rpe", 8, "weight", 0.0),
                Map.of("name", "Bounding",             "sets", 3, "reps", 6, "rest", 120, "rpe", 8, "weight", 0.0),
                Map.of("name", "Split Jumps",          "sets", 4, "reps", 6, "rest", 90,  "rpe", 8, "weight", 0.0),
                Map.of("name", "Power Clean",          "sets", 4, "reps", 4, "rest", 150, "rpe", 9, "weight", 60.0),
                Map.of("name", "Hang Clean",           "sets", 4, "reps", 4, "rest", 150, "rpe", 9, "weight", 55.0)
        );
    }


    // METHODE 1: Erklärung nach Feedback

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

    // METHODE 2: Trainingsplan generieren


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
                    GeneratedPlan reordered = reorderGeneratedPlanByFocus(parsed, request);
                    // ── NEU: Post-Processing — Duplikate beheben & Qualität sichern ──
                    GeneratedPlan validated = validateAndEnrichPlan(reordered, request, user);
                    long total = validated.days().stream().mapToLong(d -> d.exercises().size()).sum();
                    log.info("KI-Plan: '{}' mit {} Tagen, {} Übungen", validated.planName(), validated.days().size(), total);
                    return validated;
                }
            }
            log.warn("KI lieferte keinen verwertbaren Plan → Fallback");
        } catch (Exception e) {
            log.error("Fehler bei KI-Plan-Generierung: {}", e.getMessage());
        }
        return buildFallbackPlan(request);
    }

    // =========================================================================
    // POST-PROCESSING: Qualitätsvalidierung & Duplikat-Behebung
    // =========================================================================

    /**
     * Validiert und bereichert den generierten Plan:
     *  0. Mobilitätsblock vom Rest trennen (wird separat behandelt)
     *  1. Trainingstage auf maximal numDayPlans kappen (verhindert 4+ Tage)
     *  2. Duplikate über Tage hinweg erkennen & durch Pool-Alternativen ersetzen
     *  3. Explosive Übung auf Bein-Tagen sicherstellen (INTERMEDIATE/ADVANCED)
     *  4. Übungsanzahl pro Tag prüfen & ggf. trimmen
     *  5. Mobilitätsblock garantiert anhängen wenn gewünscht — mit Qualitätsprüfung
     */
    private GeneratedPlan validateAndEnrichPlan(GeneratedPlan plan,
                                                GeneratePlanRequest request,
                                                AppUser user) {
        if (plan == null || plan.days().isEmpty()) return plan;

        // ── Schritt 0: Mobilitätsblock vom Rest trennen ──────────────────────
        List<GeneratedDay> trainingDays = plan.days().stream()
                .filter(d -> !"Mobilitätsblock".equalsIgnoreCase(d.dayName()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        GeneratedDay kiMobilityDay = plan.days().stream()
                .filter(d -> "Mobilitätsblock".equalsIgnoreCase(d.dayName()))
                .findFirst().orElse(null);

        // ── Schritt 1: Trainingstage auf numDayPlans kappen ─────────────────
        int numDayPlans = calcNumDayPlans(request.daysPerWeek() != null ? request.daysPerWeek() : 3);
        if (trainingDays.size() > numDayPlans) {
            log.warn("KI hat {} Trainingstage geliefert — kürze auf {}", trainingDays.size(), numDayPlans);
            trainingDays = new ArrayList<>(trainingDays.subList(0, numDayPlans));
        }

        // ── Schritt 2: Duplikat-Bereinigung (nur Trainingstage) ──────────────
        GeneratedPlan trimmed = new GeneratedPlan(plan.planName(), plan.description(), trainingDays);
        GeneratedPlan result  = deduplicateAcrossDays(trimmed);

        // ── Schritt 3: Explosive Übungen sicherstellen ───────────────────────
        String level = resolveRawLevel(request, user);
        if ("INTERMEDIATE".equals(level) || "ADVANCED".equals(level)) {
            result = ensureExplosiveOnLegDays(result);
        }

        // ── Schritt 4: Übungsanzahl pro Tag prüfen ───────────────────────────
        result = ensureExerciseCount(result, request, user);

        // ── Schritt 5: Mobilitätsblock als Hybrid einfügen ─────────────────
        List<GeneratedDay> finalDays = new ArrayList<>(result.days());
        if (Boolean.TRUE.equals(request.includeMobilityPlan())) {
            GeneratedDay hybridMobilityDay = buildHybridMobilityDay(kiMobilityDay);
            finalDays.add(hybridMobilityDay);
            log.info("Mobilitätsblock als Hybrid erstellt (3 Fallback-Basisübungen + bis zu 3 KI-Übungen)");
        }

        return new GeneratedPlan(result.planName(), result.description(), finalDays);
    }

    /**
     * Erstellt den finalen Mobilitätsblock als Hybrid:
     *  - 3 kuratierte Fallback-Übungen (verlässliche Basis)
     *  - 3 qualitative KI-Übungen (individualisierte Ergänzung)
     *
     * Wenn die KI weniger als 3 brauchbare Mobility-Übungen liefert,
     * werden die fehlenden Slots mit weiteren kuratierten Fallback-Übungen aufgefüllt.
     */
    private GeneratedDay buildHybridMobilityDay(GeneratedDay kiMobilityDay) {
        List<GeneratedExercise> finalExercises = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();

        for (GeneratedExercise fallback : buildFallbackMobilityFoundationExercises()) {
            finalExercises.add(markAsFallbackMobility(fallback));
            usedNames.add(normalizeMobilityExerciseName(fallback.exerciseName()));
        }

        List<GeneratedExercise> kiExercises = extractQualitativeKiMobilityExercises(kiMobilityDay);
        int addedKi = 0;
        for (GeneratedExercise exercise : kiExercises) {
            if (addedKi >= 3) break;
            String normalized = normalizeMobilityExerciseName(exercise.exerciseName());
            if (usedNames.contains(normalized)) continue;
            finalExercises.add(markAsKiMobility(exercise));
            usedNames.add(normalized);
            addedKi++;
        }

        if (addedKi < 3) {
            log.warn("KI lieferte nur {} qualitative Mobility-Übungen — ergänze mit Fallback", addedKi);
            for (GeneratedExercise fallback : buildFallbackMobilityReserveExercises()) {
                if (finalExercises.size() >= 6) break;
                String normalized = normalizeMobilityExerciseName(fallback.exerciseName());
                if (usedNames.contains(normalized)) continue;
                finalExercises.add(markAsFallbackMobility(fallback));
                usedNames.add(normalized);
            }
        }

        return new GeneratedDay("Mobilitätsblock", "Mobility & Dehnung",
                finalExercises.subList(0, Math.min(6, finalExercises.size())));
    }

    /**
     * Gibt true zurück wenn der Übungsname typisch für ein Krafttraining ist
     * und NICHT in einen Mobilitätsblock gehört.
     */
    private boolean isMobilityBlockStrengthExercise(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("kniebeuge")
                || n.contains("bankdrücken")
                || n.contains("kreuzheben")
                || n.contains("klimmzüge")
                || n.contains("rudern")
                || n.contains("schulterdrücken")
                || n.contains("beinpresse")
                || n.contains("beinstrecker")
                || n.contains("beinbeuger")
                || n.contains("seitheben")
                || n.contains("trizeps")
                || n.contains("bizeps")
                || n.contains("curl")
                || n.contains("press")
                || n.contains("squat")
                || n.contains("deadlift")
                || n.contains("bench")
                || n.contains("mit gewicht")
                || n.contains("langhantel")
                || n.contains("kurzhantel")
                || n.contains("kabelzug")
                || n.contains("maschine");
    }

    private List<GeneratedExercise> extractQualitativeKiMobilityExercises(GeneratedDay kiMobilityDay) {
        if (kiMobilityDay == null || kiMobilityDay.exercises() == null || kiMobilityDay.exercises().isEmpty()) {
            return Collections.emptyList();
        }

        List<GeneratedExercise> result = new ArrayList<>();
        for (GeneratedExercise exercise : kiMobilityDay.exercises()) {
            if (!isQualitativeMobilityExercise(exercise)) {
                log.debug("KI-Mobility verworfen: '{}'", exercise.exerciseName());
                continue;
            }
            result.add(normalizeKiMobilityExercise(exercise));
        }
        return result;
    }

    private boolean isQualitativeMobilityExercise(GeneratedExercise exercise) {
        if (exercise == null || exercise.exerciseName() == null || exercise.exerciseName().isBlank()) return false;

        String normalizedName = normalizeMobilityExerciseName(exercise.exerciseName());
        if (normalizedName.isBlank()) return false;
        if (isMobilityBlockStrengthExercise(exercise.exerciseName())) return false;
        if (!looksLikeRealMobilityExercise(exercise.exerciseName())) return false;
        if (exercise.weightKg() > 0.0) return false;
        if (exercise.sets() > 3) return false;
        if (exercise.targetRpe() > 4) return false;

        return true;
    }

    private boolean looksLikeRealMobilityExercise(String name) {
        if (name == null) return false;
        String n = name.toLowerCase().trim();

        if (n.equals("mobilitätsübung") || n.equals("stretching") || n.equals("mobility")) return false;

        return n.contains("stretch")
                || n.contains("rotation")
                || n.contains("pose")
                || n.contains("hold")
                || n.contains("cat-cow")
                || n.contains("cat cow")
                || n.contains("90/90")
                || n.contains("thoracic")
                || n.contains("ankle")
                || n.contains("hip")
                || n.contains("couch")
                || n.contains("pigeon")
                || n.contains("thread the needle")
                || n.contains("adductor rock back")
                || n.contains("rock back")
                || n.contains("deep squat")
                || n.contains("child")
                || n.contains("world's greatest")
                || n.contains("worlds greatest")
                || n.contains("wall drill")
                || n.contains("mobilisation")
                || n.contains("mobilization");
    }

    private GeneratedExercise normalizeKiMobilityExercise(GeneratedExercise exercise) {
        int normalizedSets = clamp(exercise.sets(), 1, 2);
        int normalizedReps = clamp(exercise.reps(), 6, 60);
        String description = buildHighQualityMobilityDescription(exercise.exerciseName(), exercise.description());

        return new GeneratedExercise(
                cleanMobilityExerciseName(exercise.exerciseName()),
                normalizedSets,
                normalizedReps,
                0.0,
                30,
                3,
                description
        );
    }

    private GeneratedExercise markAsFallbackMobility(GeneratedExercise exercise) {
        return new GeneratedExercise(
                exercise.exerciseName(),
                exercise.sets(),
                exercise.reps(),
                0.0,
                30,
                3,
                buildHighQualityMobilityDescription(exercise.exerciseName(), exercise.description())
        );
    }

    private GeneratedExercise markAsKiMobility(GeneratedExercise exercise) {
        return new GeneratedExercise(
                exercise.exerciseName(),
                exercise.sets(),
                exercise.reps(),
                0.0,
                30,
                3,
                buildHighQualityMobilityDescription(exercise.exerciseName(), exercise.description())
        );
    }

    private String cleanMobilityExerciseName(String name) {
        if (name == null) return "Mobility-Übung";
        return name.trim().replaceAll("\\s+", " ");
    }

    private String normalizeMobilityExerciseName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replace("’", "'")
                .replaceAll("[^a-z0-9äöüß/ ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildHighQualityMobilityDescription(String exerciseName, String aiDescription) {
        String curated = getCuratedMobilityDescription(exerciseName);
        if (curated != null) return curated;

        if (aiDescription != null && aiDescription.trim().length() >= 90) {
            String normalized = aiDescription.trim().replaceAll("\\s+", " ");
            if (!normalized.endsWith(".")) normalized += ".";
            return normalized;
        }

        return "Starte kontrolliert in einer stabilen Ausgangsposition und richte den Oberkörper aktiv auf. "
                + "Bewege dich langsam in die Endposition, bis du eine deutliche, aber angenehme Dehnung oder Mobilisation spürst. "
                + "Halte die Spannung ruhig, atme gleichmäßig weiter und vermeide Ausweichbewegungen oder Schwung.";
    }

    private String getCuratedMobilityDescription(String exerciseName) {
        if (exerciseName == null) return null;
        String n = exerciseName.toLowerCase();

        if (n.contains("hip flexor stretch")) {
            return "Gehe in einen halben Kniestand, ein Fuß steht vorne stabil auf dem Boden, das hintere Knie liegt weich auf. "
                    + "Spanne Gesäß und Bauch an und schiebe die Hüfte langsam nach vorne, bis du die Dehnung deutlich an der Vorderseite der Hüfte des hinteren Beins spürst. "
                    + "Der Oberkörper bleibt aufrecht, das Hohlkreuz wird vermieden und die Bewegung erfolgt ruhig ohne Wippen.";
        }
        if (n.contains("thoracic rotation")) {
            return "Gehe in den Vierfüßlerstand oder in die Seitlage mit stabiler Beckenposition. "
                    + "Führe den oberen Arm langsam auf und rotiere die Brustwirbelsäule kontrolliert, ohne dass die Hüfte mitdreht. "
                    + "Der Blick folgt der Hand, die Atmung bleibt ruhig und die Bewegung kommt bewusst aus dem oberen Rücken.";
        }
        if (n.contains("world's greatest stretch") || n.contains("worlds greatest stretch")) {
            return "Mache einen großen Ausfallschritt nach vorne und platziere beide Hände kontrolliert neben dem vorderen Fuß. "
                    + "Senke die Hüfte leicht ab, öffne anschließend den Oberkörper zur vorderen Seite und strecke den oberen Arm aktiv zur Decke. "
                    + "Die Ferse des vorderen Fußes bleibt stabil am Boden und die Rotation kommt aus Brustwirbelsäule und Hüfte, nicht aus Schwung.";
        }
        if (n.contains("cat-cow") || n.contains("cat cow")) {
            return "Starte im Vierfüßlerstand mit Händen unter den Schultern und Knien unter der Hüfte. "
                    + "Beim Einatmen ziehst du das Brustbein nach vorne und lässt den Rücken kontrolliert in die Streckung gehen, beim Ausatmen rundest du die Wirbelsäule Wirbel für Wirbel nach oben. "
                    + "Die Bewegung soll fließend sein und bewusst über die ganze Wirbelsäule stattfinden, ohne ins Hohlkreuz zu fallen.";
        }
        if (n.contains("90/90 hip stretch")) {
            return "Setze dich aufrecht in die 90/90-Position, wobei beide Knie etwa im rechten Winkel gebeugt sind. "
                    + "Richte den Oberkörper lang auf und lehne dich langsam über das vordere Schienbein oder rotiere kontrolliert zur anderen Seite. "
                    + "Das Becken bleibt möglichst stabil am Boden und die Dehnung soll tief in Hüfte und Gesäß spürbar sein, ohne dass du einsackst.";
        }
        if (n.contains("pigeon pose")) {
            return "Bringe aus dem Vierfüßlerstand ein Knie nach vorne und lege den Unterschenkel so ab, wie es deine Hüftbeweglichkeit zulässt. "
                    + "Schiebe das hintere Bein lang nach hinten und senke den Oberkörper langsam über das vordere Bein ab. "
                    + "Achte darauf, dass die Hüfte möglichst gerade bleibt und du die Position ruhig hältst, statt in die Dehnung hineinzudrücken.";
        }
        if (n.contains("couch stretch")) {
            return "Positioniere ein Knie nahe an einer Wand oder Bank, das andere Bein steht vorne im halben Kniestand. "
                    + "Richte den Oberkörper langsam auf und spanne Gesäß sowie Bauch aktiv an, damit die Dehnung an der Vorderseite von Oberschenkel und Hüfte zunimmt. "
                    + "Bleibe lang im Oberkörper und vermeide es, ins Hohlkreuz auszuweichen oder die Spannung zu verlieren.";
        }
        if (n.contains("deep squat hold")) {
            return "Gehe kontrolliert in eine tiefe Kniebeuge, die Füße bleiben stabil vollflächig am Boden. "
                    + "Drücke die Knie leicht nach außen, richte die Brust auf und halte die Position aktiv, statt passiv einzusacken. "
                    + "Die Fersen bleiben unten und die Spannung in Rumpf und Hüfte sorgt dafür, dass die Haltung sauber und ruhig bleibt.";
        }
        if (n.contains("doorway chest stretch")) {
            return "Stelle dich in einen Türrahmen und platziere Unterarm und Ellenbogen etwa auf Schulterhöhe am Rahmen. "
                    + "Gehe langsam mit dem Oberkörper oder dem gegenüberliegenden Bein nach vorne, bis du die Dehnung klar in Brust und vorderer Schulter spürst. "
                    + "Die Schulter bleibt tief und entspannt, du weichst nicht ins Hohlkreuz aus und gehst nicht ruckartig in die Endposition.";
        }
        if (n.contains("thread the needle")) {
            return "Starte im Vierfüßlerstand und schiebe einen Arm langsam unter dem Körper nach innen durch. "
                    + "Lege Schulter und Kopf kontrolliert ab und rotiere den Oberkörper sanft, während die andere Hand stabil unterstützt oder nach vorne greift. "
                    + "Die Bewegung bleibt ruhig und kommt aus Brustwirbelsäule und Schultergürtel, nicht aus Schwung.";
        }
        if (n.contains("ankle dorsiflexion wall drill")) {
            return "Stelle einen Fuß einige Zentimeter vor eine Wand und halte die Ferse vollständig am Boden. "
                    + "Schiebe das Knie langsam nach vorne in Richtung Wand, ohne dass der Fuß einknickt oder die Ferse abhebt. "
                    + "Arbeite kontrolliert in den schmerzfreien Bewegungsbereich und halte die Fußachse stabil über dem Mittelfuß.";
        }
        if (n.contains("adductor rock back")) {
            return "Gehe in den Vierfüßlerstand und strecke ein Bein seitlich aus, der Fuß bleibt flach oder leicht aufgestellt. "
                    + "Schiebe die Hüfte langsam nach hinten, bis du die Dehnung an der Innenseite des ausgestreckten Beins spürst. "
                    + "Der Rücken bleibt neutral, das Becken kontrolliert und die Bewegung erfolgt ruhig ohne Einrollen der Wirbelsäule.";
        }
        return null;
    }

    private List<GeneratedExercise> buildFallbackMobilityFoundationExercises() {
        return List.of(
                new GeneratedExercise("World's Greatest Stretch", 2, 8, 0.0, 30, 3,
                        "Großer Ausfallschritt, Hüfte senken und Oberkörper kontrolliert aufdrehen."),
                new GeneratedExercise("90/90 Hip Stretch", 2, 45, 0.0, 30, 3,
                        "Aufrecht in der 90/90-Position sitzen und die Hüfte kontrolliert mobilisieren."),
                new GeneratedExercise("Thoracic Rotation", 2, 10, 0.0, 30, 3,
                        "Brustwirbelsäule kontrolliert rotieren, während die Hüfte stabil bleibt.")
        );
    }

    private List<GeneratedExercise> buildFallbackMobilityReserveExercises() {
        return List.of(
                new GeneratedExercise("Hip Flexor Stretch", 2, 45, 0.0, 30, 3,
                        "Halbkniestand, Hüfte sanft nach vorne schieben und Gesäß anspannen."),
                new GeneratedExercise("Cat-Cow", 2, 15, 0.0, 30, 3,
                        "Wirbelsäule im Vierfüßlerstand fließend beugen und strecken."),
                new GeneratedExercise("Pigeon Pose", 2, 45, 0.0, 30, 3,
                        "Hüfte und Gesäß in einer ruhigen, kontrollierten Position öffnen."),
                new GeneratedExercise("Couch Stretch", 2, 45, 0.0, 30, 3,
                        "Vorderseite von Hüfte und Oberschenkel gezielt aufdehnen."),
                new GeneratedExercise("Ankle Dorsiflexion Wall Drill", 2, 12, 0.0, 30, 3,
                        "Sprunggelenk kontrolliert nach vorne mobilisieren, Ferse bleibt unten."),
                new GeneratedExercise("Thread the Needle", 2, 10, 0.0, 30, 3,
                        "Brustwirbelsäule und Schultergürtel kontrolliert aufdrehen.")
        );
    }

    // ── 1. Duplikat-Erkennung & Ersetzung ────────────────────────────────────

    /**
     * Geht alle Trainingstage sequenziell durch.
     * Jede Übung, die in einem früheren Tag bereits vorkommt, wird durch eine
     * Pool-Alternative aus derselben Bewegungsmuster-Kategorie ersetzt.
     */
    private GeneratedPlan deduplicateAcrossDays(GeneratedPlan plan) {
        Set<String> usedNormalized = new LinkedHashSet<>();
        List<GeneratedDay> fixedDays = new ArrayList<>();
        int duplicatesFixed = 0;

        for (GeneratedDay day : plan.days()) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                fixedDays.add(day);
                continue;
            }

            List<GeneratedExercise> fixedExercises = new ArrayList<>();
            for (GeneratedExercise exercise : day.exercises()) {
                String normalized = normalizeExerciseName(exercise.exerciseName());
                if (usedNormalized.contains(normalized)) {
                    String alternative = findAlternativeExercise(exercise.exerciseName(), usedNormalized);
                    if (alternative != null) {
                        log.info("Duplikat '{}' in {} → ersetzt durch '{}'",
                                exercise.exerciseName(), day.dayName(), alternative);
                        fixedExercises.add(new GeneratedExercise(
                                alternative, exercise.sets(), exercise.reps(),
                                exercise.weightKg(), exercise.restSeconds(),
                                exercise.targetRpe(), exercise.description()));
                        usedNormalized.add(normalizeExerciseName(alternative));
                        duplicatesFixed++;
                    } else {
                        // Kein Ersatz gefunden → Übung trotzdem behalten
                        fixedExercises.add(exercise);
                        log.warn("Kein Pool-Ersatz für Duplikat '{}' gefunden — behalte Original", exercise.exerciseName());
                    }
                } else {
                    fixedExercises.add(exercise);
                    usedNormalized.add(normalized);
                }
            }
            fixedDays.add(new GeneratedDay(day.dayName(), day.focus(), fixedExercises));
        }

        if (duplicatesFixed > 0) log.info("Post-Processing: {} Duplikat(e) behoben", duplicatesFixed);
        return new GeneratedPlan(plan.planName(), plan.description(), fixedDays);
    }

    // ── 2. Explosive Pflicht auf Bein-Tagen ──────────────────────────────────

    /**
     * Stellt sicher, dass Bein-Tage bei INTERMEDIATE/ADVANCED eine explosive Übung
     * als ERSTE Übung besitzen.
     */
    private GeneratedPlan ensureExplosiveOnLegDays(GeneratedPlan plan) {
        List<GeneratedDay> days = new ArrayList<>();
        // Track schon verwendete explosive Übungen über alle Tage
        Set<String> usedExplosive = new LinkedHashSet<>();

        for (GeneratedDay day : plan.days()) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                days.add(day);
                continue;
            }
            if (!isLegDay(day)) {
                days.add(day);
                continue;
            }

            List<GeneratedExercise> exercises = new ArrayList<>(day.exercises());

            // Bereits vorhandene explosive Übungen im Tag erfassen
            int explosiveIndex = -1;
            for (int i = 0; i < exercises.size(); i++) {
                if ("explosive_legs".equals(findMovementCategory(exercises.get(i).exerciseName()))) {
                    usedExplosive.add(normalizeExerciseName(exercises.get(i).exerciseName()));
                    explosiveIndex = i;
                    break;
                }
            }

            if (explosiveIndex < 0) {
                // Keine explosive Übung → rotierende Auswahl, noch nicht verwendet
                GeneratedExercise explosive = pickRotatingExplosive(usedExplosive, plan.planName());
                usedExplosive.add(normalizeExerciseName(explosive.exerciseName()));
                exercises.add(0, explosive);
                if (exercises.size() > 8) exercises.remove(exercises.size() - 1);
                log.info("Explosive Übung '{}' auf Bein-Tag '{}' ergänzt", explosive.exerciseName(), day.dayName());
            } else if (explosiveIndex > 0) {
                GeneratedExercise explosive = exercises.remove(explosiveIndex);
                exercises.add(0, explosive);
                log.info("Explosive Übung '{}' → Position 1 in '{}'", explosive.exerciseName(), day.dayName());
            }

            days.add(new GeneratedDay(day.dayName(), day.focus(), exercises));
        }
        return new GeneratedPlan(plan.planName(), plan.description(), days);
    }

    private GeneratedExercise pickRotatingExplosive(Set<String> alreadyUsed, String planName) {
        int seed = Math.abs(planName != null ? planName.hashCode() : 0)
                + (int)(System.currentTimeMillis() % 1000);

        for (int offset = 0; offset < EXPLOSIVE_EXERCISE_CONFIG.size(); offset++) {
            int idx = (seed + offset) % EXPLOSIVE_EXERCISE_CONFIG.size();
            Map<String, Object> cfg = EXPLOSIVE_EXERCISE_CONFIG.get(idx);
            String name = (String) cfg.get("name");
            if (!alreadyUsed.contains(normalizeExerciseName(name))) {
                return new GeneratedExercise(
                        name,
                        (int) cfg.get("sets"),
                        (int) cfg.get("reps"),
                        (double) cfg.get("weight"),
                        (int) cfg.get("rest"),
                        (int) cfg.get("rpe"),
                        ""
                );
            }
        }
        // Fallback wenn alle schon verwendet
        Map<String, Object> cfg = EXPLOSIVE_EXERCISE_CONFIG.get(seed % EXPLOSIVE_EXERCISE_CONFIG.size());
        return new GeneratedExercise((String)cfg.get("name"), (int)cfg.get("sets"),
                (int)cfg.get("reps"), (double)cfg.get("weight"),
                (int)cfg.get("rest"), (int)cfg.get("rpe"), "");
    }


    // ── 3. Übungsanzahl sicherstellen ─────────────────────────────────────────

    private GeneratedPlan ensureExerciseCount(GeneratedPlan plan, GeneratePlanRequest request, AppUser user) {
        String level = resolveRawLevel(request, user);
        int expected = calcExercisesPerDayWithDuration(level, request.sessionDurationMinutes());

        List<GeneratedDay> days = new ArrayList<>();
        for (GeneratedDay day : plan.days()) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                days.add(day);
                continue;
            }
            List<GeneratedExercise> exercises = new ArrayList<>(day.exercises());
            if (exercises.size() > expected + 1) {
                log.info("Tag '{}': {} Übungen → trimme auf {}", day.dayName(), exercises.size(), expected);
                exercises = exercises.subList(0, expected);
            }
            days.add(new GeneratedDay(day.dayName(), day.focus(), exercises));
        }
        return new GeneratedPlan(plan.planName(), plan.description(), days);
    }

    // =========================================================================
    // EXERCISE POOL: Kategorie-Erkennung & Alternative finden
    // =========================================================================

    /**
     * Normalisiert einen Übungsnamen auf seinen Kern-Bewegungstyp.
     * Z.B.: "Kniebeuge mit Langhantel" → "kniebeuge",
     *       "Rumänisches Kreuzheben" → "rdl"
     */
    private String normalizeExerciseName(String name) {
        if (name == null) return "";
        String n = name.toLowerCase().trim();

        // Kern-Bewegungen mappen (Reihenfolge wichtig: spezifischere zuerst)
        if (n.contains("rumänisch") || n.contains("romanian") || (n.contains("rdl") && n.contains("single"))) return "rdl";
        if (n.contains("hip thrust") || n.contains("glute bridge")) return "hip_thrust";
        if (n.contains("bulgarisch") || n.contains("split kniebeuge")) return "split_squat";
        if (n.contains("kniebeuge") || n.contains("squat") || n.contains("goblet")) return "kniebeuge";
        if (n.contains("kreuzheben") || n.contains("deadlift")) return "kreuzheben";
        if (n.contains("good morning")) return "good_morning";
        if (n.contains("ausfallschritt") || n.contains("lunge") || n.contains("step-up")) return "ausfallschritte";
        if (n.contains("beinpresse")) return "beinpresse";
        if (n.contains("beinstrecker")) return "beinstrecker";
        if (n.contains("beinbeuger") || n.contains("hamstring curl") || n.contains("leg curl")) return "beinbeuger";
        if (n.contains("nordisch")) return "nordische_hamstring";
        if (n.contains("wadenheben") || n.contains("calf raise")) return "wadenheben";
        if (n.contains("box jump")) return "box_jumps";
        if (n.contains("jump squat")) return "jump_squats";
        if (n.contains("broad jump")) return "broad_jumps";
        if (n.contains("depth jump")) return "depth_jumps";
        if (n.contains("power clean")) return "power_clean";
        if (n.contains("hang clean")) return "hang_clean";
        if (n.contains("kettlebell swing")) return "kettlebell_swing";
        if (n.contains("medball")) return "medball";
        if (n.contains("schrägbankdrücken") || n.contains("incline bench")) return "schrägbankdrücken";
        if (n.contains("bankdrücken") || n.contains("bench press")) return "bankdrücken";
        if (n.contains("schulterdrücken") || n.contains("military press") || n.contains("overhead press") || n.contains("arnold")) return "schulterdrücken";
        if (n.contains("push press") || n.contains("z-press") || n.contains("landmine press")) return "push_press_variation";
        if (n.contains("dips") && !n.contains("trizeps")) return "dips";
        if (n.contains("fliegenschlagen") || n.contains("flyes") || n.contains("pec deck") || n.contains("cross-over")) return "fliegenschlagen";
        if (n.contains("seitheben")) return "seitheben";
        if (n.contains("upright row")) return "upright_row";
        if (n.contains("schädelbrechter") || n.contains("skull crusher")) return "schädelbrechter";
        if (n.contains("trizeps") || n.contains("tricep")) return "trizeps_isolation";
        if (n.contains("klimmzüge") || n.contains("pull-up") || n.contains("pullup") || n.contains("chin-up")) return "klimmzüge";
        if (n.contains("latzug") || n.contains("lat pulldown")) return "latzug";
        if (n.contains("langhantelrudern") || n.contains("pendlay") || n.contains("t-bar")) return "langhantelrudern";
        if (n.contains("einarmig") && n.contains("rudern")) return "einarmiges_rudern";
        if (n.contains("seilzug") && n.contains("rudern")) return "seilzug_rudern";
        if (n.contains("maschinenrudern") || n.contains("chest supported row") || n.contains("seal row")) return "maschinenrudern";
        if (n.contains("face pull")) return "face_pulls";
        if (n.contains("reverse fly") || n.contains("vorgebeugt") && n.contains("seitheben")) return "rear_delt_fly";
        if (n.contains("band pull")) return "band_pull_apart";
        if (n.contains("hammer curl")) return "hammer_curls";
        if (n.contains("preacher") || n.contains("spider curl") || n.contains("konzentration")) return "isolation_curl";
        if (n.contains("reverse curl")) return "reverse_curls";
        if (n.contains("bizeps") || n.contains("curl")) return "bizeps_curl";
        if (n.contains("plank")) return "plank";
        if (n.contains("ab wheel") || n.contains("rollout")) return "ab_rollout";
        if (n.contains("dead bug") || n.contains("hollow hold")) return "core_antiextension";
        if (n.contains("crunch")) return "crunch";
        if (n.contains("sit-up") || n.contains("sit up")) return "sit_up";
        if (n.contains("beinheben")) return "beinheben";
        if (n.contains("russian twist")) return "russian_twist";
        if (n.contains("woodchopper") || n.contains("rotation")) return "core_rotation";
        return n.replaceAll("\\s+(mit|am|an der|mit dem|auf dem|auf der)\\s+.*", "").trim();
    }

    /**
     * Ordnet eine Übung einer Bewegungsmuster-Kategorie im EXERCISE_POOL zu.
     */
    private String findMovementCategory(String exerciseName) {
        if (exerciseName == null) return null;
        String n = exerciseName.toLowerCase();

        // Reihenfolge: spezifisch → allgemein
        if (n.contains("box jump") || n.contains("jump squat") || n.contains("broad jump") ||
                n.contains("depth jump") || n.contains("power clean") || n.contains("hang clean") ||
                n.contains("kettlebell swing") || n.contains("medball") || n.contains("bounding") ||
                n.contains("hang snatch") || n.contains("split jump") || n.contains("trap bar jump"))
            return "explosive_legs";
        if (n.contains("bulgarisch") || n.contains("split kniebeuge") ||
                n.contains("ausfallschritt") || n.contains("lunge") || n.contains("step-up"))
            return "lunge";
        if ((n.contains("kniebeuge") || n.contains("squat") || n.contains("goblet") || n.contains("hackenschmidt"))
                && !n.contains("jump") && !n.contains("split"))
            return "squat";
        if (n.contains("rumänisch") || n.contains("hip thrust") || n.contains("good morning") ||
                n.contains("single-leg rdl") || (n.contains("kreuzheben") && !n.contains("sumo")) ||
                n.contains("rückenstrecker") || n.contains("hyperextension"))
            return "hinge";
        if (n.contains("sumo kreuzheben") || n.contains("trap bar kreuzheben"))
            return "hinge";
        if (n.contains("beinpresse") || n.contains("beinstrecker"))
            return "quad_machine";
        if (n.contains("beinbeuger") || n.contains("nordisch") || n.contains("leg curl"))
            return "hamstring_iso";
        if (n.contains("hip thrust") || n.contains("glute bridge") || n.contains("donkey kick") ||
                n.contains("abduktor") || n.contains("kickback") && !n.contains("trizeps") || n.contains("clamshell"))
            return "glute";
        if (n.contains("wadenheben") || n.contains("calf raise") || n.contains("donkey calf"))
            return "calf";
        if (n.contains("schrägbankdrücken") || n.contains("incline"))
            return "horizontal_push";
        if ((n.contains("bankdrücken") || n.contains("bench press")) && !n.contains("schulter"))
            return "horizontal_push";
        if (n.contains("dips") && !n.contains("trizeps"))
            return "dips_push";
        if (n.contains("schulterdrücken") || n.contains("military press") || n.contains("overhead press") ||
                n.contains("arnold press") || n.contains("push press") || n.contains("z-press") ||
                n.contains("frontdrücken") || n.contains("landmine press"))
            return "vertical_push";
        if (n.contains("fliegenschlagen") || n.contains("flyes") || n.contains("pec deck") || n.contains("cross-over"))
            return "chest_iso";
        if (n.contains("seitheben") || n.contains("upright row"))
            return "side_delt";
        if (n.contains("schädelbrechter") || n.contains("skull crusher") || n.contains("trizeps") ||
                n.contains("pushdown") || n.contains("overhead extension") || n.contains("kickback") ||
                n.contains("jm press") || n.contains("nahgriff"))
            return "triceps";
        if (n.contains("klimmzüge") || n.contains("pull-up") || n.contains("latzug") ||
                n.contains("pulldown") || n.contains("straight-arm"))
            return "vertical_pull";
        if (n.contains("rudern") || n.contains("row") || n.contains("pendlay") || n.contains("t-bar"))
            return "horizontal_pull";
        if (n.contains("face pull") || n.contains("reverse fly") || (n.contains("vorgebeugt") && n.contains("seitheben")) ||
                n.contains("band pull") || n.contains("w-raise") || n.contains("y-t-w"))
            return "rear_delt";
        if (n.contains("curl") || n.contains("bizeps") || n.contains("preacher") || n.contains("spider curl"))
            return "biceps";
        if (n.contains("plank") || n.contains("ab wheel") || n.contains("dead bug") ||
                n.contains("hollow hold") || n.contains("pallof") || n.contains("suitcase carry"))
            return "core_stability";
        if (n.contains("crunch") || n.contains("sit-up") || n.contains("beinheben") ||
                n.contains("v-up") || n.contains("dragon flag"))
            return "core_flexion";
        if (n.contains("russian twist") || n.contains("woodchopper") || n.contains("rotation"))
            return "core_rotation";
        return null;
    }

    /**
     * Liefert eine noch nicht verwendete Alternative aus derselben oder einer
     * verwandten Pool-Kategorie.
     */
    private String findAlternativeExercise(String originalName, Set<String> usedNormalized) {
        String category = findMovementCategory(originalName);
        if (category == null) category = guessRelatedCategory(originalName);
        if (category == null) return null;

        String alternative = pickFromPool(category, usedNormalized);
        if (alternative != null) return alternative;

        // Pool erschöpft → verwandte Kategorie versuchen
        String related = getRelatedCategory(category);
        if (related != null) return pickFromPool(related, usedNormalized);
        return null;
    }

    private String pickFromPool(String category, Set<String> usedNormalized) {
        List<String> pool = EXERCISE_POOL.get(category);
        if (pool == null) return null;
        for (String candidate : pool) {
            if (!usedNormalized.contains(normalizeExerciseName(candidate))) return candidate;
        }
        return null;
    }

    private String guessRelatedCategory(String exerciseName) {
        String n = exerciseName.toLowerCase();
        if (n.contains("bein") || n.contains("knie") || n.contains("quad")) return "squat";
        if (n.contains("brust")) return "horizontal_push";
        if (n.contains("rücken")) return "horizontal_pull";
        if (n.contains("schulter")) return "vertical_push";
        if (n.contains("bizeps") || n.contains("arm")) return "biceps";
        if (n.contains("trizeps")) return "triceps";
        if (n.contains("bauch") || n.contains("core")) return "core_stability";
        return null;
    }

    private String getRelatedCategory(String category) {
        return switch (category) {
            case "squat"           -> "lunge";
            case "lunge"           -> "squat";
            case "hinge"           -> "hamstring_iso";
            case "hamstring_iso"   -> "hinge";
            case "quad_machine"    -> "squat";
            case "horizontal_push" -> "vertical_push";
            case "vertical_push"   -> "horizontal_push";
            case "dips_push"       -> "triceps";
            case "chest_iso"       -> "horizontal_push";
            case "vertical_pull"   -> "horizontal_pull";
            case "horizontal_pull" -> "vertical_pull";
            case "biceps"          -> "vertical_pull";
            case "triceps"         -> "horizontal_push";
            case "side_delt"       -> "rear_delt";
            case "rear_delt"       -> "side_delt";
            case "core_stability"  -> "core_flexion";
            case "core_flexion"    -> "core_stability";
            default                -> null;
        };
    }

    /** Erkennt anhand Focus-Text und Übungsmuster, ob ein Tag ein Bein-Tag ist. */
    private boolean isLegDay(GeneratedDay day) {
        String combined = (day.dayName() + " " + day.focus()).toLowerCase();
        if (combined.contains("bein") || combined.contains("leg") || combined.contains("quad") ||
                combined.contains("hamstring") || combined.contains("glute") || combined.contains("gesäß"))
            return true;
        long legExercises = day.exercises().stream()
                .filter(e -> {
                    String cat = findMovementCategory(e.exerciseName());
                    return cat != null && (cat.contains("squat") || cat.contains("hinge") ||
                            cat.contains("lunge") || cat.contains("hamstring") ||
                            cat.contains("glute") || cat.contains("explosive") ||
                            cat.contains("quad_machine"));
                }).count();
        return legExercises >= 3;
    }

    // =========================================================================
    // Tagesreihenfolge nach Fokus korrigieren
    // =========================================================================

    private GeneratedPlan reorderGeneratedPlanByFocus(GeneratedPlan plan, GeneratePlanRequest request) {
        if (plan.days().size() <= 1) return plan;
        String focusMuscles = resolveFocusMuscles(request);
        if (focusMuscles.isBlank()) return plan;

        List<GeneratedDay> days = new ArrayList<>(plan.days());
        GeneratedDay mobilityDay = null;
        List<GeneratedDay> trainingDays = new ArrayList<>();
        for (GeneratedDay day : days) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) mobilityDay = day;
            else trainingDays.add(day);
        }

        int priorityIndex = findFocusDayIndex(trainingDays, focusMuscles);
        if (priorityIndex > 0) {
            GeneratedDay focusDay = trainingDays.remove(priorityIndex);
            trainingDays.add(0, focusDay);
            log.info("Tagesreihenfolge angepasst: '{}' → Tag A (Fokus: {})", focusDay.dayName(), focusMuscles);
        }

        List<GeneratedDay> renamed = renameDaysSequentially(trainingDays);
        if (mobilityDay != null) renamed.add(mobilityDay);
        return new GeneratedPlan(plan.planName(), plan.description(), renamed);
    }

    private int findFocusDayIndex(List<GeneratedDay> days, String focusMuscles) {
        if (days.isEmpty()) return 0;
        String fl = focusMuscles.toLowerCase();
        FocusType focus = detectFocusType(fl);
        for (int i = 0; i < days.size(); i++) {
            String dayFocus = (days.get(i).focus() + " " + days.get(i).dayName()).toLowerCase();
            if (matchesFocusType(dayFocus, focus)) return i;
        }
        for (int i = 0; i < days.size(); i++) {
            boolean exerciseMatch = days.get(i).exercises().stream()
                    .anyMatch(ex -> exerciseMatchesFocus(ex.exerciseName().toLowerCase(), focus));
            if (exerciseMatch) return i;
        }
        return 0;
    }

    private enum FocusType { BEINE, BRUST, RUECKEN, SCHULTER, ARME, CORE, UNKNOWN }

    private FocusType detectFocusType(String focusLower) {
        if (focusLower.contains("bein") || focusLower.contains("quad") ||
                focusLower.contains("hamstring") || focusLower.contains("gesäß") ||
                focusLower.contains("glute") || focusLower.contains("legs") || focusLower.contains("wade"))
            return FocusType.BEINE;
        if (focusLower.contains("brust") || focusLower.contains("pecto") ||
                focusLower.contains("chest") || focusLower.contains("push"))
            return FocusType.BRUST;
        if (focusLower.contains("rücken") || focusLower.contains("back") ||
                focusLower.contains("latiss") || focusLower.contains("rudern") || focusLower.contains("pull"))
            return FocusType.RUECKEN;
        if (focusLower.contains("schulter") || focusLower.contains("deltoid") || focusLower.contains("shoulder"))
            return FocusType.SCHULTER;
        if (focusLower.contains("arm") || focusLower.contains("bizeps") ||
                focusLower.contains("trizeps") || focusLower.contains("bicep") || focusLower.contains("tricep"))
            return FocusType.ARME;
        if (focusLower.contains("core") || focusLower.contains("bauch") || focusLower.contains("abs"))
            return FocusType.CORE;
        return FocusType.UNKNOWN;
    }

    private boolean matchesFocusType(String dayFocusLower, FocusType focus) {
        return switch (focus) {
            case BEINE    -> dayFocusLower.contains("bein") || dayFocusLower.contains("quad") ||
                    dayFocusLower.contains("hamstring") || dayFocusLower.contains("gesäß") ||
                    dayFocusLower.contains("glute") || dayFocusLower.contains("leg") || dayFocusLower.contains("unterkörper");
            case BRUST    -> dayFocusLower.contains("brust") || dayFocusLower.contains("push") ||
                    dayFocusLower.contains("chest") || dayFocusLower.contains("pecto");
            case RUECKEN  -> dayFocusLower.contains("rücken") || dayFocusLower.contains("pull") ||
                    dayFocusLower.contains("back") || dayFocusLower.contains("latiss");
            case SCHULTER -> dayFocusLower.contains("schulter") || dayFocusLower.contains("shoulder") || dayFocusLower.contains("deltoid");
            case ARME     -> dayFocusLower.contains("arm") || dayFocusLower.contains("bizeps") || dayFocusLower.contains("trizeps");
            case CORE     -> dayFocusLower.contains("core") || dayFocusLower.contains("bauch") || dayFocusLower.contains("abs");
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
            case SCHULTER -> exerciseLower.contains("schulterdrücken") || exerciseLower.contains("seitheben") || exerciseLower.contains("military press");
            case ARME     -> exerciseLower.contains("curl") || exerciseLower.contains("pushdown") ||
                    exerciseLower.contains("trizeps") || exerciseLower.contains("hammer");
            case CORE     -> exerciseLower.contains("plank") || exerciseLower.contains("crunch") ||
                    exerciseLower.contains("sit-up") || exerciseLower.contains("beinheben");
            case UNKNOWN  -> false;
        };
    }

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

            REGEL 3 — ABSOLUTES DUPLIKAT-VERBOT:
              ✗ VERBOTEN: Eine Übung (oder eine sehr ähnliche Variante) in mehr als einem Tag.
              ✓ Beispiel: Wenn Tag A "Kniebeuge mit Langhantel" hat →
                          Tag B darf KEINE Kniebeuge mehr haben (weder LH, KH noch Goblet Squat).
                          Tag B nutzt stattdessen Hinge (Rumänisches KH) oder Lunge.
              ✓ Jeder Tag hat KOMPLETT andere Übungen als alle anderen Tage.

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
                    → Die KI erstellt GENAU 3 qualitativ hochwertige Mobility-Übungen.
                      (Der Service ergänzt zusätzlich 3 kuratierte Fallback-Übungen.)
                    → Nur echte Mobility-/Dehn-/Beweglichkeitsübungen — KEINE Kraftübungen.
                    → sets: 1–2 | reps: 6–60 | weightKg: 0.0 | restSeconds: 30 | targetRpe: 3
                    → VERBOTEN: Generische Namen wie "Mobilitätsübungen", "Seitenbewegungen", "Hüftrotationen"
                    → PFLICHT: Konkrete Namen wie "Couch Stretch", "Deep Squat Hold",
                      "Thoracic Rotation", "Ankle Dorsiflexion Wall Drill", "Thread the Needle",
                      "Adductor Rock Back", "Pigeon Pose", "90/90 Hip Stretch"
                    → JEDE Übung MUSS eine hochwertige Beschreibung auf Deutsch haben:
                      1. klare Startposition
                      2. genaue Bewegungsausführung
                      3. klarer Technikhinweis / worauf achten
                    → Die Beschreibung soll 2–4 vollständige Sätze enthalten und praktisch umsetzbar sein.

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

            REGEL 16 — ÜBUNGS-TAXONOMIE (NUTZE DIESEN POOL — VARIIERE AKTIV):
              Wähle aus diesen Kategorien. Pro Kategorie darf jede Variante maximal
              einmal über ALLE Tage hinweg vorkommen:

              KNIEBEUGE-MUSTER (maximal 1 Variante pro Plan):
                Kniebeuge mit Langhantel | Front Squat | Goblet Squat | Box Squat
                Hackenschmidt-Kniebeuge | Zercher Kniebeuge | Sumo Kniebeuge | Pause Kniebeuge

              HINGE-MUSTER (maximal 1 Variante pro Tag, aber andere pro Tag erlaubt):
                Rumänisches Kreuzheben | Kreuzheben | Sumo Kreuzheben | Hip Thrust mit Langhantel
                Good Morning | Single-Leg RDL | Trap Bar Kreuzheben | Defizit Kreuzheben

              LUNGE-MUSTER:
                Ausfallschritte mit Kurzhanteln | Bulgarische Split Kniebeuge | Reverse Lunge
                Ausfallschritte laufend | Seitliche Ausfallschritte | Step-ups auf Kasten

              EXPLOSIV (Bein-Tage, INTERMEDIATE/ADVANCED):
                Box Jumps | Jump Squats | Broad Jumps | Depth Jumps | Power Clean
                Hang Clean | Kettlebell Swing | Medball Slam | Laterale Sprünge

              HORIZONTALES DRÜCKEN:
                Bankdrücken mit Langhantel | Bankdrücken mit Kurzhanteln
                Schrägbankdrücken mit Langhantel | Schrägbankdrücken mit Kurzhanteln
                Decline Bankdrücken | Guillotine Press | Push-ups auf Ringen

              VERTIKALES DRÜCKEN:
                Schulterdrücken mit Langhantel | Schulterdrücken mit Kurzhanteln
                Arnold Press | Push Press | Z-Press sitzend | Landmine Press | Bradford Press

              BRUST-ISOLATION:
                Kurzhantel-Fliegenschlagen | Kabelzug-Fliegenschlagen | Pec Deck Maschine
                Low-to-High Kabelzug | High-to-Low Kabelzug

              SEITE SCHULTER:
                Seitheben mit Kurzhanteln | Seitheben am Kabelzug | Maschinen-Seitheben
                Upright Row | Kabelzug-Seitheben einseitig

              TRIZEPS:
                Trizeps Pushdown am Kabelzug | Overhead Trizeps Extension KH
                Schädelbrechter mit Langhantel | Nahgriff Bankdrücken | JM Press
                Trizeps Dips mit Zusatzgewicht | Kickbacks mit Kurzhantel

              VERTIKALES ZIEHEN:
                Klimmzüge | Klimmzüge mit Zusatzgewicht | Latzug am Kabelzug (weit/eng/Untergriff)
                Straight-Arm Pulldown | Ringklimmzüge | Assistierte Klimmzüge

              HORIZONTALES ZIEHEN:
                Langhantelrudern | Einarmiges Kurzhantelrudern | Seilzug-Rudern sitzend
                T-Bar Rudern | Pendlay Rudern | Meadows Row | Chest Supported Row | Seal Row

              HINTERE SCHULTER:
                Face Pulls am Kabelzug | Vorgebeugtes Seitheben | Reverse Flyes
                Band Pull-Aparts | W-Raises | Rear Delt Maschine

              BIZEPS:
                Bizeps-Curl mit Langhantel | Bizeps-Curl mit Kurzhanteln | Hammer Curls
                Preacher Curl | Spider Curls | Incline Dumbbell Curl | Zottman Curl
                Kabelzug-Curl | Reverse Curls

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


    // User-Prompt

    private String buildPlanUserPrompt(AppUser user, GeneratePlanRequest request) {
        List<String> focusParts = new ArrayList<>();
        if (request.focusMuscleGroups() != null) focusParts.addAll(request.focusMuscleGroups());
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
        String sleepStr   = request.sleepHoursPerNight() != null
                ? request.sleepHoursPerNight() + " Stunden" : "Nicht angegeben";
        String stressStr  = translateStress(request.stressLevel());
        String injuriesStr = (request.injuries() != null && !request.injuries().isBlank())
                ? request.injuries().trim() : "Keine";
        boolean mobility  = Boolean.TRUE.equals(request.includeMobilityPlan());

        int sleepH = request.sleepHoursPerNight() != null ? request.sleepHoursPerNight() : 7;
        String stress = request.stressLevel() != null ? request.stressLevel().toUpperCase() : "MODERATE";
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

        String focusSection = buildFocusSection(focusMuscles, numDayPlans, request.focusStrategy());
        boolean isDoubleFocus = "DOUBLE_FOCUS".equalsIgnoreCase(request.focusStrategy());
        String focusOrderingRule = buildFocusOrderingRule(focusMuscles);
        String rotationLine      = buildRotationHint(daysPerWeek, numDayPlans, List.of("Tag A", "Tag B", "Tag C"));

        // ── NEU: Konkrete Duplikat-Verbotsliste für diesen Plan ───────────────
        String duplicateExampleHint = buildDuplicateHint(focusMuscles, numDayPlans);

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
                + "3. MUSKELGRUPPEN-AUFTEILUNG"
                + (isDoubleFocus ? " (FOKUS-STRATEGIE: DOUBLE_FOCUS — FOKUS VERDOPPELN!):\n" : ":\n")
                + (isDoubleFocus ? "" : standardDist + "\n")
                + focusSection + "\n"
                + "4. TAGESREIHENFOLGE (KRITISCH — REGEL 12):\n"
                + focusOrderingRule + "\n"
                + "\n"
                + "5. ÜBUNGSREIHENFOLGE PRO TAG: Große Muskelgruppen zuerst. Schulter/Arme niemals vor Brust/Rücken/Beinen.\n"
                + "\n"
                + "6. ABSOLUTES DUPLIKAT-VERBOT (KRITISCH — REGEL 3 + 15):\n"
                + "   Jede Übung darf im GESAMTEN Plan nur EINMAL vorkommen.\n"
                + duplicateExampleHint + "\n"
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
                + "    Die KI soll EXAKT 3 qualitative Mobility-Übungen erzeugen.\n"
                + "    Der Service ergänzt zusätzlich 3 kuratierte Fallback-Mobility-Übungen.\n"
                + "    Nur echte Mobility-/Dehnübungen, keine Kraftübungen.\n"
                + "    Jede KI-Übung braucht eine starke 'description' mit Startposition, Ausführung und Technikhinweis.\n"
                + "    weightKg: 0.0 | sets: 1–2 | reps: 6–60 | restSeconds: 30 | targetRpe: 3\n\n"
                : "")
                + buildExplosiveRule(rawLevel, numDayPlans)
                + buildVariabilityHint(rawLevel, numDayPlans)
                + "Gib ausschließlich das JSON zurück.";
    }

    /**
     * NEU: Erzeugt konkrete Duplikat-Verbots-Beispiele für den User-Prompt.
     * Macht Regel 3 anschaulicher und verhindert typische Fehler.
     */
    private String buildDuplicateHint(String focusMuscles, int numDayPlans) {
        if (numDayPlans <= 1) return "   (Nur 1 Tag → kein Duplikat-Problem)";

        String fl = (focusMuscles != null ? focusMuscles : "").toLowerCase();
        boolean hasBein = fl.contains("bein") || fl.contains("quad") || fl.contains("hamstring") || fl.contains("gesäß");

        if (hasBein && numDayPlans >= 2) {
            return """
                   KONKRETE VERBOTSLISTE (Beispiele — gilt für alle Übungen):
                   ✗ Kniebeuge (irgendeine Variante) darf nur auf EINEM Tag vorkommen.
                   ✗ Ausfallschritte dürfen nur auf EINEM Tag vorkommen.
                   ✗ Beinpresse darf nur auf EINEM Tag vorkommen.
                   ✗ Rumänisches Kreuzheben darf nur auf EINEM Tag vorkommen.
                   ✗ Beinbeuger darf nur auf EINEM Tag vorkommen.
                   ✓ Wenn Tag A Kniebeuge hat → Tag B nutzt Deadlift / Hip Thrust / Split Kniebeuge statt nochmal Kniebeuge.
                   ✓ Wenn Tag A Beinbeuger hat → Tag B nutzt Nordische Hamstring Curls oder SL-RDL statt Beinbeuger.""";
        }

        return """
               KONKRETE VERBOTSLISTE:
               ✗ Bankdrücken in irgendwelchen Varianten nur auf EINEM Tag erlaubt.
               ✗ Klimmzüge / Latzug darf nur auf EINEM Tag vorkommen.
               ✗ Schulterdrücken nur auf EINEM Tag.
               ✗ Kniebeuge-Varianten nur auf EINEM Tag.
               ✓ Prüfe vor jedem Eintrag: Gibt es diese Übung schon in einem anderen Tag?
               ✓ Wenn ja → wähle eine andere Variante aus dem Pool (REGEL 16).""";
    }

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

    private String buildFocusSection(String focusMuscles, int numDayPlans, String focusStrategy) {
        if (focusMuscles == null || focusMuscles.isBlank()) return "";

        boolean isDoubleFocus = "DOUBLE_FOCUS".equalsIgnoreCase(focusStrategy);

        if (isDoubleFocus) {
            return buildDoubleFocusSection(focusMuscles, numDayPlans);
        }

        // ── BALANCED (default) — bisherige Logik ──────────────────────────────
        String fl = focusMuscles.toLowerCase();
        boolean isBein     = fl.contains("bein") || fl.contains("quad") || fl.contains("hamstring") || fl.contains("gesäß") || fl.contains("glute");
        boolean isSchulter = fl.contains("schulter") || fl.contains("deltoid");
        boolean isBrust    = fl.contains("brust") || fl.contains("pecto");
        boolean isRuecken  = fl.contains("rücken") || fl.contains("latiss");

        if (numDayPlans == 3) {
            if (isBein) return """
 
                    FOKUS-REGEL BEINE (3 Tage — BEINE ZUERST als Tag A):
                    Tag A (Beine – Quad, ERSTER TAG): Kniebeuge ODER Front Squat + Beinpresse + Ausfallschritte + Wadenheben.
                    Tag B (Beine – Hinge/Posterior, VERSCHIEDENE ÜBUNGEN): Rumänisches Kreuzheben + Beinbeuger + Hip Thrust + Bulgarische Split Kniebeuge.
                    Tag C (Oberkörper): Brust, Rücken, Schulter, Arme — KEINE Bein-Übungen mehr.
                    WICHTIG: Keine einzige Übung aus Tag A darf in Tag B erscheinen!""";
            if (isSchulter) return """
 
                    FOKUS-REGEL SCHULTER (3 Tage — SCHULTER ZUERST):
                    Tag A (Schulter+Brust, ERSTER TAG): Schulterdrücken + Seitheben + Bankdrücken + Fliegenschlagen + Trizeps.
                    Tag B (Schulter+Rücken, ANDERE ÜBUNGEN): Vorgebeugtes Seitheben + Face Pulls + Latzug + Rudern + Bizeps.
                    Tag C (Beine): Kniebeuge + Beinpresse + Rumänisches KH + Ausfallschritte + Waden.""";
            if (isBrust) return """
 
                    FOKUS-REGEL BRUST (3 Tage — BRUST/PUSH ZUERST):
                    Tag A (Brust+Schulter, ERSTER TAG): Bankdrücken + Schrägdrücken + Schulterdrücken + Seitheben + Trizeps.
                    Tag B (Brust+Trizeps, ANDERE ÜBUNGEN): Schrägbankdrücken KH + Fliegenschlagen + Pec Deck + Trizeps-Variation.
                    Tag C (Rücken+Beine): Klimmzüge + Rudern + Kniebeuge + Rumänisches KH.""";
            if (isRuecken) return """
 
                    FOKUS-REGEL RÜCKEN (3 Tage — RÜCKEN/PULL ZUERST):
                    Tag A (Rücken-Breite, ERSTER TAG): Klimmzüge + Latzug (Untergriff) + Einarmiges Rudern + Hammer Curls.
                    Tag B (Rücken-Dicke, ANDERE ÜBUNGEN): Langhantelrudern + T-Bar Rudern + Seilzug-Rudern + Face Pulls + Bizeps-Curl.
                    Tag C (Brust+Beine): Bankdrücken + Schrägdrücken + Kniebeuge + Rumänisches KH.""";
        } else if (numDayPlans == 2) {
            if (isBein) return """
 
                    FOKUS-REGEL BEINE (2 Tage — BEINE ZUERST als Tag A):
                    Tag A (Beine – Quad): Kniebeuge + Beinpresse + Ausfallschritte + Wadenheben + Oberkörper ergänzend.
                    Tag B (Beine – Hinge, KOMPLETT ANDERE ÜBUNGEN): Rumänisches KH + Beinbeuger + Hip Thrust + Oberkörper.
                    KRITISCH: Tag B darf KEINE Kniebeuge, KEINE Beinpresse, KEINE Ausfallschritte enthalten!""";
            if (isSchulter) return """
 
                    FOKUS-REGEL SCHULTER (2 Tage — SCHULTER ZUERST):
                    Tag A (Schulter+Brust, ERSTER TAG): Schulterdrücken + Seitheben + Bankdrücken + Trizeps.
                    Tag B (Schulter+Rücken, ANDERE ÜBUNGEN): Vorgebeugtes Seitheben + Face Pulls + Latzug + Bizeps.""";
            if (isBrust) return """
 
                    FOKUS-REGEL BRUST (2 Tage — BRUST ZUERST):
                    Tag A (Brust+Push, ERSTER TAG): Bankdrücken + Schrägdrücken + Schulterdrücken + Trizeps.
                    Tag B (Rücken+Beine, ANDERE ÜBUNGEN): Klimmzüge + Rudern + Kniebeuge + Rumänisches KH.""";
            if (isRuecken) return """
 
                    FOKUS-REGEL RÜCKEN (2 Tage — RÜCKEN ZUERST):
                    Tag A (Rücken+Pull, ERSTER TAG): Klimmzüge + Latzug + Langhantelrudern + Bizeps.
                    Tag B (Brust+Beine, ANDERE ÜBUNGEN): Bankdrücken + Schrägdrücken + Kniebeuge + Beinbeuger.""";
        }
        return "\nFOKUS '" + focusMuscles + "': Tag A MUSS diesen Fokus priorisieren — klar stärker gewichten als andere Tage.";
    }

    private String buildDoubleFocusSection(String focusMuscles, int numDayPlans) {
        String fl = focusMuscles.toLowerCase();
        boolean isBein     = fl.contains("bein") || fl.contains("quad") || fl.contains("hamstring") || fl.contains("gesäß") || fl.contains("glute");
        boolean isSchulter = fl.contains("schulter") || fl.contains("deltoid") || fl.contains("shoulder");
        boolean isBrust    = fl.contains("brust") || fl.contains("pecto") || fl.contains("chest");
        boolean isRuecken  = fl.contains("rücken") || fl.contains("back") || fl.contains("latiss");

        if (isBein) {
            if (numDayPlans >= 3) return """
 
                    FOKUS-STRATEGIE: BEINE VERDOPPELN (DOUBLE_FOCUS — KRITISCH):
                    ✓ PFLICHT: Exakt 2 Bein-Trainingstage + 1 Oberkörper-Ausgleichstag.
 
                    Tag A – Beine Quad (ERSTER TAG, Kraft-Fokus):
                      1. Explosive Übung (Box Jumps / Jump Squats) — bei INTERMEDIATE/ADVANCED
                      2. Kniebeuge mit Langhantel ODER Front Squat (Hauptübung, 4–5 Sätze)
                      3. Beinpresse (Variation mit anderer Fußstellung)
                      4. Ausfallschritte ODER Bulgarische Split Kniebeuge
                      5. Beinstrecker an der Maschine (Quad-Isolation)
                      6. Wadenheben stehend
 
                    Tag B – Beine Posterior/Hinge (ZWEITER BEIN-TAG, Hypertrophie-Fokus):
                      1. Rumänisches Kreuzheben (Hauptübung — NICHT Kniebeuge!)
                      2. Hip Thrust mit Langhantel ODER Glute Bridge
                      3. Beinbeuger an der Maschine ODER Nordische Hamstring Curls
                      4. Single-Leg RDL ODER Good Morning
                      5. Abduktor Maschine ODER Cable Kickbacks
                      6. Wadenheben sitzend (andere Variation als Tag A)
 
                    Tag C – Oberkörper Ausgleich (ausgewogener Push+Pull-Tag):
                      Brust + Rücken + Schulter + Arme — KEINE Bein-Übungen!
                      Beispiel: Bankdrücken + Klimmzüge + Schulterdrücken + Seitheben + Trizeps + Bizeps
 
                    ABSOLUTES VERBOT:
                    ✗ Kniebeuge-Varianten NUR auf Tag A.
                    ✗ Beinpresse NUR auf Tag A.
                    ✗ Rumänisches KH / Hip Thrust NUR auf Tag B.
                    ✗ Beinbeuger NUR auf Tag B.
                    ✓ Wadenheben erlaubt auf beiden Tagen, aber VERSCHIEDENE Varianten.""";
            else return """
 
                    FOKUS-STRATEGIE: BEINE VERDOPPELN (DOUBLE_FOCUS, 2 Tage):
                    ✓ PFLICHT: Beide Trainingstage sind Bein-Tage mit unterschiedlichem Schwerpunkt.
 
                    Tag A – Beine Quad (Kraft):
                      Kniebeuge + Beinpresse + Ausfallschritte + Beinstrecker + Wadenheben stehend
 
                    Tag B – Beine Posterior (Hinge, KOMPLETT ANDERE ÜBUNGEN):
                      Rumänisches KH + Hip Thrust + Beinbeuger + Nordische Curls ODER SL-RDL + Wadenheben sitzend
 
                    ABSOLUTES VERBOT: Keine einzige Übung darf in beiden Tagen vorkommen!""";
        }

        if (isBrust) {
            if (numDayPlans >= 3) return """
 
                    FOKUS-STRATEGIE: BRUST VERDOPPELN (DOUBLE_FOCUS):
                    ✓ PFLICHT: 2 Brust-orientierte Tage + 1 Bein/Rücken-Ausgleichstag.
 
                    Tag A – Brust Kraft (schwere Compound-Übungen):
                      Bankdrücken mit Langhantel (5×5 oder 4×6) + Schrägbankdrücken LH
                      + Schulterdrücken mit Langhantel + Seitheben + Trizeps Dips mit Gewicht
 
                    Tag B – Brust Hypertrophie (Isolations- und KH-Fokus):
                      Schrägbankdrücken mit Kurzhanteln + Kurzhantel-Fliegenschlagen
                      + Pec Deck Maschine ODER Low-to-High Kabelzug + Arnold Press
                      + Overhead Trizeps Extension
 
                    Tag C – Rücken + Beine (Ausgleich):
                      Klimmzüge + Langhantelrudern + Kniebeuge + Rumänisches KH + Bizeps-Curl
 
                    VERBOT: Tag A und Tag B dürfen KEINE gleichen Übungen haben!""";
            else return """
 
                    FOKUS-STRATEGIE: BRUST VERDOPPELN (DOUBLE_FOCUS, 2 Tage):
                    Tag A – Brust Kraft: Bankdrücken LH + Schrägdrücken LH + Schulterdrücken + Trizeps
                    Tag B – Brust Hypertrophie: Schrägdrücken KH + Fliegenschlagen + Pec Deck + Arnold Press
                    VERBOT: Keine gleichen Übungen in beiden Tagen!""";
        }

        if (isRuecken) {
            if (numDayPlans >= 3) return """
 
                    FOKUS-STRATEGIE: RÜCKEN VERDOPPELN (DOUBLE_FOCUS):
                    ✓ PFLICHT: 2 Rücken-Tage + 1 Brust/Beine-Ausgleichstag.
 
                    Tag A – Rücken Breite (vertikales Ziehen):
                      Klimmzüge mit Zusatzgewicht + Latzug Untergriff + Kabelzug-Rudern
                      + Face Pulls + Incline Dumbbell Curl + Hammer Curls
 
                    Tag B – Rücken Dicke (horizontales Ziehen, schwer):
                      Langhantelrudern (Pendlay) + T-Bar Rudern + Chest Supported Row
                      + Meadows Row + Rear Delt Maschine + Bizeps-Curl LH
 
                    Tag C – Brust + Beine (Ausgleich):
                      Bankdrücken + Schrägdrücken + Kniebeuge + Rumänisches KH + Trizeps
 
                    VERBOT: Keine gleichen Zugübungen in Tag A und Tag B!""";
            else return """
 
                    FOKUS-STRATEGIE: RÜCKEN VERDOPPELN (DOUBLE_FOCUS, 2 Tage):
                    Tag A – Rücken Breite: Klimmzüge + Latzug + Kabelzug-Rudern + Bizeps
                    Tag B – Rücken Dicke: Langhantelrudern + T-Bar + Chest Supported Row + Bizeps-Curl LH
                    VERBOT: Keine gleichen Zugübungen in beiden Tagen!""";
        }

        if (isSchulter) {
            if (numDayPlans >= 3) return """
 
                    FOKUS-STRATEGIE: SCHULTER VERDOPPELN (DOUBLE_FOCUS):
                    ✓ PFLICHT: 2 Schulter-Tage + 1 Beine-Ausgleichstag.
 
                    Tag A – Schulter Drücken (vertikale Kraft):
                      Schulterdrücken mit Langhantel ODER Push Press + Arnold Press
                      + Seitheben mit Kurzhanteln + Schrägbankdrücken (Brustergänzung)
                      + Schädelbrechter + Trizeps Pushdown
 
                    Tag B – Schulter Isolation (Seite & Hinten):
                      Seitheben am Kabelzug + Maschinen-Seitheben + Vorgebeugtes Seitheben
                      + Face Pulls + W-Raises + Klimmzüge ODER Latzug (Rücken-Ergänzung)
                      + Hammer Curls
 
                    Tag C – Beine (Ausgleich):
                      Kniebeuge + Beinpresse + Rumänisches KH + Beinbeuger + Wadenheben
 
                    VERBOT: Keine gleichen Schulter-Übungen in Tag A und Tag B!""";
            else return """
 
                    FOKUS-STRATEGIE: SCHULTER VERDOPPELN (DOUBLE_FOCUS, 2 Tage):
                    Tag A – Schulter Kraft: Schulterdrücken + Arnold Press + Seitheben + Trizeps
                    Tag B – Schulter Isolation: Seitheben Kabelzug + Face Pulls + Vorgebeugtes Seitheben + Klimmzüge
                    VERBOT: Keine gleichen Übungen in beiden Tagen!""";
        }

        // Generischer DOUBLE_FOCUS-Fallback für andere Fokusgruppen
        return "\nFOKUS-STRATEGIE: DOPPEL-FOKUS für '" + focusMuscles + "':\n"
                + "Tag A UND Tag B MÜSSEN beide den Fokus '" + focusMuscles + "' trainieren,\n"
                + "jedoch mit VOLLSTÄNDIG unterschiedlichen Übungen (Kraft vs. Hypertrophie).\n"
                + (numDayPlans >= 3 ? "Tag C: Ausgleichstag — andere Muskelgruppen.\n" : "");
    }

    private String buildExplosiveRule(String level, int numDayPlans) {
        if (level == null) return "";
        String l = level.toUpperCase();
        if (!l.equals("INTERMEDIATE") && !l.equals("ADVANCED")) return "";
        if (numDayPlans < 1) return "";
        return "12. EXPLOSIVE ÜBUNG AUF BEIN-TAGEN (PFLICHT für " + level + " — REGEL 13):\n"
                + "    Jeder Bein-Trainingstag MUSS als ERSTE Übung eine explosive Übung enthalten.\n"
                + "    Erlaubt: Box Jumps | Jump Squats | Broad Jumps | Power Clean | Kettlebell Swing\n"
                + "             Medball Slam | Laterale Sprünge | Depth Jumps | Bounding | Hang Clean\n"
                + "    weightKg: 0.0 | sets: 3–4 | reps: 5–8 | restSeconds: 90–120 | targetRpe: 8–9\n\n";
    }

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

    // JSON-Parser

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

    // Fallback-Plan

    private GeneratedPlan buildFallbackPlan(GeneratePlanRequest request) {
        String planName   = request.planName() != null ? request.planName() : "Trainingsplan";
        String level      = resolveRawLevel(request, null);
        int daysPerWeek   = request.daysPerWeek() != null ? request.daysPerWeek() : 3;
        int numDayPlans   = calcNumDayPlans(daysPerWeek);
        int variant       = (int) (System.currentTimeMillis() % 4);

        List<String> dayNames = List.of("Tag A", "Tag B", "Tag C");
        String rotation   = buildRotationHint(daysPerWeek, numDayPlans, dayNames);
        String description = "Fallback-Plan | " + request.userPrompt()
                + (rotation.isBlank() ? "" : " | " + rotation);

        List<GeneratedDay> allDays = switch (level) {
            case "ADVANCED"     -> buildAdvancedDays(variant);
            case "INTERMEDIATE" -> buildIntermediateDays(variant);
            default             -> buildBeginnerDays();
        };

        int sets = calcFallbackSets(request);
        if (sets != 3) allDays = adjustSets(allDays, sets);

        String focusMuscles = resolveFocusMuscles(request);
        if (!focusMuscles.isBlank()) {
            allDays = reorderFallbackDaysByFocus(allDays, focusMuscles);
        }

        List<GeneratedDay> days = new ArrayList<>(allDays.subList(0, Math.min(numDayPlans, allDays.size())));

        if (Boolean.TRUE.equals(request.includeMobilityPlan())) {
            days.add(buildFallbackMobilityDay());
        }

        return new GeneratedPlan(planName, description, days);
    }

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
        if (focusIndex <= 0) return renameDaysSequentially(days);

        List<GeneratedDay> reordered = new ArrayList<>(days);
        GeneratedDay focusDay = reordered.remove(focusIndex);
        reordered.add(0, focusDay);
        log.info("Fallback-Tage umgeordnet: '{}' → Tag A (Fokus: {})", focusDay.dayName(), focusMuscles);
        return renameDaysSequentially(reordered);
    }

    private List<GeneratedDay> renameDaysSequentially(List<GeneratedDay> days) {
        String[] labels = {"A", "B", "C", "D"};
        List<GeneratedDay> result = new ArrayList<>();
        int labelIndex = 0;
        for (GeneratedDay day : days) {
            if ("Mobilitätsblock".equalsIgnoreCase(day.dayName())) {
                result.add(day);
                continue;
            }
            if (labelIndex >= labels.length) { result.add(day); continue; }
            String suffix = "";
            String name = day.dayName();
            int dashIdx = name.indexOf(" – ");
            if (dashIdx >= 0) suffix = " – " + name.substring(dashIdx + 3);
            result.add(new GeneratedDay("Tag " + labels[labelIndex] + suffix, day.focus(), day.exercises()));
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
                new GeneratedExercise("Seilzug-Rudern sitzend",            3, 12, 20.0, 90, 6, ""),
                new GeneratedExercise("Bizeps-Curl mit Kurzhanteln",       3, 12,  6.0, 60, 6, ""),
                new GeneratedExercise("Goblet Squat mit Kurzhantel",       3, 12, 10.0, 90, 6, ""),
                new GeneratedExercise("Rumänisches Kreuzheben",            3, 12, 20.0, 90, 6, "")
        ));
        var tagC = new GeneratedDay("Tag C – Beine", "Quadrizeps, Hamstrings, Core", List.of(
                new GeneratedExercise("Kniebeuge mit Langhantel",          3, 10, 30.0, 90, 6, ""),
                new GeneratedExercise("Beinpresse",                        3, 12, 50.0, 90, 6, ""),
                new GeneratedExercise("Bulgarische Split Kniebeuge",       3, 10,  8.0, 90, 6, ""),
                new GeneratedExercise("Beinbeuger an der Maschine",        3, 12, 25.0, 60, 6, ""),
                new GeneratedExercise("Wadenheben stehend",                3, 15,  0.0, 60, 6, "")
        ));
        return new ArrayList<>(List.of(tagA, tagB, tagC));
    }

    // ── Fallback-Tage: Fortgeschritten (je 6 Übungen, 3 Varianten) ──────────

    private List<GeneratedDay> buildIntermediateDays(int variant) {

        // ── Bein-Tage: 4 Varianten, jede mit anderer explosiver Übung ────────
        GeneratedDay legs = switch (variant) {
            case 1 -> new GeneratedDay("Tag C – Beine", "Hamstring-Fokus, explosiv", List.of(
                    new GeneratedExercise("Broad Jumps",                         3,  6,  0.0,  90, 8, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              4,  8, 65.0, 120, 7, ""),
                    new GeneratedExercise("Beinbeuger liegend",                  3, 10, 40.0,  90, 7, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",         3, 10, 18.0,  90, 7, ""),
                    new GeneratedExercise("Hip Thrust mit Langhantel",           3, 12, 60.0,  90, 7, ""),
                    new GeneratedExercise("Einbeiniges Wadenheben",              3, 15,  0.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag C – Beine", "Kraft-Ausdauer, explosiv", List.of(
                    new GeneratedExercise("Kettlebell Swing",                    4,  8, 24.0,  90, 8, ""),
                    new GeneratedExercise("Front Squat mit Langhantel",          4, 10, 50.0,  90, 7, ""),
                    new GeneratedExercise("Beinpresse weite Fußstellung",        3, 12, 80.0,  90, 7, ""),
                    new GeneratedExercise("Nordische Hamstring Curls",           3,  6,  0.0, 120, 8, ""),
                    new GeneratedExercise("Step-ups auf Kasten",                 3, 10, 16.0,  90, 7, ""),
                    new GeneratedExercise("Wadenheben sitzend",                  3, 15, 30.0,  60, 6, "")
            ));
            case 3 -> new GeneratedDay("Tag C – Beine", "Unilateral & Athletik", List.of(
                    new GeneratedExercise("Laterale Sprünge",                    3,  8,  0.0,  90, 8, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",         4,  8, 20.0, 120, 7, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              4,  8, 60.0, 120, 7, ""),
                    new GeneratedExercise("Step-ups auf Kasten (explosiv)",      3, 10, 16.0,  90, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",          3, 12, 35.0,  90, 7, ""),
                    new GeneratedExercise("Wadenheben stehend",                  3, 15,  0.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag C – Beine", "Quad-Fokus, explosiv", List.of(
                    new GeneratedExercise("Jump Squats",                         3,  8,  0.0,  90, 8, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel",            4,  8, 70.0, 120, 7, ""),
                    new GeneratedExercise("Beinpresse enge Fußstellung",         3, 10, 80.0,  90, 7, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben",              3, 10, 60.0,  90, 7, ""),
                    new GeneratedExercise("Ausfallschritte mit Kurzhanteln",     3, 12, 16.0,  90, 7, ""),
                    new GeneratedExercise("Beinbeuger an der Maschine",          3, 12, 35.0,  60, 6, "")
            ));
        };

        // ── Push-Tage: 4 Varianten ────────────────────────────────────────────
        GeneratedDay push = switch (variant) {
            case 1 -> new GeneratedDay("Tag A – Push", "Schulter-Fokus, Hypertrophie", List.of(
                    new GeneratedExercise("Schulterdrücken mit Langhantel",      4, 10, 35.0,  90, 7, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",   3, 10, 22.0,  90, 7, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln",           4, 12,  8.0,  60, 7, ""),
                    new GeneratedExercise("Vorgebeugtes Seitheben",              3, 12,  6.0,  60, 6, ""),
                    new GeneratedExercise("Schädelbrechter mit Langhantel",      3, 12, 20.0,  90, 7, ""),
                    new GeneratedExercise("Overhead Trizeps Extension KH",      3, 12, 12.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag A – Push", "Brust-Fokus, Kraft-Ausdauer", List.of(
                    new GeneratedExercise("Bankdrücken mit Kurzhanteln",         4, 10, 28.0,  90, 7, ""),
                    new GeneratedExercise("Kabelzug-Fliegenschlagen",            3, 15, 15.0,  60, 7, ""),
                    new GeneratedExercise("Dips",                                3, 12,  0.0,  90, 7, ""),
                    new GeneratedExercise("Arnold Press",                        3, 12, 14.0,  90, 7, ""),
                    new GeneratedExercise("Seitheben am Kabelzug",               3, 15, 10.0,  60, 6, ""),
                    new GeneratedExercise("Trizeps Pushdown am Kabelzug",        3, 12, 20.0,  60, 6, "")
            ));
            case 3 -> new GeneratedDay("Tag A – Push", "Brust & Schulter variiert", List.of(
                    new GeneratedExercise("Schrägbankdrücken mit Langhantel",    4,  8, 55.0, 120, 7, ""),
                    new GeneratedExercise("Bankdrücken mit Langhantel",          3,  8, 65.0, 120, 7, ""),
                    new GeneratedExercise("Pec Deck Maschine",                   3, 12,  0.0,  90, 7, ""),
                    new GeneratedExercise("Push Press",                          4,  6, 45.0, 120, 7, ""),
                    new GeneratedExercise("Maschinen-Seitheben",                 3, 15,  0.0,  60, 6, ""),
                    new GeneratedExercise("Nahgriff Bankdrücken",                3, 10, 50.0,  90, 7, "")
            ));
            default -> new GeneratedDay("Tag A – Push", "Brust, Schulter, Trizeps", List.of(
                    new GeneratedExercise("Bankdrücken mit Langhantel",          4,  8, 60.0, 120, 7, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Langhantel",    3, 10, 45.0,  90, 7, ""),
                    new GeneratedExercise("Kurzhantel-Fliegenschlagen",          3, 12, 16.0,  90, 7, ""),
                    new GeneratedExercise("Schulterdrücken mit Kurzhanteln",     3, 10, 20.0,  90, 7, ""),
                    new GeneratedExercise("Maschinen-Seitheben",                 3, 12,  0.0,  60, 6, ""),
                    new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",      3, 10, 10.0,  90, 7, "")
            ));
        };

        // ── Pull-Tage: 4 Varianten ────────────────────────────────────────────
        GeneratedDay pull = switch (variant) {
            case 1 -> new GeneratedDay("Tag B – Pull", "Rücken-Breite, Hypertrophie", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht",         4,  8, 10.0, 120, 7, ""),
                    new GeneratedExercise("Latzug Untergriff",                   3, 10, 50.0,  90, 7, ""),
                    new GeneratedExercise("Einarmiges Kurzhantelrudern",         3, 10, 24.0,  90, 7, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",              3, 15, 20.0,  60, 6, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",        3, 12, 14.0,  60, 6, ""),
                    new GeneratedExercise("Konzentrations Curls",                3, 12, 10.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag B – Pull", "Rücken-Dicke, Kraft", List.of(
                    new GeneratedExercise("Langhantelrudern",                    5,  6, 55.0, 150, 8, ""),
                    new GeneratedExercise("T-Bar Rudern",                        4,  8, 40.0, 120, 7, ""),
                    new GeneratedExercise("Latzug weit Griff",                   3, 10, 50.0,  90, 7, ""),
                    new GeneratedExercise("Rear Delt Maschine",                  3, 15,  0.0,  60, 6, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",          3, 10, 22.0,  60, 7, ""),
                    new GeneratedExercise("Reverse Curls",                       3, 12, 10.0,  60, 6, "")
            ));
            case 3 -> new GeneratedDay("Tag B – Pull", "Vertikale Stärke", List.of(
                    new GeneratedExercise("Klimmzüge (weit Griff)",              4,  7,  0.0, 120, 7, ""),
                    new GeneratedExercise("Chest Supported Row",                 4,  8, 40.0, 120, 7, ""),
                    new GeneratedExercise("Straight-Arm Pulldown",               3, 12, 22.0,  90, 6, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",              3, 15, 18.0,  60, 6, ""),
                    new GeneratedExercise("Incline Dumbbell Curl",               3, 10, 12.0,  60, 7, ""),
                    new GeneratedExercise("Zottman Curl",                        3, 12,  8.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag B – Pull", "Rücken, Bizeps", List.of(
                    new GeneratedExercise("Klimmzüge",                           4,  6,  0.0, 120, 7, ""),
                    new GeneratedExercise("Pendlay Rudern",                      4,  8, 50.0, 120, 7, ""),
                    new GeneratedExercise("Seilzug-Rudern sitzend",              3, 10, 45.0,  90, 7, ""),
                    new GeneratedExercise("W-Raises",                            3, 15,  5.0,  60, 6, ""),
                    new GeneratedExercise("Preacher Curl",                       3, 10, 20.0,  90, 7, ""),
                    new GeneratedExercise("Zottman Curl",                        3, 12,  8.0,  60, 6, "")
            ));
        };

        return new ArrayList<>(List.of(push, pull, legs));
    }

    // ── Fallback-Tage: Experte (je 7 Übungen, 3 Varianten) ───────────────────

    private List<GeneratedDay> buildAdvancedDays(int variant) {

        // ── Bein-Tage (4 Varianten statt 3) ──────────────────────────────────
        GeneratedDay legs = switch (variant) {
            case 0 -> new GeneratedDay("Tag C – Beine", "Quad-Hypertrophie & Power", List.of(
                    new GeneratedExercise("Box Jumps",                               4,  5,   0.0, 120, 9, ""),
                    new GeneratedExercise("Pause Kniebeuge (3s unten)",              5,  4, 100.0, 180, 8, ""),
                    new GeneratedExercise("Beinpresse weite Fußstellung",            4,  8, 140.0, 120, 7, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge",             3,  8,  28.0, 120, 8, ""),
                    new GeneratedExercise("Beinstrecker an der Maschine",            3, 12,   0.0,  90, 7, ""),
                    new GeneratedExercise("Isometrisches Wandsitzen (60s)",          3, 60,   0.0,  60, 7, ""),
                    new GeneratedExercise("Einbeiniges Wadenheben",                  4, 12,   0.0,  60, 7, "")
            ));
            case 1 -> new GeneratedDay("Tag C – Beine", "Posterior Chain & Athletik", List.of(
                    new GeneratedExercise("Depth Jumps",                             4,  5,   0.0, 120, 9, ""),
                    new GeneratedExercise("Rumänisches Kreuzheben (Tempo 3-1-1)",    5,  5,  95.0, 180, 8, ""),
                    new GeneratedExercise("Nordische Hamstring Curls",               4,  5,   0.0, 120, 9, ""),
                    new GeneratedExercise("Hip Thrust mit Langhantel (Pause oben)",  4,  8,  90.0, 120, 8, ""),
                    new GeneratedExercise("Single-Leg RDL mit Kurzhantel",           3,  8,  20.0, 120, 7, ""),
                    new GeneratedExercise("Beinbeuger liegend (exzentrisch 5s)",     3,  8,  40.0,  90, 8, ""),
                    new GeneratedExercise("Donkey Calf Raises",                      4, 15,   0.0,  60, 7, "")
            ));
            case 2 -> new GeneratedDay("Tag C – Beine", "Athletik & Power Clean", List.of(
                    new GeneratedExercise("Power Clean",                             4,  4,  60.0, 150, 9, ""),
                    new GeneratedExercise("Front Squat mit Langhantel",              4,  5,  80.0, 150, 8, ""),
                    new GeneratedExercise("Step-ups auf Kasten (explosiv)",          4,  6,  24.0, 120, 8, ""),
                    new GeneratedExercise("Sumo Kreuzheben",                         3,  6, 110.0, 150, 8, ""),
                    new GeneratedExercise("Bulgarische Split Kniebeuge (Pause 2s)",  3,  8,  26.0, 120, 8, ""),
                    new GeneratedExercise("Beinbeuger sitzend",                      3, 10,  45.0,  90, 7, ""),
                    new GeneratedExercise("Wadenheben an der Maschine",              4, 15,  80.0,  60, 7, "")
            ));
            default -> new GeneratedDay("Tag C – Beine", "Unilateral & Explosiv", List.of(
                    new GeneratedExercise("Kettlebell Swing",                        5,  8,  32.0,  90, 8, ""),
                    new GeneratedExercise("Kniebeuge mit Langhantel (Tempo 3-0-1)",  4,  6, 100.0, 150, 8, ""),
                    new GeneratedExercise("Einbeinige Beinpresse",                   3, 10,  80.0, 120, 7, ""),
                    new GeneratedExercise("Hackenschmidt-Kniebeuge",                 3,  8,  80.0, 120, 7, ""),
                    new GeneratedExercise("Ausfallschritte laufend (langsam, 3s)",   3, 10,  24.0,  90, 7, ""),
                    new GeneratedExercise("Nordische Hamstring Curls",               3,  5,   0.0, 120, 9, ""),
                    new GeneratedExercise("Wadenheben mit Pause (3s unten)",         4, 12,   0.0,  60, 7, "")
            ));
        };

        // ── Push-Tage (4 Varianten) ───────────────────────────────────────────
        GeneratedDay push = switch (variant) {
            case 0 -> new GeneratedDay("Tag A – Push", "Brust Kraft + Schulter", List.of(
                    new GeneratedExercise("Bankdrücken mit Langhantel (Tempo 2-1-1)", 5,  4,  92.0, 180, 8, ""),
                    new GeneratedExercise("Nahgriff Bankdrücken (eng)",               4,  6,  70.0, 150, 8, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",        3,  8,  32.0, 120, 7, ""),
                    new GeneratedExercise("Schulterdrücken mit Langhantel",           4,  6,  60.0, 150, 8, ""),
                    new GeneratedExercise("Seitheben am Kabelzug (Pause oben)",       4, 12,  12.0,  60, 7, ""),
                    new GeneratedExercise("Trizeps Dips mit Zusatzgewicht",           3, 10,  20.0, 120, 7, ""),
                    new GeneratedExercise("JM Press",                                 3,  8,  35.0, 120, 7, "")
            ));
            case 1 -> new GeneratedDay("Tag A – Push", "Schulter-Fokus & Kontrolle", List.of(
                    new GeneratedExercise("Push Press",                               5,  5,  65.0, 150, 8, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Langhantel",         4,  6,  70.0, 150, 8, ""),
                    new GeneratedExercise("Seitheben mit Kurzhanteln (exzentrisch)",  4, 12,  10.0,  60, 7, ""),
                    new GeneratedExercise("Vorgebeugtes Seitheben",                   4, 12,   8.0,  60, 7, ""),
                    new GeneratedExercise("Arnold Press (langsam)",                   3,  8,  22.0, 120, 7, ""),
                    new GeneratedExercise("Schädelbrechter mit Langhantel",           3,  8,  28.0, 120, 7, ""),
                    new GeneratedExercise("Overhead Trizeps Extension KH",            3, 10,  18.0,  90, 7, "")
            ));
            case 2 -> new GeneratedDay("Tag A – Push", "Brust Hypertrophie", List.of(
                    new GeneratedExercise("Bankdrücken weiter Griff (Pause unten)",   4,  6,  85.0, 150, 8, ""),
                    new GeneratedExercise("Guillotine Press",                         4,  8,  68.0, 120, 7, ""),
                    new GeneratedExercise("Low-to-High Kabelzug",                     3, 15,  15.0,  60, 7, ""),
                    new GeneratedExercise("Dips (Gewicht + langsam exzentrisch)",     4, 10,  15.0, 120, 7, ""),
                    new GeneratedExercise("Z-Press sitzend",                          3,  8,  42.0, 120, 8, ""),
                    new GeneratedExercise("Maschinen-Seitheben",                      3, 15,   0.0,  60, 6, ""),
                    new GeneratedExercise("Trizeps Pushdown am Kabelzug",             3, 15,  28.0,  60, 7, "")
            ));
            default -> new GeneratedDay("Tag A – Push", "Kraft & Kontrolle", List.of(
                    new GeneratedExercise("Bankdrücken mit Langhantel (5×5)",         5,  5,  90.0, 180, 8, ""),
                    new GeneratedExercise("Schrägbankdrücken mit Kurzhanteln",        4,  8,  30.0, 120, 8, ""),
                    new GeneratedExercise("Pec Deck Maschine",                        3, 12,   0.0,  90, 7, ""),
                    new GeneratedExercise("Schulterdrücken mit Kurzhanteln (sitzend)",4,  8,  24.0, 120, 8, ""),
                    new GeneratedExercise("Seitheben am Kabelzug",                    4, 15,  12.0,  60, 7, ""),
                    new GeneratedExercise("Nahgriff Bankdrücken",                     3,  8,  70.0, 120, 7, ""),
                    new GeneratedExercise("Kickbacks mit Kurzhantel",                 3, 12,  10.0,  60, 6, "")
            ));
        };

        // ── Pull-Tage (4 Varianten) ───────────────────────────────────────────
        GeneratedDay pull = switch (variant) {
            case 0 -> new GeneratedDay("Tag B – Pull", "Rücken Kraft + Klimmzüge", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht",              5,  5,  20.0, 150, 8, ""),
                    new GeneratedExercise("Langhantelrudern (Pause oben 1s)",         4,  6,  80.0, 150, 8, ""),
                    new GeneratedExercise("Latzug eng Griff (Tempo 3-1-1)",          3, 10,  65.0,  90, 7, ""),
                    new GeneratedExercise("Chest Supported Row",                      4,  8,  50.0, 120, 7, ""),
                    new GeneratedExercise("Face Pulls am Kabelzug",                   3, 15,  22.0,  60, 6, ""),
                    new GeneratedExercise("Hammer Curls mit Kurzhanteln",             4, 10,  18.0,  60, 7, ""),
                    new GeneratedExercise("Incline Dumbbell Curl",                    3, 10,  14.0,  60, 7, "")
            ));
            case 1 -> new GeneratedDay("Tag B – Pull", "Rücken-Dicke & Athletik", List.of(
                    new GeneratedExercise("Kreuzheben (Kraft, kein Bein-Fokus)",      5,  3, 130.0, 180, 9, ""),
                    new GeneratedExercise("Pendlay Rudern",                           4,  5,  70.0, 150, 8, ""),
                    new GeneratedExercise("Ringklimmzüge (Tempo 3-0-1)",              4,  6,   0.0, 120, 8, ""),
                    new GeneratedExercise("Meadows Row",                              3,  8,  36.0, 120, 7, ""),
                    new GeneratedExercise("Rear Delt Maschine",                       3, 15,   0.0,  60, 6, ""),
                    new GeneratedExercise("Zottman Curl (langsam exzentrisch)",       4,  8,  16.0,  90, 7, ""),
                    new GeneratedExercise("Reverse Curls",                            3, 12,  14.0,  60, 6, "")
            ));
            case 2 -> new GeneratedDay("Tag B – Pull", "Vertikale & Horizontale Kraft", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht (weiter Griff)",5, 4,  22.0, 150, 8, ""),
                    new GeneratedExercise("T-Bar Rudern",                              4,  6,  55.0, 150, 8, ""),
                    new GeneratedExercise("Seal Row (exzentrisch 4s)",                 3,  8,  44.0, 120, 7, ""),
                    new GeneratedExercise("Straight-Arm Pulldown",                     3, 12,  25.0,  90, 7, ""),
                    new GeneratedExercise("Band Pull-Aparts",                          3, 20,   0.0,  60, 6, ""),
                    new GeneratedExercise("Bizeps-Curl mit Langhantel",                4,  8,  32.0,  90, 7, ""),
                    new GeneratedExercise("Spider Curls",                              3, 12,  12.0,  60, 6, "")
            ));
            default -> new GeneratedDay("Tag B – Pull", "Rücken Hypertrophie", List.of(
                    new GeneratedExercise("Klimmzüge mit Zusatzgewicht (Untergriff)",  5,  5,  15.0, 150, 8, ""),
                    new GeneratedExercise("Seilzug-Rudern sitzend (Pause vorne 2s)",   4,  8,  60.0, 120, 7, ""),
                    new GeneratedExercise("Einarmiges Kurzhantelrudern",                4,  8,  36.0, 120, 7, ""),
                    new GeneratedExercise("Latzug weit Griff",                          3, 10,  65.0,  90, 7, ""),
                    new GeneratedExercise("W-Raises",                                   3, 15,   6.0,  60, 6, ""),
                    new GeneratedExercise("Preacher Curl",                              4,  8,  22.0,  90, 7, ""),
                    new GeneratedExercise("Kabelzug-Curl (isometrisches Halten 3s)",    3, 10,  22.0,  60, 7, "")
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
                    .timeout(Duration.ofSeconds(60))
                    .onErrorResume(e -> { log.warn("Ollama Text: {}", e.getMessage()); return Mono.empty(); })
                    .block();
        } catch (Exception e) { log.error("Ollama Text Fehler: {}", e.getMessage()); return null; }
    }

    private String callOllamaJson(String userPrompt) {
        String url = aiConfig.getOllama().getBaseUrl() + "/api/generate";
        String fullPrompt = buildPlanSystemPrompt() + "\n\n" + userPrompt;
        Map<String, Object> body = Map.of("model", aiConfig.getOllama().getModel(),
                "prompt", fullPrompt, "stream", false, "format", "json",
                // ── Temperature erhöht: 0.15 → 0.35 für mehr Variabilität ──
                "options", Map.of("temperature", 0.35, "num_predict", 3000));
        try {
            return webClient.post().uri(url).header("Content-Type", "application/json").bodyValue(body)
                    .retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), r -> r.bodyToMono(String.class)
                            .doOnNext(e -> log.warn("Ollama JSON HTTP-Fehler: {}", e)).flatMap(e -> Mono.empty()))
                    .bodyToMono(Map.class)
                    .map(m -> (m != null && m.get("response") != null) ? (String) m.get("response") : null)
                    .timeout(Duration.ofSeconds(320))
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
                "temperature", 0.4, "max_tokens", 4000);
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
                    .timeout(Duration.ofSeconds(150))
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