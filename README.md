# Zero-Trust Employee Onboarding & Multi-App Provisioning

Workflow automation project on **Camunda 8** (project idea 28, inspired by Atlassian).

HR hires a new engineer. The manager selects the software tools the engineer needs (GitHub, AWS, Jira, Slack) and the hardware. The workflow then runs three things in parallel:

1. **Background verification and reference check**: an embedded sub-process. If it fails, onboarding is cancelled and the laptop shipment is halted.
2. **Zero-trust access**: security approves each requested app separately, and only approved apps get an account.
3. **Hardware**: a reusable *Hardware Asset Procurement & Courier* process, started by a call activity.

When all three are done, the new hire is welcomed on Slack.

All data in this repository is synthetic. No real credentials are stored; secrets are supplied through environment variables.

## Status

| Milestone | Scope | Status |
|---|---|---|
| 1. BPMN Design and Architecture | Executable BPMN, mandatory constructs, connectors and FEEL, data contracts | Done |
| 2. Full-Stack Integration and Deployment | React frontend and custom Tasklist, Spring Boot services with JPA/PostgreSQL, Docker | Planned |

## Repository structure

```
finalProjectCamunda/
├── bpmn-models/
│   ├── main/employee-onboarding.bpmn        Main BPMN process
│   ├── child/hardware-procurement.bpmn      Child BPMN process (called by the call activity)
│   ├── forms/                               Camunda forms (app-access-approval, reference-check, hardware-packing)
│   ├── diagrams/                            Rendered diagrams
│   ├── test-payloads/                       Start payloads for the test scenarios
│   └── README.md                            Models, data contracts and deployment instructions
├── backend-springboot/                      REST APIs (Milestone 1: mock external systems for the connectors)
├── frontend-react/                          React app and custom Tasklist (Milestone 2)
├── .env.example                             Names of the secrets to provide
└── README.md
```

The Docker files are added in Milestone 2.

## Process overview

**Main process** `employee-onboarding`:

```
New hire submitted → Validate onboarding request (FEEL script) → Request valid?
   no  → Request rejected
   yes → parallel split
        ├─ Background verification & reference check (sub-process)
        │     Run background check (REST) → Background clear? → HR reference check → References confirmed?
        │     ├─ timer boundary (non-interrupting): Verification SLA reached → Remind HR on Slack (REST)
        │     └─ error boundary: Verification failed → Record cancellation reason → Onboarding cancelled (terminate)
        ├─ Security approval per app   (user task, PARALLEL multi-instance over requestedApps)
        │     → Provision app account (REST, SEQUENTIAL multi-instance over approved apps)
        └─ Hardware asset procurement & courier (call activity → hardware-procurement)
   parallel join → Welcome new hire on Slack (REST) → Employee onboarded
```

**Child process** `hardware-procurement`: Hardware requested → Assign asset tag (FEEL) → Pack laptop and accessories (user task) → Book courier (REST) → Laptop shipped.

## Mandatory constructs

| Construct | Where |
|---|---|
| Parallel multi-instance | `Security approval per app`: one user task per requested app (`inputCollection =requestedApps`) |
| Sequential multi-instance | `Provision app account (REST)`: one IAM call per approved app (`=appApprovals[approved = true].app`) |
| Sub-process with error boundary | `Background verification & reference check` catches `VERIFICATION_FAILED` |
| Sub-process with timer boundary | Non-interrupting timer `=if verificationSla != null then verificationSla else "P3D"` |
| Reusable call activity | `Hardware asset procurement & courier` → process `hardware-procurement` |
| Outbound connectors | REST Outbound Connector template (v12) on 5 tasks: background check, IAM provisioning, courier, two Slack messages |
| Externalized secrets | `{{secrets.IAM_API_TOKEN}}`, `{{secrets.BGV_API_KEY}}`, `{{secrets.SLACK_BOT_TOKEN}}` |
| Data contract | Call activity with explicit input and output mappings (no variable propagation); multi-instance input and output elements |

## How to run (local)

Requirements: Camunda 8 Run 8.8, Java 21, Maven, Camunda Desktop Modeler 5.x.

1. Create the secrets file:
   ```bash
   cp .env.example .env
   ```
   Then put your own values in `.env`.

2. Start Camunda 8 Run with the secrets in its environment, so the Connectors runtime can resolve `{{secrets.*}}`:
   ```bash
   cd ~/Downloads/c8run-8.8.32
   set -a && source /path/to/finalProjectCamunda/.env && set +a
   ./start.sh
   ```

3. Start the mock external APIs (port 8095). They read the same `.env`:
   ```bash
   cd backend-springboot
   mvn spring-boot:run
   ```

4. Deploy from Desktop Modeler (target Camunda 8.8, c8run local): the three forms, then `child/hardware-procurement.bpmn`, then `main/employee-onboarding.bpmn`.

5. Start a scenario:
   ```bash
   curl -u demo:demo -H "Content-Type: application/json" \
     -X POST http://localhost:8080/v2/process-instances \
     -d @bpmn-models/test-payloads/1-happy-path.json
   ```

6. Complete the user tasks in Tasklist (`http://localhost:8080/tasklist`, demo/demo) and follow the instance in Operate (`http://localhost:8080/operate`).

## Test scenarios

| # | Payload | What happens |
|---|---|---|
| 1 | `1-happy-path.json` | 4 parallel approval tasks (approve GITHUB, JIRA and SLACK, reject AWS) → 3 sequential IAM calls; HR reference check; packing task in the child process; SLA timer fires after 45 s and reminds HR; Slack welcome → **Employee onboarded** |
| 2 | `2-background-check-failed.json` | EMP-9xxx is flagged by the background check → error end → error boundary → **Onboarding cancelled** (terminate end). The approval tasks and the hardware child process are cancelled. |
| 3 | `3-invalid-request.json` | FEEL validation fails → **Request rejected** |

## Mock external APIs (`backend-springboot`, port 8095)

| Method and path | Auth | Stands in for |
|---|---|---|
| `POST /api/background-checks` | header `X-API-Key` | Background-verification vendor (EMP-9xxx → FLAGGED) |
| `POST /api/iam/accounts` | Bearer token | IAM / GitHub / Keycloak account creation |
| `GET /api/iam/accounts?employeeId=` | none | List created accounts |
| `POST /api/courier/shipments` | none | Courier booking |
| `POST /api/slack/messages` | Bearer token | Slack `chat.postMessage` |

A missing or wrong secret returns `401`. The connector then retries 3 times and raises an incident in Operate.
