@echo off
setlocal
rem ======================================================================
rem  Instalador da Populacao Hibrida para Europa Universalis IV
rem  Copia o mod para a pasta "mod" que o jogo realmente usa. Ele pergunta
rem  ao Windows onde fica "Documentos", porque com OneDrive ela costuma
rem  estar em C:\Users\NOME\OneDrive\Documentos e nao em C:\Users\NOME\Documents.
rem ======================================================================

if not exist "%~dp0populacao_hibrida\descriptor.mod" (
    echo [ERRO] Nao encontrei a pasta populacao_hibrida ao lado deste arquivo.
    echo        Extraia o zip inteiro primeiro e rode o INSTALAR.bat de dentro
    echo        da pasta extraida, e nao de dentro do zip.
    pause
    exit /b 1
)

for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('MyDocuments')"`) do set "DOCS=%%D"
set "EU4DOCS=%DOCS%\Paradox Interactive\Europa Universalis IV"
set "DESTINO=%EU4DOCS%\mod"

if not exist "%EU4DOCS%" (
    echo [ERRO] Nao encontrei a pasta do jogo em:
    echo        %EU4DOCS%
    echo        Abra o EU4 pelo menos uma vez para ele criar essa pasta e
    echo        rode o instalador de novo.
    pause
    exit /b 1
)

if not exist "%DESTINO%" mkdir "%DESTINO%"
rem Remove uma instalacao antiga deste mod para nao sobrar arquivo velho.
if exist "%DESTINO%\populacao_hibrida" rmdir /S /Q "%DESTINO%\populacao_hibrida"
xcopy /E /I /Y /Q "%~dp0populacao_hibrida" "%DESTINO%\populacao_hibrida" >nul
copy /Y "%~dp0populacao_hibrida.mod" "%DESTINO%\populacao_hibrida.mod" >nul

if not exist "%DESTINO%\populacao_hibrida\descriptor.mod" (
    echo [ERRO] A copia falhou. Verifique se o antivirus ou a
    echo        "Protecao contra ransomware" do Windows bloqueou o acesso
    echo        a pasta Documentos.
    pause
    exit /b 1
)

echo.
echo Mod instalado em:
echo   %DESTINO%
echo.
echo Agora abra o launcher do EU4, va em "Playsets", adicione
echo "Populacao Hibrida" a um playset e jogue por ele.
echo.
pause
