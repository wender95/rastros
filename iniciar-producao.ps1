# Sobe o RastrOS em modo producao: API e tela no mesmo endereco, porta 8080.
#
# Uso:  .\iniciar-producao.ps1
#
# Gere o pacote antes com .\gerar-pacote.ps1. Este e o script que o
# instalar-servico.ps1 registra para subir junto com o Windows.
#
# Configuracao por variavel de ambiente (todas opcionais):
#   SERVER_PORT     porta (padrao 8080)
#   DB_URL, DB_USER, DB_PASSWORD, DB_DRIVER   para usar PostgreSQL em vez do H2
#   JWT_SECRET      chave dos logins; sem ela, a instalacao gera a sua em data\jwt.secret
#   BACKUP_PASTA, BACKUP_MANTER, BACKUP_CRON  backup do H2

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$backend = Join-Path $raiz 'backend'
$jar = Join-Path $backend 'target\rastros-api-1.0.0.jar'
if (-not (Test-Path $jar)) { throw "Pacote nao encontrado ($jar). Rode .\gerar-pacote.ps1 antes." }

# Acha o Java 21+ (JAVA_HOME, instalacoes comuns ou PATH) - a tarefa agendada roda sem o PATH do usuario.
. (Join-Path $raiz 'scripts\ambiente.ps1')
$java = Get-JavaExe

# Producao: um banco novo nasce sem dados ficticios, so com um administrador.
if (-not $env:CARGA_DEMO) { $env:CARGA_DEMO = 'false' }
if (-not $env:LOGGING_FILE_NAME) { $env:LOGGING_FILE_NAME = 'logs/rastros.log' }

# O banco (data\) e os backups (backups\) ficam relativos a pasta backend.
Set-Location $backend
& $java -jar $jar
