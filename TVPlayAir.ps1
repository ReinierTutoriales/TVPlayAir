#Requires -Version 5.1
param([switch]$CheckDependencies)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageName = 'com.tvplayair.receiver'
$NotificationPermission = 'android.permission.POST_NOTIFICATIONS'
$OverlayPermission = 'android.permission.SYSTEM_ALERT_WINDOW'
$ToolsUrl = 'https://developer.android.com/tools/releases/platform-tools'
$PlatformTools = Join-Path $PSScriptRoot 'platform-tools'
$Adb = Join-Path $PlatformTools 'adb.exe'
$Serial = $null
$AndroidUser = $null
$ApiLevel = 0

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    # Windows PowerShell 5.1 can wrap native stderr as error records.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $Adb @Arguments 2>&1)
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Text = (($lines | ForEach-Object { [string]$_ }) -join "`n").Trim()
    }
}

function Test-PlatformTools {
    $missing = foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll')) {
        $file = Get-Item -LiteralPath (Join-Path $PlatformTools $name) -ErrorAction SilentlyContinue
        if ($null -eq $file -or $file.PSIsContainer -or $file.Length -eq 0) { $name }
    }
    if ($missing) {
        throw "Faltan archivos de ADB en ${PlatformTools}: $($missing -join ', ').`nDescarga las SDK Platform-Tools para Windows de Google y extrae la carpeta platform-tools completa al lado del script.`n$ToolsUrl"
    }

    try {
        $result = Invoke-Adb -Arguments @('version')
    } catch {
        throw "No se pudo ejecutar $Adb. Extrae de nuevo las Platform-Tools oficiales.`n$($_.Exception.Message)"
    }
    $identity = [regex]::Match($result.Text, '(?m)^Android Debug Bridge version[^\r\n]+')
    if ($result.ExitCode -ne 0 -or !$identity.Success) {
        throw "No se pudo verificar $Adb. Extrae de nuevo las Platform-Tools oficiales.`n$($result.Text)"
    }
    Write-Host "Dependencias verificadas en $PlatformTools"
    Write-Host $identity.Value
}

function Invoke-TvAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    Invoke-Adb -Arguments (@('-s', $Serial) + $Arguments)
}

function Read-Port {
    param([string]$Prompt, [int]$Default = 0)
    while ($true) {
        $answer = (Read-Host $Prompt).Trim()
        if (!$answer -and $Default) { return $Default }
        $port = 0
        if ([int]::TryParse($answer, [ref]$port) -and $port -ge 1 -and $port -le 65535) {
            return $port
        }
        Write-Host 'Introduce un puerto entre 1 y 65535.' -ForegroundColor Yellow
    }
}

function Read-TvIp {
    while ($true) {
        $answer = (Read-Host 'IP del televisor, sin puerto').Trim()
        $ip = $null
        if ([System.Net.IPAddress]::TryParse($answer, [ref]$ip)) {
            if ($ip.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
                return "[$ip]"
            }
            if ($answer -eq $ip.ToString() -and $answer -match '^\d{1,3}(\.\d{1,3}){3}$') {
                return $answer
            }
        }
        Write-Host 'Ejemplo de IP válida: 192.168.1.200' -ForegroundColor Yellow
    }
}

function Connect-Tv {
    $address = Read-TvIp
    do {
        $mode = (Read-Host '[1] Puerto ADB (5555 habitual)  [2] Emparejar depuración inalámbrica').Trim()
    } until ($mode -in '1', '2')

    if ($mode -eq '2') {
        $pairPort = Read-Port 'Puerto de la pantalla del código de emparejamiento'
        Write-Host 'ADB pedirá el código de seis dígitos que muestra el televisor.'
        & $Adb pair ("${address}:$pairPort")
        if ($LASTEXITCODE -ne 0) {
            throw 'Falló el emparejamiento. Abre un código nuevo en el TV y vuelve a intentarlo.'
        }
        $connectPort = Read-Port 'Puerto de conexión de la pantalla principal de depuración inalámbrica'
    } else {
        $connectPort = Read-Port 'Puerto ADB [5555]' -Default 5555
    }

    $script:Serial = "${address}:$connectPort"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $connection = Invoke-Adb -Arguments @('connect', $Serial)
        if ($connection.Text) { Write-Host $connection.Text }
        $state = Invoke-TvAdb -Arguments @('get-state')
        if ($state.ExitCode -eq 0 -and $state.Text -match '(?m)^device\s*$') {
            $sdk = Invoke-TvAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')
            $user = Invoke-TvAdb -Arguments @('shell', 'am', 'get-current-user')
            if ($sdk.ExitCode -ne 0 -or $sdk.Text -notmatch '^\d+$' -or
                $user.ExitCode -ne 0 -or $user.Text -notmatch '^\d+$') {
                throw 'El televisor no devolvió su versión Android o el perfil activo por ADB.'
            }
            $script:ApiLevel = [int]$sdk.Text
            $script:AndroidUser = $user.Text
            $model = (Invoke-TvAdb -Arguments @('shell', 'getprop', 'ro.product.model')).Text
            Write-Host "Conectado a $model ($Serial), API $ApiLevel, perfil $AndroidUser." -ForegroundColor Green
            return
        }
        if ($attempt -lt 3) {
            if ($state.Text) { Write-Host $state.Text -ForegroundColor Yellow }
            $continue = Read-Host 'Revisa IP y puerto o acepta la autorización en el TV. Intro para reintentar, X para salir'
            if ($continue -eq 'X') { break }
        }
    }
    throw 'ADB no autorizó el destino. Comprueba que el PC y el TV estén en la misma red y que el TV escuche en el puerto elegido.'
}

function Get-InstalledPackage {
    $result = Invoke-TvAdb -Arguments @('shell', 'pm', 'path', '--user', $AndroidUser, $PackageName)
    if ($result.ExitCode -ne 0 -and $result.Text -and
        $result.Text -notmatch '(?i)(\bpackage\b.*not found|unknown package)') {
        throw $result.Text
    }
    return ($result.ExitCode -eq 0 -and $result.Text -match '(?m)^package:')
}

function Read-ApkPath {
    $files = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.apk' -File | Sort-Object Name)
    for ($index = 0; $index -lt $files.Count; $index++) {
        Write-Host ("[{0}] {1}" -f ($index + 1), $files[$index].Name)
    }
    while ($true) {
        $prompt = 'Número de la lista o ruta del APK. X cancela'
        if ($files.Count -eq 1) { $prompt += ' [Intro: 1]' }
        $answer = (Read-Host $prompt).Trim().Trim('"').Trim("'")
        if ($answer -eq 'X') { return $null }
        if (!$answer -and $files.Count -eq 1) { return $files[0].FullName }
        $index = 0
        if ([int]::TryParse($answer, [ref]$index) -and $index -ge 1 -and $index -le $files.Count) {
            return $files[$index - 1].FullName
        }
        if ($answer) {
            if (![System.IO.Path]::IsPathRooted($answer)) { $answer = Join-Path $PSScriptRoot $answer }
            $file = Get-Item -LiteralPath $answer -ErrorAction SilentlyContinue
            if ($null -ne $file -and !$file.PSIsContainer -and $file.Extension -ieq '.apk') { return $file.FullName }
        }
        Write-Host 'Selecciona un archivo .apk válido.' -ForegroundColor Yellow
    }
}

function Get-PackageInfo {
    $packageInfo = Invoke-TvAdb -Arguments @('shell', 'dumpsys', 'package', $PackageName)
    if ($packageInfo.ExitCode -ne 0 -or !$packageInfo.Text) { throw 'ADB no pudo leer el manifiesto instalado.' }
    return $packageInfo.Text
}

function Test-DeclaredPermission {
    param([string]$PackageInfo, [string]$Permission)
    $expression = '(?m)^\s*' + [regex]::Escape($Permission) + '[ \t]*$'
    return $PackageInfo -match $expression
}

function Get-NotificationGrant {
    param([string]$PackageInfo)
    $profilePattern = '(?ms)^\s*User ' + [regex]::Escape($AndroidUser) + ':.*?(?=^\s*User \d+:|\z)'
    $profile = [regex]::Match($PackageInfo, $profilePattern)
    if (!$profile.Success) { return 'sin confirmación' }
    $permissionPattern = '(?m)^\s*' + [regex]::Escape($NotificationPermission) + ':\s*granted=(true|false)'
    $grant = [regex]::Match($profile.Value, $permissionPattern)
    if (!$grant.Success) { return 'sin confirmación' }
    if ($grant.Groups[1].Value -eq 'true') { return 'concedido' }
    return 'denegado'
}

function Set-ReceiverPermissions {
    $packageInfo = Get-PackageInfo
    if ($ApiLevel -ge 33 -and (Test-DeclaredPermission $packageInfo $NotificationPermission)) {
        $notification = Invoke-TvAdb -Arguments @('shell', 'pm', 'grant', '--user', $AndroidUser, $PackageName, $NotificationPermission)
        if ($notification.ExitCode -eq 0 -and $notification.Text -notmatch '(?i)error:|exception') {
            $grant = Get-NotificationGrant (Get-PackageInfo)
            Write-Host "Notificaciones: $grant."
        } else {
            Write-Host "Notificaciones: Android no concedió el permiso. $($notification.Text)" -ForegroundColor Yellow
        }
    } elseif ($ApiLevel -ge 33) {
        Write-Host 'Este APK no declara POST_NOTIFICATIONS.' -ForegroundColor Yellow
    } else {
        Write-Host 'Esta versión de Android no pide permiso de ejecución para notificaciones.'
    }

    if (!(Test-DeclaredPermission $packageInfo $OverlayPermission)) {
        Write-Host 'Este APK no declara SYSTEM_ALERT_WINDOW. Usa una compilación que lo incluya.' -ForegroundColor Yellow
        return
    }

    $overlay = Invoke-TvAdb -Arguments @('shell', 'appops', 'set', '--user', $AndroidUser, $PackageName, 'SYSTEM_ALERT_WINDOW', 'allow')
    $check = Invoke-TvAdb -Arguments @('shell', 'appops', 'get', '--user', $AndroidUser, $PackageName, 'SYSTEM_ALERT_WINDOW')
    if ($overlay.ExitCode -eq 0 -and $check.ExitCode -eq 0 -and $check.Text -match '(?im)^\s*SYSTEM_ALERT_WINDOW:\s*allow\b') {
        Write-Host 'Mostrar sobre otras aplicaciones: concedido y verificado.' -ForegroundColor Green
        Write-Host 'En TVPlayAir, activa «Abrir al recibir vídeo» si quieres la apertura automática.'
    } else {
        Write-Host "El firmware no confirmó el acceso especial. $($overlay.Text) $($check.Text)" -ForegroundColor Yellow
        Write-Host 'Revisa Ajustes del TV > Apps > Acceso especial > Mostrar sobre otras aplicaciones.'
    }
}

function Install-Receiver {
    $apk = Read-ApkPath
    if (!$apk) { return }
    Write-Host "Instalando $([System.IO.Path]::GetFileName($apk)) en $Serial, perfil $AndroidUser..."
    $result = Invoke-TvAdb -Arguments @('install', '--no-streaming', '-r', '--user', $AndroidUser, '--pkg', $PackageName, $apk)
    if ($result.ExitCode -ne 0 -or $result.Text -notmatch '(?m)^Success\s*$' -or !(Get-InstalledPackage)) {
        $hint = switch -Regex ($result.Text) {
            'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES' {
                'La firma es diferente. Usa un APK firmado igual o elige desinstalar manualmente si aceptas perder los datos.'; break
            }
            'INSTALL_FAILED_VERSION_DOWNGRADE' { 'El APK es más antiguo que la versión instalada.'; break }
            'INSTALL_FAILED_NO_MATCHING_ABIS' { 'El APK no contiene una arquitectura compatible con este televisor.'; break }
            'INSTALL_FAILED_USER_RESTRICTED' { 'El televisor o su administrador bloqueó la instalación.'; break }
            default { 'Revisa el APK y el detalle devuelto por Android.' }
        }
        throw "La instalación no se confirmó. $hint `n$($result.Text)"
    }
    Write-Host 'Instalación confirmada.' -ForegroundColor Green
    $answer = (Read-Host 'Conceder notificaciones y mostrar sobre otras apps [S/n]').Trim()
    if (!$answer -or $answer -match '^(s|sí|si)$') { Set-ReceiverPermissions }
    Write-Host 'Abre TVPlayAir desde el lanzador del TV para revisar sus ajustes.'
}

function Uninstall-Receiver {
    if (!(Get-InstalledPackage)) {
        Write-Host 'TVPlayAir no está instalado en el perfil activo.'
        return
    }
    Write-Host "Se eliminarán TVPlayAir y sus datos del perfil $AndroidUser en $Serial." -ForegroundColor Yellow
    if ((Read-Host 'Escribe DESINSTALAR para confirmar') -cne 'DESINSTALAR') {
        Write-Host 'Desinstalación cancelada.'
        return
    }
    $result = Invoke-TvAdb -Arguments @('shell', 'pm', 'uninstall', '--user', $AndroidUser, $PackageName)
    if ($result.ExitCode -ne 0 -or $result.Text -notmatch '(?m)^Success\s*$' -or (Get-InstalledPackage)) {
        throw "La desinstalación no se confirmó. $($result.Text)"
    }
    Write-Host 'Desinstalación confirmada.' -ForegroundColor Green
}

function Show-ReceiverStatus {
    if (!(Get-InstalledPackage)) {
        Write-Host 'TVPlayAir no está instalado en el perfil activo.'
        return
    }
    $info = Get-PackageInfo
    $version = [regex]::Match($info, '(?m)^\s*versionName=(.+)$')
    if ($version.Success) { Write-Host "TVPlayAir $($version.Groups[1].Value.Trim())" }
    foreach ($permission in @($NotificationPermission, $OverlayPermission)) {
        $declared = if (Test-DeclaredPermission $info $permission) { 'declarado' } else { 'ausente del APK' }
        Write-Host "$permission - $declared"
    }
    $check = Invoke-TvAdb -Arguments @('shell', 'appops', 'get', '--user', $AndroidUser, $PackageName, 'SYSTEM_ALERT_WINDOW')
    if ($ApiLevel -ge 33) { Write-Host "Notificaciones: $(Get-NotificationGrant $info)" }
    if ($check.Text) { Write-Host $check.Text }
}

try {
    Test-PlatformTools
    if ($CheckDependencies) { return }
    Write-Host 'TVPlayAir para Android TV / Google TV'
    Connect-Tv
    do {
        Write-Host "`n[1] Instalar o actualizar   [2] Desinstalar   [3] Conceder permisos   [4] Consultar estado   [0] Salir"
        $choice = (Read-Host 'Opción').Trim()
        try {
            switch ($choice) {
                '1' { Install-Receiver }
                '2' { Uninstall-Receiver }
                '3' {
                    if (Get-InstalledPackage) { Set-ReceiverPermissions }
                    else { Write-Host 'Instala TVPlayAir primero.' -ForegroundColor Yellow }
                }
                '4' { Show-ReceiverStatus }
                '0' { }
                default { Write-Host 'Elige una opción del menú.' -ForegroundColor Yellow }
            }
        } catch {
            Write-Host $_.Exception.Message -ForegroundColor Red
        }
    } until ($choice -eq '0')
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
