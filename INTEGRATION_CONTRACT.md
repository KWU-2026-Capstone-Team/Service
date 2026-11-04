# DeepGame integration contract (2026-11-04)

Local-first unified service. Frontend: Next.js static export under `frontend/`, served by FastAPI on loopback (one origin). Existing root Pages demo stays intact until deployment authorization. Original deepGame source: ../deepGame-reference, commit 85d18621a8d82e7299036aa8725b150932b1a617.

## API
- Existing analysis/session APIs unchanged; Authorization Bearer session token. Same token used for all features.
- GET /api/v1/games -> {items:[game summaries]}.
- POST /api/v1/games {rounds:1..30,difficulty:'all'|'easy'|'medium'|'hard'} -> {id,status,total_rounds,next_round}. Balanced REAL/FAKE selection within available matching clips; insufficient clips => 409.
- GET /api/v1/games/{id} -> {id,status,total_rounds,answered_rounds,next_round,difficulty,score,max_streak}. Ordinals start at 1.
- GET /api/v1/games/{id}/rounds/{n} -> {round,video_url,answered,result?}. No pre-answer label/explanation/AI scores.
- POST .../answer {verdict:'REAL'|'FAKE',confidence:'LOW'|'MEDIUM'|'HIGH',time_used_seconds?:number} -> {round,answer,ground_truth,user_correct,ai_correct:boolean|null,ai:object|null,explanation,cues:[],tips:[],technique,points,streak}. Confidence defaults MEDIUM for migrated UI. Same answer retries idempotent. Server controls scoring: 100 + 10*prior_streak when correct, 0 on wrong. Timing is self-reported telemetry, never trusted for score/ranking.
- POST .../skip {reason:'VIDEO_UNAVAILABLE'} -> persist skip and advance; a skipped round gets no score and is distinct from incorrect answer. Results track played/skipped denominators explicitly. No fabricated answers on media failure.
- GET .../result -> old fields plus {score,max_streak,grade,average_time_seconds,skipped_rounds,played_rounds,ai_evaluated_rounds,rounds:[reveals]}. ai_score/accuracy null if no evaluated clips. Accuracy uses answered non-skipped rounds, with count clearly returned.
- DELETE /api/v1/games/{id} -> 204, owner scoped (can cancel active game).
- Optional POST .../replace returns another unused same-label clip at same ordinal; if unsupported UI uses skip explicitly, never inventing replacement.

## Storage/import
- Durable migrations preserve existing analyses/sessions/game rows. Existing game_clips spatial/temporal scores migrate into nullable separate evaluations; no fake scores for new clips.
- Game clips add difficulty, technique, cues, tips and verified source/license metadata. JSON labels never enter static frontend assets.
- Import local reference 24 clips via backend CLI; avoid copying videos/answer catalog to public export or committing them. Existing data path read-only. Default record permission as local-review-only/pending redistribution, do not assert a license grant.
- Optional evaluated scores are tied to model version; no automatic model downloads. Actual model assessment is explicit CLI only.

## Acceptance
- 5/10/15 rounds, difficulty, server score/combo, keyboard controls, game resume after reload and backend restart, session-protected history, honest AI unavailable state. Interrupted local analyses fail with WORKER_RESTARTED on restart and require resubmission; queued work remains queued.
- Analysis lifecycle preserved and integrated UI. Error states, absent dataset and invalid video handling.
- npm build static export, Python regression tests + new migration/game tests, local HTTP smoke across real static UI assets and API; browser verification where supported.
