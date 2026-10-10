# Phase 3 Material 3 UI audit — 2026-10-08

Reference principles: [Material button hierarchy](https://github.com/material-components/material-web/blob/main/docs/components/button.md),
[Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
[adaptive window classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).
This is a scoped desktop run-screen assessment, not a claim of full accessibility certification.

- Primary action: New Run uses a filled button; preflight is tonal, secondary actions outlined.
  Enabled states reflect preflight and active-run ownership. Destructive stop requires a dialog.
- Color/type: MaterialTheme semantic colors and typography; selected tabs and status surfaces
  use matched container/on-container pairs. Status remains readable as text, not color alone.
- Adaptive layout: at 1280×850, timeline/log appear side by side. At 790×742, toolbar wraps,
  and visit/log selectors expose one panel at a time. Both sizes were visually inspected in
  the actual packaged application; controls did not overlap.
- Interaction: standard Material buttons/chips/text fields expose accessible labels and
  selected/disabled semantics in macOS accessibility trees. Minimum interactive target
  defaults are preserved. Formal screen-reader navigation and all OS text scaling remain
  unmeasured; no blanket WCAG conformance claim is made.
- Feedback: preflight lists exact CLI paths/versions and auth states. Interrupted records
  explicitly prevent resume/retry and direct the user to a new run. Unknown terminal
  durations/results are not displayed as ongoing progress.
- Logs: capped at 20,000 lines per visit in UI, full stream retained on disk; filtering runs
  off the UI dispatcher; follow-to-bottom can be disabled. Dense log tools scroll horizontally. Actual 25,001-line run completed in 4 seconds;
  last line and truncation notice remained reachable after window resize. Passed compact
  preflight details collapse to reserve log space, with a visible expand button.

Broader visual editor, complete settings/history navigation and mobile layouts are outside
Phase 3. Compact desktop validation is based on actual measured windows, not every device.

## 2026-10-10 design follow-up

The editor, history and settings now share theme, selection, section and icon controls.
The application uses a navigation rail and one repository picker. History switches between
list/detail/file at compact widths and shows three panes at wide widths. See
[Phase 6 results](phase6-result.md) for the revised acceptance criteria and actual checks.
The earlier Phase 3 observations remain historical evidence rather than certification of
all later screens or keyboard/screen-reader interactions.
