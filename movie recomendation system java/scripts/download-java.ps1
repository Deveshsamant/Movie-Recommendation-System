# Downloads a private copy of Java for START-Windows.bat, into the runtime folder.
# It comes from Eclipse Temurin (adoptium.net), needs no admin rights and changes nothing
# else on the computer. The download's SHA-256 checksum is verified before it is used.

param([Parameter(Mandatory = $true)][string]$Destination)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # the progress display makes large downloads far slower
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$assets = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
$package = (Invoke-RestMethod -Uri $assets -TimeoutSec 60)[0].binary.package
Write-Host ("  Downloading {0} ({1} MB)..." -f $package.name, [math]::Round($package.size / 1MB))

$work = Join-Path ([IO.Path]::GetTempPath()) ('movie-recommender-java-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $zip = Join-Path $work 'java.zip'
    Invoke-WebRequest -Uri $package.link -OutFile $zip -UseBasicParsing -TimeoutSec 900

    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    if ($actual -ine $package.checksum) {
        throw "The download failed its checksum test, so it was not used."
    }
    Write-Host '  Download verified. Unpacking...'

    $unpacked = Join-Path $work 'unpacked'
    Expand-Archive -LiteralPath $zip -DestinationPath $unpacked
    $java = Get-ChildItem -LiteralPath $unpacked -Directory | Select-Object -First 1
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    Move-Item -LiteralPath $java.FullName -Destination $Destination
    Write-Host '  Java is ready.'
}
finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
