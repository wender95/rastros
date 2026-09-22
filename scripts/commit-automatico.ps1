# Rede de seguranca do versionamento.
#
# Roda no evento Stop do Claude Code, ou seja, quando um turno termina. Se sobrou
# qualquer alteracao sem commit, ela vira um commit de checkpoint para nao se perder.
#
# Este script NAO decide o que e uma mudanca relevante - ele nao tem como saber.
# Os commits com mensagem descritiva sao feitos deliberadamente durante o trabalho;
# aqui e so a garantia de que nada fica solto na arvore.
#
# Sempre sai com codigo 0: uma falha de versionamento nunca deve travar o trabalho.

$ErrorActionPreference = 'Stop'

function Responder($mensagem) {
    if ($mensagem) { @{ systemMessage = $mensagem } | ConvertTo-Json -Compress }
    exit 0
}

try {
    $repo = Split-Path -Parent $PSScriptRoot
    if (-not (Test-Path (Join-Path $repo '.git'))) { exit 0 }

    $git = 'C:\Program Files\Git\cmd\git.exe'
    if (-not (Test-Path $git)) { $git = 'git' }

    Set-Location $repo

    $pendentes = & $git status --porcelain
    if (-not $pendentes) { exit 0 }   # nada solto: o caso normal, silencioso

    & $git add -A
    $arquivos = @(& $git diff --cached --name-only)
    if ($arquivos.Count -eq 0) { exit 0 }

    # Resume as areas tocadas para a mensagem dizer algo util.
    $areas = $arquivos | ForEach-Object {
        switch -Regex ($_) {
            '^backend/'  { 'backend' ; break }
            '^frontend/' { 'frontend'; break }
            '^docs/'     { 'docs'    ; break }
            '^database/' { 'banco'   ; break }
            default      { 'projeto' }
        }
    } | Sort-Object -Unique

    $plural = if ($arquivos.Count -eq 1) { 'arquivo' } else { 'arquivos' }
    $titulo = "checkpoint: $($arquivos.Count) $plural em $($areas -join ', ')"
    $corpo = "Commit automatico do hook: alteracoes que ficaram fora de um commit nomeado."

    & $git commit -q -m $titulo -m $corpo
    if ($LASTEXITCODE -ne 0) { Responder "Versionamento: git commit falhou (codigo $LASTEXITCODE)." }

    $hash = (& $git rev-parse --short HEAD).Trim()
    Responder "Versionamento: checkpoint $hash com $($arquivos.Count) $plural."
}
catch {
    Responder "Versionamento: o commit automatico falhou ($($_.Exception.Message))."
}
