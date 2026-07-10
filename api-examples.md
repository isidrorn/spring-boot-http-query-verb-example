# API examples — cURL reference

A per-resource, copy-pasteable reference of every route. For a scripted, runnable walkthrough of
the whole flow (create users, filter slots with `QUERY`, schedule and cancel a meeting) against a
live instance, see [`demo.sh`](demo.sh) instead — this file is for browsing and copy-pasting one
request at a time.

Start the app first (no Maven wrapper is checked into this repo — use a local Maven install with a
**JDK 21** toolchain; see the [README](README.md#run) for why):
```bash
# With docker-compose (PostgreSQL)
docker-compose up

# Or local H2 (no Docker required)
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

> The seeder creates two users (Alice and Bob). Check the logs for their IDs:
> `Seeded user='Alice' userId=1 calendarId=1`

```bash
BASE="http://localhost:8080"
```

Dates below are placeholders — swap them for something in the future relative to when you run
this, or just run [`demo.sh`](demo.sh), which computes them dynamically.

---

## Users

```bash
# List all users
curl -s "$BASE/api/users" | jq

# Get one user
curl -s "$BASE/api/users/1" | jq

# Create a user (also creates their calendar automatically)
curl -s -X POST "$BASE/api/users" \
  -H "Content-Type: application/json" \
  -d '{"name":"Carol","email":"carol@example.com"}' | jq

# Invalid input → 400 with a ProblemDetail body (see RequestValidator)
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users" \
  -H "Content-Type: application/json" \
  -d '{"name":"","email":"not-an-email"}'
```

---

## Slots

Every `SlotResponse` carries the owning `userId`, so a slot (or a meeting's slot list) is always
traceable back to its participant without a second lookup.

```bash
USER=1   # replace with the actual userId from the seeder log

# List all slots (GET — no filter)
curl -s "$BASE/api/users/$USER/slots" | jq

# ── HTTP QUERY verb — filter by body ──────────────────────────────────────────
# Filter by status
curl -s -X QUERY "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"status":"FREE"}' | jq

# Filter by time range
curl -s -X QUERY "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"from":"2027-01-01T08:00:00Z","to":"2027-01-01T11:00:00Z"}' | jq

# Combined filter
curl -s -X QUERY "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"status":"FREE","from":"2027-01-01T08:00:00Z","to":"2027-01-01T12:00:00Z"}' | jq

# No body at all → treated as "no filter", same as GET. This is the core QUERY gotcha this
# project exists to demonstrate: some clients don't send a body for a non-standard method,
# so an absent body must NOT be rejected — see SlotHandler.parseFilter.
curl -s -X QUERY "$BASE/api/users/$USER/slots" -H "Accept: application/json" | jq
# ──────────────────────────────────────────────────────────────────────────────

# Get single slot
curl -s "$BASE/api/users/$USER/slots/1" | jq

# Create a slot
curl -s -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2027-01-02T09:00:00Z","endTime":"2027-01-02T10:00:00Z"}' | jq

# Create an overlapping slot → 409 (see the locking note in the README's Domain section)
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2027-01-02T09:30:00Z","endTime":"2027-01-02T10:30:00Z"}'

# Mark slot as BUSY
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"status":"BUSY"}' | jq

# Mark slot as FREE again
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"status":"FREE"}' | jq

# Delete a slot
curl -s -X DELETE "$BASE/api/users/$USER/slots/3" -o /dev/null -w "%{http_code}\n"
```

---

## Meetings

```bash
ALICE=1
BOB=2
ALICE_SLOT=1   # Alice's FREE slot
BOB_SLOT=4     # Bob's FREE slot in the same time window

# Schedule a meeting (converts Alice's slot, books Bob's slot)
curl -s -X POST "$BASE/api/users/$ALICE/slots/$ALICE_SLOT/meeting" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Team sync\",\"description\":\"Weekly\",\"participantSlotIds\":[$BOB_SLOT]}" | jq

# Invalid input (no participants) → 400
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$ALICE/slots/$ALICE_SLOT/meeting" \
  -H "Content-Type: application/json" \
  -d '{"title":"Solo","description":"","participantSlotIds":[]}'

# Get meeting by id — each slot in the response carries its owning userId
curl -s "$BASE/api/meetings/1" | jq

# Cancel meeting (frees all participant slots)
curl -s -X DELETE "$BASE/api/users/$ALICE/slots/$ALICE_SLOT/meeting" -o /dev/null -w "%{http_code}\n"
```

---

## Observability

```bash
# Health
curl -s "$BASE/actuator/health" | jq

# Prometheus metrics
curl -s "$BASE/actuator/prometheus" | grep http_server_requests

# Swagger UI
open "$BASE/swagger-ui.html"
```
