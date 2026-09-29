# Sobe a API do RastrOS na porta 8080.
# Uso:  .\iniciar-backend.ps1

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$backend = Join-Path $raiz 'backend'
$jar = Join-Path $backend 'target\rastros-api-1.0.0.jar'

# JDK e Maven (este so para recompilar): acha onde estiverem.
. (Join-Path $raiz 'scripts\ambiente.ps1')
Add-FerramentasAoPath

if (-not (Test-Path $jar)) {
    Write-Host 'Jar nao encontrado, compilando com Maven...' -ForegroundColor Yellow
    Push-Location $backend
    try { mvn -B -DskipTests package } finally { Pop-Location }
}

Write-Host 'API subindo em http://localhost:8080 (Ctrl+C para parar)' -ForegroundColor Green
Set-Location $backend
java -jar $jar
