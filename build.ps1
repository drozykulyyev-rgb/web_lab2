$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force 'build/classes' | Out-Null
    $sources = Get-ChildItem -LiteralPath 'src' -Filter '*.java' | ForEach-Object { $_.FullName }
    & javac --release 17 -encoding UTF-8 -d build/classes @sources
    if ($LASTEXITCODE -ne 0) {
        throw 'Compilation failed'
    }
    $jarCommand = Get-Command jar -ErrorAction SilentlyContinue
    if ($jarCommand) {
        $jarPath = $jarCommand.Source
    } else {
        $javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
        if ($javaSettings -notmatch '(?m)^\s*java\.home\s*=\s*(.+)$') {
            throw 'Cannot locate JDK home'
        }
        $jarPath = Join-Path $Matches[1].Trim() 'bin/jar.exe'
        if (!(Test-Path -LiteralPath $jarPath)) {
            throw 'jar.exe not found; install JDK 17+'
        }
    }
    & $jarPath --create --file build/lab2.jar --main-class Main -C build/classes .
    if ($LASTEXITCODE -ne 0) {
        throw 'JAR creation failed'
    }
    Write-Output 'Built build/lab2.jar (Java 17+)'
} finally {
    Pop-Location
}
