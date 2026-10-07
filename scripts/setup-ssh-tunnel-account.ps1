# Make SSH a separate lock from Windows: a dedicated "tunnel" account that can only forward
# Remote Desktop with a device key (no passwords), plus optional terminal access as your own
# account, which needs a device key AND your Windows password.
# Does NOT touch Remote Desktop or its firewall rules; only sshd restarts.
#
# Run elevated:             powershell -ExecutionPolicy Bypass -File setup-ssh-tunnel-account.ps1
# Terminal access:          ... -Shell on | off     (default: keep the current setting)
# Add a device's key:       ... -AddKey "ssh-ed25519 AAAA... laptop"
# Remove a device's key:    ... -RemoveKey "laptop"   (matches the key's comment)

#Requires -RunAsAdministrator
param([string]$AddKey, [string]$RemoveKey, [ValidateSet("on", "off", "keep")][string]$Shell = "keep",
      [string]$ShellUser = $env:USERNAME)
$ErrorActionPreference = "Stop"
$user = "tunnel"
$sshDir = "$env:ProgramData\ssh"
$config = "$sshDir\sshd_config"
$keys = "$sshDir\tunnel_authorized_keys"
$sshd = "$env:WINDIR\System32\OpenSSH\sshd.exe"

# 1. The account: random password nobody knows (logins are key-only), in no groups at all,
#    so Windows won't let it sign in at the keyboard or over Remote Desktop.
if (-not (Get-LocalUser $user -ErrorAction SilentlyContinue)) {
    $pw = ConvertTo-SecureString ([Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))) -AsPlainText -Force
    New-LocalUser $user -Password $pw -PasswordNeverExpires -UserMayNotChangePassword -AccountNeverExpires `
        -Description "SSH tunnel to Remote Desktop only" | Out-Null
}
foreach ($g in Get-LocalGroup) {
    if (Get-LocalGroupMember $g -Member $user -ErrorAction SilentlyContinue) { Remove-LocalGroupMember $g -Member $user }
}
# Hide it from the sign-in screen.
$hide = "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList"
New-Item $hide -Force | Out-Null
Set-ItemProperty $hide -Name $user -Value 0 -Type DWord

# 2. Its key list, readable by sshd, writable only by SYSTEM/Administrators.
if (-not (Test-Path $keys)) { New-Item $keys -ItemType File | Out-Null }
$lines = @(Get-Content $keys | Where-Object { $_.Trim() })
if ($RemoveKey) { $lines = @($lines | Where-Object { -not $_.EndsWith(" $RemoveKey") }) }
if ($AddKey -and ($lines -notcontains $AddKey.Trim())) { $lines += $AddKey.Trim() }
Set-Content $keys -Value $lines -Encoding ascii
icacls $keys /inheritance:r /grant:r "SYSTEM:F" "Administrators:F" "${user}:R" | Out-Null

# 3. sshd settings. Globals go first (a Match block runs to the end of the file). Our Match blocks go
#    before Windows' own "Match Group administrators", because when several Match blocks apply,
#    the first value of each setting wins - that keeps your account on the app-managed key list.
Copy-Item $config "$config.bak" -Force
$current = (Get-Content $config | Where-Object { $_ -match '^\s*AllowUsers\b' } | Select-Object -First 1)
$shellOn = if ($Shell -eq "keep") { [bool]($current -and $current -match "\b$ShellUser\b") } else { $Shell -eq "on" }
$begin, $end = "# BEGIN WG Switch tunnel", "# END WG Switch tunnel"
$body = @(); $skip = $false
foreach ($l in Get-Content $config) {
    if ($l -eq $begin) { $skip = $true; continue }
    if ($l -eq $end) { $skip = $false; continue }
    if (-not $skip -and $l -notmatch '^\s*(MaxAuthTries|LogLevel|PasswordAuthentication|KbdInteractiveAuthentication|AllowUsers|PubkeyAuthentication)\b') { $body += $l }
}
$globals = @("MaxAuthTries 3", "LogLevel VERBOSE", "PubkeyAuthentication yes",
             "PasswordAuthentication no", "KbdInteractiveAuthentication no",
             "AllowUsers $user$(if ($shellOn) { " $ShellUser" })")
$block = @($begin,
    "Match User $user",
    "    AuthorizedKeysFile __PROGRAMDATA__/ssh/tunnel_authorized_keys",
    "    AuthenticationMethods publickey",
    "    AllowTcpForwarding local",
    "    PermitOpen localhost:3389 127.0.0.1:3389",
    "    PermitTTY no",
    "    X11Forwarding no",
    "    AllowAgentForwarding no",
    "    AllowStreamLocalForwarding no",
    "    PermitTunnel no",
    "    GatewayPorts no",
    "    ForceCommand echo This account can only forward Remote Desktop.",
    "Match User $ShellUser",
    "    AuthorizedKeysFile __PROGRAMDATA__/ssh/tunnel_authorized_keys",
    "    AuthenticationMethods publickey,password",
    "    PasswordAuthentication yes",
    $end)
$firstMatch = [array]::FindIndex([string[]]$body, [Predicate[string]] { param($l) $l -match '^\s*Match\b' })
if ($firstMatch -lt 0) { $firstMatch = $body.Count }
$before = if ($firstMatch -gt 0) { $body[0..($firstMatch - 1)] } else { @() }
$after = if ($firstMatch -lt $body.Count) { $body[$firstMatch..($body.Count - 1)] } else { @() }
Set-Content $config -Value ($globals + $before + $block + $after) -Encoding ascii

# 4. Validate before restarting; put the old file back if sshd rejects it.
& $sshd -t 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    $why = & $sshd -t 2>&1
    Copy-Item "$config.bak" $config -Force
    throw "sshd rejected the new config, old one restored: $why"
}
Restart-Service sshd

# Show what sshd will actually enforce for each account. Informational only: sshd's test mode can
# print warnings (e.g. for an account in no groups), which must not stop the script.
foreach ($u in $user, $ShellUser) {
    try {
        $ErrorActionPreference = "Continue"
        $eff = & $sshd -T -C "user=$u,host=check,addr=10.100.0.4" 2>$null |
            Where-Object { $_ -match '^(authenticationmethods|authorizedkeysfile|passwordauthentication|permittty|allowtcpforwarding|forcecommand) ' }
        Write-Host "effective for ${u}: $(if ($eff) { $eff -join ' | ' } else { '(sshd test mode could not evaluate this account)' })"
    } catch {
        Write-Host "effective for ${u}: (check skipped: $_)"
    } finally {
        $ErrorActionPreference = "Stop"
    }
}
Write-Host "terminal access for ${ShellUser}: $(if ($shellOn) { 'on (device key + Windows password)' } else { 'off' })"

$groups = @(Get-LocalGroup | Where-Object { Get-LocalGroupMember $_ -Member $user -ErrorAction SilentlyContinue }).Name
Write-Host "tunnel account groups: $(if ($groups) { $groups -join ', ' } else { 'none' })"
Write-Host "authorized keys: $(@(Get-Content $keys | Where-Object { $_.Trim() }).Count)"
Write-Host "sshd: $((Get-Service sshd).Status). Password logins off; only '$user' may connect, and only to Remote Desktop."
