# Start the Android Auto Desktop Head Unit
# Prerequisites: phone connected via USB, Android Auto developer mode enabled,
#                "Start head unit server" tapped in Android Auto settings

$dhu = "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"

if (-not (Test-Path $dhu)) {
    Write-Error "DHU not found at: $dhu"
    Write-Host "Install via: Android Studio → SDK Manager → SDK Tools → Android Auto Desktop Head Unit Emulator"
    exit 1
}

Write-Host "Forwarding ADB port..."
adb forward tcp:5277 tcp:5277

Write-Host "Launching DHU..."
& $dhu
