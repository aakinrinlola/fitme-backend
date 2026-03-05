# KI-basierte Trainingsplan-Anwendung — Backend

## Architektur-Übersicht

```
┌─────────────────────────────────────────────────────────────┐
│                     Angular Frontend                         │
│         (Benutzer gibt RPE-Feedback nach Training)           │
└──────────────────────┬──────────────────────────────────────┘
                       │ POST /api/feedback/{userId}
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  Spring Boot Backend                         │
│                                                              │
│  ┌──────────────────┐    ┌──────────────────────────────┐   │
│  │ FeedbackController│───▶│      TrainingService          │   │
│  └──────────────────┘    │  (Orchestrierung)              │   │
│                          │                                │   │
│                          │  ┌────────────────────────┐    │   │
│                          ├─▶│ RpeAdjustmentService    │    │   │
│                          │  │ (Regelbasierte Logik)   │    │   │
│                          │  │ RPE → Gewicht/Wdh/Sätze │    │   │
│                          │  └────────────────────────┘    │   │
│                          │                                │   │
│                          │  ┌────────────────────────┐    │   │
│                          └─▶│ AiService               │    │   │
│                             │ (LLM-Erklärung)         │    │   │
│                             └──────┬─────────────────┘    │   │
│                                    │                      │   │
│  ┌───────────────┐                 │                      │   │
│  │  PostgreSQL    │                 │                      │   │
│  │  (Datenbank)   │                 │                      │   │
│  └───────────────┘                 │                      │   │
└────────────────────────────────────┼──────────────────────┘
                                     │
                          ┌──────────▼──────────┐
                          │   Ollama / OpenAI    │
                          │   (LLM-Server)       │
                          └─────────────────────┘
```

## Schnellstart

### Option 1: Lokale Entwicklung (H2-Datenbank)

```bash
# Projekt starten (H2 in-memory, kein Docker nötig)
./mvnw spring-boot:run

# H2-Konsole erreichbar unter: http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:trainingsdb / User: sa / Passwort: (leer)
```

### Option 2: Docker (PostgreSQL + Ollama)

```bash
# 1. Alle Services starten
docker compose up -d

# 2. Ollama-Modell laden (nur beim ersten Mal!)
docker exec -it trainings-ollama ollama pull llama3.2

# 3. Prüfen ob alles läuft
docker compose ps

# Backend erreichbar unter: http://localhost:8080
```

## API-Endpunkte

### 1. Benutzer anlegen
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "akintayo",
    "email": "test@example.com",
    "age": 25,
    "weightKg": 80,
    "heightCm": 180,
    "fitnessLevel": "INTERMEDIATE"
  }'
```

### 2. Trainingsplan erstellen
```bash
curl -X POST http://localhost:8080/api/training-plans \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "planName": "Push Day",
    "description": "Brust, Schulter, Trizeps",
    "exercises": [
      { "exerciseName": "Bankdrücken", "sets": 4, "reps": 8, "weightKg": 60, "restSeconds": 120, "targetRpe": 7 },
      { "exerciseName": "Schulterdrücken", "sets": 3, "reps": 10, "weightKg": 30, "restSeconds": 90, "targetRpe": 7 },
      { "exerciseName": "Trizeps Pushdown", "sets": 3, "reps": 12, "weightKg": 20, "restSeconds": 60, "targetRpe": 8 }
    ]
  }'
```

### 3. ⭐ Trainings-Feedback einreichen (Kernfunktion!)
```bash
curl -X POST http://localhost:8080/api/feedback/1 \
  -H "Content-Type: application/json" \
  -d '{
    "trainingPlanId": 1,
    "sessionRpe": 7,
    "userNote": "Bankdrücken fühlte sich heute schwer an",
    "exerciseFeedbacks": [
      { "plannedExerciseId": 1, "exerciseRpe": 8, "setsCompleted": 4, "repsCompleted": 8, "weightUsed": 60.0 },
      { "plannedExerciseId": 2, "exerciseRpe": 6, "setsCompleted": 3, "repsCompleted": 10, "weightUsed": 30.0 },
      { "plannedExerciseId": 3, "exerciseRpe": 7, "setsCompleted": 3, "repsCompleted": 12, "weightUsed": 20.0 }
    ]
  }'
```

**Antwort (Beispiel):**
```json
{
  "sessionId": 1,
  "sessionRpe": 7,
  "aiExplanation": "Dein Training war heute im optimalen Bereich! Beim Bankdrücken hast du RPE 8 gemeldet – das war nah am Limit, deshalb reduzieren wir das Gewicht leicht auf 57 kg...",
  "adjustments": [
    {
      "exerciseId": 1,
      "exerciseName": "Bankdrücken",
      "previousWeight": 60.0,
      "newWeight": 57.0,
      "previousReps": 8,
      "newReps": 8,
      "adjustmentReason": "RPE 8: Optimaler Belastungsbereich. Parameter beibehalten."
    }
  ],
  "adherenceStats": {
    "totalPlanned": 1,
    "totalCompleted": 1,
    "adherencePercent": 100.0
  }
}
```

### 4. Trainingshistorie abrufen
```bash
curl http://localhost:8080/api/feedback/history/1
```

### 5. Trainingsplan anzeigen (mit aktuellen Werten)
```bash
curl http://localhost:8080/api/training-plans/1
```

## RPE-Anpassungslogik

| RPE  | Bedeutung      | Gewicht-Anpassung | Aktion                     |
|------|----------------|-------------------|----------------------------|
| 1–4  | Sehr leicht    | +10%              | Gewicht + Wdh erhöhen      |
| 5–6  | Leicht         | +5%               | Gewicht moderat erhöhen     |
| 7    | Zielbereich    | +2.5%             | Minimale Steigerung         |
| 8    | Optimal        | ±0%               | Beibehalten                 |
| 9    | Schwer         | −5%               | Leichte Reduktion           |
| 10   | Maximum        | −10%              | Deutliche Reduktion + Wdh↓  |

## KI-Integration wechseln

### Ollama (Self-Hosted, Standard)
Kein API-Key nötig, Daten bleiben lokal.
```yaml
# In docker-compose.yml:
AI_PROVIDER: ollama
OLLAMA_URL: http://ollama:11434
OLLAMA_MODEL: llama3.2
```

### OpenAI API
```yaml
# In docker-compose.yml:
AI_PROVIDER: openai
OPENAI_API_KEY: sk-dein-key-hier
OPENAI_MODEL: gpt-4o-mini
```

## Projektstruktur

```
src/main/java/com/mike/backend/myBackendTest/
├── MyBackendTestApplication.java     # Einstiegspunkt
├── config/
│   ├── AiConfig.java                 # KI-Provider-Konfiguration
│   └── WebConfig.java                # CORS + WebClient
├── controller/
│   ├── UserController.java           # /api/users
│   ├── TrainingPlanController.java   # /api/training-plans
│   └── FeedbackController.java       # /api/feedback ⭐
├── dto/
│   ├── CreateUserRequest.java
│   ├── CreateTrainingPlanRequest.java
│   ├── SessionFeedbackRequest.java   # Input vom Frontend
│   └── SessionFeedbackResponse.java  # Output mit KI-Antwort
├── entity/
│   ├── AppUser.java
│   ├── TrainingPlan.java
│   ├── PlannedExercise.java
│   ├── TrainingSession.java
│   └── SessionExercise.java
├── exception/
│   ├── ResourceNotFoundException.java
│   └── GlobalExceptionHandler.java
├── repository/
│   ├── UserRepository.java
│   ├── TrainingPlanRepository.java
│   ├── PlannedExerciseRepository.java
│   └── TrainingSessionRepository.java
└── service/
    ├── UserService.java
    ├── TrainingService.java          # Orchestrierung ⭐
    ├── RpeAdjustmentService.java     # Regelbasierte Logik ⭐
    └── AiService.java                # LLM-Anbindung ⭐
```

## Nächste Schritte (Frontend-Anbindung)

In deinem Angular-Frontend brauchst du einen HTTP-Call wie:

```typescript
// feedback.service.ts
import { HttpClient } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class FeedbackService {
  private apiUrl = 'http://localhost:8080/api';

  constructor(private http: HttpClient) {}

  submitFeedback(userId: number, feedback: SessionFeedback) {
    return this.http.post<SessionFeedbackResponse>(
      `${this.apiUrl}/feedback/${userId}`,
      feedback
    );
  }
}
```
