#!/usr/bin/env bash
# migrate-reviewtype-enums.sh
#
# DEV-ONLY one-shot migration: rewrites legacy reviewType values to the new enum.
#   SELF_REVIEW    | SELF REVIEW    -> SELF
#   MANAGER_REVIEW | MANAGER REVIEW -> MANAGER
#   PEER_REVIEW    | PEER REVIEW    -> PEER_360
#   360_REVIEW     | 360 REVIEW     -> PEER_360
# in:
#   - CatalystFlow_ReviewTemplates_dev (top-level reviewType)
#   - CatalystFlow_ReviewCycles_dev    (milestones[].templateSnapshot.reviewType)
#   - CatalystFlow_Reviews_dev         (top-level reviewType)
#
# Idempotent — safe to re-run. Refuses to run against the prod tables.
# Requires: jq.
#
# Note: PROBATION has no clean target in the new enum. Rows with reviewType=PROBATION
#       are reported and skipped — confirm with a human reviewer how to handle them.

set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-ap-southeast-2}"
TEMPLATES_TABLE="CatalystFlow_ReviewTemplates_dev"
CYCLES_TABLE="CatalystFlow_ReviewCycles_dev"
REVIEWS_TABLE="CatalystFlow_Reviews_dev"

for t in "$TEMPLATES_TABLE" "$CYCLES_TABLE" "$REVIEWS_TABLE"; do
  if [[ "$t" != *"_dev" ]]; then
    echo "ERROR: refusing to run — $t does not end with _dev." >&2
    exit 2
  fi
done

if ! command -v jq >/dev/null; then
  echo "ERROR: jq is required." >&2
  exit 2
fi

map_value() {
  case "$1" in
    "SELF_REVIEW"|"SELF REVIEW")        echo "SELF" ;;
    "MANAGER_REVIEW"|"MANAGER REVIEW")  echo "MANAGER" ;;
    "PEER_REVIEW"|"PEER REVIEW"|"360_REVIEW"|"360 REVIEW") echo "PEER_360" ;;
    *) return 1 ;;
  esac
}

LEGACY_REGEX='^(SELF_REVIEW|SELF REVIEW|MANAGER_REVIEW|MANAGER REVIEW|PEER_REVIEW|PEER REVIEW|360_REVIEW|360 REVIEW)$'

update_toplevel() {
  local table="$1" pk_field="$2"
  echo "=== ${table} (top-level reviewType) ==="
  aws dynamodb scan --table-name "${table}" --region "${REGION}" \
    --projection-expression "${pk_field},reviewType" --output json \
  | jq -r --arg re "${LEGACY_REGEX}" --arg pk "${pk_field}" '
      .Items[]?
      | select(.reviewType.S? // "" | test($re))
      | "\(.[$pk].S)|\(.reviewType.S)"' \
  | while IFS='|' read -r pk old; do
      new=$(map_value "$old")
      aws dynamodb update-item --table-name "${table}" --region "${REGION}" \
        --key "{\"${pk_field}\":{\"S\":\"${pk}\"}}" \
        --update-expression "SET reviewType = :new" \
        --condition-expression "reviewType = :old" \
        --expression-attribute-values "{\":new\":{\"S\":\"${new}\"},\":old\":{\"S\":\"${old}\"}}" \
        >/dev/null
      echo "  rewrote ${pk}: ${old} -> ${new}"
    done

  aws dynamodb scan --table-name "${table}" --region "${REGION}" \
    --projection-expression "${pk_field},reviewType" --output json \
  | jq -r --arg pk "${pk_field}" '.Items[]? | select(.reviewType.S? == "PROBATION")
           | "  WARNING: PROBATION row " + .[$pk].S + " — handle manually"'
  echo
}

update_toplevel "${TEMPLATES_TABLE}" "templateId"
update_toplevel "${REVIEWS_TABLE}"   "reviewId"

echo "=== ${CYCLES_TABLE} (milestones[].templateSnapshot.reviewType) ==="
TMP_DIR=$(mktemp -d); trap 'rm -rf "${TMP_DIR}"' EXIT

aws dynamodb scan --table-name "${CYCLES_TABLE}" --region "${REGION}" --output json \
> "${TMP_DIR}/scan.json"

jq -c --arg re "${LEGACY_REGEX}" '
  .Items[]
  | select([.milestones.L[]?.M.templateSnapshot.M.reviewType.S? | select(. != null and (. | test($re)))] | any)
' "${TMP_DIR}/scan.json" \
| while read -r item; do
    cid=$(echo "$item" | jq -r '.cycleId.S')
    new_item=$(echo "$item" | jq --arg re "${LEGACY_REGEX}" '
      .milestones.L |= map(
        if (.M.templateSnapshot.M.reviewType.S? // "") | test($re) then
          .M.templateSnapshot.M.reviewType.S |=
            (if . == "SELF_REVIEW" or . == "SELF REVIEW" then "SELF"
             elif . == "MANAGER_REVIEW" or . == "MANAGER REVIEW" then "MANAGER"
             elif . == "PEER_REVIEW" or . == "PEER REVIEW" or . == "360_REVIEW" or . == "360 REVIEW" then "PEER_360"
             else . end)
        else . end
      )
    ')
    out_file="${TMP_DIR}/${cid}.json"
    echo "$new_item" > "${out_file}"
    aws dynamodb put-item --table-name "${CYCLES_TABLE}" --region "${REGION}" \
      --item "file://${out_file}" >/dev/null
    echo "  rewrote cycle ${cid}"
  done

echo
echo "Done."
