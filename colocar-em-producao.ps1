# Coloca o OS Tracker em producao nesta maquina, num passo so.
#
# Uso (PowerShell ABERTO COMO ADMINISTRADOR):
#   .\colocar-em-producao.ps1
#   .\colocar-em-producao.ps1 -SemImportar      <- nao traz a agenda da planilha
#
# O que faz, em ordem:
#   1. para o sistema se ele estiver rodando por fora (terminal, teste)
#   2. libera a porta 8080 no firewall, para as outras maquinas da rede entrarem
#   3. registra o sistema para subir sozinho com o Windows e ja o inicia
#   4. importa da planilha a agenda de HOJE EM DIANTE (pede a senha do admin)
#   5. mostra os enderecos para a equipe acessar
#
# Pode rodar de novo sem problema: o que ja estiver feito e mantido.

param(
    [switch] $SemImportar,
    [string] $APartirDe = (Get-Date -Format 'yyyy-MM-dd'),
    [int]    $Porta = 8080
)

$ErrorActionPreference = 'Stop'
$raiz = $PSScriptRoot

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    throw 'Abra o PowerShell como administrador (botao direito > Executar como administrador) e rode de novo.'
}

$jar = Join-Path $raiz 'backend\target\os-tracker-api-1.0.0.jar'
if (-not (Test-Path $jar)) { throw "Pacote nao encontrado. Rode antes: .\gerar-pacote.ps1" }

Write-Host "`n1/5  Parando o sistema que estiver rodando por fora..." -ForegroundColor Cyan
Stop-ScheduledTask -TaskName 'OS Tracker' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*os-tracker-api*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "     parado (processo $($_.ProcessId))" }
Start-Sleep -Seconds 2

Write-Host "2/5  Liberando a porta $Porta no firewall..." -ForegroundColor Cyan
if (-not (Get-NetFirewallRule -DisplayName 'OS Tracker' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'OS Tracker' -Direction Inbound -Protocol TCP -LocalPort $Porta -Action Allow | Out-Null
    Write-Host '     regra criada'
} else {
    Write-Host '     regra ja existia'
}

Write-Host '3/5  Registrando para subir com o Windows e iniciando...' -ForegroundColor Cyan
& (Join-Path $raiz 'instalar-servico.ps1')

$ok = $false
foreach ($i in 1..90) {
    try {
        if ((Invoke-RestMethod "http://localhost:$Porta/api/health" -TimeoutSec 2).status -eq 'ok') { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $ok) { throw "O sistema nao respondeu em 90s. Veja o log em backend\logs\ostracker.log" }
Write-Host '     no ar'

if (-not $SemImportar) {
    Write-Host "4/5  Importando a agenda da planilha a partir de $APartirDe..." -ForegroundColor Cyan
    $meses = @('Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
               'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro')
    $inicio = [datetime]::ParseExact($APartirDe, 'yyyy-MM-dd', $null)
    $abas = $meses[($inicio.Month - 1)..11]
    & (Join-Path $raiz 'importar-agenda.ps1') -Ano $inicio.Year -Meses $abas -APartirDe $APartirDe -Api "http://localhost:$Porta"
} else {
    Write-Host '4/5  Importacao pulada (-SemImportar).' -ForegroundColor Cyan
}

Write-Host '5/5  Pronto. Enderecos para a equipe:' -ForegroundColor Green
Write-Host "     nesta maquina:   http://localhost:$Porta"
Write-Host "     na rede:         http://$($env:COMPUTERNAME):$Porta"
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host "                      http://$($_.IPAddress):$Porta" }
Write-Host "`nBackups diarios em backend\backups  |  logs em backend\logs\ostracker.log"
