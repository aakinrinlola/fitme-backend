-- Seed data für Entwicklung (nur im dev-Profil mit H2)
-- Wird automatisch beim Start geladen

INSERT INTO app_user (id, username, email, age, weight_kg, height_cm, fitness_level, created_at)
VALUES (1, 'testuser', 'test@example.com', 25, 80, 180, 'INTERMEDIATE', CURRENT_TIMESTAMP);

INSERT INTO training_plan (id, plan_name, description, user_id, active, created_at)
VALUES (1, 'Push Day', 'Brust, Schulter, Trizeps', 1, true, CURRENT_TIMESTAMP);

INSERT INTO planned_exercise (id, exercise_name, sets, reps, weight_kg, rest_seconds, exercise_order, target_rpe, training_plan_id)
VALUES
  (1, 'Bankdrücken',       4, 8,  60, 120, 0, 7, 1),
  (2, 'Schulterdrücken',   3, 10, 30, 90,  1, 7, 1),
  (3, 'Trizeps Pushdown',  3, 12, 20, 60,  2, 8, 1);
