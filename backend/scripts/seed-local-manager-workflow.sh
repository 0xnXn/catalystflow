#!/usr/bin/env bash
set -euo pipefail

DDB_ENDPOINT="${DDB_ENDPOINT:-http://localhost:8001}"
AWS_REGION="${AWS_REGION:-ap-southeast-2}"
SEED_PREFIX="${SEED_PREFIX:-seedmgr1}"

USERS_TABLE="${USERS_TABLE:-CatalystFlow_Users}"
TEMPLATES_TABLE="${TEMPLATES_TABLE:-CatalystFlow_ReviewTemplates}"
CYCLES_TABLE="${CYCLES_TABLE:-CatalystFlow_ReviewCycles}"
REVIEWS_TABLE="${REVIEWS_TABLE:-CatalystFlow_Reviews}"

now_iso() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
NOW="$(now_iso)"

CYCLE_ID="${SEED_PREFIX}-cycle-2026-q2"

MANAGER_USER_ID="${MANAGER_USER_ID:-598e4498-40c1-70bd-498f-f9a7755bf134}"
MANAGER_EMAIL="${MANAGER_EMAIL:-Kevin@gmail.com}"
SUBJECT_USER_ID="${SEED_PREFIX}-employee-1"
PEER1_USER_ID="${SEED_PREFIX}-peer-1"
PEER2_USER_ID="${SEED_PREFIX}-peer-2"
PEER3_USER_ID="${SEED_PREFIX}-peer-3"

SELF_TEMPLATE_ID="${SEED_PREFIX}-tmpl-self"
MANAGER_TEMPLATE_ID="${SEED_PREFIX}-tmpl-manager"
PEER_TEMPLATE_ID="${SEED_PREFIX}-tmpl-peer360"

SELF_REVIEW_ID="${SEED_PREFIX}-review-self-1"
MANAGER_REVIEW_ID="${SEED_PREFIX}-review-manager-1"
PEER_REVIEW_ID_1="${SEED_PREFIX}-review-peer-1"
PEER_REVIEW_ID_2="${SEED_PREFIX}-review-peer-2"
PEER_REVIEW_ID_3="${SEED_PREFIX}-review-peer-3"

say() { echo "[seed] $*"; }

ddb_put() {
  local table="$1"
  local item_json="$2"
  aws dynamodb put-item \
    --endpoint-url "$DDB_ENDPOINT" \
    --region "$AWS_REGION" \
    --table-name "$table" \
    --item "$item_json" >/dev/null
}

check_table() {
  local table="$1"
  aws dynamodb describe-table \
    --endpoint-url "$DDB_ENDPOINT" \
    --region "$AWS_REGION" \
    --table-name "$table" >/dev/null
}

say "Checking required tables on $DDB_ENDPOINT"
check_table "$USERS_TABLE"
check_table "$TEMPLATES_TABLE"
check_table "$CYCLES_TABLE"
check_table "$REVIEWS_TABLE"

say "Seeding users"
for uid in "$MANAGER_USER_ID" "$SUBJECT_USER_ID" "$PEER1_USER_ID" "$PEER2_USER_ID" "$PEER3_USER_ID"; do
  role="EMPLOYEE"
  status="Active"
  first="User"
  last=""
  discipline=""
  seniority=""
  reportingTo=""
  email="${uid}@local.test"

  case "$uid" in
    "$MANAGER_USER_ID")
      role="MANAGER"; first="Kevin"; last="Manager"; email="$MANAGER_EMAIL";;
    "$SUBJECT_USER_ID")
      role="EMPLOYEE"; first="Evan"; last="Engineer"; discipline="Software & DevOps Engineering"; seniority="Engineer"; reportingTo="$MANAGER_USER_ID";;
    "$PEER1_USER_ID")
      first="Priya"; last="Peer"; reportingTo="$MANAGER_USER_ID";;
    "$PEER2_USER_ID")
      first="Sam"; last="Peer"; reportingTo="$MANAGER_USER_ID";;
    "$PEER3_USER_ID")
      first="Jordan"; last="Peer"; reportingTo="$MANAGER_USER_ID";;
  esac

  item=$(jq -nc \
    --arg userId "$uid" \
    --arg firstName "$first" \
    --arg lastName "$last" \
    --arg email "$email" \
    --arg role "$role" \
    --arg status "$status" \
    --arg discipline "$discipline" \
    --arg seniority "$seniority" \
    --arg reportingTo "$reportingTo" \
    --arg createdAt "$NOW" \
    --arg updatedAt "$NOW" \
    '{
      userId: {S: $userId},
      firstName: {S: $firstName},
      lastName: {S: $lastName},
      email: {S: $email},
      role: {S: $role},
      employeeType: {S: "Full-time"},
      status: {S: $status},
      createdAt: {S: $createdAt},
      updatedAt: {S: $updatedAt}
    }
    + (if $discipline != "" then {discipline: {S: $discipline}} else {} end)
    + (if $seniority != "" then {seniority: {S: $seniority}} else {} end)
    + (if $reportingTo != "" then {reportingTo: {S: $reportingTo}} else {} end)
    ')

  ddb_put "$USERS_TABLE" "$item"
done

say "Seeding templates"
SELF_Q='[{"questionId":"11111111-1111-1111-1111-111111111111","text":"What did you deliver this cycle?","type":"TEXT"},{"questionId":"11111111-1111-1111-1111-111111111112","text":"How did you collaborate with stakeholders?","type":"TEXT"},{"questionId":"11111111-1111-1111-1111-111111111113","text":"What challenges did you handle?","type":"TEXT"}]'
MGR_Q='[{"questionId":"33333333-3333-3333-3333-333333333331","text":"How did this consultant demonstrate leadership and delivery impact this cycle?","type":"TEXT","category":"Technology / Core Skills"},{"questionId":"33333333-3333-3333-3333-333333333332","text":"How effectively did this consultant manage client and stakeholder relationships?","type":"TEXT","category":"Consulting Skills"},{"questionId":"33333333-3333-3333-3333-333333333333","text":"What development opportunities should be prioritised next?","type":"TEXT","category":"Development Opportunities"}]'
PEER_Q='[{"questionId":"22222222-2222-2222-2222-222222222221","text":"What strengths did you observe?","type":"TEXT"},{"questionId":"22222222-2222-2222-2222-222222222222","text":"Where could this person improve?","type":"TEXT"}]'

for t in self manager peer; do
  tid=""
  tname=""
  rtype=""
  questions=""
  case "$t" in
    self) tid="$SELF_TEMPLATE_ID"; tname="Seed SELF Template"; rtype="SELF"; questions="$SELF_Q";;
    manager) tid="$MANAGER_TEMPLATE_ID"; tname="Seed MANAGER Template"; rtype="MANAGER"; questions="$MGR_Q";;
    peer) tid="$PEER_TEMPLATE_ID"; tname="Seed PEER360 Template"; rtype="PEER_360"; questions="$PEER_Q";;
  esac

  item=$(jq -nc \
    --arg templateId "$tid" \
    --arg templateName "$tname" \
    --arg reviewType "$rtype" \
    --arg createdAt "$NOW" \
    --arg updatedAt "$NOW" \
    --argjson questions "$questions" \
    '{
      templateId: {S: $templateId},
      templateName: {S: $templateName},
      reviewType: {S: $reviewType},
      questions: {S: ($questions | tojson)},
      usageCount: {N: "0"},
      createdAt: {S: $createdAt},
      updatedAt: {S: $updatedAt}
    }')

  ddb_put "$TEMPLATES_TABLE" "$item"
done

say "Seeding review cycle"
cycle_item=$(jq -nc \
  --arg cycleId "$CYCLE_ID" \
  --arg title "Seed Cycle Q2 2026" \
  --arg startDate "2026-04-01" \
  --arg dueDate "2026-05-15" \
  --arg status "ACTIVE" \
  --arg createdAt "$NOW" \
  --arg updatedAt "$NOW" \
  --arg selfTid "$SELF_TEMPLATE_ID" \
  --arg mgrTid "$MANAGER_TEMPLATE_ID" \
  --arg peerTid "$PEER_TEMPLATE_ID" \
  '{
    cycleId: {S: $cycleId},
    cycleTitle: {S: $title},
    startDate: {S: $startDate},
    dueDate: {S: $dueDate},
    status: {S: $status},
    milestones: {
      L: [
        {
          M: {
            milestoneId: {S: "ms-self-1"},
            name: {S: "Self Review"},
            assignedRoles: {L: [{S: "EMPLOYEE"}]},
            startDate: {S: "2026-04-01"},
            endDate: {S: "2026-04-20"},
            status: {S: "OPEN"},
            templateSnapshot: {
              M: {
                templateId: {S: $selfTid},
                templateName: {S: "Seed SELF Template"},
                reviewType: {S: "SELF"},
                questions: {
                  L: [
                    {M: {questionId: {S: "11111111-1111-1111-1111-111111111111"}, text: {S: "What did you deliver this cycle?"}, type: {S: "TEXT"}}},
                    {M: {questionId: {S: "11111111-1111-1111-1111-111111111112"}, text: {S: "How did you collaborate with stakeholders?"}, type: {S: "TEXT"}}},
                    {M: {questionId: {S: "11111111-1111-1111-1111-111111111113"}, text: {S: "What challenges did you handle?"}, type: {S: "TEXT"}}}
                  ]
                }
              }
            }
          }
        },
        {
          M: {
            milestoneId: {S: "ms-peer-1"},
            name: {S: "Peer 360"},
            assignedRoles: {L: [{S: "EMPLOYEE"}]},
            startDate: {S: "2026-04-10"},
            endDate: {S: "2026-04-25"},
            status: {S: "OPEN"},
            templateSnapshot: {
              M: {
                templateId: {S: $peerTid},
                templateName: {S: "Seed PEER360 Template"},
                reviewType: {S: "PEER_360"},
                questions: {
                  L: [
                    {M: {questionId: {S: "22222222-2222-2222-2222-222222222221"}, text: {S: "What strengths did you observe?"}, type: {S: "TEXT"}}},
                    {M: {questionId: {S: "22222222-2222-2222-2222-222222222222"}, text: {S: "Where could this person improve?"}, type: {S: "TEXT"}}}
                  ]
                }
              }
            }
          }
        },
        {
          M: {
            milestoneId: {S: "ms-manager-1"},
            name: {S: "Manager Review"},
            assignedRoles: {L: [{S: "MANAGER"}]},
            startDate: {S: "2026-04-20"},
            endDate: {S: "2026-05-10"},
            status: {S: "OPEN"},
            templateSnapshot: {
              M: {
                templateId: {S: $mgrTid},
                templateName: {S: "Seed MANAGER Template"},
                reviewType: {S: "MANAGER"},
                questions: {
                  L: [
                    {M: {questionId: {S: "33333333-3333-3333-3333-333333333331"}, text: {S: "How did this consultant demonstrate leadership and delivery impact this cycle?"}, type: {S: "TEXT"}, category: {S: "Technology / Core Skills"}}},
                    {M: {questionId: {S: "33333333-3333-3333-3333-333333333332"}, text: {S: "How effectively did this consultant manage client and stakeholder relationships?"}, type: {S: "TEXT"}, category: {S: "Consulting Skills"}}},
                    {M: {questionId: {S: "33333333-3333-3333-3333-333333333333"}, text: {S: "What development opportunities should be prioritised next?"}, type: {S: "TEXT"}, category: {S: "Development Opportunities"}}}
                  ]
                }
              }
            }
          }
        }
      ]
    },
    createdAt: {S: $createdAt},
    updatedAt: {S: $updatedAt}
  }')

ddb_put "$CYCLES_TABLE" "$cycle_item"

say "Seeding reviews (SELF / MANAGER / PEER_360)"
SELF_ANS='[{"questionId":"11111111-1111-1111-1111-111111111111","response":"I led a migration that reduced deployment time from 45 minutes to 12 minutes and reduced rollback incidents."},{"questionId":"11111111-1111-1111-1111-111111111112","response":"I coordinated weekly with product and QA, aligned release scope, and documented runbooks for on-call."},{"questionId":"11111111-1111-1111-1111-111111111113","response":"Scope changed twice; I re-planned milestones and communicated trade-offs to stakeholders."}]'
MGR_ANS='[{"questionId":"33333333-3333-3333-3333-333333333331","response":"They improved delivery outcomes and contributed strongly to project execution."},{"questionId":"33333333-3333-3333-3333-333333333332","response":"They collaborated with stakeholders and managed communication effectively."},{"questionId":"33333333-3333-3333-3333-333333333333","response":"Could improve executive-level communication and broader cross-team influence."}]'
PEER1_ANS='[{"questionId":"22222222-2222-2222-2222-222222222221","response":"Strong ownership under ambiguity and reliable delivery cadence."},{"questionId":"22222222-2222-2222-2222-222222222222","response":"Could provide more proactive updates when risks emerge."}]'
PEER2_ANS='[{"questionId":"22222222-2222-2222-2222-222222222221","response":"Very strong collaborator with product and QA; keeps work moving."},{"questionId":"22222222-2222-2222-2222-222222222222","response":"Could tighten narrative clarity in stakeholder demos."}]'
PEER3_ANS='[{"questionId":"22222222-2222-2222-2222-222222222221","response":"Handled changing scope calmly and kept milestones realistic."},{"questionId":"22222222-2222-2222-2222-222222222222","response":"More visibility on long-term technical trade-offs would help."}]'

put_review() {
  local review_id="$1"
  local template_id="$2"
  local milestone_id="$3"
  local subject_id="$4"
  local author_id="$5"
  local review_type="$6"
  local status="$7"
  local answers_json="$8"
  local overall_rating="$9"
  local overall_comment="${10}"

  local item
  item=$(jq -nc \
    --arg reviewId "$review_id" \
    --arg cycleId "$CYCLE_ID" \
    --arg templateId "$template_id" \
    --arg milestoneId "$milestone_id" \
    --arg subjectUserId "$subject_id" \
    --arg authorUserId "$author_id" \
    --arg reviewType "$review_type" \
    --arg cycleTypeKey "${CYCLE_ID}#${review_type}" \
    --arg status "$status" \
    --arg overallRating "$overall_rating" \
    --arg overallComment "$overall_comment" \
    --arg createdAt "$NOW" \
    --arg updatedAt "$NOW" \
    --arg submittedAt "$NOW" \
    --arg createdBy "$author_id" \
    --argjson answers "$answers_json" \
    '{
      reviewId: {S: $reviewId},
      cycleId: {S: $cycleId},
      templateId: {S: $templateId},
      milestoneId: {S: $milestoneId},
      subjectUserId: {S: $subjectUserId},
      authorUserId: {S: $authorUserId},
      reviewType: {S: $reviewType},
      cycleTypeKey: {S: $cycleTypeKey},
      status: {S: $status},
      answers: {S: ($answers | tojson)},
      overallRating: {S: $overallRating},
      overallComment: {S: $overallComment},
      createdAt: {S: $createdAt},
      updatedAt: {S: $updatedAt},
      createdBy: {S: $createdBy},
      version: {N: "1"}
    }
    + (if ($status == "COMPLETED" or $status == "PUBLISHED") then {submittedAt: {S: $submittedAt}} else {} end)
  ')

  ddb_put "$REVIEWS_TABLE" "$item"
}

put_review "$SELF_REVIEW_ID" "$SELF_TEMPLATE_ID" "ms-self-1" "$SUBJECT_USER_ID" "$SUBJECT_USER_ID" "SELF" "COMPLETED" "$SELF_ANS" "4" "Consistent delivery with increasing ownership."
put_review "$MANAGER_REVIEW_ID" "$MANAGER_TEMPLATE_ID" "ms-manager-1" "$SUBJECT_USER_ID" "$MANAGER_USER_ID" "MANAGER" "DRAFT" "$MGR_ANS" "" ""
put_review "$PEER_REVIEW_ID_1" "$PEER_TEMPLATE_ID" "ms-peer-1" "$SUBJECT_USER_ID" "$PEER1_USER_ID" "PEER_360" "COMPLETED" "$PEER1_ANS" "" ""
put_review "$PEER_REVIEW_ID_2" "$PEER_TEMPLATE_ID" "ms-peer-1" "$SUBJECT_USER_ID" "$PEER2_USER_ID" "PEER_360" "COMPLETED" "$PEER2_ANS" "" ""
put_review "$PEER_REVIEW_ID_3" "$PEER_TEMPLATE_ID" "ms-peer-1" "$SUBJECT_USER_ID" "$PEER3_USER_ID" "PEER_360" "COMPLETED" "$PEER3_ANS" "" ""

say "Running verification checks"
aws dynamodb get-item --endpoint-url "$DDB_ENDPOINT" --region "$AWS_REGION" --table-name "$REVIEWS_TABLE" --key "{\"reviewId\":{\"S\":\"$MANAGER_REVIEW_ID\"}}" --output json | jq -e '.Item.reviewId.S != null' >/dev/null
aws dynamodb get-item --endpoint-url "$DDB_ENDPOINT" --region "$AWS_REGION" --table-name "$REVIEWS_TABLE" --key "{\"reviewId\":{\"S\":\"$SELF_REVIEW_ID\"}}" --output json | jq -e '.Item.status.S == "COMPLETED"' >/dev/null

peer_count=$(aws dynamodb query \
  --endpoint-url "$DDB_ENDPOINT" \
  --region "$AWS_REGION" \
  --table-name "$REVIEWS_TABLE" \
  --index-name SubjectIndex \
  --key-condition-expression "#pk = :pk AND begins_with(#sk, :sk)" \
  --expression-attribute-names '{"#pk":"subjectUserId","#sk":"cycleTypeKey"}' \
  --expression-attribute-values "{\":pk\":{\"S\":\"$SUBJECT_USER_ID\"},\":sk\":{\"S\":\"$CYCLE_ID#PEER_360\"}}" \
  --output json | jq '.Count')

if [[ "$peer_count" -lt 3 ]]; then
  echo "[seed] ERROR: expected at least 3 peer reviews, got $peer_count"
  exit 1
fi

cat <<OUT

[seed] Done.

Seeded IDs
----------
cycleId:          $CYCLE_ID
subjectUserId:    $SUBJECT_USER_ID
managerUserId:    $MANAGER_USER_ID
managerReviewId:  $MANAGER_REVIEW_ID
selfReviewId:     $SELF_REVIEW_ID
peerReviewIds:    $PEER_REVIEW_ID_1, $PEER_REVIEW_ID_2, $PEER_REVIEW_ID_3

Template IDs
------------
selfTemplateId:   $SELF_TEMPLATE_ID
managerTemplateId:$MANAGER_TEMPLATE_ID
peerTemplateId:   $PEER_TEMPLATE_ID

Next Steps
----------
1) Open manager flow with query params based on IDs above.
2) Run question-suggest manual script:
   /home/halwa/Projects/CatalystFlow/CatalystFlow-backend/scripts/test-manager-question-suggest.sh

Notes
-----
- Endpoint default is DDB_ENDPOINT=$DDB_ENDPOINT
- Script is idempotent: reruns overwrite same deterministic IDs.
OUT
