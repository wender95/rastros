@echo off
rem Para o RastrOS para trocar o app.jar. Clique duas vezes.
rem Fica na pasta do sistema (ex.: C:\RastrOS).

rem Sem administrador nao da para parar o sistema: pede a permissao e abre de novo.
net session >nul 2>&1 || (powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs" & exit /b)

cd /d "%~dp0"
echo.
echo Parando o RastrOS...
schtasks /End /TN "RastrOS" >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name = 'java.exe'\" | Where-Object { $_.CommandLine -like '*app.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
timeout /t 3 /nobreak >nul

rem Uma copia do banco antes de trocar a versao, por garantia.
if not exist "%~dp0backups-atualizacao" mkdir "%~dp0backups-atualizacao"
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set QUANDO=%%d
rem O banco se chama rastros.mv.db; antes da troca de nome era ostracker.mv.db (o sistema
rem renomeia sozinho na primeira partida). Guarda uma copia do que existir.
if exist "%~dp0data\rastros.mv.db" copy /y "%~dp0data\rastros.mv.db" "%~dp0backups-atualizacao\rastros-%QUANDO%.mv.db" >nul
if exist "%~dp0data\ostracker.mv.db" copy /y "%~dp0data\ostracker.mv.db" "%~dp0backups-atualizacao\ostracker-%QUANDO%.mv.db" >nul
if exist "%~dp0app.jar" copy /y "%~dp0app.jar" "%~dp0backups-atualizacao\app-%QUANDO%.jar" >nul

echo.
echo Pronto: sistema PARADO.
echo Copia do banco e do app.jar antigo em: %~dp0backups-atualizacao
echo.
echo Agora substitua o app.jar desta pasta pelo novo e depois
echo clique duas vezes em 2-LIGAR-SISTEMA.cmd
echo.
pause
