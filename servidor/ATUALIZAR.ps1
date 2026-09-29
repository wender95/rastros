# Atualiza o RastrOS no servidor com o app.jar que esta NESTA pasta.
#
# Rode no PowerShell ABERTO COMO ADMINISTRADOR, dentro desta pasta:
#
#   .\ATUALIZAR.ps1
#
# Se o sistema nao estiver em C:\RastrOS, diga onde esta:
#
#   .\ATUALIZAR.ps1 -Destino D:\outra\pasta\RastrOS
#
# O que ele faz: para o sistema, guarda uma copia do banco e do app.jar antigo (em
# backups-atualizacao, dentro da pasta do sistema), troca o app.jar e liga de novo. Se o
# sistema nao voltar, ele poe o app.jar antigo de volta sozinho.

param(
    [string] $Destino = 'C:\RastrOS'
)

$ErrorActionPreference = 'Stop'
$nome = 'RastrOS'
$saude = 'http://localhost:8080/api/health'
$novo = Join-Path $PSScriptRoot 'app.jar'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Abra o PowerShell como administrador (botao direito > Executar como administrador).' }
if (-not (Test-Path $novo)) { throw "Nao achei o app.jar novo nesta pasta ($PSScriptRoot)." }
if (-not (Test-Path (Join-Path $Destino 'app.jar'))) {
    throw "Nao achei o sistema em $Destino. Rode de novo com -Destino apontando para a pasta do sistema."
}
if ((Resolve-Path $PSScriptRoot).Path -eq (Resolve-Path $Destino).Path) {
    throw 'Extraia o pacote de atualizacao em outra pasta (ex.: Downloads), nao dentro da pasta do sistema.'
}

function Parar-Sistema {
    Stop-ScheduledTask -TaskName $nome -ErrorAction SilentlyContinue
    # A tarefa para o cmd; o Java que ele abriu tambem precisa sair.
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$Destino*app.jar*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 3
}

function Copiar-Com-Paciencia([string] $de, [string] $para) {
    # O Windows as vezes demora uns segundos para soltar o arquivo depois que o Java sai.
    foreach ($i in 1..15) {
        try { Copy-Item $de $para -Force; return } catch { Start-Sleep -Seconds 1 }
    }
    throw "Nao consegui gravar $para (arquivo em uso). Veja se ainda ha um java.exe rodando e tente de novo."
}

function Sistema-No-Ar {
    foreach ($i in 1..150) {
        try { if ((Invoke-RestMethod $saude -TimeoutSec 2).status -eq 'ok') { return $true } } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

$quando = Get-Date -Format 'yyyyMMdd-HHmm'
$copias = Join-Path $Destino 'backups-atualizacao'
New-Item -ItemType Directory -Force $copias | Out-Null

Write-Host '1/4  Parando o sistema...' -ForegroundColor Cyan
Parar-Sistema

Write-Host '2/4  Guardando uma copia do banco e do app.jar atual...' -ForegroundColor Cyan
$banco = @('data\rastros.mv.db', 'data\ostracker.mv.db') | ForEach-Object { Join-Path $Destino $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($banco) { Copiar-Com-Paciencia $banco (Join-Path $copias "rastros-$quando.mv.db") }
$anterior = Join-Path $copias "app-$quando.jar"
Copiar-Com-Paciencia (Join-Path $Destino 'app.jar') $anterior
Write-Host "     copias em $copias" -ForegroundColor DarkGray

Write-Host '3/4  Trocando o app.jar...' -ForegroundColor Cyan
Copiar-Com-Paciencia $novo (Join-Path $Destino 'app.jar')

Write-Host '4/4  Ligando o sistema (a primeira partida pode levar 1 ou 2 minutos)...' -ForegroundColor Cyan
Start-ScheduledTask -TaskName $nome
if (Sistema-No-Ar) {
    Write-Host ''
    Write-Host 'Pronto: sistema atualizado e no ar. Recarregue a pagina com Ctrl+F5.' -ForegroundColor Green
    return
}

# Nao voltou: poe a versao anterior de volta para ninguem ficar sem sistema.
Write-Host 'O sistema novo nao respondeu. Voltando a versao anterior...' -ForegroundColor Yellow
Parar-Sistema
Copiar-Com-Paciencia $anterior (Join-Path $Destino 'app.jar')
# O banco volta junto: a versao nova pode ter comecado a atualiza-lo. Ninguem usou o
# sistema nesse meio tempo (ele nao chegou a subir), entao nada se perde.
$copiaDoBanco = Join-Path $copias "rastros-$quando.mv.db"
if (Test-Path $copiaDoBanco) { Copiar-Com-Paciencia $copiaDoBanco $banco }
Start-ScheduledTask -TaskName $nome
if (Sistema-No-Ar) {
    Write-Host "A versao anterior voltou e esta no ar. Mande o log para analise: $Destino\logs\rastros.log" -ForegroundColor Yellow
} else {
    Write-Host "O sistema nao voltou. Veja o log em $Destino\logs\rastros.log" -ForegroundColor Red
}
