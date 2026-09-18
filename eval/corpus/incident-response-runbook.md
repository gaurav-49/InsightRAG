# Halcyon Robotics — Incident Response Runbook

Owner: Site Reliability Engineering. Version 2.3. This runbook describes how Halcyon detects, classifies, manages and learns from production incidents affecting the Halcyon Cloud fleet-management platform and customer-facing services.

## 1. Severity Levels

- **SEV1** — Complete outage of fleet-management, a safety-relevant malfunction reported by a customer, or confirmed exposure of customer data. Target response: incident commander engaged within 15 minutes, status page updated within 30 minutes.
- **SEV2** — Major degradation affecting more than 20 percent of customers, or loss of a critical feature such as remote emergency stop telemetry. Target response: incident commander engaged within 30 minutes.
- **SEV3** — Minor degradation with a workaround available, or a problem affecting a single customer. Handled during business hours by the owning team.
- **SEV4** — Cosmetic issues and internal tooling problems. Tracked as normal tickets.

When in doubt, declare the higher severity. Downgrading later is cheap; under-reacting is not.

## 2. Declaring an Incident

Anyone can declare an incident by running `/incident declare` in the chat tool. This creates an incident channel, pages the on-call incident commander, and opens an incident record. The person declaring the incident states what they observe, the suspected severity, and the first time the problem was noticed.

## 3. Roles

- **Incident Commander (IC)** — owns the incident, coordinates responders, and makes decisions. The IC does not debug.
- **Communications Lead** — updates the status page and customer success team, and posts internal updates every 30 minutes for SEV1 and every 60 minutes for SEV2.
- **Operations Lead** — directs technical investigation and mitigation.
- **Scribe** — keeps the timeline in the incident record.

For SEV1 incidents all four roles must be filled by different people. For SEV2 the IC may also act as communications lead.

## 4. Mitigation First

The first goal is to stop customer impact, not to find the root cause. Prefer reversible mitigations: roll back the last deployment, disable the feature flag, fail over to the secondary region, or scale out. The secondary region is in Frankfurt and can take full production traffic within 20 minutes of a failover decision.

Any action taken against production during an incident must be announced in the incident channel before it is executed.

## 5. Customer Communication

The status page is the single source of truth for customers. For SEV1 incidents affecting safety-relevant functions, the Head of Customer Safety must also notify affected customers directly by phone within 2 hours. Regulators are notified by Legal when required; responders must not contact regulators themselves.

## 6. Resolution and Handover

An incident is resolved when customer impact has ended and monitoring has been normal for at least 60 minutes. Incidents that last longer than 8 hours require a formal handover between incident commanders using the handover template, so that no responder works a shift longer than 8 hours.

## 7. Postmortems

A blameless postmortem is required for every SEV1 and SEV2 incident, and is optional for SEV3. The postmortem draft is due within 5 business days of resolution and is reviewed in the weekly reliability review. It records the timeline, impact, contributing factors, what went well, and action items with owners and due dates.

Action items from SEV1 postmortems must be completed within 30 days; SEV2 action items within 60 days. Overdue action items are reported to the VP of Engineering every month.

## 8. Metrics

SRE reports monthly on number of incidents by severity, mean time to acknowledge, mean time to mitigate, and the percentage of postmortem action items closed on time. The target mean time to mitigate for SEV1 incidents is under 60 minutes.

## 9. Drills

Each quarter SRE runs a game day that simulates a SEV1 incident, including a regional failover to Frankfurt. Every on-call engineer must take part in at least one game day per year.
