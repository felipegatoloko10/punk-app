param([string]$HostAddress = '', [switch]$NoOpen)
$ErrorActionPreference = 'Stop'
$punkPython = Join-Path $env:LOCALAPPDATA 'hermes\hermes-agent\venv\Scripts\python.exe'
$punkArguments = @((Join-Path $PSScriptRoot 'pair.py'))
if ($HostAddress) { $punkArguments += @('--host', $HostAddress) }
& $punkPython @punkArguments
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível gerar o pareamento.' }
if (!$NoOpen) { Start-Process -FilePath (Join-Path $PSScriptRoot 'runtime\pareamento.html') }
