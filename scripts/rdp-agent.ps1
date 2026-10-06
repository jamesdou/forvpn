# WG Switch helper: applies the Remote Desktop access mode chosen in the app.
#   direct - Windows' normal Remote Desktop rules; this script adds nothing.
#   tunnel - one block rule for inbound port 3389 from the network. Connections through the
#            SSH tunnel arrive on loopback, which Windows Firewall does not filter, so they still work.
# Installed by install-rdp-agent.ps1 as a scheduled task running as SYSTEM.
$ErrorActionPreference = "Stop"
$api = "http://10.100.0.1:8080"  # reachable only through the WireGuard tunnel
$group = "WG Switch RDP mode"
$interval = 10

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

while ($true) {
    $err = ""
    try {
        # If the server can't be reached nothing changes: the current mode stays as it is.
        $want = (Invoke-RestMethod "$api/agent" -TimeoutSec 5).rdp_mode
        if ($want -eq "tunnel") {
            if (-not (Test-Sshd)) { Start-Service sshd -ErrorAction SilentlyContinue }
            if (-not (Test-Sshd)) {
                # Never block Remote Desktop when the tunnel can't work - that would lock you out.
                $err = "SSH server is not running, so Remote Desktop stays direct"
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
        $body = @{ applied = (Get-Applied); sshd = (Test-Sshd); error = $err } | ConvertTo-Json -Compress
        Invoke-RestMethod "$api/agent/status" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 5 | Out-Null
    } catch {
        # Server unreachable; report again next round.
    }
    Start-Sleep -Seconds $interval
}
