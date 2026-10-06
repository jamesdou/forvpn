# Let your other VPN devices reach this PC: ping, Remote Desktop and file sharing.
# Every rule only accepts traffic from the VPN subnet, so nothing new is exposed
# to the LAN or the internet. Safe to re-run; it replaces its own rules.
#
# Run in an elevated PowerShell:  powershell -ExecutionPolicy Bypass -File allow-vpn-peers.ps1
# Undo:                            Get-NetFirewallRule -Group "WireGuard peers" | Remove-NetFirewallRule

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$vpn = "10.100.0.0/24"
$group = "WireGuard peers"

Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue | Remove-NetFirewallRule

$rules = @(
    @{ DisplayName = "VPN peers - ping";                 Protocol = "ICMPv4"; IcmpType = 8 },
    @{ DisplayName = "VPN peers - Remote Desktop (TCP)"; Protocol = "TCP"; LocalPort = 3389 },
    @{ DisplayName = "VPN peers - Remote Desktop (UDP)"; Protocol = "UDP"; LocalPort = 3389 },
    @{ DisplayName = "VPN peers - file sharing (SMB)";   Protocol = "TCP"; LocalPort = 445 }
)
foreach ($r in $rules) {
    New-NetFirewallRule @r -Group $group -Direction Inbound -Action Allow -RemoteAddress $vpn -Profile Any | Out-Null
    Write-Host "Added: $($r.DisplayName)"
}

$rdpOff = (Get-ItemProperty "HKLM:\System\CurrentControlSet\Control\Terminal Server").fDenyTSConnections -ne 0
if ($rdpOff) {
    Write-Host "`nRemote Desktop is turned OFF on this PC. To accept connections, enable it in"
    Write-Host "Settings > System > Remote Desktop (Windows Pro/Enterprise only)."
}
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -like "10.100.0.*").IPAddress
Write-Host "`nDone. Other VPN devices can reach this PC at $ip"
