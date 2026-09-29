@echo off
rem RastrOS no servidor. Quem roda este arquivo e a tarefa "RastrOS",
rem registrada pelo SERVIDOR-INSTALAR.ps1 - nao precisa abrir na mao.
cd /d "%~dp0"

rem Producao: banco novo nasce sem dados ficticios, so com o administrador.
set CARGA_DEMO=false
set LOGGING_FILE_NAME=logs/rastros.log

rem O administrador "agindo como" outra pessoa, sem a senha dela: so para a fase de teste.
rem Para ligar, troque para true e rode:  .\SERVIDOR-INSTALAR.ps1 -Reiniciar
set PERSONIFICACAO=false

rem Previsao do tempo do painel: a cidade da empresa (latitude e longitude).
set CLIMA_CIDADE=Sao Paulo
set CLIMA_LATITUDE=-23.5505
set CLIMA_LONGITUDE=-46.6333

rem Enderecos que podem chamar o sistema pelo navegador: o endereco publico (Cloudflare)
rem (so o da empresa, empresa.rastros.cloud: rastros.cloud e os outros subdominios sao de
rem outras aplicacoes) e a extensao do ERP. Sem o endereco publico aqui, a tela abre em branco.
set CORS_ORIGENS=https://empresa.rastros.cloud,chrome-extension://*,moz-extension://*

"%~dp0java\bin\java.exe" -jar "%~dp0app.jar" %*
