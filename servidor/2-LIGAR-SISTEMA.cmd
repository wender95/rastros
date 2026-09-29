@echo off
rem Liga o RastrOS de novo (depois de trocar o app.jar). Clique duas vezes.
rem Fica na pasta do sistema (ex.: C:\RastrOS).

rem Sem administrador nao da para ligar a tarefa do sistema: pede a permissao e abre de novo.
net session >nul 2>&1 || (powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs" & exit /b)

cd /d "%~dp0"
echo.
echo Ligando o RastrOS...
schtasks /Run /TN "RastrOS" >nul
if errorlevel 1 (
    echo Nao achei a tarefa "RastrOS". Rode o SERVIDOR-INSTALAR.ps1 uma vez.
    pause
    exit /b 1
)

echo Esperando o sistema responder (na primeira vez depois de atualizar pode levar 1 a 2 minutos)...
powershell -NoProfile -Command "foreach ($i in 1..150) { try { if ((Invoke-RestMethod 'http://localhost:8080/api/health' -TimeoutSec 2).status -eq 'ok') { exit 0 } } catch { }; Start-Sleep -Seconds 1 }; exit 1"
if errorlevel 1 (
    echo.
    echo O sistema NAO respondeu. Veja o log em: %~dp0logs\rastros.log
    echo Para voltar a versao anterior: rode 1-PARAR-SISTEMA.cmd, copie o app-*.jar mais novo
    echo de backups-atualizacao por cima do app.jar, e rode este arquivo de novo.
) else (
    echo.
    echo Pronto: sistema NO AR. Recarregue o empresa.rastros.cloud com Ctrl+F5.
)
echo.
pause
