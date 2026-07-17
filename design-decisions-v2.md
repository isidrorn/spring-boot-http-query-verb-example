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
