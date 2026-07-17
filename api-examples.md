# API examples — cURL reference

A per-resource, copy-pasteable reference of every route. For a scripted, runnable walkthrough of
the whole flow (create users, filter slots with `QUERY`, propose/vote/confirm/cancel a meeting)
against a live instance, see [`demo.sh`](demo.sh) instead — this file is for browsing and
copy-pasting one request at a time.

Start the app first (no Maven wrapper is checked into this repo — use a local Maven install with a
**JDK 21** toolchain; see the [README](README.md#run) for why):
```bash
# With docker-compose (PostgreSQL)
docker-compose up

# Or local H2 (no Docker required)
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

> The seeder creates two users (Alice and Bob), each with grid-aligned slots in the same time
> window. Check the logs for their IDs: `Seeded user='Alice' userId=1 calendarId=1`

```bash
BASE="http://localhost:8080"
```

Dates below are placeholders — swap them for something in the future relative to when you run
this, or just run [`demo.sh`](demo.sh), which computes them dynamically. They're also shown aligned
to the default 30-minute slot grid (`scheduling.slot-duration-minutes`) — every `startTime` must
satisfy `epochSecond % (slotDurationMinutes * 60) == 0` or the request is rejected with 400.

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

`endTime` is never supplied by the client — it's always `startTime + slotDurationMinutes` (the
system-wide `scheduling.slot-duration-minutes`, default 30), computed server-side.

```bash
USER=1   # replace with the actual userId from the seeder log

# List all slots (GET — no filter)
curl -s "$BASE/api/users/$USER/slots" | jq

# ── HTTP QUERY verb — filter by body ──────────────────────────────────────────
# Not in Swagger UI: springdoc-openapi can't document a non-standard HTTP method (yet — this is an
# OpenAPI-spec/tooling-version gap, not a bug in this app). See query-endpoint.openapi.yaml for a
# hand-written OpenAPI-style doc of this one route, and design-decisions-v2.md for the full story.
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

# Bulk-create slots — every startTime is created (or none are, on any failure: see
# design-decisions-v2.md on why this is transactional all-or-nothing)
curl -s -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"startTimes":["2027-01-02T09:00:00Z","2027-01-02T09:30:00Z"]}' | jq

# Not grid-aligned → 400
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"startTimes":["2027-01-02T09:07:00Z"]}'

# A startTime that already has a slot → 409 (whole batch fails, nothing is created)
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"startTimes":["2027-01-02T09:00:00Z"]}'

# Mark slot as BUSY
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"status":"BUSY"}' | jq

# Reschedule — endTime is recomputed from the system slot duration, not supplied
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2027-01-02T10:00:00Z"}' | jq

# Delete a slot
curl -s -X DELETE "$BASE/api/users/$USER/slots/3" -o /dev/null -w "%{http_code}\n"
```

A slot booked into a `CONFIRMED` meeting cannot be modified or deleted (409) — a slot only in
`PROPOSED` meetings can be, since nothing has actually been reserved for those yet.

---

## Meetings

A meeting starts `PROPOSED` with no slots booked. It only books slots — and only for participants
who have a full, free cover of the meeting's window — once every `REQUIRED` participant votes YES.

```bash
ALICE=1
BOB=2
CAROL=3

# Propose a meeting: Alice organizes, Bob is required, Carol is optional.
# The organizer's vote is implicitly YES from creation.
curl -s -X POST "$BASE/api/meetings" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Team sync\",\"description\":\"Weekly\",\"organizerUserId\":$ALICE,\"startTime\":\"2027-01-02T09:00:00Z\",\"endTime\":\"2027-01-02T10:00:00Z\",\"requiredParticipantUserIds\":[$BOB],\"optionalParticipantUserIds\":[$CAROL]}" | jq

# Invalid input (blank title) → 400
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"\",\"organizerUserId\":$ALICE,\"startTime\":\"2027-01-02T09:00:00Z\",\"endTime\":\"2027-01-02T10:00:00Z\"}"

# Get meeting by id — participants carry userId, name, role, and current vote
curl -s "$BASE/api/meetings/1" | jq

# Bob (REQUIRED) votes YES — if he's the last REQUIRED participant to vote YES, the
# meeting confirms and books FREE slots covering the window for every participant that has them
curl -s -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"YES"}' | jq

# A REQUIRED participant voting NO cancels the meeting immediately
curl -s -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"NO"}' | jq

# Voting on a meeting that isn't PROPOSED anymore → 409
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"YES"}'

# Cancel a meeting — only the organizer can, and the body identifies the caller.
# If it was CONFIRMED, every booked slot goes back to FREE.
curl -s -w "\n%{http_code}\n" -X DELETE "$BASE/api/meetings/1" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":$ALICE}"

# Non-organizer tries to cancel → 403
curl -s -w "\n%{http_code}\n" -X DELETE "$BASE/api/meetings/1" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":$BOB}"
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
