param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Create', 'Build', 'CopyBitwardenNote')]
    [string]$Action,
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'FlossyPickle\FPGridMonitor\signing')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$keystore = Join-Path $SigningDirectory 'fp-grid-monitor-release.jks'
$storeSecret = Join-Path $SigningDirectory 'store-password.dpapi'
$keySecret = Join-Path $SigningDirectory 'key-password.dpapi'
$fingerprintFile = Join-Path $SigningDirectory 'certificate-sha256.txt'
$alias = 'fp-grid-monitor'

function New-RandomPassword {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    return [BitConverter]::ToString($bytes).Replace('-', '')
}

function Read-LocalPassword([string]$path) {
    $secure = Get-Content -LiteralPath $path -Raw | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

if ($Action -eq 'Create') {
    if ((Test-Path -LiteralPath $keystore) -or
        (Test-Path -LiteralPath $storeSecret) -or
        (Test-Path -LiteralPath $keySecret)) {
        throw 'Release signing material already exists. Refusing to replace the permanent app identity.'
    }
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $env:FP_GRID_RELEASE_STORE_PASSWORD = New-RandomPassword
    $env:FP_GRID_RELEASE_KEY_PASSWORD = New-RandomPassword
    try {
        $env:FP_GRID_RELEASE_STORE_PASSWORD |
            ConvertTo-SecureString -AsPlainText -Force |
            ConvertFrom-SecureString |
            Set-Content -LiteralPath $storeSecret -NoNewline
        $env:FP_GRID_RELEASE_KEY_PASSWORD |
            ConvertTo-SecureString -AsPlainText -Force |
            ConvertFrom-SecureString |
            Set-Content -LiteralPath $keySecret -NoNewline

        & keytool -genkeypair -keystore $keystore -storetype JKS `
            -alias $alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA `
            -validity 10000 -dname 'CN=FP Grid Monitor, OU=Flossy Pickle, O=Flossy Pickle, C=MT' `
            -storepass:env FP_GRID_RELEASE_STORE_PASSWORD `
            -keypass:env FP_GRID_RELEASE_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw 'The permanent release key could not be created.'
        }
        $certificate = & keytool -exportcert -rfc -keystore $keystore -alias $alias `
            -storepass:env FP_GRID_RELEASE_STORE_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw 'The release certificate could not be exported.'
        }
        $certificateText = $certificate -join "`n"
        $base64 = ($certificateText -split "`n" | Where-Object {
            $_ -notmatch '^-----' -and -not [string]::IsNullOrWhiteSpace($_)
        }) -join ''
        $certificateBytes = [Convert]::FromBase64String($base64)
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            $fingerprint = [BitConverter]::ToString($sha256.ComputeHash($certificateBytes)).Replace('-', '')
        } finally {
            $sha256.Dispose()
        }
        Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -NoNewline
        Write-Output "Permanent release key created: $keystore"
        Write-Output "Certificate SHA-256: $fingerprint"
        Write-Output 'Passwords are protected with Windows DPAPI and were not printed.'
    } finally {
        Remove-Item Env:FP_GRID_RELEASE_STORE_PASSWORD, Env:FP_GRID_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
    exit
}

foreach ($path in @($keystore, $storeSecret, $keySecret, $fingerprintFile)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing signing material: $path"
    }
}

if ($Action -eq 'CopyBitwardenNote') {
    $storePassword = Read-LocalPassword $storeSecret
    $keyPassword = Read-LocalPassword $keySecret
    try {
        $note = @"
FP Grid Monitor APK signing key
Keystore attachment: fp-grid-monitor-release.jks
Keystore password: $storePassword
Key alias: $alias
Key password: $keyPassword
Certificate SHA-256: $(Get-Content -LiteralPath $fingerprintFile -Raw)
Keep this item and its file attachment for every future GitHub APK update.
"@
        Set-Clipboard -Value $note
        Write-Output 'The signing details are on the clipboard. Paste them into a private Bitwarden Note, attach the .jks file, then clear the clipboard.'
        Write-Output "Attachment file: $keystore"
    } finally {
        $storePassword = $null
        $keyPassword = $null
    }
    exit
}

$env:FP_GRID_RELEASE_STORE_FILE = $keystore
$env:FP_GRID_RELEASE_STORE_PASSWORD = Read-LocalPassword $storeSecret
$env:FP_GRID_RELEASE_KEY_ALIAS = $alias
$env:FP_GRID_RELEASE_KEY_PASSWORD = Read-LocalPassword $keySecret
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
try {
    Push-Location $projectRoot
    try {
        & .\gradlew.bat assembleRelease
        if ($LASTEXITCODE -ne 0) {
            throw 'The signed release build failed.'
        }
        & .\tools\verify-release.ps1 `
            -ApkPath .\app\build\outputs\apk\release\app-release.apk `
            -ExpectedCertificateSha256 (Get-Content -LiteralPath $fingerprintFile -Raw).Trim()
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:FP_GRID_RELEASE_STORE_FILE, Env:FP_GRID_RELEASE_STORE_PASSWORD, `
        Env:FP_GRID_RELEASE_KEY_ALIAS, Env:FP_GRID_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
}
