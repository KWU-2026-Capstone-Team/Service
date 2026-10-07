# Design

## Source of truth
- Status: Active. Last refreshed: 2026-10-07.
- Primary surface: `index.html`, GitHub Pages with user-operated loopback API.
- Evidence: `prototype/prototype-version.html`, exported screen set, backend API.md and model policy.
## Brand
- Preserve the dark navy, blue accents and traffic-light vocabulary of the prototype.
- Trust: explicit demo labels, real server states, no invented percentages or attention maps.
## Product goals
- Upload, poll, inspect results/artifacts, retain session across reload, delete terminal analyses.
- Non-goals: public backend hosting, accounts, verified detection accuracy, fabricated game dataset.
- Success: Pages browser completes the real local API lifecycle.
## Personas and jobs
- Team members testing on their own Windows PC; no infrastructure account required.
## Information architecture
- Connection setup, video analysis/history/detail, optional curated game, separate original prototype link.
## Design principles
- Server is source of truth. Inference failure is not GREEN. Demo never implies actual face detection.
- Explain local permissions and queued-worker state before suggesting retries.
## Visual language
- Background #0b0f14, surface #151c24, foreground #f2f6fa, muted #a6b5c7, blue #36a9e1.
- System Korean fonts, 16px base, 8px spacing multiples, 14px card radius. No decorative animation.
## Components
- Cards, native labeled inputs, buttons, status text, traffic summary, result metrics and image figures.
- Existing prototype stays unchanged; functional page has its own small stylesheet.
## Accessibility
- Semantic headings, labels, visible focus, live status, keyboard operation, color plus text.
- Target WCAG AA contrast; no flashing/motion. Do not assert certified conformance.
## Responsive behavior
- Two-column analysis layout above 850px, one column below; wrap long IDs and filenames.
## Interaction states
- Offline: explain backend/CORS/browser permission. Empty: no invented history.
- Busy: disable duplicate mutations; polls never overlap. Terminal failure: display server reason.
- Success: actual policy and scores. Delete disabled until terminal. Connection explicit, not automatic.
## Content voice
- Korean, clear and non-alarmist; model signals are not proven forgery probabilities.
## Implementation constraints
- Native HTML/CSS/ES modules, relative asset paths for /Service/, no CDN or new production dependencies.
- API fixed to loopback, sessionStorage token scoped to origin/tab, no tokens in URLs or logs.
- Automated API/client checks plus browser integration and responsive inspection.
## Open questions
- Curated licensed game data remains a team responsibility. Without it show the server's not-ready error.
- Public backend and actual model hosting are outside this local demo scope.
