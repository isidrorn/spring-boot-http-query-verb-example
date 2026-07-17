# minidoodle

A "mini Doodle" meeting-scheduling backend: users define available time slots on a personal
calendar, propose meetings against other users, and a meeting confirms once every required
participant votes yes — booking each participant's free slots automatically. Built with Spring Boot
4.1 and Java 21.

This repo also happens to demonstrate the HTTP `QUERY` method on one route (filtering slots by a
structured body instead of query-string parameters) — that's a self-contained side quest with its
own story worth reading, but it's not the point of the exercise, so it's kept out of this file. See
[`query-method.md`](query-method.md) if you're curious how `QUERY` is routed, and what it took to get
it documented in Swagger UI.

## What it does

- **Time slot management** — create slots with a system-wide, configurable duration
  (`scheduling.slot-duration-minutes`), modify or delete them, mark them busy/free.
- **Meeting scheduling** — propose a meeting with a title, description, and participants (each with
  a role — organizer / required / optional); once every required participant votes yes, the meeting
  confirms and books each participant's free slots for the window automatically.
- **Calendar** is a domain concept only — it's never exposed as its own REST resource; slots are
  addressed through `/api/users/{userId}/slots`.
- **Querying availability** — filter a user's slots by status and/or time range via `QUERY` (or list
  them all via plain `GET`).
- Designed with "hundreds of users, thousands of slots" in mind: an index on `(calendar_id,
  start_time)`, row-level locking sized to avoid serializing unrelated writes (see below), and a
  concurrency test that actually proves it rather than just asserting it in a docstring.

## Domain

```
User  1 ──── 1  Calendar  1 ──── N  Slot  N ──── M  Meeting
                                                       │
                                              1 ──── N  MeetingParticipant
                                                       │
                                              N ──── 1  User
```

- `User` cascades `ALL` to its `Calendar`, which cascades `ALL` to its `Slot`s. Always persist
  through `userRepository.save(user)` when the user is new — saving from the `Calendar` or `Slot`
  side does not cascade upward and throws `TransientPropertyValueException`.
- **Slot duration is a system parameter** (`scheduling.slot-duration-minutes`, default 30 —
  `SlotDurationConfig`), not something a client chooses per-slot. Every slot's `endTime` is always
  `startTime + slotDurationMinutes`, and every `startTime` must land on that grid
  (`epochSecond % (slotDurationMinutes * 60) == 0`).
- A `Slot` can belong to several `PROPOSED` meetings at once, but at most one `CONFIRMED` one — that
  constraint isn't enforced at the DB level (the `slot_meeting` join table has no such check);
  `MeetingService.confirm()` enforces it in code, by only ever calling `Meeting.addSlot()` on slots
  it already confirmed are `FREE`.
- A `Meeting` starts `PROPOSED` with no slots booked. Each participant is a `MeetingParticipant`
  with a `ParticipantRole` (`ORGANIZER` / `REQUIRED` / `OPTIONAL`) and a `Vote`
  (`PENDING` / `YES` / `NO`) — the organizer's vote is implicitly `YES` from creation. Once every
  `REQUIRED` participant has voted `YES`, the meeting confirms and books whichever participants have
  a full, contiguous `FREE` cover of `[startTime, endTime)` — see
  [`design-decisions-v2.md`](design-decisions-v2.md) for why a participant *without* that coverage
  doesn't block confirmation for everyone else. A `REQUIRED` participant voting `NO` cancels the
  meeting immediately.
- `Slot` carries an optimistic-locking `@Version`; two requests racing to book the same slot get a
  409 from the second writer.
- `SlotService.create()`/`update()` take a `PESSIMISTIC_WRITE` lock on the *parent* `Calendar` row
  (`CalendarRepository.findByOwnerIdForUpdate`) rather than on individual `Slot` rows — a row-level
  lock on existing rows can't close a phantom-read gap for a brand-new `INSERT`, so the overlap
  check and the write are serialized per user's calendar instead. `SlotService.create()` builds each
  new `Slot` via a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly, rather
  than going through `Calendar.addSlot()` — the latter mutates (and thus forces a full load of)
  `Calendar.slots`, which would mean reloading every existing slot for that user on every creation.
  See [`spec-review.md`](spec-review.md#2-toctou-race-between-the-overlap-check-and-the-insertupdate)
  for how this was originally found and verified.

## API routes

All routes are declared in
[`SlotRouterConfig`](src/main/java/io/irn/minidoodle/web/SlotRouterConfig.java) as functional
routes (`WebMvc.fn`) rather than `@RestController` methods — the one route using `QUERY`
(`HttpMethod.valueOf("QUERY")`) can't be expressed through `@RequestMapping`, which is closed to a
fixed enum of methods, so the whole API is declared consistently the same way. See
[`query-method.md`](query-method.md) for the full reasoning.

```
GET    /api/users                                  → list users
GET    /api/users/{userId}                         → get user
POST   /api/users                                  → create user (also creates their calendar)

GET    /api/users/{userId}/slots                   → list all slots
QUERY  /api/users/{userId}/slots                   → filter slots (status, from, to in body)
POST   /api/users/{userId}/slots                   → bulk-create slots (startTimes[] in body)
GET    /api/users/{userId}/slots/{slotId}          → get slot
PATCH  /api/users/{userId}/slots/{slotId}          → update slot (startTime, status)
DELETE /api/users/{userId}/slots/{slotId}          → delete slot

POST   /api/meetings                                         → propose a meeting (PROPOSED)
GET    /api/meetings/{meetingId}                              → get meeting
DELETE /api/meetings/{meetingId}                              → cancel meeting (organizer only)
POST   /api/meetings/{meetingId}/participants/{userId}/vote  → cast a vote
```

Every path variable and request body is validated before it reaches business logic — a bad-typed id
or a malformed/mistyped body returns 400 with a specific message, not a 500. See
[`design-decisions-v3.md`](design-decisions-v3.md) and the "Input validation" section of
[`api-examples.md`](api-examples.md).

## Run

No Maven wrapper is checked into this repo — use a local Maven install (or your IDE's bundled one)
with a **JDK 21** toolchain (Lombok's annotation processing does not currently work with newer JDKs
such as 26 — getters/builders/`@Slf4j` silently fail to generate, which shows up as a wall of
"cannot find symbol" compile errors).

```bash
# Without Docker (H2 in-memory)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# With Docker (PostgreSQL) — includes the app and its database, no extra setup
docker-compose up
```

App starts on `http://localhost:8080` and seeds two users (Alice and Bob) with a few grid-aligned
slots each, in the same time window — check the logs for their generated `userId`s.

## Consume

- [`api-examples.md`](api-examples.md) — a full set of copy-pasteable `curl` examples covering
  users, slots (bulk create, all `QUERY` filter variants, the overlap-conflict case), input
  validation, and the full meeting propose → vote → confirm/cancel flow.
- [`demo.sh`](demo.sh) — a runnable, self-contained walkthrough of the same flow end-to-end against
  a live instance (`./demo.sh`, requires `curl` + `jq`); prints every request and response as it goes.
- Swagger UI at `/swagger-ui.html` documents all 13 routes, including `QUERY` — see
  [`query-method.md`](query-method.md) for how a non-standard HTTP method ended up documented there.
- Metrics: Prometheus-formatted metrics (including request latency percentiles) at
  `/actuator/prometheus`; health at `/actuator/health`.

```bash
# List all slots
curl http://localhost:8080/api/users/1/slots

# QUERY: filter by status and/or time range
curl -X QUERY http://localhost:8080/api/users/1/slots \
  -H "Content-Type: application/json" \
  -d '{"status":"FREE"}'

# Bulk-create slots — endTime is always startTime + the system's slot duration
curl -X POST http://localhost:8080/api/users/1/slots \
  -H "Content-Type: application/json" \
  -d '{"startTimes":["2027-01-01T09:00:00Z","2027-01-01T09:30:00Z"]}'
```

`SlotQueryFilter` fields (`status`, `from`, `to`) are all optional — an empty/absent body is a valid
QUERY meaning "no filter."

## Tests

```bash
# Unit + repository tests only (default Surefire include pattern: *Test.java)
mvn test

# Everything, including the *IT.java integration tests (no failsafe plugin is configured,
# so *IT classes aren't picked up unless explicitly selected)
mvn test -Dtest=*Test,*IT
```

| Layer | Classes |
|---|---|
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest`, `RequestValidatorTest` |
| Repository (`@DataJpaTest`) | `SlotRepositoryTest`, `CalendarRepositoryTest` |
| Integration (`@SpringBootTest`, `RANDOM_PORT`, H2) | `UserRouteIT`, `SlotRouteIT`, `MeetingRouteIT` |

104 tests total. Integration tests share seeding/cleanup helpers from
[`TestSupport`](src/test/java/io/irn/minidoodle/TestSupport.java) rather than a common base
class — each IT class carries its own `@SpringBootTest`/`@AutoConfigureTestRestTemplate` setup.

`SlotRouteIT.createSlots_concurrentOverlappingRequests_onlyOneSucceeds` is worth calling out
specifically: it's a real concurrency test, not a unit test with mocked repositories — 8 threads
race over real HTTP to bulk-create the exact same slot, synchronized with a `CountDownLatch`,
asserting exactly one `201` and seven `409`s. It's what actually proves the `PESSIMISTIC_WRITE`
locking in `SlotService` works, rather than just compiling.

The full flow (bulk create, `QUERY` filtering, and propose → vote → confirm → cancel) is also
smoke-tested against a real `docker-compose` Postgres instance, not just H2 — see
[`design-decisions-v2.md`](design-decisions-v2.md) for why that matters for this codebase
specifically (H2 and Postgres disagree on how they type-check certain bind parameters).

## Known limitations / trade-offs

Flagged deliberately rather than left for a reviewer to discover — the brief invites shipping an
incomplete solution as long as the reasoning is explained:

- **No pagination** on `GET /api/users` or `GET /api/users/{userId}/slots` — a real gap given the
  "thousands of slots" scale note; a user with a large calendar gets every slot in one response.
  Deferred rather than fixed because it needs a decision on cursor-vs-offset pagination and a
  response-envelope change that touches every list endpoint. See
  [`spec-review.md`](spec-review.md#discussed-deliberately-not-changed).
- **Schema managed by Hibernate's `ddl-auto: update`**, not a migration tool (Flyway/Liquibase) —
  fine for a take-home, not how a production service should manage schema changes.
- **No authentication/authorization** — every endpoint trusts the `userId` supplied in the path or
  body as-is (e.g. cancelling a meeting only checks that the *supplied* `userId` matches the
  organizer, not that the caller has proven they are that user). Out of scope for this brief, but a
  real gap if this were exposed beyond a trusted network.
- **No cross-participant "find a time that works for everyone" suggestion** — the closest real Doodle
  gets to its own core feature. This implementation requires a proposer to already pick a
  `startTime`/`endTime`; participants can `QUERY` their own free slots first, but there's no endpoint
  that intersects several users' availability automatically.

## Design decisions & how this was validated

Three documents record how this implementation evolved and why, each covering a separate pass —
they intentionally aren't merged into one:

- [`spec-review.md`](spec-review.md) — the original spec-compliance pass against the take-home
  brief: dead bean validation, a TOCTOU race in slot creation, meetings not exposing participants,
  an O(n) collection load on every slot create, and the tests added to prove each fix.
- [`design-decisions-v2.md`](design-decisions-v2.md) — the refactor to the current
  propose/vote/confirm meeting model with system-wide slot duration and bulk slot creation,
  including a contradiction in the brief that had to be resolved (what happens when a participant
  lacks free slots at confirmation time) and the reasoning behind the choice made.
- [`design-decisions-v3.md`](design-decisions-v3.md) — the rebrand to `minidoodle` and an
  input-validation hardening pass closing a real bug (a bad-typed path parameter 500ing instead of
  400ing).

(The original take-home prompt isn't included in this repo, since take-home exercises are typically
not meant to be republished — its requirements are summarized in `spec-review.md`.)
