# Gera o pacote de producao do RastrOS: um unico jar com a API e a tela.
#
# Uso:
#   .\gerar-pacote.ps1
#   .\gerar-pacote.ps1 -PularTestes      <- so em emergencia
#
# Faz o build do React (frontend\dist), roda os testes do backend e empacota tudo em
# backend\target\rastros-api-1.0.0.jar. Com o jar no ar, o sistema inteiro fica em
# http://localhost:8080 - sem o servidor do Vite.

param([switch] $PularTestes)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path

# JDK, Node e Maven: acha onde estiverem (JAVA_HOME, instalacoes comuns ou PATH).
. (Join-Path $raiz 'scripts\ambiente.ps1')
Add-FerramentasAoPath

Write-Host '1/2  Tela (React)...' -ForegroundColor Cyan
Push-Location (Join-Path $raiz 'frontend')
try {
    if (-not (Test-Path 'node_modules')) { npm install --no-fund --no-audit }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'O build da tela falhou.' }
} finally { Pop-Location }

Write-Host '2/2  API + testes + pacote...' -ForegroundColor Cyan
Push-Location (Join-Path $raiz 'backend')
try {
    $argumentos = @('-B', 'clean', 'package')
    if ($PularTestes) { $argumentos += '-DskipTests' }
    mvn @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'O build da API falhou (ou algum teste nao passou).' }
} finally { Pop-Location }

$jar = Join-Path $raiz 'backend\target\rastros-api-1.0.0.jar'
Write-Host "`nPacote pronto: $jar" -ForegroundColor Green
Write-Host 'Para subir: .\iniciar-producao.ps1  (ou registre com .\instalar-servico.ps1)'
