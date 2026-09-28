#requires -Version 5.1
<#
Mi Garaje - generador local de certificados para Windows.
Requiere OpenSSL 3.x (por ejemplo, el incluido con Git para Windows).

Ejecutar desde PowerShell, en la raiz del proyecto:
  .\herramientas\generar_certificados.ps1

Opcional: incluir tambien la IP actual del ESP32 en el certificado:
  .\herramientas\generar_certificados.ps1 -IpEsp32 "192.168.1.100"
Esa IP es solo un ejemplo. La app verifica el nombre TLS garaje.local.
Incluir ese nombre NO activa mDNS ni configura una IP fija.

El script no modifica el ESP32 ni la app. No envia datos a Internet.
Genera archivos en una carpeta nueva fuera del proyecto y no sobrescribe
una carpeta existente. No ejecutar para reemplazar una instalacion que ya
funciona, salvo que se vaya a renovar su certificado deliberadamente.

Despues de generar:
  - Copiar certificados.h junto a apertura_de_puerta.ino (archivo PRIVADO).
  - Copiar garaje_certificado.pem a app/src/main/res/raw/ en Android.
  - Compilar y cargar el firmware; compilar e instalar la app con ese mismo
    certificado publico. Nunca incluir servidor-clave.pem en Android.
  - Guardar privadamente los originales y anotar la fecha de vencimiento.

El certificado es autofirmado: no sera de confianza automaticamente en los
navegadores. La app utiliza su copia del certificado para verificar el ESP32.
Al renovarlo, deben actualizarse tanto el ESP32 como las apps instaladas.
#>
[CmdletBinding()]
param(
    [string]$CarpetaSalida = (Join-Path $env:USERPROFILE "MiGaraje_TLS"),
    [string]$RutaOpenSSL = "",
    [string]$IpEsp32 = ""
)

& {
    $ErrorActionPreference = "Stop"
    if ($env:OS -ne "Windows_NT") {
        throw "Este script requiere Windows para proteger los archivos mediante permisos NTFS."
    }

    $sanGaraje = "DNS:garaje.local"
    if ($IpEsp32.Length -gt 0) {
        [System.Net.IPAddress]$ipValidada = $null
        if (-not [System.Net.IPAddress]::TryParse($IpEsp32, [ref]$ipValidada)) {
            throw "IpEsp32 no es una direccion IPv4 valida."
        }
        if ($ipValidada.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
            $ipValidada.ToString() -cne $IpEsp32) {
            throw "Usa una IPv4 con cuatro numeros separados por puntos, sin ceros iniciales."
        }
        $sanGaraje += ",IP:" + $ipValidada.ToString()
    }

    $opensslGaraje = $RutaOpenSSL
    if ([string]::IsNullOrWhiteSpace($opensslGaraje)) {
        $comandoGaraje = Get-Command openssl.exe -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $comandoGaraje) {
            $opensslGaraje = $comandoGaraje.Source
        } else {
            foreach ($baseGaraje in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
                if ([string]::IsNullOrWhiteSpace($baseGaraje)) { continue }
                foreach ($relativaGaraje in @("Git\usr\bin\openssl.exe", "Programs\Git\usr\bin\openssl.exe")) {
                    $candidataGaraje = Join-Path $baseGaraje $relativaGaraje
                    if (Test-Path -LiteralPath $candidataGaraje -PathType Leaf) {
                        $opensslGaraje = $candidataGaraje
                        break
                    }
                }
                if (-not [string]::IsNullOrWhiteSpace($opensslGaraje)) { break }
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($opensslGaraje) -or
        -not (Test-Path -LiteralPath $opensslGaraje -PathType Leaf)) {
        throw "No se encontro OpenSSL. Instala Git para Windows o indica -RutaOpenSSL con la ruta completa a openssl.exe."
    }
    $versionGaraje = & $opensslGaraje version
    if ($LASTEXITCODE -ne 0 -or ($versionGaraje -join " ") -notmatch '^OpenSSL 3\.') {
        throw "Se requiere OpenSSL 3.x. Comprueba la ruta seleccionada."
    }

    $carpetaGaraje = [System.IO.Path]::GetFullPath($CarpetaSalida)
    if (Test-Path -LiteralPath $carpetaGaraje) {
        throw "Ya existe $carpetaGaraje. No se ha sobrescrito nada. Conserva sus archivos. Para otra instalacion usa -CarpetaSalida con una carpeta nueva."
    }
    New-Item -ItemType Directory -Path $carpetaGaraje -ErrorAction Stop | Out-Null

    # Aplicar permisos ANTES de generar las claves. Si falla, detenerse.
    $identidadGaraje = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $sistemaGaraje = New-Object System.Security.Principal.SecurityIdentifier("S-1-5-18")
    $permisosGaraje = New-Object System.Security.AccessControl.DirectorySecurity
    $permisosGaraje.SetOwner($identidadGaraje)
    $permisosGaraje.SetAccessRuleProtection($true, $false)
    foreach ($cuentaGaraje in @($identidadGaraje, $sistemaGaraje)) {
        $reglaGaraje = New-Object System.Security.AccessControl.FileSystemAccessRule(
            $cuentaGaraje, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow"
        )
        $permisosGaraje.AddAccessRule($reglaGaraje)
    }
    Set-Acl -LiteralPath $carpetaGaraje -AclObject $permisosGaraje

    $configGaraje = Join-Path $carpetaGaraje "garaje.cnf"
    $claveGaraje = Join-Path $carpetaGaraje "servidor-clave.pem"
    $certGaraje = Join-Path $carpetaGaraje "garaje_certificado.pem"
    $cabeceraGaraje = Join-Path $carpetaGaraje "certificados.h"

    @"
[req]
prompt = no
distinguished_name = identidad
x509_extensions = extensiones

[identidad]
CN = garaje.local

[extensiones]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = $sanGaraje
subjectKeyIdentifier = hash
"@ | Set-Content -LiteralPath $configGaraje -Encoding ASCII

    $argumentosGaraje = @(
        "req", "-x509", "-newkey", "ec",
        "-pkeyopt", "ec_paramgen_curve:prime256v1",
        "-sha256", "-noenc", "-days", "730", "-batch",
        "-config", $configGaraje,
        "-keyout", $claveGaraje,
        "-out", $certGaraje
    )
    & $opensslGaraje @argumentosGaraje
    if ($LASTEXITCODE -ne 0) { throw "No se pudo generar el certificado." }

    & $opensslGaraje pkey -in $claveGaraje -check -noout
    if ($LASTEXITCODE -ne 0) { throw "La comprobacion de la clave fallo." }
    & $opensslGaraje verify -CAfile $certGaraje -purpose sslserver -verify_hostname garaje.local $certGaraje
    if ($LASTEXITCODE -ne 0) { throw "La comprobacion del certificado fallo." }
    if ($IpEsp32.Length -gt 0) {
        & $opensslGaraje verify -CAfile $certGaraje -purpose sslserver -verify_ip $IpEsp32 $certGaraje
        if ($LASTEXITCODE -ne 0) { throw "La comprobacion de la IP del certificado fallo." }
    }

    $textoCertGaraje = (Get-Content -LiteralPath $certGaraje -Raw -Encoding ASCII).Trim()
    $textoClaveGaraje = (Get-Content -LiteralPath $claveGaraje -Raw -Encoding ASCII).Trim()
    if ($textoCertGaraje -notmatch '\A-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----\z' -or
        $textoClaveGaraje -notmatch '\A-----BEGIN PRIVATE KEY-----\s+[A-Za-z0-9+/=\s]+-----END PRIVATE KEY-----\z') {
        throw "Los archivos generados no tienen el formato PEM esperado."
    }
    $textoCabeceraGaraje = @"
#pragma once

// ARCHIVO PRIVADO: contiene la clave TLS de este ESP32.
// No publicarlo ni incluirlo en Android. Conservar una copia privada.
static const char certificadoServidor[] = R"GARAJE_CERT(
$textoCertGaraje
)GARAJE_CERT";

static const char clavePrivadaServidor[] = R"GARAJE_KEY(
$textoClaveGaraje
)GARAJE_KEY";
"@
    $salidaGaraje = [System.IO.File]::Open(
        $cabeceraGaraje, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::None
    )
    $bytesCabeceraGaraje = $null
    try {
        $bytesCabeceraGaraje = [System.Text.Encoding]::ASCII.GetBytes($textoCabeceraGaraje)
        $salidaGaraje.Write($bytesCabeceraGaraje, 0, $bytesCabeceraGaraje.Length)
    } finally {
        $salidaGaraje.Dispose()
        if ($null -ne $bytesCabeceraGaraje) {
            [Array]::Clear($bytesCabeceraGaraje, 0, $bytesCabeceraGaraje.Length)
        }
        $textoClaveGaraje = $null
        $textoCabeceraGaraje = $null
    }

    & $opensslGaraje x509 -in $certGaraje -noout -subject -dates -fingerprint -sha256 -ext subjectAltName
    if ($LASTEXITCODE -ne 0) { throw "No se pudo leer la informacion publica del certificado." }
    Write-Host ""
    Write-Host "LISTO. Archivos nuevos en: $carpetaGaraje"
    Write-Host "PRIVADOS: certificados.h y servidor-clave.pem. No publicarlos."
    Write-Host "PUBLICO: garaje_certificado.pem, para la app de esta instalacion."
    Write-Host "Vencimiento: fecha notAfter mostrada arriba (730 dias)."
    Write-Host "El nombre garaje.local sirve para verificar TLS; no configura descubrimiento de red."
    Write-Host "No se modificaron el ESP32, el proyecto Android ni los telefonos."
}
