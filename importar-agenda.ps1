# Importa o cronograma do Google Sheets para a agenda do OS Tracker.
#
# Uso:
#   .\importar-agenda.ps1 -Meses Novembro,Dezembro
#   .\importar-agenda.ps1 -Ano 2027 -Meses Janeiro,Fevereiro
#   .\importar-agenda.ps1 -CompletarDiasAusentes
#   .\importar-agenda.ps1 -Meses Setembro,Outubro -APartirDe 2026-09-21   <- so dali em diante
#
# A agenda vive no OS Tracker; a planilha e historico. Por isso a importacao so grava em
# periodo vazio: se o sistema ja tem agenda em algum dia que a aba cobre, ela e recusada
# e nada e gravado. Nao ha como duplicar nem desfazer o que foi remanejado aqui.
#
# -CompletarDiasAusentes repara uma base importada pela versao antiga do importador, que
# pulava a semana de um dia so no fim de cada aba ("DIA 31/08"). Grava apenas os dias em
# que o sistema nao tem nenhum agendamento em nenhuma coluna, e lista as diferencas de
# score nos demais dias sem alterar nada.

param(
    # ID da planilha do Google (o trecho entre /d/ e /edit do endereco). Sem ele, vem da
    # variavel PLANILHA_AGENDA_ID ou do arquivo planilha-agenda.id (fora do git).
    [string] $PlanilhaId,
    [int]    $Ano = 2026,
    [string] $Api = 'http://localhost:8080',
    [string] $Usuario = 'admin',
    [string] $Senha,
    [string[]] $Meses = @('Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
                          'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'),
    [switch] $CompletarDiasAusentes,
    # So importa deste dia em diante (aaaa-mm-dd). Vazio = a aba inteira.
    [string] $APartirDe
)

$ErrorActionPreference = 'Stop'

if (-not $PlanilhaId) { $PlanilhaId = $env:PLANILHA_AGENDA_ID }
$arquivoId = Join-Path $PSScriptRoot 'planilha-agenda.id'
if (-not $PlanilhaId -and (Test-Path $arquivoId)) { $PlanilhaId = (Get-Content $arquivoId -Raw).Trim() }
if (-not $PlanilhaId) {
    throw 'Informe a planilha: -PlanilhaId <id>, a variavel PLANILHA_AGENDA_ID ou o arquivo planilha-agenda.id ao lado deste script.'
}
$utf8 = [System.Text.UTF8Encoding]::new($false)
$temp = Join-Path $env:TEMP "ostracker-agenda"
New-Item -ItemType Directory -Force -Path $temp | Out-Null

if (-not $Senha) {
    $segura = Read-Host "Senha do usuario $Usuario" -AsSecureString
    $Senha = [System.Net.NetworkCredential]::new('', $segura).Password
}

$login = @{ usuario = $Usuario; senha = $Senha } | ConvertTo-Json
$token = (Invoke-RestMethod -Uri "$Api/api/auth/login" -Method Post -ContentType 'application/json' -Body $login).token
$cabecalho = @{ Authorization = "Bearer $token" }
$rota = if ($CompletarDiasAusentes) { 'completar' } else { 'importar' }

$totalCriados = 0

foreach ($mes in $Meses) {
    # headers=0 desliga a inferencia de cabecalho do gviz, que funde as primeiras
    # linhas da aba numa so e destruiria o primeiro bloco semanal do mes.
    $url = "https://docs.google.com/spreadsheets/d/$PlanilhaId/gviz/tq?tqx=out:csv&headers=0&sheet=" +
           [uri]::EscapeDataString($mes)
    $arquivoCsv = Join-Path $temp "$mes.csv"
    Invoke-WebRequest -Uri $url -OutFile $arquivoCsv -UseBasicParsing

    $csv = [System.IO.File]::ReadAllText($arquivoCsv, $utf8)
    $corpo = Join-Path $temp "_corpo.json"
    $pedido = @{ aba = $mes; ano = $Ano; csv = $csv }
    if ($APartirDe) { $pedido.aPartirDe = $APartirDe }
    [System.IO.File]::WriteAllText($corpo, ($pedido | ConvertTo-Json -Depth 3), $utf8)

    try {
        $r = Invoke-RestMethod -Uri "$Api/api/admin/agenda/$rota" -Method Post -Headers $cabecalho `
            -ContentType 'application/json; charset=utf-8' -InFile $corpo
    } catch {
        $erro = $_.ErrorDetails.Message
        if ($erro) { $erro = ($erro | ConvertFrom-Json).erro } else { $erro = $_.Exception.Message }
        Write-Host ('{0,-10} RECUSADO: {1}' -f $mes, $erro) -ForegroundColor Yellow
        continue
    }

    $totalCriados += $r.criados
    '{0,-10} semanas={1,2}  celulas={2,4}  criados={3,4}' -f $mes, $r.semanas, $r.celulasLidas, $r.criados | Write-Host
    if ($r.diasCompletados.Count -gt 0) {
        Write-Host "           dias completados: $($r.diasCompletados -join ', ')" -ForegroundColor Cyan
    }
    if ($r.colunasNovas.Count -gt 0) { Write-Host "           novos adesivadores: $($r.colunasNovas -join ', ')" -ForegroundColor Cyan }
    foreach ($aviso in $r.avisos) { Write-Host "           AVISO: $aviso" -ForegroundColor Yellow }
}

Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "`nTotal: $totalCriados agendamento(s) criado(s)." -ForegroundColor Green
