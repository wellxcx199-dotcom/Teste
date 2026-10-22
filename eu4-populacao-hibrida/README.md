# População Híbrida (mod para Europa Universalis IV)

Este mod adiciona ao EU4 um sistema de população, migração e economia inspirado no Victoria, adaptado às ferramentas de script que o EU4 oferece. Cada província passa a ter habitantes de verdade (camponeses e urbanos), que crescem, sofrem com a guerra, migram para lugares mais atraentes e mudam a economia da província e do país.

O mod foi escrito para a versão 1.37 do EU4 e depende de os `on_actions` serem aditivos, algo que existe desde a 1.36. **Ele ainda não foi testado dentro do jogo.** A sintaxe foi conferida contra as regras do CWTools (o validador usado pela comunidade) e contra o código de um mod grande e real, o Anbennar, e as ferramentas incluídas aqui verificam a estrutura e a matemática. Mesmo assim, o primeiro carregamento no jogo é o teste que falta. A seção "O que testar primeiro" diz onde olhar.

## Estrutura da pasta

A pasta `populacao_hibrida/` é o mod em si, e o arquivo `populacao_hibrida.mod` é o que o launcher do jogo lê para saber que o mod existe. A pasta `ferramentas/` tem três scripts em Python que não vão para o jogo: o validador, o simulador e o instalador do painel de província. A pasta `docs/` tem o relatório da investigação de desempenho.

Dentro do mod, cada pasta tem um papel. `common/scripted_effects/pop_efeitos.txt` é o motor da simulação, com todo o cálculo de crescimento, urbanização, migração e economia. `common/on_actions/pop_on_actions.txt` liga esse motor aos momentos em que o jogo chama o mod (início da partida, todo mês, fim de cerco). `common/event_modifiers/pop_modificadores.txt` define os bônus e penalidades de cada faixa de população. `decisions/` e `events/` formam a interface: o Painel Demográfico e as camadas de mapa. `localisation/` tem todos os textos em português, e `customizable_localization/` tem os textos que mudam de acordo com o estado do jogo, como "ligada" ou "desligada".

## Instalação

Copie a pasta `populacao_hibrida` e o arquivo `populacao_hibrida.mod` para a pasta de mods do jogo, que no Windows fica em `Documentos/Paradox Interactive/Europa Universalis IV/mod/`. O resultado deve ficar assim:

```
Documentos/Paradox Interactive/Europa Universalis IV/mod/
    populacao_hibrida.mod
    populacao_hibrida/
        descriptor.mod
        common/ ...
```

Depois abra o launcher, crie ou edite uma playlist e ative "Populacao Hibrida". Como o mod mexe com a economia, as conquistas (achievements) ficam desativadas, como em qualquer mod de jogabilidade.

### Painel na janela da província (opcional)

Para mostrar a população dentro da janela da província, o EU4 exige que o elemento esteja dentro do arquivo `interface/provinceview.gui` do jogo base. Esse arquivo é da Paradox e muda a cada patch, então o mod não o distribui. Em vez disso, o script `ferramentas/instalar_painel_provincia.py` lê a sua cópia, insere uma linha de texto e grava o resultado na pasta do mod. Por exemplo, numa instalação Steam no Windows:

```
python ferramentas/instalar_painel_provincia.py "C:/Program Files (x86)/Steam/steamapps/common/Europa Universalis IV" --saida "C:/Users/SEU_NOME/Documents/Paradox Interactive/Europa Universalis IV/mod/populacao_hibrida/interface"
```

No jogo, a janela da província passa a mostrar algo como "População: 64.5 mil | 9.6 urbanos", e passar o mouse em cima mostra camponeses, capacidade, ocupação, atratividade e a última variação. A posição padrão (`--x 30 --y 440`) é um palpite. Se o texto ficar em cima de outro elemento, rode de novo com outros valores, como `--x 40 --y 400`. Lembre que outro mod que também altere `provinceview.gui` vai conflitar com este, e que o script precisa ser rodado de novo depois de cada patch do EU4.

## Como jogar

Na aba **Decisões** aparece a decisão **Painel Demográfico**. Ela abre um menu, feito com eventos, que mostra a população total do país, quantos são urbanos e camponeses, a porcentagem de urbanização, o maior centro urbano, a variação da população no último ciclo e quantos emigrantes saíram de províncias pressionadas.

Do painel você chega a quatro telas. Em **Camadas de mapa** você escolhe o que quer ver: províncias superpovoadas, subpovoadas, grandes centros urbanos, destinos de migração ou população em declínio. O EU4 não deixa um mod criar modos de mapa novos, mas uma decisão pode destacar províncias. Então, depois de escolher a camada, aparece na aba Decisões uma decisão como "Mapa: grandes centros urbanos", e clicar nela destaca as províncias no mapa. Aceitar essa decisão desliga a camada e volta ao menu. Em **Desempenho** você escolhe entre Alto detalhe, Equilibrado e Leve, e se a IA é processada de forma simplificada. Em **Regras** você liga e desliga a migração, o desenvolvimento gerado por superpopulação, o alcance mundial das camadas, a simulação inteira e o registro de depuração. **Como funciona?** resume as regras dentro do jogo.

## Como a simulação funciona

Todas as quantidades de população estão em milhares de habitantes.

### Capacidade e população inicial

Cada província tem uma **capacidade de sustento** de 15 mil pessoas por ponto de desenvolvimento. Ela sobe 15% se a província for próspera, 20% com a manufatura de fazenda (`farm_estate`), 10% na capital e 5% com porto, e cai 10% com devastação acima de 20 ou 25% acima de 50.

Por exemplo, uma província com desenvolvimento 10, sem nenhum bônus, tem capacidade de 150 mil. No início da partida ela começa com 85% disso, 127,5 mil habitantes. Uma colônia que acabou de virar cidade começa com só 15% da capacidade e vai crescendo.

### Crescimento logístico

A população cresce segundo o modelo **logístico**, o mesmo usado em biologia e demografia:

```
crescimento = população × taxa × (1 − população / capacidade)
```

A taxa base é de 1,5% ao ano (15 por mil), mais 0,5% se a província for próspera, menos 0,2% se a cultura ou a religião forem diferentes das do dono e menos 0,4% com revolta acima de 5. O termo entre parênteses faz o crescimento diminuir sozinho perto da capacidade e ficar negativo acima dela.

Com a província do exemplo: 127,5 × 0,015 × (1 − 127,5/150) = 127,5 × 0,015 × 0,15 ≈ 0,29 mil, ou seja, cerca de 290 pessoas a mais no primeiro ano. Se o desenvolvimento subir para 12, a capacidade vira 180 mil, o termo entre parênteses vira 0,29 e o crescimento quase dobra. É assim que o desenvolvimento do EU4 "puxa" a população.

Guerra, cerco, ocupação, saque e devastação somam perdas separadas, **fora** do termo logístico. Isso evita um erro sutil: se a perda de guerra fosse multiplicada pelo termo logístico, uma província superlotada (termo negativo) em guerra (taxa negativa) teria "negativo vezes negativo" e cresceria. Um cerco vencido ainda tira 3% da população na hora.

### Camponeses e urbanos

Cada província tem uma **parcela urbana alvo**, de 4% mais 25% da fatia de produção no desenvolvimento, com bônus para capital, centro de comércio, mercados, oficinas e manufaturas, até o máximo de 60%. A parcela real anda 10% da distância até o alvo a cada ano. Construir um mercado numa província, por exemplo, não cria burgueses da noite para o dia: a cidade cresce aos poucos ao longo de algumas décadas.

### Migração

Uma província lotada (95% da capacidade ou mais) ou pouco atraente (atratividade negativa) manda 2% da população embora a cada ano, ou 4% se estiver em crise. A **atratividade** vem do espaço livre, da prosperidade, de ser capital ou centro de comércio, e cai com revolta, devastação, cerco e cultura diferente da do dono. O destino é um vizinho do mesmo dono que tenha espaço e seja pelo menos 1 ponto mais atraente. No modo Alto detalhe, a migração também atravessa fronteiras.

Quando a província está acima da capacidade e o dono tem colônias, há 50% de chance, a cada vez que a província é processada (uma vez por ano no modo Equilibrado), de mil pessoas saírem para uma colônia, que ganha 40 colonos. É a versão do mod para a emigração europeia para as Américas.

### Economia

A população vira bônus em faixas, porque o EU4 não aceita um modificador com valor calculado:

| Camponeses | Efeito | Urbanos | Efeito |
|---|---|---|---|
| menos de 25 mil | imposto e soldados −10% | menos de 5 mil | nenhum |
| 25 a 60 mil | nenhum | 5 a 15 mil | produção +5% |
| 60 a 120 mil | imposto e soldados +5% | 15 a 40 mil | produção +10%, comércio +5% |
| 120 a 200 mil | imposto e soldados +10% | 40 a 100 mil | produção +15%, comércio +10%, instituições +10% |
| 200 a 320 mil | +15%, suprimento +10% | 100 a 250 mil | +20%, +15%, +20%, revolta +1 |
| mais de 320 mil | +20%, suprimento +20%, revolta +1 | mais de 250 mil | +25%, +20%, +30%, revolta +2 |

Uma província abaixo de 50% da capacidade fica **subpovoada** (desenvolver custa 10% menos, imposto −5%). Acima de 115%, fica **superpovoada** (revolta +2, desenvolver custa 10% mais, suprimento −10%). Se a superpopulação passar de 110% e não houver devastação, há 10% de chance, a cada processamento, de a província ganhar 1 de produção (se a parcela urbana for de 25% ou mais) ou 1 de imposto. Isso aumenta a capacidade e fecha o ciclo entre população e desenvolvimento.

No país, a porcentagem de urbanização define a sociedade. Abaixo de 10% ela é **agrária** (soldados +5%, produção −5%). Entre 10% e 20% está em transição, sem efeito. Entre 20% e 30% ela é **mercantil** (eficiência de comércio e de produção +5%). A partir de 30% ela é **urbana** (as duas +10% e revolta +1).

## Desempenho

A explicação completa está em `docs/DESEMPENHO.md`. Em resumo, cada país processa um quarto das províncias por vez, em meses sorteados para não coincidir com os outros países. A IA é processada com metade da frequência, a migração só olha vizinhos, os modificadores só são trocados quando a faixa muda e a guerra é tratada por ganchos que o jogo dispara. O documento também ensina a medir o impacto no seu computador.

## Ferramentas

`python ferramentas/validar_mod.py` verifica o mod inteiro antes de você abrir o jogo: chaves `{ }` sem par, localização sem BOM ou com chave repetida, textos que faltam, eventos, efeitos e modificadores chamados que não existem e variáveis lidas que nunca são escritas. Durante o desenvolvimento, ele pegou um erro real: `pop_menu.1.d` era usado ao mesmo tempo como descrição do evento e como nome de uma opção, e o jogo teria mostrado o texto errado sem avisar.

`python ferramentas/simular_provincia.py --dev 5 --guerra 1460-1466` roda a conta de uma província fora do jogo, com a mesma aritmética de 3 casas decimais do EU4, nos três modos de desempenho. Foi com ele que a taxa base subiu de 0,6% para 1,5% ao ano: com 0,6%, uma província levava mais de um século para se recuperar de seis anos de guerra.

`python ferramentas/instalar_painel_provincia.py` foi explicado na instalação.

## O que testar primeiro

Na primeira vez que abrir o jogo com o mod, veja o arquivo `error.log` em `Documentos/Paradox Interactive/Europa Universalis IV/logs/`. Qualquer linha com `pop_` aponta um problema deste mod. Depois confira se a decisão Painel Demográfico aparece e se o painel mostra números diferentes de zero.

Três pontos dependem de detalhes do motor que não deu para confirmar sem o jogo. O primeiro é o formato dos números: o mod mostra os valores com `GetValue`, e o EU4 usa ponto como separador decimal, então "64.5 mil" quer dizer sessenta e quatro mil e quinhentos. O segundo é o nome da maior cidade no painel, que usa um alvo de evento (`[pop_maior_cidade.GetName]`) dentro de um texto dinâmico. O terceiro é a posição do painel na janela da província.

Se aparecer no `error.log` um aviso sobre `pop_painel_provincia` e você não tiver rodado o instalador do painel, isso é esperado: a definição existe, mas o elemento de interface ainda não. Não atrapalha o resto do mod.

## Próximos passos possíveis

Algumas ideias que cabem no mesmo desenho: cultura e religião dos pops (a população de cultura diferente poderia ser uma terceira variável, que converte devagar); eventos de peste e fome que usem a pressão demográfica; e uma janela própria com botões feita com `custom_window`, para quem aceitar sobrescrever arquivos de interface do jogo.
