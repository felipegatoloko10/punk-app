param([switch]$Foreground)
$ErrorActionPreference = 'Stop'
$punkPython = Join-Path $env:LOCALAPPDATA 'hermes\hermes-agent\venv\Scripts\python.exe'
$punkRuntime = Join-Path $PSScriptRoot 'runtime'
if (!(Test-Path -LiteralPath $punkPython)) { throw 'Não encontrei o Python da instalação do Hermes.' }
$punkArguments = @('-u', ('"' + (Join-Path $PSScriptRoot 'server.py') + '"'), '--runtime', ('"' + $punkRuntime + '"'))
if ($Foreground) { & $punkPython -u (Join-Path $PSScriptRoot 'server.py') --runtime $punkRuntime; exit $LASTEXITCODE }
$punkListener = Get-NetTCPConnection -LocalPort 18765 -State Listen -ErrorAction SilentlyContinue
if ($punkListener) { Write-Host 'O bridge já está ativo na porta 18765.'; exit 0 }
$punkProcess = Start-Process -FilePath $punkPython -ArgumentList $punkArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $punkRuntime 'bridge.log') -RedirectStandardError (Join-Path $punkRuntime 'bridge-errors.log') -PassThru
$punkProcess.Id | Set-Content -LiteralPath (Join-Path $punkRuntime 'bridge.pid')
Write-Host 'Punk Bridge iniciado. Abra o app e faça o pareamento.'
