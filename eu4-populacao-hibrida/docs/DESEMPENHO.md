# Investigação de desempenho: como fazer o mod não pesar no jogo

Este documento explica o que deixa um mod do EU4 lento, quais decisões a População Híbrida tomou por causa disso e como você mesmo pode medir o impacto no seu computador. Os números de custo abaixo são **estimativas feitas contando operações no script**, não medições dentro do jogo. A seção final ensina a medir de verdade.

## De onde vem a lentidão num mod de script

O EU4 roda a simulação dia após dia, e tudo o que um mod pede ao jogo entra nessa conta. O motor é rápido para ler e escrever variáveis, mas fica caro quando o script repete o mesmo trabalho para milhares de coisas ao mesmo tempo. O mapa tem perto de 4 900 províncias terrestres e, em 1444, cerca de 3 mil delas têm dono. Qualquer coisa multiplicada por esse número fica grande muito rápido.

Pesquisando como os mods grandes lidam com isso (o Anbennar, por exemplo, que tem sistemas próprios enormes) e lendo as regras de sintaxe do jogo, aparecem sempre os mesmos vilões.

O primeiro são os **modificadores ativados por gatilho** (`province_triggered_modifier`). Eles parecem práticos, porque o jogo liga e desliga o bônus sozinho, mas para isso ele testa a condição em toda província o tempo todo. Um sistema de população feito assim testaria milhares de condições sem parar, mesmo quando nada mudou.

O segundo são os **eventos com `mean_time_to_happen`**. O jogo precisa sortear periodicamente se cada um deles dispara, para cada país ou província elegível. Mesmo eventos ocultos têm custo de criação, então o mod usa só um evento oculto por país a cada processamento, e nunca um por província.

O terceiro são os **laços aninhados sobre o mapa todo**. Imagine uma migração ingênua: para cada província, procurar em todas as outras a melhor de destino. Isso dá 3 000 × 4 900 ≈ 14,7 milhões de comparações por rodada. Se cada país fizer `every_province`, o problema volta com outra cara: 400 países × 4 900 províncias.

O quarto é **trocar modificadores à toa**. Quando um modificador é adicionado ou removido, o jogo recalcula os valores da província e do país dono. Remover e colocar o mesmo modificador todo mês, só porque o script não verificou se algo mudou, joga trabalho fora.

O quinto são os **picos sincronizados**. Mesmo um trabalho pequeno por país vira um engasgo visível se os 400 países resolverem fazê-lo no mesmo dia. É o famoso travamento no dia 1º do mês.

## O que a População Híbrida faz em cada caso

### Agendamento em lotes e meses diferentes

O gancho mensal (`on_monthly_pulse`) faz só uma conta barata por país: soma 1 num contador e compara com o intervalo. Só quando o contador alcança o intervalo o país faz um "tick", e cada tick processa **um quarto** das suas províncias, porque cada província recebe ao nascer um número de lote de 0 a 3 sorteado.

O contador de cada país também começa num valor sorteado. O resultado é que, em vez de todos os países processarem tudo de uma vez, o trabalho fica espalhado: França processa o lote 2 em março, Castela o lote 0 em abril, e assim por diante.

Um exemplo concreto no modo Equilibrado: a França com 40 províncias faz um tick a cada 3 meses e em cada tick atualiza cerca de 10 províncias. Em 12 meses, ela passou pelas 40 uma vez. O total de trabalho por ano é o mesmo de "atualizar tudo uma vez por ano", só que dividido em pedaços pequenos.

### O fator de tempo

Processar menos vezes poderia mudar o resultado da simulação. Para evitar isso, cada passo usa `pop_fator_tempo`, que é o tamanho do passo em anos (0,667 no Alto detalhe, 1 no Equilibrado e 2 no Leve). As taxas são anuais e são multiplicadas por esse fator.

O simulador `ferramentas/simular_provincia.py` confere isso com a mesma aritmética de 3 casas decimais do EU4. Para uma província de desenvolvimento 5 que passa por uma guerra entre 1460 e 1466, depois de 60 anos o resultado fica em 64,1 mil no Alto detalhe, 64,4 mil no Equilibrado e 63,5 mil no Leve. A diferença entre o modo mais caro e o mais barato é de cerca de 1%, enquanto o custo cai a um terço.

### IA simplificada

Por padrão, os países da IA são processados com metade da frequência do jogador (o fator de tempo compensa, como acima). No modo Leve, a IA também não faz migração. Você continua vendo o seu país em detalhe, e os outros 400 países custam menos.

### Migração só entre vizinhos e só quando há pressão

Uma província só procura destino se estiver lotada (95% da capacidade ou mais) ou pouco atraente (atratividade negativa). A busca usa `random_neighbor_province`, que olha só os vizinhos imediatos, cerca de 6 províncias. Comparando com a versão ingênua: 3 000 províncias × 6 vizinhos = 18 mil comparações por ciclo, contra 14,7 milhões.

Um truque pequeno também economiza variáveis: para exigir que o destino seja pelo menos 1 ponto mais atraente, o script soma 1 à atratividade da origem só durante a busca e tira depois. Assim não precisamos de uma variável extra em cada uma das 3 mil províncias.

### Modificadores por faixa, trocados só quando a faixa muda

Cada província guarda em `pop_faixa_r`, `pop_faixa_u` e `pop_faixa_p` qual faixa está aplicada. A cada passo o script calcula a faixa nova e só remove e adiciona modificadores se ela for diferente. Como a população muda devagar, na grande maioria dos passos nenhum modificador é tocado. As faixas "neutras" não têm modificador nenhum, o que também deixa menos coisa para o jogo somar.

### Trabalho fora dos ganchos

Os ganchos do jogo (`on_actions`) só fazem contas mínimas. Quando chega a vez de um país, o pulso mensal dispara o evento oculto `pop_motor.1`, e é dentro dele que as províncias são processadas. Isso custa um evento a cada 2, 3 ou 6 meses por país, e não um evento por província.

Essa regra veio de um travamento real. A primeira versão do mod inicializava todas as províncias do mundo dentro do `on_startup`, e o jogo fechou com `EXCEPTION_STACK_OVERFLOW`. Sem acesso ao código do jogo não dá para provar que foi exatamente isso, mas é a suspeita principal: o Anbennar, um dos maiores mods de EU4, tem vários comentários no próprio código sobre isso: lógica movida "para um evento para evitar travamentos", eventos atrasados em um dia "para não causar travamentos" e o aviso de que um evento pesado no início pode causar "tela preta ao iniciar". Por isso a versão atual não inicializa nada no `on_startup`. Cada país prepara as suas províncias no primeiro processamento, e como os países começam em meses sorteados, essa preparação fica espalhada pelos três primeiros meses de jogo.

### Ganchos em vez de vigilância

Os efeitos de guerra usam `on_siege_won_province`, que o jogo dispara só quando um cerco termina. Em vez de perguntar todo mês "esta província está em guerra?", o mod espera o jogo avisar.

### Interface que só custa quando aberta

As camadas de mapa são decisões com `ai = no` como primeiro teste do `potential`. A IA descarta a decisão na primeira checagem, e o teste de `provinces_to_highlight` só roda enquanto você está olhando a decisão. O Painel Demográfico calcula os totais nacionais e a maior cidade só quando você o abre, nunca no tick.

## Estimativa de custo

Contando as operações de `pop_atualizar_provincia`, cada província custa algo entre 100 e 150 operações simples (ler um gatilho, mudar uma variável), mais umas 30 se ela procurar destino de migração. Com 3 mil províncias no modo Equilibrado, isso dá na ordem de 400 mil operações por ano de jogo, espalhadas pelos 12 meses e pelos 400 países, ou algo como mil operações por dia de jogo.

A versão ingênua, que atualiza todas as províncias todo mês, seria 12 vezes mais trabalho, e todo ele concentrado no dia 1º de cada mês. Com uma migração por `every_province`, seriam dezenas de milhões de comparações por rodada.

O custo de memória também importa: cada província guarda cerca de 14 variáveis, ou cerca de 42 mil variáveis no total. Isso aumenta um pouco o tamanho do save, mas não pesa durante o jogo.

## Limites que continuam existindo

Variáveis do EU4 têm só 3 casas decimais. Por isso as taxas são guardadas "por mil" e só divididas depois de multiplicadas pela população. Um efeito colateral é que a urbanização para de se mover quando fica a menos de cerca de 0,01 do alvo, o que não muda o jogo, mas vale saber.

A migração escolhe um vizinho válido ao acaso, e não o melhor de todos. Escolher o melhor exigiria ordenar os vizinhos, o que custaria mais. Na prática, a população flui na direção certa ao longo de vários ciclos.

## Como medir no seu computador

Como o EU4 não tem um medidor de desempenho para scripts, a forma confiável de medir é cronometrar. Abra o jogo com o mod desligado, comece uma partida em 1444, abra o console com a tecla `º` (ou `'`, dependendo do teclado) e digite `observe` para assistir sem jogar. Coloque na velocidade 5 e cronometre quanto tempo real leva para o jogo chegar a 11 de novembro de 1454. Repita com o mod ligado no modo Equilibrado e depois no modo Leve. Se a diferença entre "sem mod" e "Equilibrado" for de poucos por cento, o mod está fazendo o que promete.

Para ver o que o mod está fazendo, ligue o "registro de depuração" em Painel Demográfico → Regras. Cada tick escreve no arquivo `game.log` (em `Documentos/Paradox Interactive/Europa Universalis IV/logs/`) uma linha como esta:

```
POP | France | lote 2 | provincias: 11
```

Se aparecerem números muito maiores do que um quarto das províncias do país, ou muitos países no mesmo dia, algo no agendamento está errado. Desligue o registro depois de testar, porque escrever no log também custa.
