param(
    [int]$Iterations = 50,
    [string]$User = 'user',
    [string]$Pass = 'password',
    [string]$Room = 'deluxe-101',
    [string]$StartDate = (Get-Date).AddDays(10).ToString('yyyy-MM-dd'),
    [string]$EndDate = (Get-Date).AddDays(12).ToString('yyyy-MM-dd')
)

$ErrorActionPreference = 'Stop'  # fail fast so we see errors
$ProgressPreference = 'SilentlyContinue'

Write-Host "=== Smoke test config ==="
Write-Host "Iterations : $Iterations"
Write-Host "Booking    : POST http://localhost:8085/api/bookings"
Write-Host "Availability: GET  http://localhost:8086/api/availability/$Room?startDate=$StartDate&endDate=$EndDate"
Write-Host "User: $User  Room: $Room  Dates: $StartDate -> $EndDate"
Write-Host "==========================`n"

$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$User:$Pass"))

$latPost = New-Object System.Collections.Generic.List[double]
$latGet = New-Object System.Collections.Generic.List[double]
$postErrors = 0
$getErrors = 0

function NowMs { return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

for ($i = 1; $i -le $Iterations; $i++) {
    # POST /api/bookings
    $t0 = NowMs
    try {
        $resPost = Invoke-RestMethod -Method Post `
            "http://localhost:8085/api/bookings?roomId=$Room&startDate=$StartDate&endDate=$EndDate" `
            -Headers @{Authorization = $auth } -TimeoutSec 10
        $codePost = 201
    }
    catch {
        $postErrors++
        $codePost = "ERR"
    }
    $latPost.Add([double](NowMs - $t0))

    Start-Sleep -Milliseconds 100

    # GET /api/availability
    $t1 = NowMs
    try {
        $resGet = Invoke-RestMethod -Method Get `
            "http://localhost:8086/api/availability/$Room?startDate=$StartDate&endDate=$EndDate" `
            -Headers @{Authorization = $auth } -TimeoutSec 10
        $codeGet = 200
    }
    catch {
        $getErrors++
        $codeGet = "ERR"
    }
    $latGet.Add([double](NowMs - $t1))

    # per-iteration progress line
    Write-Host ("[{0}/{1}] POST:{2} {3}ms | GET:{4} {5}ms" -f $i, $Iterations, $codePost, [int]$latPost[$latPost.Count - 1], $codeGet, [int]$latGet[$latGet.Count - 1])

    Start-Sleep -Milliseconds 300
}

function Show-Stats($name, $vals, $errors) {
    if ($vals.Count -eq 0) { Write-Host "$name: no samples"; return }
    $sorted = $vals | Sort-Object
    $count = $vals.Count
    $avg = "{0:N1}" -f ($vals | Measure-Object -Average).Average
    $min = "{0:N1}" -f $sorted[0]
    $max = "{0:N1}" -f $sorted[-1]
    function Pct([double]$p) {
        $idx = [Math]::Ceiling(($p / 100.0) * $count) - 1
        if ($idx -lt 0) { $idx = 0 }
        $sorted[[Math]::Min($idx, $count - 1)]
    }
    $p90 = "{0:N1}" -f (Pct 90)
    $p95 = "{0:N1}" -f (Pct 95)

    Write-Host "`n=== $name ==="
    Write-Host "samples: $count  errors: $errors"
    Write-Host "avg: $avg ms   p90: $p90 ms   p95: $p95 ms   min: $min ms   max: $max ms"
}

Show-Stats "POST /api/bookings" $latPost $postErrors
Show-Stats "GET  /api/availability" $latGet $getErrors
