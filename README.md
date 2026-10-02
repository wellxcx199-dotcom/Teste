# Clima Realista (mod Fabric para Minecraft 1.21.1)

Simulação climática para o Minecraft: temperatura, pressão, vento, umidade, nuvens e
precipitação são calculados numa grade que cobre a região ao redor do jogador. O clima
simulado comanda a chuva e as trovoadas do jogo e, com o mod também no cliente, cada
jogador vê o tempo do lugar onde está, com nuvens 3D e neblina.

![Tempestade vista de baixo](docs/tempestade.png)

## Requisitos

| Item | Versão |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16 ou mais novo (testado com 0.19.5) |
| Fabric API | 0.116.17+1.21.1 |
| Java | 21 |

A 1.21.1 foi escolhida por ser a versão da linha 1.21 com mais mods e ferramentas
disponíveis (Fabric e NeoForge), o que facilita usar o mod junto com outros.

## Compilar e testar

```bash
./gradlew build        # compila e roda os testes; o .jar fica em build/libs/
./gradlew test         # só os testes do motor climático
./gradlew demo demo2   # simulações de demonstração no terminal, sem abrir o jogo
./gradlew runClient    # abre o Minecraft com o mod (ambiente de desenvolvimento)
./gradlew runServer    # servidor dedicado com o mod (aceite a EULA em run/eula.txt)
```

Para instalar num Minecraft normal, copie `build/libs/clima-realista-<versão>.jar` (o que
**não** termina em `-sources`) e o Fabric API para a pasta `mods/`.

## Como usar

O mod funciona sozinho. O comando `/clima` mostra o tempo onde você está:

![Saída do comando /clima](docs/comando-clima.png)

No servidor, o clima vanilla (chuva e trovoada, que afetam plantações, mobs e raios)
segue a **maioria** dos jogadores, porque no Minecraft a chuva é uma só para o mundo
todo. Para controlar o tempo manualmente com `/weather`, desligue o ciclo:
`/gamerule doWeatherCycle false`. Com a regra desligada, o mod não mexe no clima vanilla.

Clientes **sem** o mod podem entrar num servidor que o tem: eles veem só o clima global.
Clientes **com** o mod recebem o clima local, o que permite que um jogador veja chuva
enquanto outro, longe dali, vê céu limpo.

## Organização do código

```
src/main/java/br/climate/core/   Motor climático em Java puro, sem dependência do Minecraft
  ClimateGrid      a simulação (temperatura, advecção, pressão, vento, umidade, nuvens)
  Physics          fórmulas: Magnus, ponto de orvalho, pressão barométrica
  ClimateConfig    parâmetros ajustáveis (escala do planeta, duração do ano, limiares)
  TerrainSource    interface que o motor usa para "ver" o relevo
src/main/java/br/climate/mod/    Integração com o servidor
  ClimateMod       ciclo de simulação, clima vanilla, comando /clima, envio aos jogadores
  ClimateSavedData salva/carrega o estado em world/data/climamod_climate.dat
  ClimatePayload   pacote de rede com o clima local e as nuvens ao redor do jogador
  MinecraftTerrain lê relevo e bioma do gerador de mundo, em segundo plano e com cache
src/client/java/br/climate/client/  Parte visual (só no cliente)
  ClimateClient, ClientClimate   recebem o pacote e suavizam chuva, trovoada e neblina
  CloudRenderer                  desenha as nuvens 3D conforme o tipo
  mixin/                         chuva local, neve pela temperatura, neblina, oculta as nuvens vanilla
src/test/java/br/climate/core/   Testes JUnit e as demonstrações Demo/Demo2
```

## Escalas do "planeta"

Um bloco na horizontal vale 500 m e na vertical 15 m. O equador fica em Z = 0 e o polo
norte em Z = −20.000 (o Z negativo é o norte). O ano tem 24 dias de jogo. Como as
duas escalas são muito diferentes, as nuvens são desenhadas com a espessura comprimida
(100 m por bloco). A física não é afetada, só o desenho.

## Limitações conhecidas

A grade (96 × 96 células, cerca de 1.500 blocos de lado) segue o primeiro jogador da
lista. Jogadores muito longe dele ficam fora da área simulada e recebem céu limpo. Os
biomas do Minecraft continuam decidindo onde a neve se *acumula* no chão; o mod só
decide se cai chuva ou neve na tela. As nuvens não são ordenadas por distância antes de
desenhar, então nuvens translúcidas sobrepostas podem ficar um pouco estranhas em
alguns ângulos.
