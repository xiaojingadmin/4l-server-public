# Compile-check helper used while several workers port modules in parallel.
#
# Running Maven directly would make concurrent builds clash on target/, so this
# compiles every source file into a separate output directory (build-verify)
# using the dependency classpath that Maven exported to cp.txt.
#
#   powershell -NoProfile -File tools\verify-compile.ps1
#   powershell -NoProfile -File tools\verify-compile.ps1 -Out build-verify-agent2
param(
    [string]$Out = 'build-verify'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root $Out
$cpFile = Join-Path $root 'cp.txt'

if (-not (Test-Path $cpFile)) {
    throw "Missing $cpFile - run: mvn -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt"
}

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) { throw 'No Java sources found' }

& javac -encoding UTF-8 -nowarn -d $outDir -cp (Get-Content $cpFile -Raw).Trim() $sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed (exit $LASTEXITCODE)" }
Write-Output "OK: compiled $($sources.Count) source files into $Out"
