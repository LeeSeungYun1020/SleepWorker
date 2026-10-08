APPROVED
9fa4b3f438326336f2a0b48288e5dd9de5aa7233
4cbf1499de9a6e0eef4498fae84e2669497d2151

- `shared/src/commonMain/kotlin/aiflow/ui/run/RunScreen.kt`: timeline labels now reflect the current automatic attempt out of the two-attempt limit, including the RETRYING transition before attempt 2 is appended.
- `shared/src/commonTest/kotlin/aiflow/RunAttemptLabelTest.kt`: covers no attempts, single attempt, active/completed automatic retry, and manual retry as a separate visit.
- `.aiflow/CHECK.log` records `BUILD SUCCESSFUL`, Python tests `OK`, and `CHECK_EXIT=0`. The reviewed diff stays within the unit scope and does not alter broader Phase 3 contracts.
