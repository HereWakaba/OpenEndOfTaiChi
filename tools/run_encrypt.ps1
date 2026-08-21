# Encrypt build jar: build/libs/reflection-1.0.jar -> build/libs/reflection-enc.jar
# Usage: run after `gradlew build` (.\tools\run_encrypt.ps1), then deploy the enc jar to mods.
# NOTE: keep messages ASCII-only (PowerShell 5.1 reads .ps1 without BOM as ANSI; non-ASCII breaks parsing).
$ErrorActionPreference = "Stop"
$root = "e:\Minecraft\Reflection"
$javac = "C:\Program Files\Java\jdk-17.0.4\bin\javac.exe"
$java = "C:\Program Files\Java\jdk-17.0.4\bin\java.exe"
$src = Join-Path $root "tools\ClassEncryptor.java"
$outDir = Join-Path $root "tools\out"
$inJar = Join-Path $root "build\libs\reflection-1.0.jar"
$outJar = Join-Path $root "build\libs\reflection-enc.jar"

if (-not (Test-Path $inJar)) { Write-Error "build artifact not found: $inJar (run gradlew build first)"; exit 1 }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
& $javac -encoding UTF-8 -d $outDir $src
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp $outDir ClassEncryptor $inJar $outJar
exit $LASTEXITCODE
