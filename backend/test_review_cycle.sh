#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:3000}"
CURL_OPTS=(-sS)
PASS_COUNT=0
FAIL_COUNT=0

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required"
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo "FAIL: $1"
  if [[ $# -ge 2 ]]; then
    echo "  $2"
  fi
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local out_body="$TMP_DIR/body.json"
  local out_code="$TMP_DIR/code.txt"

  if [[ -n "$body" ]]; then
    curl "${CURL_OPTS[@]}" -o "$out_body" -w "%{http_code}" \
      -X "$method" "$BASE_URL$path" \
      -H "Content-Type: application/json" \
      -d "$body" > "$out_code"
  else
    curl "${CURL_OPTS[@]}" -o "$out_body" -w "%{http_code}" \
      -X "$method" "$BASE_URL$path" > "$out_code"
  fi

  HTTP_CODE="$(cat "$out_code")"
  HTTP_BODY="$(cat "$out_body" 2>/dev/null || true)"
}

assert_status() {
  local expected="$1"
  local name="$2"
  if [[ "$HTTP_CODE" == "$expected" ]]; then
    pass "$name (status $expected)"
  else
    fail "$name" "expected status $expected, got $HTTP_CODE; body=$HTTP_BODY"
  fi
}

assert_jq_equals() {
  local jq_expr="$1"
  local expected="$2"
  local name="$3"
  local actual
  actual="$(printf '%s' "$HTTP_BODY" | jq -r "$jq_expr")"
  if [[ "$actual" == "$expected" ]]; then
    pass "$name"
  else
    fail "$name" "expected [$expected], got [$actual]; body=$HTTP_BODY"
  fi
}

assert_jq_not_empty() {
  local jq_expr="$1"
  local name="$2"
  local actual
  actual="$(printf '%s' "$HTTP_BODY" | jq -r "$jq_expr")"
  if [[ -n "$actual" && "$actual" != "null" ]]; then
    pass "$name"
  else
    fail "$name" "value was empty/null; body=$HTTP_BODY"
  fi
}

assert_body_contains() {
  local needle="$1"
  local name="$2"
  if printf '%s' "$HTTP_BODY" | grep -Fq "$needle"; then
    pass "$name"
  else
    fail "$name" "expected body to contain [$needle]; body=$HTTP_BODY"
  fi
}

assert_iso_datetime_like() {
  local jq_expr="$1"
  local name="$2"
  local actual
  actual="$(printf '%s' "$HTTP_BODY" | jq -r "$jq_expr")"
  if [[ "$actual" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$ ]]; then
    pass "$name"
  else
    fail "$name" "expected ISO datetime like yyyy-MM-ddTHH:mm:ss, got [$actual]"
  fi
}

echo "Using BASE_URL=$BASE_URL"
echo "Starting ReviewCycle API integration checks..."
echo

###############################################################################
# 1) CREATE draft success: default dates + timestamps + milestoneId
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Draft Default Dates Test",
  "templateId": "template-001",
  "startDate": "2026-06-01",
  "dueDate": "2026-06-30",
  "milestones": [
    {
      "name": "Self Review",
      "assignedRoles": ["EMPLOYEE"],
      "status": "NOT_STARTED"
    },
    {
      "name": "Manager Review",
      "assignedRoles": ["MANAGER"],
      "startDate": "2026-06-05",
      "endDate": "2026-06-10",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create draft with default milestone dates"
assert_jq_equals '.status' 'DRAFT' "Created cycle defaults to DRAFT"
assert_jq_equals '.startDate' '01/06/2026' "Cycle startDate normalized"
assert_jq_equals '.dueDate' '30/06/2026' "Cycle dueDate normalized"
assert_jq_equals '.milestones[0].startDate' '01/06/2026' "Missing milestone startDate defaults to cycle startDate"
assert_jq_equals '.milestones[0].endDate' '01/06/2026' "Missing milestone endDate defaults to cycle startDate"
assert_jq_equals '.milestones[1].startDate' '05/06/2026' "Provided milestone startDate preserved"
assert_jq_equals '.milestones[1].endDate' '10/06/2026' "Provided milestone endDate preserved"
assert_jq_not_empty '.cycleId' "Created draft returns cycleId"
assert_jq_not_empty '.milestones[0].milestoneId' "Auto-generated milestoneId for first milestone"
assert_jq_not_empty '.milestones[1].milestoneId' "Auto-generated milestoneId for second milestone"
assert_iso_datetime_like '.createdAt' "createdAt exists and looks like datetime"
assert_iso_datetime_like '.updatedAt' "updatedAt exists and looks like datetime"
assert_jq_equals '.createdAt' "$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')" "createdAt equals updatedAt on create"

DEFAULT_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"
DEFAULT_CREATED_AT="$(printf '%s' "$HTTP_BODY" | jq -r '.createdAt')"
DEFAULT_UPDATED_AT="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"

###############################################################################
# 2) CREATE invalid status
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Bad Status",
  "status": "ACTIVE"
}'
assert_status 400 "Create rejects non-DRAFT status"
assert_body_contains "must remain DRAFT" "Create invalid status message"

###############################################################################
# 3) CREATE invalid cycle date order
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Bad Cycle Dates",
  "startDate": "2026-06-30",
  "dueDate": "2026-06-01"
}'
assert_status 400 "Create rejects startDate after dueDate"
assert_body_contains "startDate must be on or before dueDate" "Create bad cycle date message"

###############################################################################
# 4) CREATE invalid milestone date order
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Bad Milestone Dates",
  "startDate": "2026-06-01",
  "dueDate": "2026-06-30",
  "milestones": [
    {
      "name": "Broken Milestone",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-06-20",
      "endDate": "2026-06-10"
    }
  ]
}'
assert_status 400 "Create rejects milestone startDate after endDate"
assert_body_contains "startDate after endDate" "Create bad milestone date message"

###############################################################################
# 5) LIST invalid status filter
###############################################################################
request GET "/cycles?status=INVALID"
assert_status 400 "List rejects invalid status filter"
assert_body_contains "Invalid review cycle status" "List invalid status message"

###############################################################################
# 6) CREATE draft with no title, then publish -> should fail
###############################################################################
request POST "/cycles" '{
  "templateId": "template-no-title",
  "startDate": "2026-07-01",
  "dueDate": "2026-07-31",
  "milestones": [
    {
      "name": "Some Milestone",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-07-01",
      "endDate": "2026-07-02",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create draft without cycleTitle allowed"
NO_TITLE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request POST "/cycles/$NO_TITLE_ID/publish"
assert_status 400 "Publish rejects missing cycleTitle"
assert_body_contains "cycleTitle is required before publishing" "Publish missing title message"

###############################################################################
# 7) CREATE draft with no milestones, then publish -> should fail
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "No Milestones Draft",
  "startDate": "2026-07-01",
  "dueDate": "2026-07-31"
}'
assert_status 201 "Create draft without milestones allowed"
NO_MILESTONES_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request POST "/cycles/$NO_MILESTONES_ID/publish"
assert_status 400 "Publish rejects missing milestones"
assert_body_contains "At least one milestone is required before publishing" "Publish missing milestones message"

###############################################################################
# 8) PUBLISH missing assignedRoles -> should fail
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Missing Roles Draft",
  "startDate": "2026-07-01",
  "dueDate": "2026-07-31",
  "milestones": [
    {
      "name": "Milestone Without Roles",
      "startDate": "2026-07-05",
      "endDate": "2026-07-10",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create draft with incomplete milestone allowed"
MISSING_ROLES_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request POST "/cycles/$MISSING_ROLES_ID/publish"
assert_status 400 "Publish rejects milestone without assignedRoles"
assert_body_contains "assignedRoles is required" "Publish missing roles message"

###############################################################################
# 9) CREATE milestone outside cycle range -> should fail
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Outside Range Draft",
  "startDate": "2026-07-01",
  "dueDate": "2026-07-31",
  "milestones": [
    {
      "name": "Outside Range",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-06-30",
      "endDate": "2026-07-10",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 400 "Create rejects milestone before cycle range when cycle boundaries exist"
assert_body_contains "startDate must be on or after cycle startDate" "Create outside-range message"

###############################################################################
# 10) PUBLISH success with parallel / overlapping milestones + updatedAt changes
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Parallel Publish Test",
  "templateId": "template-parallel",
  "startDate": "2026-08-01",
  "dueDate": "2026-08-31",
  "milestones": [
    {
      "name": "Milestone A",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-08-01",
      "endDate": "2026-08-20",
      "status": "NOT_STARTED"
    },
    {
      "name": "Milestone B",
      "assignedRoles": ["MANAGER"],
      "startDate": "2026-08-10",
      "endDate": "2026-08-25",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create parallel milestone draft"
PARALLEL_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"
PARALLEL_CREATED_AT="$(printf '%s' "$HTTP_BODY" | jq -r '.createdAt')"
PARALLEL_UPDATED_AT_BEFORE_PUBLISH="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"

sleep 1
request POST "/cycles/$PARALLEL_ID/publish"
assert_status 200 "Publish allows overlapping parallel milestones"
assert_jq_equals '.status' 'SCHEDULED' "Publish moves cycle to SCHEDULED"
assert_jq_equals '.createdAt' "$PARALLEL_CREATED_AT" "createdAt remains unchanged after publish"
PUBLISHED_UPDATED_AT="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"
if [[ "$PUBLISHED_UPDATED_AT" != "$PARALLEL_UPDATED_AT_BEFORE_PUBLISH" ]]; then
  pass "updatedAt changes after publish"
else
  fail "updatedAt changes after publish" "updatedAt did not change; body=$HTTP_BODY"
fi

###############################################################################
# 11) GET by id and valid list by status
###############################################################################
request GET "/cycles/$PARALLEL_ID"
assert_status 200 "Get cycle by id succeeds"
assert_jq_equals '.cycleId' "$PARALLEL_ID" "Get cycle returns correct cycleId"

request GET "/cycles?status=SCHEDULED"
assert_status 200 "List by valid status succeeds"

###############################################################################
# 12) UPDATE milestone end beyond dueDate -> cycle auto-expands + updatedAt changes
###############################################################################
sleep 1
request PUT "/cycles/$PARALLEL_ID" '{
  "milestones": [
    {
      "name": "Milestone A",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "01/08/2026",
      "endDate": "20/08/2026",
      "status": "NOT_STARTED"
    },
    {
      "name": "Milestone B",
      "assignedRoles": ["MANAGER"],
      "startDate": "10/08/2026",
      "endDate": "05/09/2026",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 200 "Update allows milestone beyond current dueDate"
assert_jq_equals '.dueDate' '05/09/2026' "Cycle auto-expands dueDate to fit milestone end"
assert_jq_equals '.createdAt' "$PARALLEL_CREATED_AT" "createdAt remains unchanged after update"
UPDATED_AT_AFTER_EXPAND="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"
if [[ "$UPDATED_AT_AFTER_EXPAND" != "$PUBLISHED_UPDATED_AT" ]]; then
  pass "updatedAt changes after milestone-driven cycle expansion"
else
  fail "updatedAt changes after milestone-driven cycle expansion" "updatedAt did not change; body=$HTTP_BODY"
fi

###############################################################################
# 13) UPDATE cycle startDate syncs only milestones using old cycle startDate
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Boundary Sync Start",
  "templateId": "template-sync-start",
  "startDate": "2026-09-01",
  "dueDate": "2026-09-30",
  "milestones": [
    {
      "name": "Uses Cycle Start",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-09-01",
      "endDate": "2026-09-10",
      "status": "NOT_STARTED"
    },
    {
      "name": "Custom Start",
      "assignedRoles": ["MANAGER"],
      "startDate": "2026-09-05",
      "endDate": "2026-09-20",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create boundary sync start test cycle"
SYNC_START_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request PUT "/cycles/$SYNC_START_ID" '{
  "startDate": "2026-09-03"
}'
assert_status 200 "Update cycle startDate succeeds"
assert_jq_equals '.startDate' '03/09/2026' "Cycle startDate updated"
assert_jq_equals '.milestones[0].startDate' '03/09/2026' "Milestone using old cycle startDate updated"
assert_jq_equals '.milestones[1].startDate' '05/09/2026' "Milestone with custom startDate unchanged"

###############################################################################
# 14) UPDATE cycle dueDate syncs only milestones using old cycle dueDate
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Boundary Sync Due",
  "templateId": "template-sync-due",
  "startDate": "2026-10-01",
  "dueDate": "2026-10-31",
  "milestones": [
    {
      "name": "Uses Cycle Due",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-10-01",
      "endDate": "2026-10-31",
      "status": "NOT_STARTED"
    },
    {
      "name": "Custom Due",
      "assignedRoles": ["MANAGER"],
      "startDate": "2026-10-05",
      "endDate": "2026-10-20",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create boundary sync due test cycle"
SYNC_DUE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request PUT "/cycles/$SYNC_DUE_ID" '{
  "dueDate": "2026-11-05"
}'
assert_status 200 "Update cycle dueDate succeeds"
assert_jq_equals '.dueDate' '05/11/2026' "Cycle dueDate updated"
assert_jq_equals '.milestones[0].endDate' '05/11/2026' "Milestone using old cycle dueDate updated"
assert_jq_equals '.milestones[1].endDate' '20/10/2026' "Milestone with custom endDate unchanged"

###############################################################################
# 15) TIMESTAMP check: createdAt unchanged, updatedAt changes on normal update
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Timestamp Integrity Test",
  "templateId": "template-time",
  "startDate": "2026-11-01",
  "dueDate": "2026-11-30",
  "milestones": [
    {
      "name": "Time Milestone",
      "assignedRoles": ["EMPLOYEE"],
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create timestamp integrity test cycle"
TIME_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"
TIME_CREATED_AT="$(printf '%s' "$HTTP_BODY" | jq -r '.createdAt')"
TIME_UPDATED_AT_1="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"

sleep 1
request PUT "/cycles/$TIME_ID" '{
  "cycleTitle": "Timestamp Integrity Test Updated"
}'
assert_status 200 "Update timestamp integrity test cycle"
assert_jq_equals '.createdAt' "$TIME_CREATED_AT" "createdAt stays unchanged on normal update"
TIME_UPDATED_AT_2="$(printf '%s' "$HTTP_BODY" | jq -r '.updatedAt')"
if [[ "$TIME_UPDATED_AT_2" != "$TIME_UPDATED_AT_1" ]]; then
  pass "updatedAt changes on normal update"
else
  fail "updatedAt changes on normal update" "updatedAt did not change; body=$HTTP_BODY"
fi

###############################################################################
# 16) UPDATE status to COMPLETED, then another update should fail with state error
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Completed Lock Test",
  "templateId": "template-completed",
  "startDate": "2026-12-01",
  "dueDate": "2026-12-31",
  "milestones": [
    {
      "name": "Complete Me",
      "assignedRoles": ["EMPLOYEE"],
      "startDate": "2026-12-01",
      "endDate": "2026-12-10",
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create completed-lock test cycle"
COMPLETED_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request PUT "/cycles/$COMPLETED_ID" '{
  "status": "COMPLETED"
}'
assert_status 200 "Update can mark cycle COMPLETED"

request PUT "/cycles/$COMPLETED_ID" '{
  "cycleTitle": "Should Fail"
}'
if [[ "$HTTP_CODE" == "400" || "$HTTP_CODE" == "409" ]]; then
  pass "Update rejects changes to COMPLETED cycle"
else
  fail "Update rejects changes to COMPLETED cycle" "expected 400 or 409, got $HTTP_CODE; body=$HTTP_BODY"
fi

###############################################################################
# 17) DELETE and verify 404 after delete
###############################################################################
request POST "/cycles" '{
  "cycleTitle": "Delete Test",
  "templateId": "template-delete",
  "startDate": "2027-01-01",
  "dueDate": "2027-01-31",
  "milestones": [
    {
      "name": "Delete Me",
      "assignedRoles": ["EMPLOYEE"],
      "status": "NOT_STARTED"
    }
  ]
}'
assert_status 201 "Create delete test cycle"
DELETE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.cycleId')"

request DELETE "/cycles/$DELETE_ID"
assert_status 204 "Delete existing cycle"

request GET "/cycles/$DELETE_ID"
assert_status 404 "Deleted cycle no longer retrievable"

echo
echo "=============================="
echo "Finished."
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo "=============================="

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi