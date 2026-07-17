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

**A first pass at this fix only got `/api-docs` from 500 to `paths: {}` — a valid but useless empty
document.** Tracing `AbstractOpenApiResource.getRouterFunctionPaths()` explained why: springdoc's
automatic documentation of `RouterFunction` routes isn't actually automatic. It only populates real
`Operation` entries for a route already carrying a `.withAttribute(OPERATION_ATTRIBUTE, ...)` marker,
or one matched against manual `@RouterOperation`/`@RouterOperations` annotations on the bean.
Without either, `mergeRouters()` runs against an empty operation list and silently produces nothing
— independent of the QUERY bug entirely: **`/api-docs` would have returned `paths: {}` for every
route in this project, not just QUERY, even before this fix and even before the v2 refactor.** The
crash was just turning that pre-existing (silent) emptiness into a 500 instead of a
valid-but-useless 200 — not something to stop at.

**Fixed properly**: `SlotRouterConfig.routes()` now carries a `@RouterOperations` block with one
`@RouterOperation(path=..., method=..., beanClass=..., beanMethod=...)` entry per non-QUERY route
(12 entries — every user/slot/meeting route except the QUERY one). Verified against the actual
generated document, not just "compiles": `GET /api-docs` now returns 7 real path templates covering
all 12 operations, correctly grouped into `user-handler`/`slot-handler`/`meeting-handler` tags in
Swagger UI. `RouterOperation.method()` is still typed `RequestMethod[]` — the same closed enum with
no `QUERY` constant — so there is no annotation-based way to document that one route either; it's
excluded from the `@RouterOperations` block and stays absent from the spec, which is a real,
structural dead end rather than something left undone.

**Known remaining gap, not chased further**: `beanMethod` reflection gives springdoc each handler's
actual Java signature to infer schemas from — but every handler method here is `ServerResponse
handler(ServerRequest request)` (the WebMvc.fn wrapper types), not a method with `SlotResponse`/
`SlotBulkCreateRequest`/etc. in its signature. So each documented operation shows a generic
`ServerResponse` response schema instead of the real DTO shape, and no request body schema at all.
Paths, verbs, and handler grouping are all correct and Swagger UI is genuinely navigable now; full
per-DTO schema detail would mean adding explicit `@Operation(requestBody=..., responses=...)`
content to each of the 12 `@RouterOperation` entries — real, bounded, but meaningfully more work,
not attempted here.

### Why QUERY itself has no live `/api-docs` entry, and how it's documented instead

QUERY is the one route that can't be reached by *any* of the mechanisms above — not automatic
discovery, not `@RouterOperation`. Both ultimately hand the route's HTTP method to
`org.springframework.web.bind.annotation.RequestMethod`, a closed 8-value enum with no `QUERY`
constant, so there is no annotation or configuration path within this project's current
dependencies that documents it as a real, renderable Swagger UI operation.

Confirmed this is a genuine dead end rather than something misconfigured, by inspecting the actual
class file this project depends on rather than trusting changelogs: `io.swagger.v3.oas.models
.PathItem` in `swagger-models-jakarta-2.2.47.jar` (the version springdoc-openapi 3.0.3 pulls in
here) has no `query` field and no `additionalOperations` map — there's no field to put this
information in even if springdoc's introspection could reach it.

**This is a documentation-tooling version gap, not a consequence of this project choosing
`RouterFunction`/WebMvc.fn over classic `@RequestMapping`.** A quick check (not a deep one — this is
exactly the "note it down, don't solve it now" item below) found that springdoc's `@RequestMapping`
-based automatic discovery goes through the same `RequestMethod`-typed machinery
(`RequestMappingInfo`'s methods condition), so a classic `@RestController` implementation of this
API would very likely hit the identical wall trying to document a non-standard verb — the officially
suggested workaround for custom HTTP methods with `@RequestMapping` is also "use an
`OpenApiCustomizer`," per the same springdoc ecosystem. The constraint traces back to
`RequestMethod` itself (and, further back, the OpenAPI spec's `Path Item Object` before version
3.2), not to this project's specific routing implementation.

**The actual fix is upstream and not yet available**: OpenAPI 3.2 (released 2026) promotes `QUERY`
to a first-class, named method in the `Path Item Object`, and separately adds a general
`additionalOperations` keyword for any other non-standard method — Swagger's own tooling (Swagger
UI, Editor, ApiDOM) has already announced 3.2 support. springdoc-openapi 3.0.3 and swagger-core
2.2.47 (both pinned in this project) still target OpenAPI 3.1 and predate this. Once springdoc (or
whatever OpenAPI generator this project uses at the time) adds 3.2 support, QUERY should document
natively, with no workaround needed — regardless of routing implementation.

**Documented by hand in the meantime**: [`query-endpoint.openapi.yaml`](query-endpoint.openapi.yaml)
— a standalone, hand-authored OpenAPI-style document for just this one route (full request/response
schemas, examples matching `api-examples.md`/`demo.sh`), using the `x-`/informal `query:` field
convention to show what this should collapse into once tooling catches up. It is **not** wired into
the live `/api-docs` JSON — see the next section for why, and for a small complementary mechanism
that *is* wired in.

#### An external proposal for wiring this into the live document — evaluated, mostly wrong

A proposal (`OpenApiCustomizer`-based, offered by an external AI tool) suggested two ways to get
QUERY into the live `/api-docs` JSON. Evaluated both against this project's actual dependency
versions rather than taken on faith — one is disprovable in about a minute of testing, the other
doesn't exist:

- **"Inject the operation via `pathItem.addExtension("query", operation)` — modern UI readers
  processing OpenAPI 3.2 look for the lowercase `'query'` property."** Tested the exact call: the
  key is silently dropped during serialization and never appears in `/api-docs` at all. Added an
  `x-`-prefixed key alongside the unprefixed one in the same test and only the prefixed one
  survived — swagger-core (2.2.47, this project's pinned version) only serializes extension keys
  that start with `x-`, per the OpenAPI spec's own extension convention. The claim about "modern UI
  readers" recognizing a bare `query` key doesn't hold for this toolchain; whether it holds for
  *any* real tool is unverified and, given the above, doubtful.
- **"Merge a static `openapi.yaml` via `springdoc.config-path: classpath:openapi.yaml`."** Not a
  real property — checked the actual `SpringDocConfigProperties` source for this project's pinned
  springdoc-openapi 3.0.3, not just a properties reference page. No `config-path` field, nothing
  resembling a YAML-merge mechanism anywhere in that class.

**What's actually implemented, corrected from that proposal**:
[`OpenApiQuerySupportConfig`](src/main/java/dev/isidro/queryverb/config/OpenApiQuerySupportConfig.java)
adds an `OpenApiCustomizer` bean that attaches the QUERY operation under the spec-compliant
`x-query` key on the `/api/users/{userId}/slots` `PathItem`, with `SlotQueryFilter`/`SlotResponse`
schemas registered into `#/components/schemas` by reflecting the real DTOs via swagger-core's
`ModelConverters` (not hand-typed — confirmed the reflected schemas correctly capture `SlotStatus`'s
enum values and every field's real type, so this can't silently drift out of sync with the DTOs the
way a hand-maintained duplicate could). Verified live: `GET /api-docs` now has a `paths./api/users/
{userId}/slots.x-query` entry with real, non-dangling `$ref`s.

At that point the investigation had concluded — reasonably, but wrongly — that an `x-`-prefixed
extension was the ceiling: Swagger UI's renderer builds operation cards from the fixed method keys,
and an extension holding an Operation-shaped object isn't one of them. **That conclusion turned out
to be incomplete**, corrected by checking one more concrete thing instead of stopping at "this is how
Swagger UI has always worked": exactly which Swagger UI build this project actually ships.

#### The actual fix: this project's own Swagger UI build already supports `query` — it just needs a real key, not an extension

`mvn dependency:tree` shows this project resolves `org.webjars:swagger-ui:5.32.2` — not an old,
long-established build. Extracting `swagger-ui-bundle.js` from that exact jar and grepping it
(not reading about Swagger UI in general — reading the literal JS this browser runs) turns up:

```
["get","put","post","delete","options","head","patch","trace","query"]
```

— a method list with `query` already in it, used by the frontend's own operation-rendering logic —
plus a set of functions named `isOAS32`/`createOnlyOAS32ComponentWrapper` that gate 3.2-specific
UI behavior (including, per usage elsewhere in the bundle, which methods count as valid operations)
behind a check on the document's own declared version:

```js
isOAS32 = s => { const o = s.get("openapi"); return typeof o === "string" && /^3\.2\.(?:[1-9]\d*|0)$/.test(o) }
```

So the ceiling was never "Swagger UI can't render this" — it's specifically: (1) the operation must
sit under a literal `query` key, not `x-query`, and (2) the document must *declare* `"openapi":
"3.2.0"` (or later) for the frontend to even check for it. Neither of those needs springdoc or
swagger-core (the Java-side dependencies) to support OpenAPI 3.2 at all — they're purely properties
of the JSON string that ends up in the browser, and nothing requires that JSON to have been produced
by fields on `io.swagger.v3.oas.models.PathItem`.

[`OpenApiQueryOperationFilter`](src/main/java/dev/isidro/queryverb/config/OpenApiQueryOperationFilter.java)
is a servlet `Filter` (not a springdoc/swagger-core hook — deliberately below that layer) mapped to
`/api-docs`, that runs *after* springdoc has fully produced its normal document: it parses the
already-serialized JSON, renames the `x-query` extension (built by `OpenApiQuerySupportConfig`,
above) to a real `query` sibling of `get`/`post`, and bumps the document's `openapi` field to
`3.2.0`. Verified against the live response, not just "should work": `GET /api-docs` now returns
`"openapi":"3.2.0"` and `paths./api/users/{userId}/slots` has a genuine `query` key — exactly the
two conditions `isOAS32()`/the method list above require. **Not confirmed with an actual browser
screenshot** — browser automation wasn't available in the environment this was built in — so treat
"Swagger UI renders this as a clickable operation card" as *very likely, backed by reading this
exact browser build's own rendering logic* rather than as visually confirmed. Check
`http://localhost:8080/swagger-ui.html` directly if you want to see it.

Building the JSON this way sidesteps a real, independently-confirmed wall: `io.swagger.v3.oas.models
.PathItem` in `swagger-models-jakarta-2.2.47.jar` (this project's pinned swagger-core version) has no
`query` field and no `additionalOperations` map at all — there is no way to make the *Java* object
carry a real `query` operation, only the JSON string downstream of it. Confirmed by inspecting
`PathItem.class` directly, not inferred.

**A quick real check on an alternative worth naming, not implemented**: springdoc does have a genuine
property for showing more than one spec in Swagger UI — `springdoc.swagger-ui.urls` (a `Set` of
`{url, name, displayName}` entries, confirmed present in `AbstractSwaggerUiConfigProperties`). It
renders as a dropdown letting a user pick between separate documents (e.g. the generated one and a
hand-written one) — it doesn't merge them into a single document the way the filter above does, and
was not needed once the filter approach worked, but it's a real, available, much simpler option if a
future maintainer wants to expose `query-endpoint.openapi.yaml` directly instead.

**Is this a consequence of this project's routing design (`RouterFunction` vs. classic
`@RequestMapping`)?** Narrowed further, still not fully closed. The Java-side wall
(`RequestMethod`/`PathItem` having no `QUERY`/`query` representation) applies to both routing styles
equally — confirmed earlier that `@RequestMapping`'s automatic discovery goes through the same
`RequestMethod`-typed machinery. The JSON-level fix above is *also* routing-implementation-agnostic:
it operates on the already-serialized document regardless of how the underlying route was declared.
So: no, choosing `RouterFunction`/WebMvc.fn over `@RequestMapping` does not appear to be why this
needed a workaround — the workaround would have been necessary either way, given this project's
pinned springdoc/swagger-core versions.

> #### 🔭 Remaining open items — noted, not chased further
>
> - Whether springdoc-openapi/swagger-core ship native OpenAPI 3.2 support in a future release,
>   making the filter above unnecessary. As of this writing (swagger-models-jakarta 2.2.47): no.
>   Worth checking again before assuming the filter is still needed.
> - Whether routing QUERY through a fully custom `HandlerMapping`/`HandlerAdapter` pair would let
>   springdoc's Java-side model represent it any differently. Given the filter above already achieves
>   a real, correctly-gated `query` key without touching routing at all, this is now lower-value to
>   chase — noted for completeness, not because it seems likely to matter anymore.
> - An actual browser screenshot confirming the operation card renders and is clickable/"Try it out"
>   -able, not just that the JSON satisfies the frontend's own stated conditions.

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
