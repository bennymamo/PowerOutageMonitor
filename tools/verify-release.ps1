param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$ExpectedCertificateSha256,
    [switch]$AllowRehearsal
)

$releaseApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$sdkBuildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'
$latestBuildTools = Get-ChildItem -LiteralPath $sdkBuildTools -Directory -ErrorAction Stop |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $latestBuildTools) {
    throw 'Android SDK Build Tools are not installed.'
}

$apksigner = Join-Path $latestBuildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner)) {
    throw "Android apksigner is missing from $($latestBuildTools.FullName)."
}

$verification = & $apksigner verify --min-sdk-version 23 --verbose --print-certs $releaseApk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Android rejected the APK signature: $($verification -join ' ')"
}

$certificateLine = $verification | Where-Object { $_ -match 'certificate SHA-256 digest: ([0-9a-fA-F]{64})' } |
    Select-Object -First 1
if ($null -eq $certificateLine) {
    throw 'The signed APK has no readable signer certificate fingerprint.'
}
$certificateSha256 = ([regex]::Match($certificateLine, '([0-9a-fA-F]{64})')).Value.ToUpperInvariant()
$certificateName = $verification | Where-Object { $_ -match 'certificate DN:' } | Select-Object -First 1
if ($certificateName -match 'Release Rehearsal' -and -not $AllowRehearsal) {
    throw 'This APK uses the disposable release-rehearsal key and must not be published.'
}
if (-not $AllowRehearsal -and [string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
    throw 'Provide the permanent release certificate SHA-256 fingerprint before publishing.'
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedCertificateSha256) -and
    $certificateSha256 -ne $ExpectedCertificateSha256.Replace(':', '').ToUpperInvariant()) {
    throw 'The APK signer does not match the expected permanent release certificate.'
}

$apkHash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash
$apkSize = (Get-Item -LiteralPath $releaseApk).Length
Write-Output "Verified APK: $releaseApk"
Write-Output "Signer certificate SHA-256: $certificateSha256"
Write-Output "APK SHA-256: $apkHash"
Write-Output "APK size: $apkSize bytes"
