# Registra o RastrOS para subir sozinho junto com o Windows.
#
# Uso (PowerShell ABERTO COMO ADMINISTRADOR):
#   .\instalar-servico.ps1            <- registra e ja inicia
#   .\instalar-servico.ps1 -Remover   <- desfaz o registro
#
# Cria uma tarefa agendada "RastrOS" que roda o iniciar-producao.ps1 na partida do
# Windows, com a conta do sistema, e reinicia a aplicacao se ela cair (ate 3 vezes, de
# minuto em minuto). Nao precisa baixar nenhum programa extra.
#
# Para outras maquinas da rede abrirem o sistema, libere a porta no firewall
# (tambem como administrador):
#   New-NetFirewallRule -DisplayName 'RastrOS' -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow

param([switch] $Remover)

$ErrorActionPreference = 'Stop'
$nome = 'RastrOS'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Abra o PowerShell como administrador para registrar a tarefa.' }

if ($Remover) {
    Stop-ScheduledTask -TaskName $nome -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $nome -Confirm:$false
    Write-Host "Tarefa '$nome' removida." -ForegroundColor Green
    return
}

$script = Join-Path $PSScriptRoot 'iniciar-producao.ps1'
$acao = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`"" -WorkingDirectory $PSScriptRoot
$gatilho = New-ScheduledTaskTrigger -AtStartup
$conta = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$regras = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask -TaskName $nome -Action $acao -Trigger $gatilho -Principal $conta -Settings $regras -Force | Out-Null
Start-ScheduledTask -TaskName $nome
Write-Host "Tarefa '$nome' registrada e iniciada. O sistema sobe sozinho com o Windows." -ForegroundColor Green
Write-Host 'Enderecos: http://localhost:8080  |  logs em backend\logs\rastros.log'
