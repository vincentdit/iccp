#!/usr/bin/env bash
# API smoke test against a running backend started with profiles dev,demo.
# Usage: scripts/smoke.sh [base-url]
set -euo pipefail
BASE=${1:-http://localhost:8080}
pass=0; fail=0
token() { curl -sf -X POST "$BASE/api/v1/dev/login" -H 'Content-Type: application/json' -d "{\"employeeNo\":\"$1\"}" | jq -r .accessToken; }
call() { # call METHOD PATH TOKEN [BODY] -> prints "status body"
  local m=$1 p=$2 t=$3 b=${4:-}
  if [[ -n $b ]]; then curl -s -o /tmp/smoke.out -w '%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else curl -s -o /tmp/smoke.out -w '%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t"; fi
}
check() { # check NAME EXPECTED ACTUAL
  if [[ $2 == "$3" ]]; then pass=$((pass+1)); printf '  \e[32mok\e[0m   %s\n' "$1"
  else fail=$((fail+1)); printf '  \e[31mFAIL\e[0m %s (expected %s, got %s) %s\n' "$1" "$2" "$3" "$(head -c 300 /tmp/smoke.out)"; fi
}
body() { jq -r "$1" /tmp/smoke.out; }

echo "ICCP smoke test against $BASE"
check "health" 200 "$(curl -s -o /tmp/smoke.out -w '%{http_code}' "$BASE/actuator/health")"
check "public config" 200 "$(curl -s -o /tmp/smoke.out -w '%{http_code}' "$BASE/api/v1/public/config")"
check "no token -> 401" 401 "$(curl -s -o /tmp/smoke.out -w '%{http_code}' "$BASE/api/v1/me")"

NEEMA=$(token TR1007); JUMA=$(token TR1012); PETRO=$(token TR1041); MWANAISHA=$(token TR1040)
YUSUFU=$(token TR1061); GRACE=$(token TR1030); ELIA=$(token TR1031); ESTHER=$(token TR1050)

echo "Identity and directory"
check "me" 200 "$(call GET /api/v1/me "$NEEMA")"; check "me.name" "Neema Mushi" "$(body .displayName)"
check "directory search" 200 "$(call GET '/api/v1/dir/staff?q=mushi' "$NEEMA")"; check "search hit" 1 "$(body '.items|length')"
check "unit tree" 200 "$(call GET /api/v1/dir/units/tree "$NEEMA")"

echo "Financial Diary (staff)"
check "summary" 200 "$(call GET /api/v1/fin/me/summary "$NEEMA")"
check "approved 3.5M" 3500000.00 "$(body '.[0].totalApproved|tostring' | sed 's/^3500000$/3500000.00/')"
check "outstanding 1.5M" 1500000 "$(body '.[0].outstanding|floor')"
check "claims" 200 "$(call GET /api/v1/fin/me/claims "$NEEMA")"; check "3 claims" 3 "$(body '.items|length')"
CLAIM=$(body '.items[] | select(.sourceRef=="CLM-2026-0457") | .publicId')
check "claim detail" 200 "$(call GET "/api/v1/fin/me/claims/$CLAIM" "$NEEMA")"
check "another staff cannot see it (404)" 404 "$(call GET "/api/v1/fin/me/claims/$CLAIM" "$JUMA")"
check "staff blocked from Finance desk" 403 "$(call GET /api/v1/fin/desk "$NEEMA")"

echo "Claim query workflow"
check "raise query" 201 "$(call POST "/api/v1/fin/me/claims/$CLAIM/queries" "$NEEMA" '{"category":"AMOUNT","description":"Per diem should be 8 days, not 5."}')"
Q=$(body .publicId)
check "second open query refused" 409 "$(call POST "/api/v1/fin/me/claims/$CLAIM/queries" "$NEEMA" '{"category":"OTHER","description":"again"}')"
check "desk lists it" 200 "$(call GET /api/v1/fin/desk "$PETRO")"
check "finance responds" 200 "$(call POST "/api/v1/fin/queries/$Q/messages" "$PETRO" '{"body":"Five days were approved; three more need a supplementary claim."}')"
check "status RESPONDED" RESPONDED "$(body .status)"
check "other staff cannot read thread" 404 "$(call GET "/api/v1/fin/queries/$Q" "$JUMA")"
check "staff reopens" 200 "$(call POST "/api/v1/fin/queries/$Q/reopen" "$NEEMA" '{"body":"The mission order covers 8 days."}')"
check "status REOPENED" REOPENED "$(body .status)"
check "finance responds again" 200 "$(call POST "/api/v1/fin/queries/$Q/messages" "$PETRO" '{"body":"Supplementary claim CLM-2026-0601 raised."}')"
check "staff confirms" 200 "$(call POST "/api/v1/fin/queries/$Q/confirm" "$NEEMA")"; check "status RESOLVED" RESOLVED "$(body .status)"

echo "Finance import and maker-checker"
check "batches" 200 "$(call GET '/api/v1/fin/batches?status=RECONCILED' "$PETRO")"
B=$(body '.items[0].publicId'); check "batch awaiting review" RECONCILED "$(body '.items[0].status')"
check "importer cannot approve own batch" 403 "$(call POST "/api/v1/fin/batches/$B/approve" "$PETRO")"
check "quarantine items" 200 "$(call GET "/api/v1/fin/batches/$B/quarantine" "$MWANAISHA")"; check "3 quarantined" 3 "$(body length)"
QI=$(body '.[0].id')
check "officer closes quarantine item" 204 "$(call POST "/api/v1/fin/quarantine/$QI/close" "$PETRO" '{"status":"REJECTED","note":"Employee TR9999 does not exist; Finance to correct at source."}')"
check "reviewer approves" 200 "$(call POST "/api/v1/fin/batches/$B/approve" "$MWANAISHA")"; check "batch PUBLISHED" PUBLISHED "$(body .status)"
check "duplicate import is a no-op" 201 "$(curl -s -o /tmp/smoke.out -w '%{http_code}' -X POST "$BASE/api/v1/fin/imports" -H "Authorization: Bearer $PETRO" -F "file=@$(dirname "$0")/../samples/finance/finance-demo-2026-10-01.csv")"
check "duplicate flag" true "$(body .duplicate)"
printf 'H,X,2026-10-20T06:00:00+03:00,FINANCE,Q2\nC,CLM-T-1,TR1007,TRAVEL,Test,1000.00,TZS,2026-10-20,,APPROVED\nT,1,999.00,0,0\n' > /tmp/bad-trailer.csv
check "bad trailer rejected" 201 "$(curl -s -o /tmp/smoke.out -w '%{http_code}' -X POST "$BASE/api/v1/fin/imports" -H "Authorization: Bearer $PETRO" -F "file=@/tmp/bad-trailer.csv")"
check "status REJECTED" REJECTED "$(body .status)"
check "dashboard" 200 "$(call GET /api/v1/fin/dashboard "$MWANAISHA")"

echo "Announcements"
check "feed" 200 "$(call GET /api/v1/com/feed "$NEEMA")"; check "3 live notices" 3 "$(body '.items|length')"
N=$(body '.items[] | select(.requiresAck) | .publicId' | head -1)
check "open notice" 200 "$(call GET "/api/v1/com/feed/$N" "$NEEMA")"
check "acknowledge" 204 "$(call POST "/api/v1/com/feed/$N/acknowledge" "$NEEMA")"
check "publisher creates notice" 201 "$(call POST /api/v1/com/announcements "$YUSUFU" '{"category":"NOTICE","requiresAck":false,"titleEn":"Smoke test notice","bodyEn":"<p>Hello <script>alert(1)</script>world</p>","audiences":[{"type":"ORG_UNIT_TREE","value":"DRD"}]}')"
A=$(body .publicId)
check "staff cannot create" 403 "$(call POST /api/v1/com/announcements "$NEEMA" '{"category":"NOTICE","titleEn":"x","bodyEn":"y","audiences":[{"type":"ALL"}]}')"
check "submit" 200 "$(call POST "/api/v1/com/announcements/$A/submit" "$YUSUFU")"
check "reach" 200 "$(call GET "/api/v1/com/announcements/$A/reach" "$YUSUFU")"
check "approver queue" 200 "$(call GET '/api/v1/com/announcements?box=review' "$GRACE")"

echo "Notifications and administration"
check "inbox" 200 "$(call GET /api/v1/ntf/inbox "$NEEMA")"
check "unread count" 200 "$(call GET /api/v1/ntf/unread-count "$NEEMA")"
check "preferences" 200 "$(call GET /api/v1/ntf/preferences "$NEEMA")"
check "audit search (security admin)" 200 "$(call GET /api/v1/sec/audit "$ELIA")"
check "audit chain intact" true "$(call POST /api/v1/sec/audit/verify "$ELIA" >/dev/null; body .intact)"
check "staff blocked from audit" 403 "$(call GET /api/v1/sec/audit "$NEEMA")"
check "HR import history" 200 "$(call GET /api/v1/dir/imports "$ESTHER")"
check "Kiswahili errors" "Huna ruhusa ya kufanya hivi." "$(curl -s -H "Authorization: Bearer $NEEMA" -H 'Accept-Language: sw' "$BASE/api/v1/fin/desk" | jq -r .message)"

echo; echo "passed: $pass  failed: $fail"; [[ $fail -eq 0 ]]
