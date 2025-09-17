# Hospitality Mini

A compact portfolio project demonstrating a small hotel domain with **two Java microservices**, **event-driven integration**, and **Docker-first** local orchestration.

- **Tech stack:** Spring Boot 3, Java 21, Maven, Redis Streams, PostgreSQL, Flyway, Docker Compose
- **Security:** HTTP Basic (username: `user`, password: `password`)
- **APIs:** Low‑latency REST (per service) + Actuator health + OpenAPI/Swagger UI
- **Testing:** k6 smoke/perf test (30s) that creates bookings and checks availability

---

## Architecture at a glance

- **booking-service**: REST + JPA, persists bookings and **publishes to Redis Stream** `booking-events`.
- **availability-service**: **Consumes** Redis Stream events (consumer group `availability`) and **updates per‑day availability** idempotently with **optimistic locking**.
- **PostgreSQL**: Separate **schemas per service** managed by **Flyway** (per-module migrations).
- **Docker Compose**: One command brings up both services + Postgres + Redis.

```text
Client → booking-service ──(Redis Stream: booking-events)──▶ availability-service
          │                                             ▲
          └─────────────── PostgreSQL (schema: booking) ┘
                                    ▲
                  PostgreSQL (schema: availability) ◀── availability-service
```

---

## Prerequisites

- **JDK 21**
- **Maven 3.9+**
- **Docker Desktop**

---

## Quick start (everything via Docker)

From the repo root:

```bash
docker compose up -d --build
```

### Ports

| Service              | Host Port → Container |
| -------------------- | --------------------- |
| booking-service      | **8085 → 8080**       |
| availability-service | **8086 → 8080**       |
| postgres             | **5432**              |
| redis                | **6379**              |

### Health endpoints

> Health is **open** (no auth). APIs below require **HTTP Basic**.

- Booking: <http://localhost:8085/actuator/health>
- Availability: <http://localhost:8086/actuator/health>

### OpenAPI / Swagger UI

- Booking: <http://localhost:8085/swagger-ui.html>
- Availability: <http://localhost:8086/swagger-ui.html>

### Credentials

All protected endpoints use **HTTP Basic**:

```
user / password
```

---

## Try it (cURL)

### Create a booking (fires a Redis Stream event)

```bash
curl -u user:password -X POST   "http://localhost:8085/api/bookings?roomId=deluxe-101&startDate=2025-01-20&endDate=2025-01-22"
```

### Check availability (idempotent, optimistic‑lock safe updates)

```bash
curl -u user:password   "http://localhost:8086/api/availability/deluxe-101?startDate=2025-01-20&endDate=2025-01-22"
```

---

## Smoke test (k6) — one‑liner

Runs a small **30s** load that creates bookings and checks availability against your _published_ ports via `host.docker.internal` (works on Docker Desktop):

```powershell
docker run --rm -v "$(Get-Location)\k6:/scripts" grafana/k6 `
  run /scripts/availability_booking_smoke.js `
  -e BASE_URL_BOOKING=http://host.docker.internal:8085 `
  -e BASE_URL_AVAIL=http://host.docker.internal:8086
```

**Linux alt:** replace `host.docker.internal` with your host IP (or map the Compose network and point at service names).

---

## Dev workflow

Build & test:

```bash
mvn -q verify
```

Build runnable JARs (skip tests):

```bash
mvn -q -DskipTests package
```

Restart **just the apps** (after code changes):

```bash
docker compose up -d --build booking_service availability_service
```

Stop everything:

```bash
docker compose down
```

### Reset DB (drop schemas only; preserves volume)

```bash
docker exec -i hotel_mini-postgres-1 psql -U postgres -d hotel   -c "DROP SCHEMA IF EXISTS booking CASCADE; DROP SCHEMA IF EXISTS availability CASCADE;"
```

### Wipe everything (including DB volume)

```bash
docker compose down -v
```

---

## Design notes

### Schemas per service

- **booking** schema
  - `booking` table
  - `flyway_schema_history_booking`
- **availability** schema
  - `availability`, `processed_event` tables
  - `flyway_schema_history_availability`

### Eventing

- **Stream:** `booking-events`
- **Producer:** `booking-service`
- **Consumer:** `availability-service` (consumer group: `availability`)
- **Semantics:** per‑day rows updated **idempotently** with **optimistic locking**

### API hygiene

- Bean validation on request parameters
- Small `@RestControllerAdvice` for consistent **400** responses

### Observability

- Spring Boot **Actuator** health endpoints
- **OpenAPI/Swagger UI** per service

---

## Troubleshooting

### Health is UP but k6 fails against service names

You’re hitting containers from a separate `docker run`. Either:

1. Use the Compose network and target service names (advanced), **or**
2. Use the provided one‑liner that targets `host.docker.internal:8085/8086`.

### Flyway “Validate failed” or “syntax error near NOT”

You likely mixed earlier migrations. Easiest fixes:

- **Drop schemas only:**

  ```bash
  docker exec -i hotel_mini-postgres-1 psql -U postgres -d hotel     -c "DROP SCHEMA IF EXISTS booking CASCADE; DROP SCHEMA IF EXISTS availability CASCADE;"
  ```

- **Or nuke the volume:**

  ```bash
  docker compose down -v && docker compose up -d --build
  ```

### “Connection refused” on 8085/8086

- Make sure the mapping is **8085→8080** and **8086→8080**.
- Health should be reachable at:
  - <http://localhost:8085/actuator/health>
  - <http://localhost:8086/actuator/health>
- If not, check: `docker compose ps`

---

## Peek into the DB

**Bookings:**

```bash
docker exec -it hotel_mini-postgres-1 psql -U postgres -d hotel -c "SET search_path TO booking; TABLE booking;"
```

**Availability (first 10 days):**

```bash
docker exec -it hotel_mini-postgres-1 psql -U postgres -d hotel -c "SET search_path TO availability; SELECT room_id, available_date, total_rooms, booked_rooms FROM availability ORDER BY available_date LIMIT 10;"
```

---

## What’s inside (quick)

- **booking-service**: REST + JPA + publish to Redis Stream
- **availability-service**: Redis Stream consumer + idempotent updater
- **Flyway** migrations (per module)
- **Basic tests** (unit + MVC slice), **CI workflow**
- **Actuator** + **OpenAPI**

---

# Enterprise Stack Mapping

| This Demo Uses                     | Enterprise Equivalent                           | Notes                                                                      |
| ---------------------------------- | ----------------------------------------------- | -------------------------------------------------------------------------- |
| **Redis Streams**                  | Kafka / AWS Kinesis / SQS                       | Swappable via Spring Cloud Stream or Spring Cloud AWS                      |
| **Postgres**                       | AWS RDS / DynamoDB / Cassandra                  | Same schema concepts; availability projection fits Cassandra/DynamoDB well |
| **Docker Compose**                 | AWS ECS / EKS with Terraform / CloudFormation   | Local-first orchestration; easily extended with IaC                        |
| **HTTP Basic Auth**                | OAuth2 / JWT (Cognito, Okta, Keycloak, etc.)    | Simplified for demo; production-ready security via Spring Security         |
| **Spring Boot Actuator + OpenAPI** | Splunk / Elasticsearch / OpenTelemetry          | Hooks already in place for enterprise observability                        |
| **Code + Issues (Git repo)**       | Bitbucket / GitHub / GitLab + Jira + Confluence | Repo + issue tracking + documentation integration                          |
| **Support**                        | ServiceNow                                      | Incidents and service requests flow into ITSM platforms                    |
| **Logs & Metrics**                 | Splunk / Elasticsearch / Prometheus / Grafana   | Actuator + OpenTelemetry metrics/logs integrate seamlessly                 |
