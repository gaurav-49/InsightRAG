# Halcyon Robotics — Engineering Handbook

Maintained by the Developer Experience team. Last reviewed March 2026. This handbook describes how software engineering works at Halcyon: how we write, review, test, ship and operate code.

## 1. Repositories and Branching

All production code lives in the company Git hosting service under the `halcyon` organisation. We use trunk-based development: the default branch is `main`, and short-lived feature branches are merged back within 3 working days. Long-running branches are discouraged; use feature flags to hide unfinished work instead.

Branch names follow the pattern `<ticket-id>-<short-description>`, for example `ROB-1432-gripper-timeout`. Commits to `main` must be made through pull requests; direct pushes are blocked.

## 2. Code Review

Every pull request needs at least one approval from a code owner of the affected area. Changes to safety-critical firmware, authentication, or payments need two approvals, one of which must come from a staff engineer or above.

Reviewers should respond within 1 business day. Authors should keep pull requests under 400 changed lines where possible; larger changes should be split or accompanied by a design note. A reviewer who approves a change shares responsibility for it.

Automated checks must pass before merge: unit tests, linting, the dependency vulnerability scan, and the licence check. Overriding a failed check requires approval from the owning team's engineering manager and must be justified in the pull request description.

## 3. Testing Standards

New code should include unit tests. Services must keep line coverage above 80 percent, and coverage may not decrease in a pull request by more than 1 percentage point. Integration tests run against real dependencies in containers rather than mocks where practical. Flaky tests must be fixed or quarantined within 2 business days of being reported; quarantined tests are tracked in the flaky-test dashboard.

Firmware for the robot arms is additionally tested on hardware-in-the-loop rigs before every release.

## 4. Deployment

Services are deployed through the Conveyor deployment pipeline. Every merge to `main` deploys automatically to the staging environment. Production deployments are promoted from staging after the automated smoke suite passes.

Production deploys happen Monday to Thursday between 09:00 and 16:00 local time. There are no production deploys on Fridays, on public holidays, or during the company-wide change freeze, which runs from 20 December to 3 January. Emergency fixes during a freeze need approval from the VP of Engineering.

Every deployment must be reversible. Database migrations are written to be backwards compatible with the previous release so that a rollback never needs a schema change. Canary releases send 5 percent of traffic to the new version for at least 30 minutes before full rollout.

## 5. On-Call

Each service team runs its own on-call rotation. Rotations are one week long and hand over on Tuesdays at 11:00. Engineers join the rotation after completing 3 months at Halcyon and shadowing one full rotation.

The on-call engineer must acknowledge a page within 5 minutes during the rotation. Engineers receive an on-call allowance of 400 dollars per week on rotation, plus time off in lieu for any incident work between 22:00 and 07:00. Nobody should be on call for more than one week in any four-week period.

If the primary on-call engineer does not acknowledge a page within 5 minutes, the page escalates to the secondary; after a further 10 minutes it escalates to the engineering manager.

## 6. Architecture Decisions

Significant technical decisions are recorded as Architecture Decision Records (ADRs) in the `adr/` folder of the relevant repository. An ADR states the context, the decision, the alternatives considered and the consequences. ADRs are never deleted; a superseded decision is marked as superseded with a link to its replacement.

New services must be approved by the Architecture Review Board, which meets every second Wednesday.

## 7. Dependencies

Third-party dependencies must use licences on the approved list (MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC and MPL-2.0). GPL-licensed dependencies require approval from Legal. Critical vulnerabilities reported by the dependency scanner must be patched within 7 days, and high-severity vulnerabilities within 30 days.

## 8. Documentation

Every service has a README with an owner, a runbook link, and instructions for running it locally. Runbooks must be updated within 5 business days after any incident that revealed a gap.
