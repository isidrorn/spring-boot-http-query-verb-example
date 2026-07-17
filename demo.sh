#!/usr/bin/env bash
#
# End-to-end walkthrough of the mini-Doodle API against a live instance: creates its
# own users and slots (never assumes DataSeeder's ids), then exercises QUERY filtering,
# validation, bulk slot creation, and the full meeting propose → vote → confirm/cancel
# lifecycle. Safe to run repeatedly — each run creates fresh demo users.
#
# Usage:
#   mvn spring-boot:run -Dspring-boot.run.profiles=local   # or: docker-compose up
#   ./demo.sh                                              # (chmod +x demo.sh if needed)
#
# Override the target with BASE_URL, e.g.: BASE_URL=http://localhost:9090 ./demo.sh
#
# Requires: curl, jq.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

for bin in curl jq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "error: '$bin' is required but not installed." >&2; exit 1; }
done

# ── output helpers ────────────────────────────────────────────────────────────

section() {
  echo
  echo "── $1 $(printf -- '─%.0s' $(seq 1 $((70 - ${#1}))))"
}

# call METHOD PATH [BODY]
# Prints the request and the pretty-printed response, and leaves the response in
# $HTTP_BODY / $HTTP_STATUS for the caller to chain from (e.g. extract an id with jq).
call() {
  local method="$1" path="$2" body="${3:-}"
  local response

  echo "→ $method $path${body:+  $body}"

  if [[ -n "$body" ]]; then
    response=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE_URL$path" \
      -H "Content-Type: application/json" -H "Accept: application/json" -d "$body")
  else
    response=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE_URL$path" -H "Accept: application/json")
  fi

  HTTP_STATUS="${response##*$'\n'}"
  HTTP_BODY="${response%$'\n'*}"

  if [[ -n "$HTTP_BODY" ]]; then
    echo "$HTTP_BODY" | jq . 2>/dev/null || echo "$HTTP_BODY"
  fi
  echo "← $HTTP_STATUS"
}

# Epoch-seconds arithmetic sidesteps the GNU date (`-d "+1 day"`) vs BSD date
# (`-v+1d`) flag mismatch between Linux/Git Bash and macOS — both platforms agree on
# `date +%s` and on formatting from an epoch, just via a different flag (-d vs -r).
iso_from_epoch() {
  local epoch="$1"
  if date -u -d "@$epoch" +%Y-%m-%dT%H:%M:%SZ >/dev/null 2>&1; then
    date -u -d "@$epoch" +%Y-%m-%dT%H:%M:%SZ
  else
    date -u -r "$epoch" +%Y-%m-%dT%H:%M:%SZ
  fi
}

NOW=$(date -u +%s)
DAY=86400
SLOT=1800   # matches the default scheduling.slot-duration-minutes (30)

# ── 0. health check ───────────────────────────────────────────────────────────

section "Health check"
if ! curl -s -f "$BASE_URL/actuator/health" >/dev/null; then
  echo "error: $BASE_URL is not reachable. Start the app first:" >&2
  echo "  mvn spring-boot:run -Dspring-boot.run.profiles=local   # or: docker-compose up" >&2
  exit 1
fi
call GET /actuator/health

# ── 1. users ──────────────────────────────────────────────────────────────────

section "Create three users"
call POST /api/users "{\"name\":\"Demo Alice\",\"email\":\"demo.alice.$NOW@example.com\"}"
ALICE_ID=$(jq -r .id <<<"$HTTP_BODY")

call POST /api/users "{\"name\":\"Demo Bob\",\"email\":\"demo.bob.$NOW@example.com\"}"
BOB_ID=$(jq -r .id <<<"$HTTP_BODY")

call POST /api/users "{\"name\":\"Demo Carol\",\"email\":\"demo.carol.$NOW@example.com\"}"
CAROL_ID=$(jq -r .id <<<"$HTTP_BODY")

echo
echo "Created userId=$ALICE_ID (Alice), userId=$BOB_ID (Bob), userId=$CAROL_ID (Carol)"

section "Validation: blank name + malformed email → 400"
call POST /api/users '{"name":"","email":"not-an-email"}'

# ── 2. slots ──────────────────────────────────────────────────────────────────

# Round up to the next slot-grid boundary, one day out, so this never collides with a
# past-dated or misaligned slot from an earlier run.
GRID_START=$(( (NOW / SLOT + 1) * SLOT + DAY ))
S1_START=$(iso_from_epoch "$GRID_START")
S2_START=$(iso_from_epoch $((GRID_START + SLOT)))
MEETING_START="$S1_START"
MEETING_END=$(iso_from_epoch $((GRID_START + 2 * SLOT)))

section "Bulk-create two consecutive slots for Alice and for Bob (same window)"
call POST "/api/users/$ALICE_ID/slots" "{\"startTimes\":[\"$S1_START\",\"$S2_START\"]}"

call POST "/api/users/$BOB_ID/slots" "{\"startTimes\":[\"$S1_START\",\"$S2_START\"]}"

echo
echo "(Carol gets no slots at all — used below to show a missing-coverage participant"
echo " doesn't block meeting confirmation, see design-decisions-v2.md)"

section "List Alice's slots"
call GET "/api/users/$ALICE_ID/slots"

section "QUERY: filter by status=FREE"
call QUERY "/api/users/$ALICE_ID/slots" '{"status":"FREE"}'

section "QUERY: filter by time range"
call QUERY "/api/users/$ALICE_ID/slots" "{\"from\":\"$S1_START\",\"to\":\"$S2_START\"}"

section "QUERY: no body at all → treated as 'no filter', same as GET"
echo "(this is the core QUERY gotcha this project exists to demonstrate — see SlotHandler.parseFilter)"
call QUERY "/api/users/$ALICE_ID/slots"

section "Validation: startTime not aligned to the slot grid → 400"
call POST "/api/users/$ALICE_ID/slots" "{\"startTimes\":[\"$(iso_from_epoch $((GRID_START + 60)))\"]}"

section "Bulk-create a slot that already exists → 409, whole batch fails"
call POST "/api/users/$ALICE_ID/slots" "{\"startTimes\":[\"$S1_START\"]}"

# ── 3. meetings ───────────────────────────────────────────────────────────────

section "Validation: propose a meeting with a blank title → 400"
call POST /api/meetings "{\"title\":\"\",\"organizerUserId\":$ALICE_ID,\"startTime\":\"$MEETING_START\",\"endTime\":\"$MEETING_END\"}"

section "Propose a meeting: Alice organizes, Bob is required, Carol is optional"
call POST /api/meetings "{\"title\":\"Team sync\",\"description\":\"Weekly\",\"organizerUserId\":$ALICE_ID,\"startTime\":\"$MEETING_START\",\"endTime\":\"$MEETING_END\",\"requiredParticipantUserIds\":[$BOB_ID],\"optionalParticipantUserIds\":[$CAROL_ID]}"
MEETING_ID=$(jq -r .id <<<"$HTTP_BODY")

echo
echo "Meeting $MEETING_ID is PROPOSED; Alice (organizer) is auto-YES, Bob and Carol are PENDING."

section "Bob (REQUIRED) votes YES — the last required vote, so the meeting confirms"
call POST "/api/meetings/$MEETING_ID/participants/$BOB_ID/vote" '{"vote":"YES"}'

section "Alice's slots are now BUSY and linked to the meeting"
call GET "/api/users/$ALICE_ID/slots"

section "Voting again on a meeting that's no longer PROPOSED → 409"
call POST "/api/meetings/$MEETING_ID/participants/$CAROL_ID/vote" '{"vote":"YES"}'

section "Non-organizer tries to cancel the meeting → 403"
call DELETE "/api/meetings/$MEETING_ID" "{\"userId\":$BOB_ID}"

section "Organizer cancels the CONFIRMED meeting — booked slots go back to FREE"
call DELETE "/api/meetings/$MEETING_ID" "{\"userId\":$ALICE_ID}"
call GET "/api/users/$ALICE_ID/slots"

# ── 4. a required NO cancels immediately ────────────────────────────────────────

section "Propose a second meeting, then have the required participant vote NO"
call POST /api/meetings "{\"title\":\"Will be declined\",\"organizerUserId\":$ALICE_ID,\"startTime\":\"$MEETING_START\",\"endTime\":\"$MEETING_END\",\"requiredParticipantUserIds\":[$BOB_ID]}"
DECLINED_MEETING_ID=$(jq -r .id <<<"$HTTP_BODY")

call POST "/api/meetings/$DECLINED_MEETING_ID/participants/$BOB_ID/vote" '{"vote":"NO"}'
echo
echo "Meeting $DECLINED_MEETING_ID is CANCELLED immediately — no confirmation step needed."

# ── 5. cleanup + observability ────────────────────────────────────────────────

section "Delete a slot"
call DELETE "/api/users/$BOB_ID/slots/$(curl -s "$BASE_URL/api/users/$BOB_ID/slots" | jq -r '.[0].id')"

section "Observability"
call GET /actuator/health
echo "Prometheus metrics: curl -s $BASE_URL/actuator/prometheus | grep http_server_requests"
echo "Swagger UI:         $BASE_URL/swagger-ui.html"

echo
echo "Demo complete. Users created: userId=$ALICE_ID, $BOB_ID, $CAROL_ID (this script doesn't"
echo "delete them — there's no DELETE /api/users endpoint — so repeated runs accumulate demo users)."
