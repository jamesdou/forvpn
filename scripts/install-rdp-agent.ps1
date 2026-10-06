# Install the WG Switch Remote Desktop helper on this PC (run once, elevated).
# It starts in whatever mode the app has set - "direct" unless you've changed it - so
# installing changes nothing about how Remote Desktop works today.
#
# Run:        powershell -ExecutionPolicy Bypass -File install-rdp-agent.ps1
# Uninstall:  Unregister-ScheduledTask -TaskName "WG Switch RDP agent" -Confirm:$false
#             Get-NetFirewallRule -Group "WG Switch RDP mode" | Remove-NetFirewallRule

#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$task = "WG Switch RDP agent"
$dir = "$env:ProgramData\WGSwitch"

# Re-installing: stop every running copy so only the new version runs (stopping the task
# alone can leave its PowerShell process behind).
if (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue) { Stop-ScheduledTask -TaskName $task }
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object CommandLine -like "*WGSwitch\rdp-agent.ps1*" |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# The helper runs as SYSTEM, so only SYSTEM and Administrators may change its file.
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item "$PSScriptRoot\rdp-agent.ps1" "$dir\rdp-agent.ps1" -Force
icacls $dir /inheritance:r /grant:r "SYSTEM:(OI)(CI)F" "Administrators:(OI)(CI)F" "Users:(OI)(CI)RX" | Out-Null

# Tunnel-only mode needs SSH available after every reboot.
Set-Service sshd -StartupType Automatic
Start-Service sshd

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$dir\rdp-agent.ps1`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings `
    -User "SYSTEM" -RunLevel Highest -Force | Out-Null
Start-ScheduledTask -TaskName $task

Start-Sleep -Seconds 3
Write-Host "Helper task: $((Get-ScheduledTask -TaskName $task).State)"
Write-Host "SSH server: $((Get-Service sshd).Status), starts automatically"
Write-Host "Block rules present: $([bool](Get-NetFirewallRule -Group 'WG Switch RDP mode' -ErrorAction SilentlyContinue))"
