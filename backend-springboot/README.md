# backend-springboot

Spring Boot application (Java 21, Spring Boot 3.5).

## Milestone 1

This folder contains **mock external REST APIs** that the Camunda REST Outbound Connectors call, so the BPMN models can be executed end to end. There are no job workers in Milestone 1; all in-process logic is done with FEEL script tasks in the BPMN.

| Method and path | Auth | Stands in for |
|---|---|---|
| `POST /api/background-checks` | header `X-API-Key` | Background-verification vendor (EMP-9xxx → FLAGGED) |
| `POST /api/iam/accounts` | Bearer token | IAM / GitHub / Keycloak account creation |
| `GET /api/iam/accounts?employeeId=` | none | List created accounts |
| `POST /api/courier/shipments` | none | Courier booking |
| `POST /api/slack/messages` | Bearer token | Slack `chat.postMessage` |

A wrong or missing secret returns `401`.

## Run

```bash
cp ../.env.example ../.env    # then put your own values in ../.env
mvn spring-boot:run           # http://localhost:8095
```

Secrets are read from `../.env`, which is git-ignored. Do not commit secrets.

## Milestone 2 (planned)

REST APIs for the React frontend, JPA entities and repositories (PostgreSQL), services, and Zeebe job workers.
