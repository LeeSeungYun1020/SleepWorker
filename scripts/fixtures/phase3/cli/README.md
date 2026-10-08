# Phase 3 CLI evidence

Actual measurements, not synthetic responses. Original stderr/stdout and capture metadata
are retained. Model/effort is explicit in argv. User session evidence is metadata-only.

- Codex 0.160.1: Luna medium new/resume; Astra medium new; Sol medium new.
- agy 1.3.0: Flash high/high new/resume, with token usage in stdout JSON.
- `agy-logged-out-*`: first user-reported desktop logout; CLI remained authenticated.
  The unbounded attempt hit the 30s harness timeout; the bounded follow-up succeeded.
  Do not use these as unauthenticated failure evidence.
- `agy131-logged-out*`: after the user fully logged out and updated to 1.3.1 on 2026-10-07, model listing
  and an actual request both exited 1. The model-list exact sign-in message supports
  non-generative preflight LoggedOut detection. Execution additionally requires JSON ERROR
  plus explicit authentication-required stderr to classify AUTH; timeout alone is Unknown.
- Available model names are not interchangeable with execution measurements. Luna low and
  other unmeasured efforts remain unverified; CLI versions retain separate contracts.

- 2026-10-08: agy 1.3.1 login restored; Flash high new/resume and model catalog succeeded.
- Codex 0.160.0 installed CLI: Luna medium new/resume succeeded. The app-bundled
  0.162.0-alpha.2 was not substituted or accepted without measurements.
