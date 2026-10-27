#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:3000}"
DDB_ENDPOINT="${DDB_ENDPOINT:-http://localhost:8000}"
AWS_REGION="${AWS_REGION:-ap-southeast-2}"
AI_JOBS_TABLE="${AI_JOBS_TABLE:-CatalystFlow_AiJobs}"
REVIEW_ID="${REVIEW_ID:-review-local-1}"

# Build unsigned JWT with MANAGER group so ClaimsContext passes
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
HEADER='{"alg":"none","typ":"JWT"}'
PAYLOAD='{"sub":"local-manager-1","cognito:groups":["MANAGER"]}'
TOKEN="$(printf '%s' "$HEADER" | b64url).$(printf '%s' "$PAYLOAD" | b64url)."

post_variant() {
  local name="$1"
  local manager_answer="$2"
  local with_peer="$3"

  local peer_digest=""
  if [[ "$with_peer" == "yes" ]]; then
    peer_digest=',"peerDigest":{"themes":["Ownership under ambiguity","Stakeholder communication"],"consensus":["Peers noted strong execution under shifting scope"],"dissent":["Some peers want more proactive status updates"],"overallSentiment":"constructive"}'
  fi

  local payload
  payload=$(cat <<JSON
{
  "questionId":"q-manager-1",
  "questionText":"How did this consultant demonstrate leadership and delivery impact this cycle?",
  "managerCurrentAnswer":"$manager_answer",
  "discipline":"Software & DevOps Engineering",
  "seniority":"Engineer",
  "employeeReview":{
    "answers":[
      {"questionId":"q-self-1","questionText":"What did you deliver?","response":"I led a migration that reduced deployment time from 45 minutes to 12 minutes and cut rollback incidents."},
      {"questionId":"q-self-2","questionText":"How did you collaborate?","response":"I coordinated with product and QA weekly, resolved release blockers, and documented runbooks for on-call."},
      {"questionId":"q-self-3","questionText":"What challenges did you handle?","response":"Scope changed twice; I re-planned milestones and communicated trade-offs to stakeholders."}
    ],
    "overallComment":"Consistent delivery with increasing ownership.",
    "overallRating":"4"
  }
  $peer_digest
}
JSON
)

  echo
  echo "===================="
  echo "Variant: $name"
  echo "===================="

  local resp
  resp=$(curl -sS --http1.1 \
    --retry 3 --retry-delay 1 --retry-connrefused \
    -H "Expect:" \
    -H "Connection: close" \
    -X POST \
    "$BASE_URL/ai-review/manager/reviews/$REVIEW_ID/question-suggest" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "$payload")

  local job_id
  job_id=$(printf '%s' "$resp" | jq -r '.jobId // empty')
  if [[ -z "$job_id" ]]; then
    echo "Request failed:"
    echo "$resp" | jq .
    return 1
  fi
  echo "jobId: $job_id"

  local status=""
  local row=""
  for _ in {1..45}; do
    row=$(aws dynamodb get-item \
      --endpoint-url "$DDB_ENDPOINT" \
      --region "$AWS_REGION" \
      --table-name "$AI_JOBS_TABLE" \
      --key "{\"jobId\":{\"S\":\"$job_id\"}}" \
      --output json)

    status=$(printf '%s' "$row" | jq -r '.Item.status.S // empty')
    [[ "$status" == "COMPLETED" || "$status" == "FAILED" ]] && break
    sleep 1
  done

  echo "status: ${status:-UNKNOWN}"

  if [[ "$status" == "FAILED" ]]; then
    printf '%s' "$row" | jq -r '.Item.errorMessage.S // "Unknown failure"' | sed 's/^/error: /'
    return 1
  fi

  local result_json
  result_json=$(printf '%s' "$row" | jq -r '.Item.resultJson.S // "{}"')

  echo
  echo "Suggestion"
  echo "----------"
  printf '%s\n' "$result_json" | jq -r '.suggestion // "(none)"'

  echo
  echo "isGoodAnswer"
  echo "------------"
  printf '%s\n' "$result_json" | jq -r '.isGoodAnswer'

  echo
  echo "Citations"
  echo "---------"
  printf '%s\n' "$result_json" | jq -r '
    if (.citations|type)!="object" or (.citations|length)==0 then
      "(none)"
    else
      .citations
      | to_entries[]
      | "\(.key): source=\(.value.source // "n/a") | section=\(.value.section // "n/a") | quote=\(.value.quote // "n/a")"
    end
  '
}

post_variant "WEAK (no peer digest)" \
  "They did well and communicated okay." \
  "no"

post_variant "MEDIUM (with peer digest)" \
  "They improved release flow and worked with stakeholders, but details are still limited." \
  "yes"

post_variant "STRONG (with peer digest)" \
  "They led the deployment migration, reduced deployment time from 45 to 12 minutes, cut rollback incidents, and proactively aligned product/QA on scope trade-offs during two requirement shifts." \
  "yes"

echo
echo "Done."
