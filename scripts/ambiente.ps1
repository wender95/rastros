# Acha as ferramentas do projeto em qualquer maquina, sem caminho fixo de versao.
# Os scripts da raiz carregam este arquivo com:  . (Join-Path $raiz 'scripts\ambiente.ps1')
#
# Ordem de busca:
#   Java  -> JAVA_HOME; o JDK 21+ mais novo em Program Files (Adoptium, Oracle, Microsoft); PATH
#   Maven -> MAVEN_HOME; uma pasta .tools\apache-maven-* ao lado do projeto; PATH
#   Node  -> Program Files\nodejs; PATH
# O que achar entra no PATH desta sessao (terminais abertos antes de instalar algo nao o
# enxergam, e a tarefa agendada do Windows roda sem o PATH do usuario).

function Get-JdkHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $candidatos = foreach ($base in @('Eclipse Adoptium', 'Java', 'Microsoft')) {
        $pasta = Join-Path $env:ProgramFiles $base
        if (Test-Path $pasta) { Get-ChildItem $pasta -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue }
    }
    $escolhido = $candidatos |
        Where-Object { (Test-Path (Join-Path $_.FullName 'bin\java.exe')) -and ($_.Name -match 'jdk-(\d+)') -and [int]$Matches[1] -ge 21 } |
        Sort-Object {
            # "jdk-21.0.12.101-hotspot" -> 21.0.12.101 (System.Version aceita no maximo 4 partes)
            $partes = @(($_.Name -replace '^jdk-', '' -replace '[^\d.].*$', '').Split('.') | Where-Object { $_ }) + @('0', '0', '0', '0')
            [version](($partes[0..3]) -join '.')
        } -Descending |
        Select-Object -First 1
    if ($escolhido) { return $escolhido.FullName }
    $noPath = Get-Command java -ErrorAction SilentlyContinue
    if ($noPath) { return Split-Path (Split-Path $noPath.Source -Parent) -Parent }
    return $null
}

function Get-JavaExe {
    $jdk = Get-JdkHome
    if (-not $jdk) { throw 'Java 21 nao encontrado. Instale o JDK 21 (ex.: Eclipse Temurin) ou defina JAVA_HOME.' }
    return Join-Path $jdk 'bin\java.exe'
}

function Add-FerramentasAoPath {
    $jdk = Get-JdkHome
    if ($jdk) {
        $env:JAVA_HOME = $jdk
        $env:Path = "$jdk\bin;$env:Path"
    }
    $node = Join-Path $env:ProgramFiles 'nodejs'
    if (Test-Path $node) { $env:Path = "$node;$env:Path" }

    $maven = $null
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME 'bin'))) { $maven = Join-Path $env:MAVEN_HOME 'bin' }
    if (-not $maven) {
        $projeto = Split-Path -Parent $PSScriptRoot
        foreach ($tools in @((Join-Path $projeto '.tools'), (Join-Path (Split-Path -Parent $projeto) '.tools'))) {
            $achado = Get-ChildItem $tools -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | Select-Object -First 1
            if ($achado) { $maven = Join-Path $achado.FullName 'bin'; break }
        }
    }
    if ($maven) { $env:Path = "$maven;$env:Path" }
}
