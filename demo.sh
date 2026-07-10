#!/usr/bin/env bash
#
# End-to-end walkthrough of the mini-Doodle API against a live instance: creates its
# own users and slots (never assumes DataSeeder's ids), then exercises QUERY filtering,
# validation, the overlap conflict, and the full meeting schedule/cancel lifecycle.
# Safe to run repeatedly — each run creates fresh demo users.
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
HOUR=3600
DAY=86400

# ── 0. health check ───────────────────────────────────────────────────────────

section "Health check"
if ! curl -s -f "$BASE_URL/actuator/health" >/dev/null; then
  echo "error: $BASE_URL is not reachable. Start the app first:" >&2
  echo "  mvn spring-boot:run -Dspring-boot.run.profiles=local   # or: docker-compose up" >&2
  exit 1
fi
call GET /actuator/health

# ── 1. users ──────────────────────────────────────────────────────────────────

section "Create two users"
call POST /api/users "{\"name\":\"Demo Alice\",\"email\":\"demo.alice.$NOW@example.com\"}"
ALICE_ID=$(jq -r .id <<<"$HTTP_BODY")

call POST /api/users "{\"name\":\"Demo Bob\",\"email\":\"demo.bob.$NOW@example.com\"}"
BOB_ID=$(jq -r .id <<<"$HTTP_BODY")

echo
echo "Created userId=$ALICE_ID (Alice), userId=$BOB_ID (Bob)"

section "Validation: blank name + malformed email → 400"
call POST /api/users '{"name":"","email":"not-an-email"}'

# ── 2. slots ──────────────────────────────────────────────────────────────────

ALICE_S1_START=$(iso_from_epoch $((NOW + DAY)))
ALICE_S1_END=$(iso_from_epoch $((NOW + DAY + HOUR)))
ALICE_S2_START=$(iso_from_epoch $((NOW + DAY + 3 * HOUR)))
ALICE_S2_END=$(iso_from_epoch $((NOW + DAY + 4 * HOUR)))
# Same window as Alice's first slot, so it's a valid meeting participant slot later on.
BOB_S1_START="$ALICE_S1_START"
BOB_S1_END="$ALICE_S1_END"

section "Create slots"
call POST "/api/users/$ALICE_ID/slots" "{\"startTime\":\"$ALICE_S1_START\",\"endTime\":\"$ALICE_S1_END\"}"
ALICE_SLOT1=$(jq -r .id <<<"$HTTP_BODY")

call POST "/api/users/$ALICE_ID/slots" "{\"startTime\":\"$ALICE_S2_START\",\"endTime\":\"$ALICE_S2_END\"}"
ALICE_SLOT2=$(jq -r .id <<<"$HTTP_BODY")

call POST "/api/users/$BOB_ID/slots" "{\"startTime\":\"$BOB_S1_START\",\"endTime\":\"$BOB_S1_END\"}"
BOB_SLOT1=$(jq -r .id <<<"$HTTP_BODY")

section "List all of Alice's slots"
call GET "/api/users/$ALICE_ID/slots"

section "QUERY: filter by status=FREE"
call QUERY "/api/users/$ALICE_ID/slots" '{"status":"FREE"}'

section "QUERY: filter by time range (only Alice's first slot falls inside it)"
call QUERY "/api/users/$ALICE_ID/slots" "{\"from\":\"$ALICE_S1_START\",\"to\":\"$ALICE_S1_END\"}"

section "QUERY: no body at all → treated as 'no filter', same as GET"
echo "(this is the core QUERY gotcha this project exists to demonstrate — see SlotHandler.parseFilter)"
call QUERY "/api/users/$ALICE_ID/slots"

section "Create an overlapping slot → 409"
call POST "/api/users/$ALICE_ID/slots" "{\"startTime\":\"$ALICE_S1_START\",\"endTime\":\"$ALICE_S1_END\"}"

# ── 3. meetings ───────────────────────────────────────────────────────────────

section "Schedule a meeting (Alice's slot + Bob's slot, same window)"
call POST "/api/users/$ALICE_ID/slots/$ALICE_SLOT1/meeting" \
  "{\"title\":\"Team sync\",\"description\":\"Weekly\",\"participantSlotIds\":[$BOB_SLOT1]}"
MEETING_ID=$(jq -r .id <<<"$HTTP_BODY")

section "Get the meeting — each slot carries its owning userId"
call GET "/api/meetings/$MEETING_ID"

section "Validation: scheduling with no participants → 400"
call POST "/api/users/$ALICE_ID/slots/$ALICE_SLOT2/meeting" \
  '{"title":"Solo","description":"","participantSlotIds":[]}'

section "Double-book: reuse a slot that's already busy → 409"
call POST "/api/users/$BOB_ID/slots/$BOB_SLOT1/meeting" \
  "{\"title\":\"Conflict\",\"description\":\"\",\"participantSlotIds\":[$ALICE_SLOT2]}"

section "Cancel the meeting — both slots go back to FREE"
call DELETE "/api/users/$ALICE_ID/slots/$ALICE_SLOT1/meeting"
call GET "/api/users/$ALICE_ID/slots/$ALICE_SLOT1"

# ── 4. cleanup + observability ────────────────────────────────────────────────

section "Delete a slot"
call DELETE "/api/users/$ALICE_ID/slots/$ALICE_SLOT2"

section "Observability"
call GET /actuator/health
echo "Prometheus metrics: curl -s $BASE_URL/actuator/prometheus | grep http_server_requests"
echo "Swagger UI:         $BASE_URL/swagger-ui.html"

echo
echo "Demo complete. Users created: userId=$ALICE_ID, userId=$BOB_ID (this script doesn't delete"
echo "them — there's no DELETE /api/users endpoint — so repeated runs accumulate demo users)."
