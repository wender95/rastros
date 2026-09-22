# Sobe o frontend do OS Tracker na porta 5173.
# Uso:  .\iniciar-frontend.ps1
# O backend precisa estar rodando na 8080 (veja iniciar-backend.ps1).

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontend = Join-Path $raiz 'frontend'

$nodeDir = 'C:\Program Files\nodejs'
if (Test-Path $nodeDir) { $env:Path = "$nodeDir;$env:Path" }

Set-Location $frontend

if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Write-Host 'Instalando dependencias do frontend...' -ForegroundColor Yellow
    npm install --no-fund --no-audit
}

Write-Host 'Frontend subindo em http://localhost:5173 (Ctrl+C para parar)' -ForegroundColor Green
npm run dev
