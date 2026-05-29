$kotlinc = (Get-Command kotlinc -ErrorAction SilentlyContinue)?.Source
if (-not $kotlinc) {
    Write-Error "kotlinc not found. Install Kotlin: https://kotlinlang.org/docs/command-line.html"
    exit 1
}
$out = "$PSScriptRoot\mock-abrp-server.jar"
Write-Host "Compiling MockAbrpServer.kt..."
& kotlinc "$PSScriptRoot\MockAbrpServer.kt" -include-runtime -d $out
if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed."
    exit 1
}
Write-Host "Starting mock ABRP server..."
java -jar $out
