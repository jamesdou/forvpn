# Turn on the built-in OpenSSH Server, reachable only from your VPN devices.
# Does NOT touch Remote Desktop or its firewall rules, so existing RDP sessions are unaffected.
#
# Run elevated:  powershell -ExecutionPolicy Bypass -File enable-ssh-vpn.ps1
# Undo:          Stop-Service sshd; Set-Service sshd -StartupType Disabled
#                Get-NetFirewallRule -Group "VPN SSH" | Remove-NetFirewallRule

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$vpn = "10.100.0.0/24"
$config = "$env:ProgramData\ssh\sshd_config"

# First start generates host keys and the default config.
Set-Service sshd -StartupType Manual
if (-not (Test-Path $config)) { Start-Service sshd; Stop-Service sshd }

# Max 3 password attempts per connection; log failures. Re-running keeps one copy of each line.
$lines = Get-Content $config | Where-Object { $_ -notmatch '^\s*(MaxAuthTries|LogLevel)\b' }
$lines = @("MaxAuthTries 3", "LogLevel VERBOSE") + $lines
Set-Content -Path $config -Value $lines -Encoding ascii

# Windows ships a rule allowing port 22 from anywhere; replace it with a VPN-only one.
Get-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -ErrorAction SilentlyContinue | Disable-NetFirewallRule
Get-NetFirewallRule -Group "VPN SSH" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "VPN peers - SSH" -Group "VPN SSH" -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort 22 -RemoteAddress $vpn -Profile Any | Out-Null

Restart-Service sshd
$listening = [bool](Get-NetTCPConnection -LocalPort 22 -State Listen -ErrorAction SilentlyContinue)
Write-Host "sshd running: $((Get-Service sshd).Status); listening on 22: $listening"
Write-Host "SSH allowed only from $vpn. Remote Desktop untouched."
