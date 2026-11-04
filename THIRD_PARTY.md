# Source and data provenance

The integrated frontend and game rules derive from the team's repository:
https://github.com/KWU-2026-Capstone-Team/deepGame
Reference commit: `85d18621a8d82e7299036aa8725b150932b1a617`.

Preserved concepts: forensic game presentation, REAL/FAKE keyboard decisions, difficulty and balanced sampling, combo rule (100 + 10 × prior consecutive correct answers), explanations and distinction between clip-specific cues and general tips.

The original server-memory game store and browser-authoritative score calculation are replaced with the Service API and durable server-side records. No claim is made that this work grants any license beyond the team's existing rights.

Game media and answer catalog are read from an explicitly supplied local deepGame checkout. They are not copied into the static frontend or this repository. FaceForensics++ and any other dataset's actual terms and consent/redistribution conditions must be verified by the team before public media hosting. Import metadata records this review as pending; it is not a permission grant. Basic clips can be used to check local integration, not to infer general detection accuracy.

New production packages are reused from deepGame's locked Next.js/React/Tailwind ecosystem; see frontend/package-lock.json for exact transitive versions. Python dependencies remain in deeptector-backend/requirements-lock.txt.
