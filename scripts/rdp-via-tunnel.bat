@echo off
rem Remote Desktop to homepc through the SSH tunnel. Copy to the laptop / work PC and double-click.
rem Needs WireGuard on. Works in both "direct" and "tunnel only" mode.
rem Uses this PC's SSH key (%USERPROFILE%\.ssh\id_ed25519); you type its passphrase, not your Windows password.
set HOST=home.forgenerative.ai
set SSHUSER=tunnel
set PORT=13389

start "SSH tunnel to homepc - keep this window open" ssh -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -N -L %PORT%:localhost:3389 %SSHUSER%@%HOST%
echo Type your SSH key passphrase in the SSH window. Waiting for the tunnel...
powershell -NoProfile -Command "for ($i = 0; $i -lt 120; $i++) { try { (New-Object Net.Sockets.TcpClient('127.0.0.1', %PORT%)).Close(); exit 0 } catch { Start-Sleep 1 } }; exit 1"
if errorlevel 1 (
    echo The tunnel did not open within 2 minutes. Check the SSH window for errors.
    pause
    exit /b 1
)
start "" mstsc /v:localhost:%PORT%
