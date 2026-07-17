# spring-boot-http-query-verb-example

Two things in one repo:

1. A playground for the new HTTP `QUERY` method
   ([draft-ietf-httpbis-safe-method-w-body](https://www.ietf.org/archive/id/draft-ietf-httpbis-safe-method-w-body-09.html))
   in a Spring Boot 4.1 / Java 21 service.
2. A small "mini Doodle" meeting-scheduling API that gives `QUERY` something real to filter:
   users book `FREE` slots on their calendar, propose meetings against other users, and meetings
   confirm once every required participant votes yes.

## Why QUERY?

`GET` cannot carry a body (by convention, and many proxies strip it). `POST` carries a body but is
neither safe nor idempotent. `QUERY` fills that gap: a **safe, idempotent, cacheable** read
operation that needs a structured filter in the request body — exactly the case of "search my
calendar slots between these dates, with this status".

## Domain

```
User  1 ──── 1  Calendar  1 ──── N  Slot  N ──── M  Meeting
                                                       │
                                              1 ──── N  MeetingParticipant
                                                       │
                                              N ──── 1  User
```

- `Calendar` is domain-only — it's never exposed as a REST resource; slots are addressed through
  `/api/users/{userId}/slots`.
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

## How QUERY is wired

Spring's `HttpMethod` is an open value object (`HttpMethod.valueOf("QUERY")`), so routing on it
doesn't require a custom annotation or touching `RequestMappingHandlerMapping`. This project uses
**Functional Route Definitions** (`WebMvc.fn`) instead of `@RestController`:

```java
route()
    .GET(SLOTS,  accept(APPLICATION_JSON), slots::listAll)
    .route(method(QUERY).and(path(SLOTS)).and(accept(APPLICATION_JSON)), slots::query)
    .POST(SLOTS, contentType(APPLICATION_JSON), slots::create)
    .build()
    .filter(routerExceptionFilter::filter);
```

See [`SlotRouterConfig`](src/main/java/dev/isidro/queryverb/web/SlotRouterConfig.java) and
[`SlotHandler`](src/main/java/dev/isidro/queryverb/web/SlotHandler.java).

Other ways this could have been done (and why they were discarded here):
- **Servlet filter rewriting `QUERY` → `POST`**: works on any Spring version but is a workaround,
  hides the real method from logs/metrics/tracing.
- **Custom `RequestCondition` + custom annotation**: closer to `@RestController` ergonomics, but
  needs extra infrastructure (a custom `HandlerMapping`) for a single extra verb.
- **Functional routes (chosen)**: standard Spring API, no custom infra, and it reads cleanly next
  to `GET`/`POST` on the same resource.

`@RestControllerAdvice` does not intercept exceptions thrown from a `HandlerFunction` (it only
targets `HandlerMethod`/`@Controller`), so error handling is done by a `HandlerFilterFunction`
chained onto the `RouterFunction` instead — see
[`RouterExceptionFilter`](src/main/java/dev/isidro/queryverb/web/RouterExceptionFilter.java).

Embedded Tomcat accepts `QUERY` on the wire; this is verified end-to-end with `TestRestTemplate`
(a real socket, not MockMvc) — see [`SlotRouteIT`](src/test/java/dev/isidro/queryverb/web/SlotRouteIT.java).

Note: some HTTP clients (including `TestRestTemplate` in this project's own tests) don't set a
`Content-Length` header for a body sent with a non-standard method like `QUERY`. Don't gate body
parsing on `Content-Length` being present — just attempt to parse the body and treat a genuinely
empty/absent one as "no filter" via the parse failure itself.

## Validation

Functional routes have no equivalent of `@Valid` on a `@RequestBody` parameter — `ServerRequest
.body(Class)` never invokes a `Validator`, so the `@NotBlank`/`@NotNull`/`@Email`/`@NotEmpty`
annotations on the request DTOs (`UserCreateRequest`, `SlotBulkCreateRequest`,
`MeetingCreateRequest`, `VoteRequest`, `MeetingCancelRequest`) would otherwise never be checked, even
with `spring-boot-starter-validation` on the classpath. Instead,
[`RequestValidator`](src/main/java/dev/isidro/queryverb/web/RequestValidator.java) parses the body
and runs it through the autoconfigured `jakarta.validation.Validator` explicitly, throwing a
`ResponseStatusException(BAD_REQUEST, ...)` on the first set of violations — which
`RouterExceptionFilter` turns into a ProblemDetail like any other. Every handler that parses a
validated DTO calls `requestValidator.parseAndValidate(request, X.class)` instead of
`request.body(X.class)` directly. (`SlotUpdateRequest` is the one exception — every field is
optional, so there's no bean-validation constraint to run; the handler calls `request.body(...)`
directly and the grid-alignment/overlap checks happen as ordinary business-rule validation in
`SlotService`.)

## API routes

All routes are declared in
[`SlotRouterConfig`](src/main/java/dev/isidro/queryverb/web/SlotRouterConfig.java):

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

## Run

No Maven wrapper is checked into this repo — use a local Maven install (or your IDE's bundled one)
with a **JDK 21** toolchain (Lombok's annotation processing does not currently work with newer JDKs
such as 26 — getters/builders/`@Slf4j` silently fail to generate, which shows up as a wall of
"cannot find symbol" compile errors).

```bash
# Without Docker (H2 in-memory)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# With Docker (PostgreSQL)
docker-compose up
```

App starts on `http://localhost:8080` and seeds two users (Alice and Bob) with a few grid-aligned
slots each, in the same time window — check the logs for their generated `userId`s.

## Consume

- [`api-examples.md`](api-examples.md) — a full set of copy-pasteable `curl` examples covering
  users, slots (bulk create, all `QUERY` filter variants, the overlap-conflict case), and the full
  meeting propose → vote → confirm/cancel flow.
- [`demo.sh`](demo.sh) — a runnable, self-contained walkthrough of the same flow end-to-end against
  a live instance (`./demo.sh`, requires `curl` + `jq`); prints every request and response as it goes.

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
QUERY meaning "no filter".

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

84 tests total. Integration tests share seeding/cleanup helpers from
[`TestSupport`](src/test/java/dev/isidro/queryverb/TestSupport.java) rather than a common base
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

## Relationship to the Doodle coding challenge

This repo doubles as the submission for a "mini Doodle" scheduling backend take-home challenge: the
`User`/`Calendar`/`Slot`/`Meeting`/`MeetingParticipant` domain, the repository query patterns, and
the functional-route style aren't a separate exercise bolted on — this repository *is* the challenge
submission, built around the `QUERY` verb demo rather than alongside it. (The original prompt isn't
included in this repo, since take-home exercises are typically not meant to be republished — but the
requirements it covers are summarized in [`spec-review.md`](spec-review.md).)

Two documents record how this implementation evolved and why:
- [`spec-review.md`](spec-review.md) — the original spec-compliance pass: dead bean validation, a
  TOCTOU race in slot creation, meetings not exposing participants, an O(n) collection load on every
  slot create, and the tests added to prove each fix.
- [`design-decisions-v2.md`](design-decisions-v2.md) — the v2 refactor to a propose/vote/confirm
  meeting model with system-wide slot duration and bulk slot creation, including a brief internal
  contradiction that had to be resolved (what happens when a participant lacks free slots at
  confirmation time) and the reasoning behind the choice.
