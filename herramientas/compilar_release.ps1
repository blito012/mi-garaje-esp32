# Compila Mi Garaje firmada (release).
# Pide la contrasena de la firma; no la guarda en ningun archivo.
# Uso:
#   Crear firma nueva y compilar:  compilar_release.ps1 -Firma C:\ruta\firma.jks -CrearFirma
#   Solo compilar:                 compilar_release.ps1 -Firma C:\ruta\firma.jks
param(
    [Parameter(Mandatory = $true)][string]$Firma,
    [switch]$CrearFirma
)
$ErrorActionPreference = 'Stop'

function Leer-Clave([string]$texto) {
    $segura = Read-Host $texto -AsSecureString
    $puntero = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($segura)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntero)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntero)
    }
}

if ($CrearFirma -and (Test-Path $Firma)) { throw "Ya existe $Firma. No se sobrescribe." }
if (-not $CrearFirma -and -not (Test-Path $Firma)) { throw "No se encontro la firma $Firma." }

$clave = Leer-Clave 'Contrasena de la firma'
if ($CrearFirma) {
    if ($clave.Length -lt 8) { throw 'Usa al menos 8 caracteres.' }
    if ($clave -ne (Leer-Clave 'Repite la contrasena')) { throw 'Las contrasenas no coinciden.' }
}

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:MIGARAJE_FIRMA = $Firma
$env:MIGARAJE_CLAVE_FIRMA = $clave
try {
    if ($CrearFirma) {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $Firma `
            -storetype PKCS12 -alias mi-garaje -keyalg RSA -keysize 4096 -validity 10000 `
            -dname 'CN=Mi Garaje, OU=Desarrollo, O=Proyecto personal, C=EC' `
            -storepass:env MIGARAJE_CLAVE_FIRMA -keypass:env MIGARAJE_CLAVE_FIRMA
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la firma.' }
        Write-Host "Firma creada: $Firma"
    }
    Push-Location (Join-Path $PSScriptRoot '..\android')
    try {
        # Sin daemon: asi la contrasena no queda en un proceso de Gradle abierto.
        & .\gradlew.bat assembleRelease --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'La compilacion fallo.' }
    } finally {
        Pop-Location
    }
    Write-Host ''
    Write-Host 'LISTO: android\app\build\outputs\apk\release\app-release.apk'
} finally {
    Remove-Item Env:MIGARAJE_CLAVE_FIRMA -ErrorAction SilentlyContinue
    $clave = $null
}
