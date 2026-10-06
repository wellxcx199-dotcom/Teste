# Mod de desempenho para Europa Universalis IV (esqueleto de estudo)

Esta pasta contém a estrutura mínima de um mod de EU4. Ela não muda nada no jogo
sozinha: serve de ponto de partida para você experimentar com segurança.

## Instalação

Copie o conteúdo da pasta `mod/` (o arquivo `desempenho.mod` e a pasta `desempenho/`)
para `Documentos/Paradox Interactive/Europa Universalis IV/mod/`. Abra o launcher
da Paradox, crie um "playset" com o mod "Desempenho (mod de estudo)" e inicie o jogo.

## Estrutura

- `desempenho.mod`: descritor lido pelo launcher; `path` aponta para a pasta do mod.
- `desempenho/descriptor.mod`: cópia do descritor dentro da pasta (exigida pelo launcher).
- `desempenho/common/defines/zz_desempenho.lua`: onde você sobrescreve constantes do jogo.

Ajuste `supported_version` para a versão que aparece no seu launcher.
