# API smoke test for Windows PowerShell 5.1 or PowerShell 7. Run against a freshly seeded backend (profiles dev,demo).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1 [-Base http://localhost:8090]
# Default is the Docker web container, which forwards /api to the backend; use -Base http://localhost:8080 for a backend run with Maven.
param([string]$Base = "http://localhost:8090")
$ErrorActionPreference = "Stop"
$script:pass = 0; $script:fail = 0

function Token($emp) {
  (Invoke-RestMethod -Method Post -Uri "$Base/api/v1/dev/login" -ContentType "application/json" -Body (@{ employeeNo = $emp } | ConvertTo-Json)).accessToken
}
function Call($method, $path, $token, $body = $null, $lang = "en") {
  $headers = @{ "Accept-Language" = $lang }
  if ($token) { $headers["Authorization"] = "Bearer $token" }
  try {
    $req = @{ Method = $method; Uri = "$Base$path"; Headers = $headers; UseBasicParsing = $true }
    if ($body) { $req["Body"] = ($body | ConvertTo-Json -Depth 5); $req["ContentType"] = "application/json" }
    $r = Invoke-WebRequest @req
    return @{ Status = [int]$r.StatusCode; Body = $(if ($r.Content) { $r.Content | ConvertFrom-Json } else { $null }) }
  } catch {
    $resp = $_.Exception.Response
    if (-not $resp) { throw }
    $text = ""
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }      # PowerShell 7
    else { try { $text = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch {} }  # Windows PowerShell 5.1
    return @{ Status = [int]$resp.StatusCode; Body = $(if ($text) { $text | ConvertFrom-Json } else { $null }) }
  }
}
function Check($name, $expected, $actual) {
  if ("$expected" -eq "$actual") { $script:pass++; Write-Host "  ok   $name" -ForegroundColor Green }
  else { $script:fail++; Write-Host "  FAIL $name (expected $expected, got $actual)" -ForegroundColor Red }
}

Write-Host "ICCP smoke test against $Base"
Check "health" 200 (Call GET "/actuator/health" $null).Status
Check "no token -> 401" 401 (Call GET "/api/v1/me" $null).Status

$neema = Token "TR1007"; $juma = Token "TR1012"; $petro = Token "TR1041"; $mwanaisha = Token "TR1040"
$yusufu = Token "TR1061"; $elia = Token "TR1031"

Write-Host "Financial Diary"
$s = Call GET "/api/v1/fin/me/summary" $neema
Check "approved 3.5M" 3500000 ([int]$s.Body[0].totalApproved)
Check "outstanding 1.5M" 1500000 ([int]$s.Body[0].outstanding)
$claims = (Call GET "/api/v1/fin/me/claims" $neema).Body.items
$claim = ($claims | Where-Object { $_.sourceRef -eq "CLM-2026-0457" }).publicId
Check "claim detail" 200 (Call GET "/api/v1/fin/me/claims/$claim" $neema).Status
Check "another staff member cannot see it" 404 (Call GET "/api/v1/fin/me/claims/$claim" $juma).Status
Check "staff blocked from Finance desk" 403 (Call GET "/api/v1/fin/desk" $neema).Status

Write-Host "Claim query"
$q = Call POST "/api/v1/fin/me/claims/$claim/queries" $neema @{ category = "AMOUNT"; description = "Per diem should be 8 days, not 5." }
Check "raise query" 201 $q.Status
$qid = $q.Body.publicId
$r = Call POST "/api/v1/fin/queries/$qid/messages" $petro @{ body = "Three more days need a supplementary claim." }
Check "Finance responds" "RESPONDED" $r.Body.status
Check "staff confirms" "RESOLVED" (Call POST "/api/v1/fin/queries/$qid/confirm" $neema).Body.status

Write-Host "Maker-checker"
$b = (Call GET "/api/v1/fin/batches?status=RECONCILED" $petro).Body.items | Select-Object -First 1
if ($b) {
  Check "importer cannot approve own batch" 403 (Call POST "/api/v1/fin/batches/$($b.publicId)/approve" $petro).Status
  Check "reviewer publishes" "PUBLISHED" (Call POST "/api/v1/fin/batches/$($b.publicId)/approve" $mwanaisha).Body.status
} else {
  # The demo stack is intentionally persistent. A prior smoke run may already have consumed the
  # single reconciled seed batch, so verify that the completed maker-checker result remains visible.
  $published = (Call GET "/api/v1/fin/batches?status=PUBLISHED" $mwanaisha).Body.items | Select-Object -First 1
  Check "maker-checker batch already published" "PUBLISHED" $published.status
}

Write-Host "Announcements"
Check "feed" 200 (Call GET "/api/v1/com/feed" $neema).Status
Check "staff cannot publish" 403 (Call POST "/api/v1/com/announcements" $neema @{ category = "NOTICE"; titleEn = "x"; bodyEn = "y"; audiences = @(@{ type = "ALL" }) }).Status
$a = Call POST "/api/v1/com/announcements" $yusufu @{ category = "NOTICE"; requiresAck = $false; titleEn = "Smoke test"; bodyEn = "<p>Hello</p>"; audiences = @(@{ type = "ORG_UNIT_TREE"; value = "DRD" }) }
Check "publisher creates notice" 201 $a.Status
Check "publish" "PUBLISHED" (Call POST "/api/v1/com/announcements/$($a.Body.publicId)/submit" $yusufu).Body.status

Write-Host "Audit"
Check "audit chain intact" "True" (Call POST "/api/v1/sec/audit/verify" $elia).Body.intact
Check "Kiswahili errors" "Huna ruhusa ya kufanya hivi." (Call GET "/api/v1/fin/desk" $neema $null "sw").Body.message

Write-Host ""; Write-Host "passed: $script:pass  failed: $script:fail"
if ($script:fail -gt 0) { exit 1 }
