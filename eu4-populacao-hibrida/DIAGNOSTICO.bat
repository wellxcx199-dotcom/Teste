@echo off
setlocal
rem ======================================================================
rem  Diagnostico da Populacao Hibrida
rem  Junta num arquivo de texto onde o EU4 procura mods, se os arquivos do
rem  mod estao no lugar certo e quais mods o launcher mandou o jogo carregar.
rem  Nao altera nada no computador: so le e escreve o relatorio.
rem ======================================================================

set "SAIDA=%~dp0diagnostico_populacao_hibrida.txt"
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('MyDocuments')"`) do set "DOCS=%%D"
set "EU4DOCS=%DOCS%\Paradox Interactive\Europa Universalis IV"

> "%SAIDA%" echo === Diagnostico da Populacao Hibrida ===
>>"%SAIDA%" echo Pasta Documentos do Windows: %DOCS%
>>"%SAIDA%" echo Pasta do EU4: %EU4DOCS%
if exist "%EU4DOCS%" (echo   pasta do EU4 existe: SIM>>"%SAIDA%") else (echo   pasta do EU4 existe: NAO>>"%SAIDA%")

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 1. Arquivos .mod direto na pasta mod ---
dir /b "%EU4DOCS%\mod\*.mod" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 2. Conteudo de populacao_hibrida.mod ---
type "%EU4DOCS%\mod\populacao_hibrida.mod" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 3. Arquivos dentro da pasta do mod ---
dir /s /b "%EU4DOCS%\mod\populacao_hibrida" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 4. Copias do .mod em qualquer subpasta, inclusive lugares errados ---
dir /s /b "%EU4DOCS%\mod\populacao_hibrida.mod" >>"%SAIDA%" 2>&1
dir /s /b "%USERPROFILE%\Documents\Paradox Interactive\Europa Universalis IV\mod\populacao_hibrida.mod" >>"%SAIDA%" 2>&1
dir /s /b "%USERPROFILE%\OneDrive\Documentos\Paradox Interactive\Europa Universalis IV\mod\populacao_hibrida.mod" >>"%SAIDA%" 2>&1
dir /s /b "%USERPROFILE%\OneDrive\Documents\Paradox Interactive\Europa Universalis IV\mod\populacao_hibrida.mod" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 5. dlc_load.json: mods que o launcher mandou carregar ---
type "%EU4DOCS%\dlc_load.json" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 6. Linhas do error.log sobre o mod ---
findstr /i /c:"pop_" /c:"populacao_hibrida" "%EU4DOCS%\logs\error.log" >>"%SAIDA%" 2>&1

>>"%SAIDA%" echo.
>>"%SAIDA%" echo --- 7. Registro de depuracao do mod no game.log ---
findstr /c:"POP |" "%EU4DOCS%\logs\game.log" >>"%SAIDA%" 2>&1

echo Relatorio salvo em:
echo   %SAIDA%
echo Ele vai abrir no Bloco de Notas. Copie o conteudo e envie.
start "" notepad "%SAIDA%"
pause
