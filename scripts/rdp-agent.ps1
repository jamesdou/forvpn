# WG Switch helper: applies what the app chose for this PC.
#   Remote Desktop access
#     direct - Windows' normal Remote Desktop rules; this script adds nothing.
#     tunnel - one block rule for inbound port 3389 from the network. Connections through the
#              SSH tunnel arrive on loopback, which Windows Firewall does not filter, so they still work.
#   SSH server on/off, and which device keys may use the "tunnel" account.
# Installed by install-rdp-agent.ps1 as a scheduled task running as SYSTEM.
$ErrorActionPreference = "Stop"
$api = "http://10.100.0.1:8080"  # reachable only through the WireGuard tunnel
$group = "WG Switch RDP mode"
$interval = 10
$keysFile = "$env:ProgramData\ssh\tunnel_authorized_keys"
# Plain public keys only - never SSH options like command= or permitopen=, even if the server sent them.
$keyPattern = '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/]+={0,2} [A-Za-z0-9._-]{1,32}$'

function Get-Applied {
    if (Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue) { "tunnel" } else { "direct" }
}

function Set-Direct {
    Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}

function Set-Tunnel {
    foreach ($proto in "TCP", "UDP") {
        New-NetFirewallRule -DisplayName "WG Switch - block Remote Desktop $proto (tunnel only)" -Group $group `
            -Direction Inbound -Action Block -Protocol $proto -LocalPort 3389 -Profile Any | Out-Null
    }
}

function Test-Sshd {
    $s = Get-Service sshd -ErrorAction SilentlyContinue
    return [bool]($s -and $s.Status -eq "Running")
}

function Get-Keys {
    if (-not (Test-Path $keysFile)) { return @() }
    # ReadAllLines gives plain strings; Get-Content's carry extra properties that ConvertTo-Json
    # would send as objects, making the server think there are no keys.
    return @([IO.File]::ReadAllLines($keysFile) | Where-Object { $_.Trim() })
}

function Sync-Keys($wanted) {
    $clean = @($wanted | Where-Object { $_ -match $keyPattern })
    if ($clean.Count -ne @($wanted).Count) { throw "server sent a key line that isn't a plain public key; keys left unchanged" }
    if ((@(Get-Keys) -join "`n") -ne ($clean -join "`n")) {
        # Rewriting in place keeps the file's locked-down permissions. sshd reads it on every login.
        [IO.File]::WriteAllLines($keysFile, [string[]]$clean)
    }
}

function Set-Ssh($on) {
    $s = Get-Service sshd -ErrorAction SilentlyContinue
    if (-not $s) { throw "OpenSSH Server is not installed" }
    if ($on) {
        if ($s.StartType -ne "Automatic") { Set-Service sshd -StartupType Automatic }
        if ($s.Status -ne "Running") { Start-Service sshd }
    } else {
        if ($s.Status -ne "Stopped") { Stop-Service sshd -Force }
        if ($s.StartType -ne "Manual") { Set-Service sshd -StartupType Manual }
    }
}

while ($true) {
    $err = ""
    try {
        # If the server can't be reached nothing changes: everything stays as it is.
        $cfg = Invoke-RestMethod "$api/agent" -TimeoutSec 5
        $want = $cfg.rdp_mode
        if ($null -ne $cfg.keys) { Sync-Keys @($cfg.keys) }  # null = server hasn't learned our keys yet
        # Tunnel only always needs SSH, whatever the on/off setting says.
        Set-Ssh ($want -eq "tunnel" -or $cfg.ssh_enabled -ne $false)
        if ($want -eq "tunnel") {
            if (-not (Test-Sshd) -or (Get-Keys).Count -eq 0) {
                # Never block Remote Desktop when the tunnel can't work - that would lock you out.
                $err = "SSH isn't running or has no keys, so Remote Desktop stays direct"
                Set-Direct
            } elseif ((Get-Applied) -ne "tunnel") {
                Set-Tunnel
            }
        } elseif ($want -eq "direct" -and (Get-Applied) -ne "direct") {
            Set-Direct
        }
    } catch {
        $err = $_.Exception.Message
    }
    try {
        $body = @{ applied = (Get-Applied); sshd = (Test-Sshd); error = $err; keys = @(Get-Keys) } | ConvertTo-Json -Compress
        Invoke-RestMethod "$api/agent/status" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 5 | Out-Null
    } catch {
        # Server unreachable; report again next round.
    }
    Start-Sleep -Seconds $interval
}
