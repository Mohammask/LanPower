# ==========================================================
# LanPower Windows Agent - Smart Installer & Manager
# Works natively on Windows 10 / 11
# Compatible with both local file execution & `irm ... | iex`
#
# NOTE: this file keeps Windows (CRLF) line endings.
# Made with <3 by Mohammask - https://github.com/Mohammask/LanPower
# ==========================================================

$InstallDir = "C:\ProgramData\LanPower"
$AgentScript = "$InstallDir\lan-power-agent.ps1"
$ConfigFile = "$InstallDir\config.json"
$LogFile = "$InstallDir\agent.log"
$TaskName = "LanPowerAgent"
$FirewallRule = "LanPower Agent"

function Write-Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg) { Write-Host "[ERROR] $msg" -ForegroundColor Red }

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Confirm-Yes($prompt) {
    $ans = Read-Host "$prompt [Y/n]"
    return ($ans -notmatch '^(n|no)$')
}

function Confirm-No($prompt) {
    $ans = Read-Host "$prompt [y/N]"
    return ($ans -match '^(y|yes)$')
}

function Test-Installed {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    return ((Test-Path $AgentScript) -and (Test-Path $ConfigFile) -and ($null -ne $task))
}

function Test-SafeInstallDir {
    param([string]$Path)
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $true }
    if (-not $item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        return $false
    }
    $ownerSid = (Get-Acl -LiteralPath $Path).GetOwner([System.Security.Principal.SecurityIdentifier]).Value
    return ($ownerSid -in @('S-1-5-18', 'S-1-5-32-544'))
}

function Protect-InstallDir {
    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
    try {
        # 1. Lock the folder itself (no /T so folder-only (OI)(CI) flags are not applied to files)
        $icacls = "$env:SystemRoot\System32\icacls.exe"
        & $icacls $InstallDir /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" /grant:r "*S-1-5-32-544:(OI)(CI)F" /Q | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Could not tighten ACL permissions on '$InstallDir' (icacls exit code $LASTEXITCODE)."
            return
        }
        # 2. Reset child files so they cleanly inherit from the locked folder
        Get-ChildItem -Path $InstallDir -Force -ErrorAction SilentlyContinue | ForEach-Object {
            & $icacls $_.FullName /reset /C /Q | Out-Null
        }
    }
    catch {
        Write-Warn "Could not tighten ACL permissions on '$InstallDir': $($_.Exception.Message)"
    }
}

function Get-Config {
    if (-not (Test-Path $ConfigFile)) { return $null }
    try {
        return (Get-Content -Path $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json)
    }
    catch {
        return $null
    }
}

function Save-Config($cfgObject) {
    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
    Protect-InstallDir
    $json = $cfgObject | ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText($ConfigFile, $json, (New-Object System.Text.UTF8Encoding($false)))
    Protect-InstallDir
}

function Test-PortFree($port) {
    try {
        $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        return -not ($listeners | Where-Object { $_.Port -eq [int]$port })
    }
    catch {
        return $true
    }
}

function Stop-AgentCleanly {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    $procs = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like "*$AgentScript*" }
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 400
}

function Test-AgentRunning {
    $procs = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like "*$AgentScript*" }
    return ($null -ne $procs)
}

function Wait-AgentReady([int]$port, [int]$maxAttempts = 6) {
    for ($i = 0; $i -lt $maxAttempts; $i++) {
        Start-Sleep -Seconds 1
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/status" -TimeoutSec 2 -ErrorAction Stop
            if ($resp.service -eq "lan-power-agent") { return $true }
        }
        catch {}
    }
    return $false
}

function Get-DefaultAdapter {
    try {
        $route = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Sort-Object { $_.RouteMetric + $_.InterfaceMetric } | Select-Object -First 1
        if ($route) {
            return (Get-NetAdapter -InterfaceIndex $route.ifIndex -ErrorAction SilentlyContinue)
        }
    }
    catch {}
    return (Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq "Up" } | Select-Object -First 1)
}

function Get-AdapterIPv4($ifIndex) {
    try {
        $ip = Get-NetIPAddress -InterfaceIndex $ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "169.254.*" -and $_.IPAddress -ne "127.0.0.1" } |
        Select-Object -First 1
        if ($ip) { return $ip.IPAddress }
    }
    catch {}
    return "unknown"
}

function Format-Mac($rawMac) {
    if ([string]::IsNullOrWhiteSpace($rawMac)) { return "UNKNOWN" }
    return ($rawMac -replace '-', ':').ToUpper()
}

function Get-AdapterInfo($adapter) {
    $desc = [string]$adapter.InterfaceDescription
    $phys = [string]$adapter.PhysicalMediaType
    $media = [string]$adapter.MediaType
    $pnp = [string]$adapter.PnPDeviceID

    $isVirtual = ($adapter.Virtual -eq $true) -or ($desc -match 'Virtual|Hyper-V|VMware|TAP|TUN|WireGuard|ZeroTier|Tailscale|VPN|Loopback')
    $isWifi = ($phys -match '802\.11|Wireless') -or ($media -match '802\.11|Wireless') -or ($desc -match 'Wi-?Fi|Wireless|802\.11')
    $isUsb = ($pnp -like 'USB\*') -or ($desc -match '\bUSB\b')

    $tags = @()
    if ($isVirtual) { $tags += "Virtual" }
    elseif ($isWifi) { $tags += "Wi-Fi" }
    else { $tags += "Ethernet" }
    if ($isUsb) { $tags += "USB" }

    return [PSCustomObject]@{
        IsVirtual = $isVirtual
        IsWifi    = $isWifi
        IsUsb     = $isUsb
        Label     = ($tags -join " / ")
    }
}

function Test-AdapterWakeCapability($adapter) {
    $supported = $false
    $pmState = "Unsupported"

    try {
        $pm = Get-NetAdapterPowerManagement -Name $adapter.Name -ErrorAction SilentlyContinue
        if ($pm -and $pm.WakeOnMagicPacket -and ($pm.WakeOnMagicPacket -ne "Unsupported")) {
            $supported = $true
            $pmState = [string]$pm.WakeOnMagicPacket
        }
    }
    catch {}

    if (-not $supported) {
        try {
            $adv = Get-NetAdapterAdvancedProperty -Name $adapter.Name -ErrorAction SilentlyContinue |
            Where-Object {
                $_.RegistryKeyword -eq "*WakeOnMagicPacket" -or
                $_.DisplayName -match "Wake on Magic Packet"
            }
            if ($adv) { $supported = $true; $pmState = "Driver-Supported" }
        }
        catch {}
    }

    if (-not $supported -and -not [string]::IsNullOrWhiteSpace($adapter.PnPDeviceID)) {
        try {
            $wmi = Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceWakeEnable -ErrorAction SilentlyContinue |
            Where-Object { $_.InstanceName -like "$($adapter.PnPDeviceID)*" }
            if ($wmi) { $supported = $true; $pmState = "WMI-Supported" }
        }
        catch {}
    }

    return [PSCustomObject]@{
        Supported = $supported
        State     = $pmState
    }
}

function Test-FastStartupActive {
    try {
        $powerCtrl = Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Power" -ErrorAction SilentlyContinue
        $hiberEnabled = $powerCtrl.HibernateEnabled

        if ($null -eq $hiberEnabled) {
            $hiberFile = Get-Item -Path "$env:SystemDrive\hiberfil.sys" -Force -ErrorAction SilentlyContinue
            $hiberEnabled = if ($hiberFile) { 1 } else { 0 }
        }
        if ($hiberEnabled -ne 1) { return $false }

        $smPower = Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -ErrorAction SilentlyContinue
        return ($smPower.HiberbootEnabled -eq 1)
    }
    catch {
        return $false
    }
}

function Write-AgentScript {
    $agentCode = @'
$ErrorActionPreference = "SilentlyContinue"

$ConfigPath      = "C:\ProgramData\LanPower\config.json"
$LogPath         = "C:\ProgramData\LanPower\agent.log"
$MaxLogBytes     = 65536
$MaxFails        = 5
$LockoutSeconds  = 60
$MaxTrackedIps   = 500
$FailIdleSeconds = 600
$FailedAttempts  = @{}

function Write-AgentLog([string]$message) {
    try {
        if (Test-Path $LogPath) {
            $len = (Get-Item -Path $LogPath -ErrorAction Stop).Length
            if ($len -ge $MaxLogBytes) {
                [System.IO.File]::WriteAllText($LogPath, "", (New-Object System.Text.UTF8Encoding($false)))
            }
        }
        $line = "[$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))] $message`r`n"
        [System.IO.File]::AppendAllText($LogPath, $line, (New-Object System.Text.UTF8Encoding($false)))
    } catch {}
}

if (-not (Test-Path $ConfigPath)) {
    Write-AgentLog "Config file not found: $ConfigPath"
    exit 1
}
try {
    $Config = Get-Content -Path $ConfigPath -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json
} catch {
    Write-AgentLog "Failed to load ${ConfigPath}: $($_.Exception.Message)"
    exit 1
}

$Port      = [int]$Config.port
$Token     = if ($null -ne $Config.token) { [string]$Config.token } else { "" }
$IfaceName = if ($null -ne $Config.interface) { [string]$Config.interface } else { "" }
$SavedMac  = if ($null -ne $Config.mac) { ([string]$Config.mac).ToUpper() } else { "UNKNOWN" }

# Resolve live MAC once at startup via fast .NET API (avoids slow WMI on every /status request)
$CachedMac = $SavedMac
if (-not [string]::IsNullOrEmpty($IfaceName)) {
    try {
        $dotNetNic = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
                     Where-Object { $_.Name -eq $IfaceName } | Select-Object -First 1
        if ($dotNetNic) {
            $hex = $dotNetNic.GetPhysicalAddress().ToString()
            if ($hex.Length -eq 12) {
                $CachedMac = ($hex -replace '(.{2})(?=.)', '$1:').ToUpper()
            }
        }
    } catch {}
}

# Pre-serialize static /status payload for sub-5ms LAN scan responses
$StatusBytes = [System.Text.Encoding]::UTF8.GetBytes(([ordered]@{
    service       = "lan-power-agent"
    version       = "1.0"
    status        = "online"
    hostname      = $env:COMPUTERNAME
    mac           = $CachedMac
    interface     = $IfaceName
    auth_required = (-not [string]::IsNullOrEmpty($Token))
} | ConvertTo-Json -Compress))

function Test-TokenEqual([string]$provided, [string]$expected) {
    if ($null -eq $provided) { $provided = "" }
    $a = [System.Text.Encoding]::UTF8.GetBytes($provided)
    $b = [System.Text.Encoding]::UTF8.GetBytes($expected)
    $diff = $a.Length -bxor $b.Length
    $maxLen = [Math]::Max($a.Length, $b.Length)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $ba = if ($i -lt $a.Length) { $a[$i] } else { 0 }
        $bb = if ($i -lt $b.Length) { $b[$i] } else { 0 }
        $diff = $diff -bor ($ba -bxor $bb)
    }
    return ($diff -eq 0)
}

function Get-LockoutRemaining([string]$ip) {
    if (-not $FailedAttempts.ContainsKey($ip)) { return 0 }
    $entry = $FailedAttempts[$ip]
    $now = [DateTime]::UtcNow
    if ($entry["LockedUntil"] -gt $now) {
        return ([int][Math]::Ceiling(($entry["LockedUntil"] - $now).TotalSeconds))
    }
    if ($entry["LockedUntil"] -gt [DateTime]::MinValue) {
        $FailedAttempts.Remove($ip)
    }
    return 0
}

function Remove-StaleAuthFailures([DateTime]$now) {
    # Active lockouts are never dropped: purge expired lockouts and idle partial
    # counters first, then (only if still over the cap) the oldest partial counters.
    foreach ($key in @($FailedAttempts.Keys)) {
        $entry = $FailedAttempts[$key]
        if ($entry["LockedUntil"] -gt [DateTime]::MinValue) {
            if ($entry["LockedUntil"] -le $now) {
                $FailedAttempts.Remove($key)
            }
        } elseif (($now - $entry["LastSeen"]).TotalSeconds -gt $FailIdleSeconds) {
            $FailedAttempts.Remove($key)
        }
    }

    if ($FailedAttempts.Count -gt $MaxTrackedIps) {
        $idleKeys = @($FailedAttempts.Keys |
            Where-Object { $FailedAttempts[$_]["LockedUntil"] -le [DateTime]::MinValue } |
            Sort-Object { $FailedAttempts[$_]["LastSeen"] })
        foreach ($key in $idleKeys) {
            if ($FailedAttempts.Count -le $MaxTrackedIps) { break }
            $FailedAttempts.Remove($key)
        }
    }
}

function Add-AuthFailure([string]$ip) {
    $now = [DateTime]::UtcNow
    if ($FailedAttempts.Count -gt $MaxTrackedIps) {
        Remove-StaleAuthFailures $now
    }
    if (-not $FailedAttempts.ContainsKey($ip)) {
        $FailedAttempts[$ip] = @{ Fails = 0; LockedUntil = [DateTime]::MinValue; LastSeen = $now }
    }
    $entry = $FailedAttempts[$ip]
    $entry["Fails"] = [int]$entry["Fails"] + 1
    $entry["LastSeen"] = $now
    if ($entry["Fails"] -ge $MaxFails) {
        $entry["LockedUntil"] = $now.AddSeconds($LockoutSeconds)
        $entry["Fails"] = 0
    }
}

function Send-RawJson($response, [int]$statusCode, [byte[]]$bytes) {
    $response.StatusCode = $statusCode
    $response.KeepAlive = $false
    $response.ContentType = "application/json; charset=utf-8"
    $response.ContentLength64 = $bytes.Length
    $response.OutputStream.Write($bytes, 0, $bytes.Length)
    $response.OutputStream.Close()
}

function Send-JsonResponse($response, [int]$statusCode, $payload) {
    $json  = $payload | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    Send-RawJson $response $statusCode $bytes
}

function Invoke-SystemSleep {
    if (-not ("LanPowerSleep" -as [type])) {
        $cs = @(
            'using System;',
            'using System.Runtime.InteropServices;',
            'public static class LanPowerSleep {',
            '    [StructLayout(LayoutKind.Sequential, Pack = 1)] private struct LUID { public uint LowPart; public int HighPart; }',
            '    [StructLayout(LayoutKind.Sequential, Pack = 1)] private struct TOKEN_PRIVILEGES { public int PrivilegeCount; public LUID Luid; public int Attributes; }',
            '    [DllImport("kernel32.dll", ExactSpelling = true)] private static extern IntPtr GetCurrentProcess();',
            '    [DllImport("advapi32.dll", ExactSpelling = true, SetLastError = true)] private static extern bool OpenProcessToken(IntPtr h, int acc, ref IntPtr phtok);',
            '    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool LookupPrivilegeValue(string host, string name, ref LUID pluid);',
            '    [DllImport("advapi32.dll", ExactSpelling = true, SetLastError = true)] private static extern bool AdjustTokenPrivileges(IntPtr htok, bool disall, ref TOKEN_PRIVILEGES newst, int len, IntPtr prev, IntPtr relen);',
            '    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool CloseHandle(IntPtr hObject);',
            '    [DllImport("powrprof.dll", SetLastError = true)] private static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);',
            '    public static bool SleepNow() {',
            '        IntPtr htok = IntPtr.Zero;',
            '        try {',
            '            if (OpenProcessToken(GetCurrentProcess(), 0x0028, ref htok)) {',
            '                TOKEN_PRIVILEGES tp = new TOKEN_PRIVILEGES { PrivilegeCount = 1, Attributes = 0x00000002 };',
            '                if (LookupPrivilegeValue(null, "SeShutdownPrivilege", ref tp.Luid)) {',
            '                    AdjustTokenPrivileges(htok, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero);',
            '                }',
            '            }',
            '        } finally { if (htok != IntPtr.Zero) CloseHandle(htok); }',
            '        return SetSuspendState(false, false, false);',
            '    }',
            '}'
        ) -join "`r`n"
        Add-Type -TypeDefinition $cs -ErrorAction Stop
    }
    return [LanPowerSleep]::SleepNow()
}
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$Port/")
try {
    $listener.Start()
} catch {
    Write-AgentLog "Cannot bind HTTP listener on port ${Port}: $($_.Exception.Message)"
    exit 1
}

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            $req    = $context.Request
            $res    = $context.Response
            $method = $req.HttpMethod.ToUpper()
            $path   = $req.Url.AbsolutePath

            if ($method -eq "GET") {
                if ($path -eq "/status") {
                    Send-RawJson $res 200 $StatusBytes
                } else {
                    Send-JsonResponse $res 404 @{ error = "not_found" }
                }
                continue
            }

            if ($method -eq "POST") {
                if ($path -notin @("/verify", "/poweroff", "/reboot", "/sleep")) {
                    Send-JsonResponse $res 404 @{ error = "not_found" }
                    continue
                }

                if ($null -ne $req.Headers["Origin"]) {
                    Send-JsonResponse $res 403 ([ordered]@{ ok = $false; error = "browser_requests_not_allowed" })
                    continue
                }

                $clientIp = $req.RemoteEndPoint.Address.ToString()
                if (-not [string]::IsNullOrEmpty($Token)) {
                    $retryAfter = Get-LockoutRemaining $clientIp
                    if ($retryAfter -gt 0) {
                        Send-JsonResponse $res 429 ([ordered]@{
                            ok          = $false
                            error       = "too_many_attempts"
                            retry_after = $retryAfter
                        })
                        continue
                    }

                    $provided = $req.Headers["X-Auth-Token"]
                    if (-not (Test-TokenEqual $provided $Token)) {
                        Add-AuthFailure $clientIp
                        Send-JsonResponse $res 401 ([ordered]@{
                            ok    = $false
                            error = "unauthorized"
                        })
                        continue
                    }
                    $FailedAttempts.Remove($clientIp)
                }

                if ($path -eq "/verify") {
                    Send-JsonResponse $res 200 ([ordered]@{ ok = $true; authorized = $true })
                    continue
                }

                $action = switch ($path) {
                    "/poweroff" { "poweroff" }
                    "/reboot"   { "reboot" }
                    "/sleep"    { "sleep" }
                }
                Send-JsonResponse $res 200 ([ordered]@{ ok = $true; action = $action })

                Start-Sleep -Milliseconds 800
                try {
                    if ($action -eq "poweroff") {
                        Start-Process -FilePath "$env:SystemRoot\System32\shutdown.exe" -ArgumentList "/s /f /t 0" -WindowStyle Hidden -ErrorAction Stop
                    } elseif ($action -eq "reboot") {
                        Start-Process -FilePath "$env:SystemRoot\System32\shutdown.exe" -ArgumentList "/r /f /t 0" -WindowStyle Hidden -ErrorAction Stop
                    } elseif ($action -eq "sleep") {
                        if (-not (Invoke-SystemSleep)) {
                            Write-AgentLog "SetSuspendState returned false (Sleep may be disabled or unsupported)."
                        }
                    }
                } catch {
                    Write-AgentLog "Failed to execute $action command: $($_.Exception.Message)"
                }
                continue
            }

            Send-JsonResponse $res 405 @{ error = "method_not_allowed" }
        } catch {
            Write-AgentLog "Request handling error: $($_.Exception.Message)"
            try { $context.Response.Abort() } catch {}
        }
    }
} catch {
    Write-AgentLog "Listener loop terminated unexpectedly: $($_.Exception.Message)"
} finally {
    if ($listener) { $listener.Stop(); $listener.Close() }
}
'@
    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }
    Protect-InstallDir
    [System.IO.File]::WriteAllText($AgentScript, $agentCode, (New-Object System.Text.UTF8Encoding($false)))
    Protect-InstallDir
}

function Enable-WindowsWoL($adapter) {
    $info = Get-AdapterInfo $adapter

    if ($info.IsVirtual) {
        Write-Warn "Selected adapter '$($adapter.Name)' is [Virtual]. Skipping Wake-on-LAN."
        Write-Info "Status, Shutdown, and Reboot API will still work normally."
        return "Skipped (Virtual)"
    }

    $wakeCap = Test-AdapterWakeCapability $adapter
    if (-not $wakeCap.Supported) {
        Write-Warn "Driver for '$($adapter.Name)' [$($info.Label)] does NOT report Wake-on-Magic-Packet support."
        Write-Info "Skipping WoL configuration. Status, Shutdown, and Reboot API will still work normally."
        return "Unsupported by driver"
    }

    $wolConfigured = $false

    # 1. Power Management (only modify if needed to avoid unnecessary adapter bounce)
    try {
        $pm = Get-NetAdapterPowerManagement -Name $adapter.Name -ErrorAction Stop
        if ($pm -and $pm.WakeOnMagicPacket -and ($pm.WakeOnMagicPacket -ne "Unsupported")) {
            if ($pm.WakeOnMagicPacket -eq "Enabled") {
                $wolConfigured = $true
            }
            else {
                try {
                    Enable-NetAdapterPowerManagement -Name $adapter.Name -ErrorAction SilentlyContinue | Out-Null
                    Set-NetAdapterPowerManagement -Name $adapter.Name -WakeOnMagicPacket Enabled -ErrorAction Stop | Out-Null
                    $wolConfigured = $true
                }
                catch {}
            }
            if ($pm.WakeOnPattern -eq "Enabled") {
                try {
                    Set-NetAdapterPowerManagement -Name $adapter.Name -WakeOnPattern Disabled -ErrorAction Stop | Out-Null
                }
                catch {}
            }
        }
    }
    catch {}

    # 2. WMI Power Management checkboxes ("Allow this device to wake" + "Only allow a magic packet")
    if (-not [string]::IsNullOrWhiteSpace($adapter.PnPDeviceID)) {
        $pnpPrefix = "$($adapter.PnPDeviceID)*"
        try {
            $wakeObj = Get-CimInstance -Namespace root\wmi -ClassName MSPower_DeviceWakeEnable -ErrorAction Stop |
            Where-Object { $_.InstanceName -like $pnpPrefix }
            foreach ($obj in $wakeObj) {
                if ($obj.Enable -eq $true) {
                    $wolConfigured = $true
                }
                else {
                    try {
                        Set-CimInstance -InputObject $obj -Property @{ Enable = $true } -ErrorAction Stop | Out-Null
                        $wolConfigured = $true
                    }
                    catch {}
                }
            }
        }
        catch {}

        try {
            $magicOnlyObj = Get-CimInstance -Namespace root\wmi -ClassName MSNdis_DeviceWakeOnMagicPacketOnly -ErrorAction Stop |
            Where-Object { $_.InstanceName -like $pnpPrefix }
            foreach ($obj in $magicOnlyObj) {
                if ($obj.EnableWakeOnMagicPacketOnly -eq $true) {
                    $wolConfigured = $true
                }
                else {
                    try {
                        Set-CimInstance -InputObject $obj -Property @{ EnableWakeOnMagicPacketOnly = $true } -ErrorAction Stop | Out-Null
                        $wolConfigured = $true
                    }
                    catch {}
                }
            }
        }
        catch {}
    }

    # 3. Advanced Driver Properties (Magic Packet & Shutdown Wake-on-LAN)
    try {
        $advProps = Get-NetAdapterAdvancedProperty -Name $adapter.Name -ErrorAction Stop |
        Where-Object {
            $_.RegistryKeyword -eq "*WakeOnMagicPacket" -or
            $_.DisplayName -match "Wake on Magic Packet|Shutdown Wake-On-Lan|Wake on Shutdown"
        }
        foreach ($prop in $advProps) {
            if ($prop.RegistryValue -contains "1") {
                $wolConfigured = $true
            }
            else {
                try {
                    Set-NetAdapterAdvancedProperty -Name $adapter.Name -RegistryKeyword $prop.RegistryKeyword -RegistryValue 1 -ErrorAction Stop | Out-Null
                    $wolConfigured = $true
                }
                catch {}
            }
        }
    }
    catch {}

    if (-not $wolConfigured) {
        Write-Warn "Could not apply Wake-on-LAN settings on '$($adapter.Name)' (continuing anyway)."
        return "Failed to configure"
    }

    if (-not $info.IsWifi -and -not $info.IsUsb) {
        Write-Ok "Wake-on-LAN (Magic Packet Only) enabled on '$($adapter.Name)' [Ethernet]."
        $summary = "Enabled (Ethernet WoL)"
    }
    elseif ($info.IsWifi -and -not $info.IsUsb) {
        Write-Ok "Wake-on-Wireless-LAN (Magic Packet Only) enabled on '$($adapter.Name)' [Built-in / PCIe Wi-Fi]."
        Write-Info "Note: WoWLAN works reliably from Sleep (S3); waking from full Shutdown (S5) depends on BIOS/motherboard support."
        $summary = "Enabled (WoWLAN - S3/BIOS-dependent S5)"
    }
    else {
        Write-Ok "Wake (Magic Packet Only) enabled on '$($adapter.Name)' [$($info.Label)]."
        Write-Info "Note: USB adapters usually support waking from Sleep (S3) if USB wake is enabled in BIOS; full Shutdown (S5) is rarely supported on consumer USB dongles."
        $summary = "Enabled ($($info.Label) - typically Sleep/S3)"
    }

    # 4. Accurate Fast Startup check (only prompts if truly active)
    if (Test-FastStartupActive) {
        Write-Warn "Windows 'Fast Startup' is ACTIVE (often blocks Wake-on-LAN after normal Start Menu shutdown)."
        if (Confirm-Yes "Disable Windows Fast Startup for reliable Wake-on-LAN?") {
            try {
                Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -Name HiberbootEnabled -Value 0 -ErrorAction Stop
                Write-Ok "Fast Startup disabled."
            }
            catch {
                Write-Warn "Could not disable Fast Startup automatically."
            }
        }
    }
    else {
        Write-Ok "Windows Fast Startup is already disabled (WoL friendly)."
    }

    return $summary
}

function Option-Install {
    if (-not (Test-SafeInstallDir $InstallDir)) {
        Write-Err "Unsafe install directory detected (unexpected owner or symlink/junction). Remove it manually before continuing."
        return
    }
    Write-Host ""
    Write-Host "=== Option 1: Full Setup & Installation ===" -ForegroundColor White

    # 1. Select Network Adapter
    $upAdapters = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq "Up" })
    if ($upAdapters.Count -eq 0) {
        $upAdapters = @(Get-NetAdapter -ErrorAction SilentlyContinue)
    }
    if ($upAdapters.Count -eq 0) {
        Write-Err "No network adapters found."
        return
    }

    $defaultAdapter = Get-DefaultAdapter
    Write-Info "Available active network adapters:"
    for ($i = 0; $i -lt $upAdapters.Count; $i++) {
        $a = $upAdapters[$i]
        $aInfo = Get-AdapterInfo $a
        $ip = Get-AdapterIPv4 $a.ifIndex
        $mac = Format-Mac $a.MacAddress
        $marker = if ($defaultAdapter -and $a.Name -eq $defaultAdapter.Name) { " (Default)" } else { "" }
        Write-Host "  [$($i + 1)] $($a.Name) [$($aInfo.Label)] - IP: $ip | MAC: $mac$marker"
    }

    $selectedAdapter = $null
    while ($null -eq $selectedAdapter) {
        $inputIdx = Read-Host "Select adapter number [Enter for Default]"
        if ([string]::IsNullOrWhiteSpace($inputIdx)) {
            $selectedAdapter = if ($defaultAdapter) { $defaultAdapter } else { $upAdapters[0] }
        }
        elseif ($inputIdx -match '^\d+$' -and [int]$inputIdx -ge 1 -and [int]$inputIdx -le $upAdapters.Count) {
            $selectedAdapter = $upAdapters[[int]$inputIdx - 1]
        }
        else {
            Write-Err "Invalid selection. Enter a number between 1 and $($upAdapters.Count)."
        }
    }

    $selectedInfo = Get-AdapterInfo $selectedAdapter
    $localIp = Get-AdapterIPv4 $selectedAdapter.ifIndex
    $macAddr = Format-Mac $selectedAdapter.MacAddress

    # 2. Choose & Validate Port
    $existingCfg = Get-Config
    $agentWasRunning = Test-AgentRunning
    $existingPort = if ($existingCfg -and $existingCfg.port) { [int]$existingCfg.port } else { 0 }
    $defaultPort = if ($existingPort -gt 0) { $existingPort } else { 8888 }

    $port = 0
    while ($true) {
        $portInput = Read-Host "API Port [$defaultPort]"
        if ([string]::IsNullOrWhiteSpace($portInput)) { $portInput = "$defaultPort" }

        if ($portInput -notmatch '^\d+$' -or [int]$portInput -lt 1 -or [int]$portInput -gt 65535) {
            Write-Err "Invalid port. Enter a number between 1 and 65535."
            continue
        }

        $port = [int]$portInput
        if ($agentWasRunning -and $port -eq $existingPort) {
            Write-Info "Port $port is currently used by LanPower Agent and will be reused."
            break
        }

        if (Test-PortFree $port) {
            Write-Ok "Port $port is available."
            break
        }
        else {
            Write-Err "Port $port is ALREADY IN USE by another application!"
            $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($conn) {
                $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
                if ($proc) { Write-Warn "Used by process: $($proc.ProcessName) (PID $($proc.Id))" }
            }
            Write-Warn "Please choose a different port."
        }
    }

    # 3. Optional PIN (warn clearly if left empty)
    $token = ""
    while ($true) {
        $token = Read-Host "Optional PIN/Token (press Enter for NO PIN)"
        if ([string]::IsNullOrEmpty($token)) {
            break
        }
        if (-not ($token -cmatch '^[\x21-\x7E]{4,64}$')) {
            Write-Err "PIN must be 4-64 printable ASCII characters with no spaces."
            continue
        }
        if ($token.Length -lt 6) {
            Write-Warn "PIN is shorter than 6 characters. At least 6 characters is recommended for stronger brute-force protection."
        }
        break
    }
    if ([string]::IsNullOrEmpty($token)) {
        Write-Warn "NO PIN is set: anyone on your network who can reach port $port can power off or reboot this machine."
        Write-Warn "Using a PIN is strongly recommended (you can add one later with option 2)."
    }

    # 4. Configure Wake-on-LAN / WoWLAN & Fast Startup
    $wolSummary = Enable-WindowsWoL $selectedAdapter

    # 5. Stop previous instance, write files & lock ACL permissions
    Stop-AgentCleanly
    Save-Config ([ordered]@{
            port      = $port
            token     = $token
            interface = $selectedAdapter.Name
            mac       = $macAddr
        })
    Write-AgentScript

    # 6. Register Startup Scheduled Task (SYSTEM, Hidden, No Timeout, Full PowerShell Path)
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    $psExe = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $action = New-ScheduledTaskAction -Execute $psExe `
        -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$AgentScript`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId "NT AUTHORITY\SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -ExecutionTimeLimit (New-TimeSpan -Seconds 0) `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1)

    try {
        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
        Write-Ok "Background startup task '$TaskName' registered."
    }
    catch {
        Write-Err "Failed to register Scheduled Task: $_"
        return
    }

    # 7. Configure Windows Firewall (LAN Subnet Only)
    try {
        Remove-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
        New-NetFirewallRule -DisplayName $FirewallRule `
            -Direction Inbound -Protocol TCP -LocalPort $port `
            -RemoteAddress LocalSubnet -Action Allow | Out-Null
        Write-Ok "Windows Firewall rule added for TCP port $port (Local LAN only)."
    }
    catch {
        Write-Warn "Could not add Windows Firewall rule automatically. Allow TCP port $port manually."
    }

    # 8. Start Agent & Verify Live HTTP Response (with retry loop)
    Start-ScheduledTask -TaskName $TaskName
    if (-not (Wait-AgentReady -port $port)) {
        Write-Err "Agent task started, but http://127.0.0.1:$port/status did not respond."
        Write-Warn "Check Task Scheduler -> '$TaskName' or log file '$LogFile'."
        return
    }

    $pinLabel = if ([string]::IsNullOrEmpty($token)) { "Disabled (None)" } else { "Enabled" }

    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host " LanPower Setup Completed Successfully!" -ForegroundColor White
    Write-Host " Hostname : $env:COMPUTERNAME"
    Write-Host " Adapter  : $($selectedAdapter.Name) [$($selectedInfo.Label)]"
    Write-Host " IP       : $localIp"
    Write-Host " MAC      : $macAddr"
    Write-Host " Port     : $port"
    Write-Host " PIN Auth : $pinLabel"
    Write-Host " Wake     : $wolSummary"
    Write-Host " Test URL : http://${localIp}:$port/status"
    Write-Host "========================================" -ForegroundColor Green
}

function Option-ChangePin {
    if (-not (Test-SafeInstallDir $InstallDir)) {
        Write-Err "Unsafe install directory detected (unexpected owner or symlink/junction). Remove it manually before continuing."
        return
    }
    Write-Host ""
    Write-Host "=== Option 2: Change / Remove PIN ===" -ForegroundColor White
    if (-not (Test-Installed)) {
        Write-Warn "LanPower Agent is NOT installed yet!"
        Write-Info "Please run Option 1 (Full Setup) first before setting a PIN."
        if (Confirm-Yes "Would you like to run Option 1 (Full Setup) now?") {
            Option-Install
        }
        return
    }

    $cfg = Get-Config
    if ($null -eq $cfg) {
        Write-Err "Config file is corrupted. Please run Option 1 to repair."
        return
    }

    $currentLabel = if ([string]::IsNullOrEmpty($cfg.token)) { "Disabled (None)" } else { "Enabled" }
    Write-Info "Current PIN status: $currentLabel"

    $newToken = ""
    while ($true) {
        $newToken = Read-Host "Enter new PIN (leave empty and press Enter to REMOVE PIN)"
        if ([string]::IsNullOrEmpty($newToken)) {
            break
        }
        if (-not ($newToken -cmatch '^[\x21-\x7E]{4,64}$')) {
            Write-Err "PIN must be 4-64 printable ASCII characters with no spaces."
            continue
        }
        if ($newToken.Length -lt 6) {
            Write-Warn "PIN is shorter than 6 characters. At least 6 characters is recommended for stronger brute-force protection."
        }
        break
    }
    if ([string]::IsNullOrEmpty($newToken)) {
        Write-Warn "NO PIN will be set: anyone on your network who can reach the agent port can power off or reboot this machine."
    }

    Save-Config ([ordered]@{
            port      = [int]$cfg.port
            token     = $newToken
            interface = [string]$cfg.interface
            mac       = [string]$cfg.mac
        })

    Stop-AgentCleanly
    Start-ScheduledTask -TaskName $TaskName

    if (Wait-AgentReady -port ([int]$cfg.port)) {
        if ([string]::IsNullOrEmpty($newToken)) {
            Write-Ok "PIN removed (authentication disabled) and agent restarted."
        }
        else {
            Write-Ok "PIN updated and agent restarted."
        }
    }
    else {
        Write-Err "Config updated, but agent failed to restart. Check '$LogFile'."
    }
}

function Option-Status {
    Write-Host ""
    Write-Host "=== LanPower Status ===" -ForegroundColor White
    if (-not (Test-Installed)) {
        Write-Warn "Status: Not installed."
        return
    }

    $cfg = Get-Config
    if ($null -eq $cfg) {
        Write-Err "Config file is corrupted. Please run Option 1 to repair."
        return
    }

    $running = if (Test-AgentRunning) { "Running" } else { "Stopped" }
    $pinLabel = if (-not [string]::IsNullOrEmpty($cfg.token)) { "Enabled" } else { "Disabled" }
    $nic = Get-NetAdapter -Name $cfg.interface -ErrorAction SilentlyContinue
    $ip = if ($nic) { Get-AdapterIPv4 $nic.ifIndex } else { "unknown" }
    $typeLbl = if ($nic) { (Get-AdapterInfo $nic).Label } else { "Unknown" }
    $fastBoot = if (Test-FastStartupActive) { "Active (may block WoL after Start Menu shutdown)" } else { "Disabled (WoL friendly)" }

    $wolMagic = "Unknown"
    if ($nic) {
        try {
            $pm = Get-NetAdapterPowerManagement -Name $nic.Name -ErrorAction SilentlyContinue
            if ($pm) { $wolMagic = "$($pm.WakeOnMagicPacket) (Pattern: $($pm.WakeOnPattern))" }
        }
        catch {}
    }

    Write-Host " Agent Status : $running"
    Write-Host " Hostname     : $env:COMPUTERNAME"
    Write-Host " Adapter      : $($cfg.interface) [$typeLbl] ($ip)"
    Write-Host " MAC Address  : $($cfg.mac)"
    Write-Host " API Port     : $($cfg.port)"
    Write-Host " PIN Auth     : $pinLabel"
    Write-Host " Wake-on-LAN  : $wolMagic"
    Write-Host " Fast Startup : $fastBoot"
}

function Option-Uninstall {
    if (-not (Test-SafeInstallDir $InstallDir)) {
        Write-Err "Unsafe install directory detected (unexpected owner or symlink/junction). Remove it manually before continuing."
        return
    }
    Write-Host ""
    Write-Host "=== Uninstall LanPower ===" -ForegroundColor White
    if (-not (Test-Installed) -and -not (Test-Path $InstallDir)) {
        Write-Warn "LanPower is not installed."
        return
    }
    if (-not (Confirm-No "Are you sure you want to completely remove LanPower Agent, Task, and Firewall rule?")) {
        Write-Info "Uninstall cancelled."
        return
    }

    Stop-AgentCleanly
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
    Remove-Item -Path $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Ok "LanPower has been completely uninstalled."
}

function Start-MainMenu {
    if (-not (Test-IsAdmin)) {
        Write-Err "Administrator privileges are required."
        Write-Info "Right-click PowerShell -> 'Run as Administrator', then run this script again."
        return
    }

    while ($true) {
        Write-Host ""
        Write-Host "========================================" -ForegroundColor White
        Write-Host "      LanPower Windows Agent Manager    " -ForegroundColor White
        Write-Host "========================================" -ForegroundColor White
        Write-Host "  1) Install / Setup Everything"
        Write-Host "  2) Change or Remove PIN"
        Write-Host "  3) Show Status & Connection Info"
        Write-Host "  4) Uninstall"
        Write-Host "  0) Exit"
        Write-Host "----------------------------------------" -ForegroundColor DarkGray
        Write-Host "  Made with <3 by Mohammask" -ForegroundColor DarkGray
        Write-Host "  github.com/Mohammask/LanPower" -ForegroundColor DarkGray
        Write-Host ""
        $choice = Read-Host "Select an option [0-4]"
        switch ($choice) {
            "1" { Option-Install }
            "2" { Option-ChangePin }
            "3" { Option-Status }
            "4" { Option-Uninstall }
            "0" { Write-Host "Bye!"; return }
            default { Write-Warn "Invalid option. Please choose 0-4." }
        }
    }
}

Start-MainMenu
