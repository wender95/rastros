# Restaura um backup do OS Tracker (banco H2).
#
# Uso (com a aplicacao PARADA):
#   .\restaurar-backup.ps1                          <- lista os backups disponiveis
#   .\restaurar-backup.ps1 -Arquivo ostracker-20260921-123000-000-diario.zip
#
# O banco atual NAO e apagado: ele e renomeado para
# backend\data\ostracker.mv.db.antes-de-restaurar-<data>, para voltar atras se preciso.
#
# Usa o H2 que ja vem dentro do jar da aplicacao; nao precisa instalar nada.

param(
    [string] $Arquivo,
    [string] $PastaBackups = (Join-Path $PSScriptRoot 'backend\backups'),
    [string] $PastaBanco = (Join-Path $PSScriptRoot 'backend\data')
)

$ErrorActionPreference = 'Stop'

if (-not $Arquivo) {
    Write-Host "Backups em $PastaBackups (mais recente primeiro):" -ForegroundColor Cyan
    Get-ChildItem $PastaBackups -Filter 'ostracker-*.zip' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { '  {0}  ({1:N0} KB)' -f $_.Name, ($_.Length / 1KB) }
    Write-Host "`nPara restaurar: .\restaurar-backup.ps1 -Arquivo <nome>"
    return
}

$zip = if (Test-Path $Arquivo) { (Resolve-Path $Arquivo).Path } else { Join-Path $PastaBackups $Arquivo }
if (-not (Test-Path $zip)) { throw "Backup nao encontrado: $zip" }

$jar = Get-ChildItem (Join-Path $PSScriptRoot 'backend\target') -Filter 'os-tracker-api-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw 'Jar da aplicacao nao encontrado em backend\target. Rode o iniciar-backend.ps1 uma vez.' }

. (Join-Path $PSScriptRoot 'scripts\ambiente.ps1')
$java = Get-JavaExe

$banco = Join-Path $PastaBanco 'ostracker.mv.db'
if (Test-Path $banco) {
    try {
        [System.IO.File]::Open($banco, 'Open', 'ReadWrite', 'None').Dispose()
    } catch {
        throw 'O banco esta em uso. Pare a aplicacao (feche o backend) antes de restaurar.'
    }
    $guardado = "$banco.antes-de-restaurar-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $banco $guardado
    Write-Host "Banco atual guardado em $guardado"
}

$url = "jdbc:h2:file:$(Join-Path $PastaBanco 'ostracker');MODE=PostgreSQL"
& $java -cp $jar.FullName "-Dloader.main=org.h2.tools.RunScript" `
    org.springframework.boot.loader.launch.PropertiesLauncher `
    -url $url -user sa -script $zip -options COMPRESSION ZIP
if ($LASTEXITCODE -ne 0) { throw 'A restauracao falhou; o banco anterior continua guardado ao lado.' }

Write-Host "Backup restaurado: $(Split-Path $zip -Leaf)" -ForegroundColor Green
Write-Host 'Pode subir a aplicacao de novo.'
