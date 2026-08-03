param(
    [switch]$Install
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

Write-Host "=== HistoryDay Android APK build ===" -ForegroundColor Cyan
Write-Host "Project: $ProjectDir"

if (-not (Test-Path ".\settings.gradle.kts") -or -not (Test-Path ".\app\build.gradle.kts")) {
    Fail "Place this script in the project root next to settings.gradle.kts."
}

# Locate Android SDK.
$sdkCandidates = @(
    @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ -and (Test-Path $_) }
)

if ($sdkCandidates.Count -eq 0) {
    Fail "Android SDK was not found. Expected path: $env:LOCALAPPDATA\Android\Sdk"
}

$Sdk = [System.IO.Path]::GetFullPath($sdkCandidates[0])
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$Sdk\platform-tools;$env:Path"

Write-Host "Android SDK: $Sdk" -ForegroundColor Green

if (-not (Test-Path "$Sdk\platforms\android-35\android.jar")) {
    Write-Host ""
    Write-Host "Installed SDK platforms:" -ForegroundColor Yellow
    Get-ChildItem "$Sdk\platforms" -Directory -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Name |
        ForEach-Object { Write-Host "  $_" }

    Fail "Android SDK Platform 35 is missing. Install Android 15 / API 35 in Android Studio SDK Manager."
}

# Locate JDK 17.
$javaHomeCandidates = New-Object System.Collections.Generic.List[string]

if ($env:JAVA_HOME) {
    $javaHomeCandidates.Add($env:JAVA_HOME)
}

$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if ($javaCommand) {
    $detectedJavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    $javaHomeCandidates.Add($detectedJavaHome)
}

Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object { $javaHomeCandidates.Add($_.FullName) }

Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object { $javaHomeCandidates.Add($_.FullName) }

$JavaExe = $null
$javaVersionText = $null

foreach ($candidate in ($javaHomeCandidates | Select-Object -Unique)) {
    $candidateJava = Join-Path $candidate "bin\java.exe"
    if (-not (Test-Path $candidateJava)) {
        continue
    }

    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $candidateJava
    $processInfo.Arguments = "-version"
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo

    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $candidateVersion = ($stdout + [Environment]::NewLine + $stderr).Trim()
    $isJava17 = $candidateVersion.Contains('version "17.') -or $candidateVersion.Contains('version "17"')

    if (($process.ExitCode -eq 0) -and $isJava17) {
        $env:JAVA_HOME = [System.IO.Path]::GetFullPath($candidate)
        $JavaExe = $candidateJava
        $javaVersionText = $candidateVersion
        break
    }
}

if (-not $JavaExe) {
    Fail "JDK 17 was not found. Install Eclipse Temurin JDK 17 or set JAVA_HOME."
}

$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
Write-Host $javaVersionText

# Create local.properties using forward slashes.
$sdkForProperties = $Sdk.Replace("\", "/")
"sdk.dir=$sdkForProperties" | Set-Content ".\local.properties" -Encoding ASCII
Write-Host "Created local.properties" -ForegroundColor Green

# Bootstrap Gradle Wrapper when the repository was freshly cloned.
if (-not (Test-Path ".\gradlew.bat")) {
    $BootstrapRoot = Join-Path $env:LOCALAPPDATA "HistoryDayBuild"
    $GradleHome = Join-Path $BootstrapRoot "gradle-8.9"
    $GradleBat = Join-Path $GradleHome "bin\gradle.bat"
    $GradleZip = Join-Path $BootstrapRoot "gradle-8.9-bin.zip"

    New-Item -ItemType Directory -Force -Path $BootstrapRoot | Out-Null

    if (-not (Test-Path $GradleBat)) {
        Write-Host "Downloading Gradle 8.9..." -ForegroundColor Yellow

        if (Test-Path $GradleZip) {
            Remove-Item $GradleZip -Force
        }

        Invoke-WebRequest `
            -Uri "https://services.gradle.org/distributions/gradle-8.9-bin.zip" `
            -OutFile $GradleZip `
            -UseBasicParsing

        Write-Host "Extracting Gradle..." -ForegroundColor Yellow
        if (Test-Path $GradleHome) {
            Remove-Item $GradleHome -Recurse -Force
        }
        Expand-Archive -Path $GradleZip -DestinationPath $BootstrapRoot -Force
    }

    if (-not (Test-Path $GradleBat)) {
        Fail "Gradle 8.9 could not be downloaded or extracted."
    }

    Write-Host "Creating Gradle Wrapper..." -ForegroundColor Yellow
    & $GradleBat wrapper --gradle-version 8.9 --distribution-type bin
    if ($LASTEXITCODE -ne 0) {
        Fail "Gradle Wrapper creation failed."
    }
}

Write-Host ""
Write-Host "Building debug APK..." -ForegroundColor Cyan
& ".\gradlew.bat" clean assembleDebug --stacktrace

if ($LASTEXITCODE -ne 0) {
    Fail "Gradle build failed. Copy the complete error output from the console."
}

$Apk = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $Apk)) {
    Fail "Build completed but APK was not found at: $Apk"
}

Write-Host ""
Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "APK: $Apk" -ForegroundColor Green

if ($Install) {
    $Adb = Join-Path $Sdk "platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) {
        Fail "adb.exe was not found in Android SDK."
    }

    Write-Host ""
    Write-Host "Connected devices:" -ForegroundColor Cyan
    & $Adb devices

    Write-Host "Installing APK..." -ForegroundColor Cyan
    & $Adb install -r $Apk

    if ($LASTEXITCODE -ne 0) {
        Fail "APK was built but installation failed."
    }

    Write-Host "HistoryDay was installed on the phone." -ForegroundColor Green
}
