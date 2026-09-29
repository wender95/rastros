# RastrOS no servidor da empresa, publicado na internet pelo Cloudflare Tunnel.
#
# Rode no PowerShell ABERTO COMO ADMINISTRADOR, dentro desta pasta:
#
#   .\SERVIDOR-INSTALAR.ps1                          liga o sistema e o registra para subir
#                                                    sozinho com o Windows
#   .\SERVIDOR-INSTALAR.ps1 -TokenCloudflare <token> idem, e liga o tunel do Cloudflare
#   .\SERVIDOR-INSTALAR.ps1 -Reiniciar               reinicia o sistema (depois de trocar o
#                                                    app.jar ou o SERVIDOR-INICIAR.cmd)
#   .\SERVIDOR-INSTALAR.ps1 -Remover                 tira o sistema da partida do Windows
#                                                    (nao apaga dados)
#
# O passo a passo completo esta em SERVIDOR-LEIA-ME.txt.

param(
    [string] $TokenCloudflare,
    [switch] $Reiniciar,
    [switch] $Remover
)

$ErrorActionPreference = 'Stop'
$nome = 'RastrOS'
$raiz = $PSScriptRoot
$saude = 'http://localhost:8080/api/health'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Abra o PowerShell como administrador (botao direito > Executar como administrador).' }

function Parar-Sistema {
    Stop-ScheduledTask -TaskName $nome -ErrorAction SilentlyContinue
    # A tarefa para o cmd; o Java que ele abriu tambem precisa sair.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$raiz*app.jar*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
}

function Esperar-Sistema {
    Write-Host '   esperando o sistema responder...' -NoNewline
    foreach ($i in 1..120) {
        try {
            if ((Invoke-RestMethod $saude -TimeoutSec 2).status -eq 'ok') { Write-Host ' no ar.' -ForegroundColor Green; return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "O sistema nao respondeu em 2 minutos. Veja o log em $raiz\logs\rastros.log"
}

if ($Remover) {
    Parar-Sistema
    Unregister-ScheduledTask -TaskName $nome -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Tarefa '$nome' removida. Os dados continuam em $raiz\data." -ForegroundColor Green
    Write-Host 'O tunel do Cloudflare, se instalado, sai com:  cloudflared service uninstall'
    return
}

if ($Reiniciar) {
    Write-Host 'Reiniciando o sistema...' -ForegroundColor Cyan
    Parar-Sistema
    Start-ScheduledTask -TaskName $nome
    Esperar-Sistema
    return
}

# ------------------------------------------------------------------ 1. o sistema
foreach ($necessario in 'app.jar', 'java\bin\java.exe', 'SERVIDOR-INICIAR.cmd') {
    if (-not (Test-Path (Join-Path $raiz $necessario))) { throw "Falta $necessario nesta pasta. Use o pacote RastrOS-portatil inteiro." }
}
$bancoNovo = -not ((Test-Path (Join-Path $raiz 'data\rastros.mv.db')) -or (Test-Path (Join-Path $raiz 'data\ostracker.mv.db')))

Write-Host "1/3  Registrando '$nome' para subir com o Windows..." -ForegroundColor Cyan
Parar-Sistema
$acao = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$raiz\SERVIDOR-INICIAR.cmd`"" -WorkingDirectory $raiz
$gatilho = New-ScheduledTaskTrigger -AtStartup
$conta = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$regras = New-ScheduledTaskSettingsSet -StartWhenAvailable -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $nome -Action $acao -Trigger $gatilho -Principal $conta -Settings $regras -Force | Out-Null
Start-ScheduledTask -TaskName $nome

Write-Host '2/3  Ligando o sistema...' -ForegroundColor Cyan
Esperar-Sistema

if ($bancoNovo) {
    $linha = Select-String -Path (Join-Path $raiz 'logs\rastros.log') -Pattern 'Primeiro acesso' -ErrorAction SilentlyContinue |
        Select-Object -Last 1
    Write-Host ''
    Write-Host 'BANCO NOVO: nao havia data\rastros.mv.db nesta pasta.' -ForegroundColor Yellow
    if ($linha) { Write-Host "   $($linha.Line -replace '^.*Primeiro acesso', 'Primeiro acesso')" -ForegroundColor Yellow }
    Write-Host '   Entre em http://localhost:8080 NESTE SERVIDOR, com o usuario admin, e troque a senha' -ForegroundColor Yellow
    Write-Host '   ANTES de ligar o tunel. (Se era para trazer os dados antigos, pare aqui: veja o LEIA-ME.)' -ForegroundColor Yellow
}

# ------------------------------------------------------------------ 2. o tunel
if (-not $TokenCloudflare) {
    Write-Host '3/3  Tunel do Cloudflare: nao pedido (use -TokenCloudflare <token> quando for a hora).' -ForegroundColor Cyan
    Write-Host "`nPronto. Neste servidor: http://localhost:8080" -ForegroundColor Green
    return
}

if ($bancoNovo) {
    throw 'Tunel NAO ligado: o banco acabou de nascer, com a senha provisoria do admin. Troque a senha em http://localhost:8080 e rode de novo com -TokenCloudflare.'
}

Write-Host '3/3  Ligando o tunel do Cloudflare...' -ForegroundColor Cyan
function Achar-Cloudflared {
    $c = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
    if ($c) { return $c }
    foreach ($p in "${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe", "$env:ProgramFiles\cloudflared\cloudflared.exe") {
        if (Test-Path $p) { return $p }
    }
    return $null
}
$cf = Achar-Cloudflared
if (-not $cf -and (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Host '   instalando o cloudflared pelo winget...'
    winget install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements | Out-Host
    $cf = Achar-Cloudflared
}
if (-not $cf) {
    throw 'cloudflared nao encontrado. Instale pelo site oficial do Cloudflare (Downloads do cloudflared para Windows) e rode de novo.'
}

& $cf service install $TokenCloudflare
if ($LASTEXITCODE -ne 0) { throw 'O cloudflared nao aceitou o token. Confira se copiou o token inteiro do painel do Cloudflare.' }

Write-Host ''
Write-Host 'Pronto. O tunel sobe sozinho com o Windows, como o sistema.' -ForegroundColor Green
Write-Host '   Endereco publico: o que foi configurado em Public Hostname no painel do Cloudflare.'
Write-Host '   Neste servidor continua valendo http://localhost:8080'
