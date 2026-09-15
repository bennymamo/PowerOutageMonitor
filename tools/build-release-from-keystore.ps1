param(
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,
    [Security.SecureString]$StorePassword,
    [Security.SecureString]$KeyPassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$keystore = (Resolve-Path -LiteralPath $KeystorePath).Path
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
    throw "The selected keystore is not a file: $keystore"
}
if ($keystore.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the signing keystore outside the Git/OneDrive project. Download it from Bitwarden to a local folder.'
}

function Reveal-SecureString([Security.SecureString]$secret) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$promptedStore = $null -eq $StorePassword
$promptedKey = $null -eq $KeyPassword
if ($promptedStore) { $StorePassword = Read-Host 'Keystore password from Bitwarden' -AsSecureString }
if ($promptedKey) { $KeyPassword = Read-Host 'Key password from Bitwarden' -AsSecureString }

try {
    $env:FP_GRID_RELEASE_STORE_FILE = $keystore
    $env:FP_GRID_RELEASE_STORE_PASSWORD = Reveal-SecureString $StorePassword
    $env:FP_GRID_RELEASE_KEY_ALIAS = 'fp-grid-monitor'
    $env:FP_GRID_RELEASE_KEY_PASSWORD = Reveal-SecureString $KeyPassword
    Push-Location $projectRoot
    try {
        & .\gradlew.bat assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'The signed release build failed.' }
        & .\tools\verify-release.ps1 `
            -ApkPath .\app\build\outputs\apk\release\app-release.apk `
            -ExpectedCertificateSha256 '760457D4265BEF7652C4A580C9BA58324225D9F8CC9CF8FCAACC65FD36AE54A6'
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:FP_GRID_RELEASE_STORE_FILE, Env:FP_GRID_RELEASE_STORE_PASSWORD, `
        Env:FP_GRID_RELEASE_KEY_ALIAS, Env:FP_GRID_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    if ($promptedStore) { $StorePassword.Dispose() }
    if ($promptedKey) { $KeyPassword.Dispose() }
}
