# Design decisions: v2 meeting/voting refactor

This is the design decision log for a refactor driven by an informal planning brief (not included in
this repo — see the note on private working notes below): system-wide slot duration, bulk slot
creation, and a full rewrite of the meeting model from "convert one slot into a meeting" to a
propose/vote/confirm workflow with `MeetingParticipant` roles and votes. It's a companion to
[`spec-review.md`](spec-review.md), which is a separate, earlier pass — don't merge them;
spec-review.md documents the v1 implementation against the original take-home brief, this file
documents this later refactor.

## Resolved: the brief contradicts itself on what happens when a participant lacks free slots

This is the one decision worth reading closely, because the brief gave two different answers.

The API contract section for `POST /api/meetings/{meetingId}/participants/{userId}/vote` says that
once every REQUIRED participant has voted YES:

> Si algún participante no tiene slots FREE suficientes → 409 indicando qué usuarios tienen
> conflicto, meeting permanece en PROPOSED

But the `MeetingService` pseudocode for the same step says the opposite, with a worked example:

> Si NO tiene slots FREE → ignorar silenciosamente. Es responsabilidad del participante reorganizar
> su calendario. El meeting se confirma igualmente. Ejemplo real: el participante puede tener ya sus
> slots BUSY por otro meeting confirmado previamente — la confirmación de este meeting no se bloquea
> por eso.

**Decision: implemented the pseudocode's version** — `MeetingService.confirm()` silently skips
booking for any participant without a full, contiguous FREE cover of `[startTime, endTime)`, and the
meeting confirms regardless. No 409 is ever returned from `vote()` for this case.

Why the pseudocode wins over the API-contract bullet: it's the more specific, reasoned answer — it
comes with a concrete scenario (a participant already booked into an earlier confirmed meeting) that
the 409-and-block approach would handle badly. A REQUIRED participant with a scheduling conflict
elsewhere would permanently block confirmation for every other participant who *is* free, with no
retry path that doesn't involve editing the meeting itself. Silently skipping means the meeting
still gets a real, useful CONFIRMED state, and the participant who couldn't be auto-booked can sort
out their own calendar (or the organizer can decide the meeting doesn't need them physically
blocked). This is implemented in `MeetingService.confirm()` / `fullyCovers()`, and covered by
`MeetingServiceTest.vote_allRequiredYes_confirms_butSkipsParticipantWithoutFullCoverage` and the
matching case in `MeetingRouteIT.vote_requiredYes_confirmsMeeting_andBooksFullyCoveredSlots` (Carol
has zero slots seeded on purpose, and the meeting still confirms).

If a future revision wants the 409-and-block behavior instead, the change is localized to
`MeetingService.confirm()`: collect the participants without full coverage, and if that set is
non-empty, throw `ResponseStatusException(CONFLICT, ...)` *before* calling `meeting.confirm()`
rather than after — confirming and then partially booking is not undoable without a compensating
"un-confirm" path, so the two behaviors aren't a small tweak of each other.

## Slot duration is a system parameter, not a per-slot choice

`SlotDurationConfig` (`scheduling.slot-duration-minutes`, default 30) is injected into both
`SlotService` and `MeetingService`. Every slot — whether bulk-created directly or produced by a
confirmed meeting — is exactly this long and starts on this grid
(`startTime.getEpochSecond() % (slotDurationMinutes * 60) == 0`).

**Extended grid-alignment validation to `SlotService.update()`, not just `create()`.** The brief only
states the alignment rule for bulk create and for meeting create, not for `PATCH .../slots/{id}`.
But `MeetingService.confirm()`'s coverage check (`fullyCovers`) assumes every slot in a user's
calendar sits exactly on the grid — a client could otherwise `PATCH` a slot's `startTime` to
`09:07` and permanently break contiguous-coverage matching for any meeting touching that window.
Validating the grid in exactly one of the two mutation paths would leave a hole, so both enforce it.

**`SlotDurationConfig` is a constructor-bound record, not a `@Component`-annotated mutable POJO
(the first draft's shape, changed on review).** It's a plain
`@ConfigurationProperties(prefix = "scheduling") record SlotDurationConfig(int slotDurationMinutes)`
with no stereotype annotation on the class itself — registration happens once, explicitly, via
`@ConfigurationPropertiesScan` on `Application.java`, rather than the properties class doubling as
a generic `@Component`. This matches the record style already used for every DTO in this codebase
and keeps the properties class an immutable, framework-registration-free data holder. The compact
constructor's `slotDurationMinutes <= 0 → 30` fallback exists because constructor-bound properties
bind an *absent* property to the primitive default (`0`), not to any "natural" default — and
`application-test.yml` deliberately doesn't set `scheduling.slot-duration-minutes`, so this path is
exercised for real by every `@SpringBootTest` IT class, not just defensive dead code.

## `SlotBulkCreateRequest` conflict-detection reuses `existsOverlap`, not a new exact-match query


The brief describes the per-item conflict check as "no existing slot at that exact `startTime`."
Implemented via the existing `SlotRepository.existsOverlap(userId, start, end, excludeId)` instead of
a new `existsByStartTime`-style query. Two reasons:
- Grid-aligned, fixed-duration slots can only overlap if they share a `startTime` — so `existsOverlap`
  is a strict superset of the required check, not a different one.
- A new query with an optional/nullable parameter is exactly the shape of bug this codebase already
  paid for once (see CLAUDE.md's note on `cast(:param as <type>)` — Postgres's extended query
  protocol can't infer a bind parameter's type from a bare `? is null` check, so H2 passes and
  Postgres 500s). Reusing an existing, already-hardened query sidesteps reintroducing that class of
  bug. Duplicate `startTime`s *within* the same request are still caught separately (a `HashSet`
  check before any DB round-trip), since two new, unsaved slots can't overlap-check against each
  other via a repository query.

The whole bulk create runs in the service's existing `@Transactional` method, so "fail in bloque"
(the brief's chosen all-or-nothing semantics) falls out of Spring's default rollback-on-exception
behavior — no explicit compensation logic was needed.

## `SlotResponse` drops `userId`; `MeetingResponse` drops the slots list

Followed the brief's JSON examples literally rather than the prose DTO-change table (which only
called out the `meetingId` → `meetingIds` rename and didn't mention `userId`). Since a
`MeetingResponse` now carries `participants[].userId` directly, nothing outside of
`/api/users/{userId}/slots/...` needs a slot to self-report its owner, so dropping it isn't a loss —
and `MeetingResponse` no longer needs a slots array at all once participants (with names, roles, and
votes) are the primary way to see who's in a meeting.

## `MeetingService.create()` dedupes participants instead of relying on the DB constraint to reject

`MeetingParticipant` has a `(meeting_id, user_id)` unique constraint. If a caller lists the same
`userId` as the organizer and again in `requiredParticipantUserIds` (or in both required and
optional), inserting it twice would surface as a raw constraint-violation 500 via
`RouterExceptionFilter`'s generic catch-all, not a meaningful error. `MeetingService.create()` tracks
already-added user IDs in a `LinkedHashSet` and silently skips repeats — first role wins (organizer
beats required beats optional) — rather than rejecting the request outright, since a caller
accidentally listing the organizer twice isn't a validation failure worth a 400 for.

## Entity graphs had to move with the data they now serve

Two `@EntityGraph` changes were needed once `SlotMapper`/`MeetingMapper` started reading different
associations than before (`open-in-view: false` means anything read after the `@Transactional`
service method returns must already be eagerly loaded — see CLAUDE.md's note on this):

- `SlotRepository.search()` and `.findByUserIdAndSlotId()` now carry
  `@EntityGraph(attributePaths = "meetings")`, because `SlotMapper` reads `slot.getMeetings()` to
  build `meetingIds`. This was caught by `SlotRouteIT` (`LazyInitializationException` on
  `Slot.meetings` — `@ManyToMany` collections default to `LAZY`), not by inspection.
- `MeetingRepository.findById()` changed from `{"slots", "slots.calendar", "slots.calendar.owner"}`
  to `{"participants", "participants.user"}` — `MeetingMapper` no longer touches `slots` at all
  (dropped from `MeetingResponse`, see above), but now needs `participant.getUser().getName()`.
  `slots` is deliberately left out of the graph: `MeetingService.confirm()`/`cancel()` still touch it,
  but always from inside an active transaction, where lazy loading works regardless of the graph.

## `findFreeSlotsCovering` has no optional parameters, so no Postgres cast gotcha applies

Unlike `search()`/`existsOverlap()`, every parameter to
`SlotRepository.findFreeSlotsCovering(userId, startTime, endTime)` is always non-null and used in a
normal comparison — there's no `cast(:param as X) is null or ...` branch, so the H2/Postgres
parameter-type-inference divergence documented in CLAUDE.md doesn't apply here. Verified directly
against `docker-compose`'s Postgres anyway (bulk create, QUERY filtering, and the full
create → vote → confirm → cancel flow), not just inferred from the query shape.

## `/api-docs` 500'd on QUERY — a pre-existing springdoc-openapi bug, not caused by this refactor

Found while doing a live end-to-end verification pass after this refactor (`docker-compose up` +
`demo.sh` against the real container, not just `mvn test`): `GET /api-docs` returned 500, and so did
`/swagger-ui.html` once its page tried to fetch the spec client-side. Confirmed this predates the v2
refactor entirely by checking out the pre-refactor commit in a throwaway `git worktree` and hitting
the same endpoint there — same 500, same stack trace.

**Root cause**, traced from the actual stack trace (springdoc's `GlobalExceptionHandler` was
silently swallowing it without logging — fixed as part of this, see below):
`org.springdoc.core.fn.RouterFunctionData.getRequestMethod()` converts each route's `HttpMethod` to
Spring MVC's `RequestMethod` enum via an exhaustive `switch` with no default-safe branch —
`RequestMethod` only has the 8 classic verbs (GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS/TRACE), with
no `QUERY` constant. It's the exact same closed-enum-vs-open-value-object mismatch this whole
project exists to demonstrate about `@RequestMapping`, just biting springdoc's introspection instead
of Spring's own routing. `springdoc.paths-to-exclude` does **not** help — verified directly, not
assumed — because it filters the already-built document, after this exception has already
propagated out of route discovery and aborted the whole request.

Springdoc's default `RouterFunctionWebMvcProvider.getRouterFunctionPaths()` also has no per-bean
isolation: it iterates every `RouterFunction` bean in the context in one loop with no try/catch, so
one bad route in any bean kills `/api-docs` for the entire application, not just that route.

**Fix**, in two parts:

1. [`SpringDocResilienceConfig`](src/main/java/dev/isidro/queryverb/config/SpringDocResilienceConfig.java)
   replaces springdoc's default `RouterFunctionProvider` bean with one that wraps each `RouterFunction`
   bean's traversal in a try/catch, logging and skipping any bean that fails instead of propagating.
   Getting Spring to actually use this bean over springdoc's own took two more findings, both from
   testing rather than assuming:
   - Naming the bean method `routerFunctionProvider` (matching springdoc's) throws
     `BeanDefinitionOverrideException` at startup — a bean-definition name collision is resolved
     before `@ConditionalOnMissingBean` ever runs.
   - `@ConditionalOnMissingBean` on springdoc's bean method matches by that method's *return type*
     (`RouterFunctionWebMvcProvider`, the concrete class) — not the `RouterFunctionProvider`
     interface. Since this replacement can't be typed as `RouterFunctionWebMvcProvider` (its
     `applicationContext` field and visitor inner class are both `private`, so there's no
     subclassing hook), that condition never matches it, and springdoc's own bean gets registered
     too. With two `RouterFunctionProvider` beans and no `@Primary`, Spring resolved the ambiguity by
     matching the constructor parameter name in springdoc's `SpringDocProviders` — literally
     `routerFunctionProvider` — silently preferring springdoc's bean over this one regardless of what
     this one was named. `@Primary` is what actually wins that tie.

2. [`SlotRouterConfig`](src/main/java/dev/isidro/queryverb/web/SlotRouterConfig.java) splits the
   QUERY route into its own `@Bean`, separate from every other route. This isn't cosmetic: since all
   routes originally lived in *one* `RouterFunction` bean, the try/catch in (1) — before this split —
   caught the exception but had to discard the *entire* bean's routes, not just QUERY, since the
   visitor aborts mid-traversal with no way to resume from where it left off. Splitting means only
   the isolated `queryRoute` bean is skipped; `routes` documents normally. Spring Boot's
   `RouterFunctionMapping` auto-configuration combines every `RouterFunction` bean in the context for
   actual request dispatch regardless of how many separate `@Bean` methods declare them, so this has
   no effect on routing — confirmed by rerunning the full test suite (84/84 green, including the
   `QUERY`-specific `SlotRouteIT` cases and the bulk-create concurrency test) after the split, not
   just assumed from how the mapping is documented to work.

**What this fix actually achieves, found while verifying it**: springdoc's automatic documentation
of `RouterFunction` routes isn't fully automatic. Tracing `AbstractOpenApiResource
.getRouterFunctionPaths()` shows it only populates real `Operation` entries for a route already
carrying a `.withAttribute(OPERATION_ATTRIBUTE, ...)` marker, or one matched against manual
`@RouterOperation`/`@RouterOperations` annotations on the bean — neither of which this project has.
Without them, `mergeRouters()` runs against an empty operation list and produces nothing. This is
independent of the QUERY bug: **`/api-docs` would have returned `paths: {}` for every route in this
project, not just QUERY, even before this fix and even before the v2 refactor** — the crash was
simply turning that pre-existing (silent) emptiness into a 500 instead of a valid-but-useless 200.
So the honest scope of this fix is: `/api-docs` and `/swagger-ui.html` no longer 500 and produce a
valid OpenAPI document (verified: `GET /api-docs` → 200, `GET /swagger-ui.html` → 302 redirect that
now resolves cleanly), but populating real per-route documentation for the functional routes would
be a separate, larger task — adding `@RouterOperation` annotations for all ~13 routes — not attempted
here since it wasn't what was asked.

**Bonus fix found along the way**: `GlobalExceptionHandler.handleGeneric()` (the `@RestControllerAdvice`
fallback for any future `@RestController`, and — as this investigation surfaced — the actual handler
for exceptions from springdoc's own `@RestController` endpoints) was swallowing every unhandled
exception with no logging at all, unlike its sibling `RouterExceptionFilter` which logs at `ERROR`.
This is why the QUERY crash was invisible until `ex.printStackTrace()` was added temporarily to
diagnose it. Now logs via `@Slf4j` the same way `RouterExceptionFilter` does.

## Everything else

- `DataSeeder` now injects `SlotDurationConfig` and seeds grid-aligned slots (4 consecutive
  30-minute slots today + 1 tomorrow, same window for both seeded users) instead of the old
  1-hour/1-hour/1-hour layout — the old shape wasn't grid-aligned under the new fixed-duration model
  and wouldn't have supported a demo meeting confirmation.
- `TestSupport.cleanUp` gained a `MeetingParticipantRepository` parameter and deletes
  `meeting_participant` before `meeting` (FK-safe order); deleting `meeting` also clears the
  `slot_meeting` join table it owns.
- The brief's cleanup list ("delete `AbstractIT.java`, empty out `SlotQueryRouteIT.java`") was already
  moot — neither file exists in the current tree.

The informal planning brief this refactor was built from isn't included in this repo, following the
same convention as the original take-home prompt (see `.gitignore` and the note in `spec-review.md`)
— it's a personal working note, not something meant to be republished. This file is the durable,
public record of what changed and why.
