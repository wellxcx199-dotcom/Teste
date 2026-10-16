# IA Inteligente e Estilos de Jogo — mod para Europa Universalis IV

Este mod faz duas coisas. A primeira é deixar a IA do jogo mais esperta, ajustando os parâmetros internos que ela usa para decidir quando lutar, que terras pedir num tratado de paz, quanto se endividar e quanto desenvolver as próprias províncias. A segunda é dar a cada país controlado pela IA uma **doutrina** sorteada aleatoriamente: um estilo de jogo com personalidade própria, bônus, penalidades e um jeito diferente de escolher amigos e inimigos. Dois reinos vizinhos e parecidos podem, assim, ter comportamentos completamente diferentes em cada campanha.

## Como instalar

Copie a pasta `ia_inteligente` e o arquivo `ia_inteligente.mod` para a pasta de mods do jogo. No Windows ela fica em `Documentos\Paradox Interactive\Europa Universalis IV\mod\`; no Linux, em `~/.local/share/Paradox Interactive/Europa Universalis IV/mod/`. Depois disso, o mod aparece no launcher da Paradox e basta ativá-lo numa playlist. O resultado final deve ficar assim:

```
mod/
├── ia_inteligente.mod
└── ia_inteligente/
    ├── descriptor.mod
    ├── common/ ...
    ├── decisions/ ...
    ├── events/ ...
    └── localisation/ ...
```

O arquivo `ia_inteligente.mod` diz ao launcher onde está a pasta (`path="mod/ia_inteligente"`). O `descriptor.mod` dentro da pasta tem as mesmas informações e é o que o launcher usa para mostrar nome, versão e tags. O campo `supported_version="1.37.*"` só serve para o launcher avisar se a versão do jogo for diferente; se você jogar em outra versão, o mod provavelmente funciona do mesmo jeito, e você pode simplesmente trocar esse número.

## Parte 1: a IA mais inteligente

O arquivo `common/defines/zz_ia_inteligente.lua` muda 22 valores da tabela `NDefines.NAI`, que é onde o jogo guarda os "números mágicos" usados pela IA. O arquivo não substitui o `defines.lua` original: ele só reescreve algumas entradas depois que o jogo carrega tudo. Por isso é compatível com quase qualquer mod, e o prefixo `zz_` garante que ele seja lido por último.

A ideia geral foi deixar a IA mais calculista, e não só mais agressiva. Um exemplo: o valor `AGGRESSIVENESS` (a chance de a IA topar começar uma guerra) subiu pouco, de 200 para 260, enquanto `AGGRESSIVENESS_BONUS_EASY_WAR` (o bônus quando o alvo é fraco ou odiado) subiu de 300 para 650. Na prática, a IA continua sem declarar guerra a todo momento, mas, quando declara, escolhe muito melhor o alvo. Do mesmo jeito, `ACCEPTABLE_BALANCE_DEFAULT` passou de 1.2 para 1.35, o que faz a IA só aceitar batalha quando tem uns 35% de vantagem, em vez de jogar o exército numa luta equilibrada e perder tudo. E `INVADING_BRAVERY` caiu de 1.3 para 1.0, o que acaba com boa parte dos desembarques suicidas em que a IA larga 10 mil homens numa ilha defendida por 20 mil.

Nos tratados de paz, a IA passa a preferir províncias que fazem fronteira com o que ela já tem (`PEACE_TERMS_PROVINCE_REAL_ADJACENT_MULT` e `PEACE_TERMS_PROVINCE_NOT_ADJACENT_MULT`), a querer menos terra quando já está sobre-estendida (`PEACE_TERMS_PROVINCE_OVEREXTENSION_MIN_MULT`) e a priorizar seus cores. Isso reduz bastante os mapas cheios de enclaves e as fronteiras malucas que a IA costuma criar. Na diplomacia, `DIPLOMATIC_ACTION_RANDOM_FACTOR` caiu de 1.0 para 0.5: no vanilla, metade da nota que a IA dá a uma ação diplomática é puro sorteio; com o mod, só um terço é sorte e o resto é cálculo. Na economia, a IA aceita gastar menos da renda com juros, tem mais vontade de quitar empréstimos e desenvolve mais as províncias boas.

Cada linha do arquivo tem um comentário explicando o que faz e qual era o valor original. Se algum ajuste não te agradar, é só apagar a linha. Se quiser experimentar, mude um número por vez e jogue algumas décadas no modo observador para ver o efeito.

## Parte 2: as doutrinas (estilos de jogo)

Pouco depois do início da partida, cada país da IA sorteia uma das doze doutrinas abaixo. Cada doutrina é formada por quatro peças que trabalham juntas. Uma flag (`smartai_ps_<nome>`) marca o país e é usada pelos outros scripts. Um modificador permanente dá um bônus coerente com a estratégia e uma penalidade que a equilibra, para que a doutrina não vire trapaça. Uma personalidade de IA do próprio jogo (militarista, diplomata, capitalista, colonialista ou equilibrada) fica travada com `set_ai_personality = { ... locked = yes }`, e é ela que muda as decisões internas do motor do jogo. Por fim, uma revisão estratégica feita a cada cerca de dois anos usa `set_ai_attitude` para dizer à IA quem ela deve tratar como alvo, amigo ou ameaça.

O **Conquistador Implacável** é o estilo mais agressivo. Ele ganha moral terrestre e fabrica reivindicações mais barato, mas paga mais caro para subir estabilidade e melhora relações mais devagar. A cada revisão, se a economia estiver saudável (sem guerra, com menos de 3 empréstimos e pelo menos metade da mão de obra), ele escolhe um vizinho com menos de 66% da sua força militar, marca esse vizinho como hostil e ganha uma reivindicação numa província que faz fronteira com ele. Com essa reivindicação a IA passa a ter um casus belli de conquista, e a própria lógica do jogo cuida do resto.

O **Abutre Oportunista** faz parecido, mas só mira vizinhos fracos que já estejam em guerra com outra pessoa. Para o jogador isso significa que, se você declarar guerra a alguém, o seu vizinho abutre pode te atacar pelas costas. O **Fanático Zeloso** escolhe como alvo o vizinho fraco de outra religião e trata como amigos os vizinhos da mesma fé. O **Senhor dos Mares** só aparece em países com pelo menos 3 portos: ganha moral naval, navios mais baratos e corsários eficientes, perde limite de tropas terrestres e mira vizinhos com litoral.

Os estilos pacíficos são o **Príncipe Mercador** (comércio), o **Arquiteto do Reino** (construções e desenvolvimento), o **Explorador Ultramarino** (colônias) e o **Reformista Iluminado** (tecnologia e ideias mais baratas). Os quatro marcam todos os vizinhos que não são rivais como amigos, o que leva a IA a buscar alianças e evitar guerras.

Os estilos diplomáticos são mais sutis. O **Hegemon Diplomático** marca os vizinhos pequenos (com menos da metade da sua força) como protegidos, o que leva a IA a dar garantias que depois viram vassalagem, e os vizinhos grandes como amigos. O **Tecelão de Intrigas** aplica a regra de que o inimigo do meu inimigo é meu amigo: fica amigo de qualquer país que seja rival de um rival seu. A **Fortaleza Eterna** marca o vizinho muito mais forte (pelo menos 1,5 vez a sua força) como ameaça, o que faz a IA buscar alianças defensivas contra ele, e recebe fortes bônus defensivos; em troca, qualquer conquista gera 15% a mais de agressividade.

Por fim, o **Rei Imprevisível** é o estilo caótico. Em cada revisão ele tem 40% de chance de escolher um vizinho fraco como alvo, 30% de chance de fazer amizade com um vizinho qualquer e 30% de chance de trocar a própria personalidade de IA.

O sorteio não é cego. As chances têm pesos diferentes (o Conquistador tem peso 12 e o Rei Imprevisível, peso 4) e cada doutrina tem uma condição de elegibilidade no arquivo de triggers. Se um país sem portos sorteia o Senhor dos Mares, por exemplo, nada acontece, e o evento tenta de novo alguns dias depois até sair uma doutrina que faça sentido. Vassalos nunca recebem estilos agressivos, porque todos eles exigem `is_subject = no`.

A doutrina também não é para sempre. Depois de pelo menos 20 anos, o evento `smartai.2` ("Nova Geração, Novas Ambições") pode apagar a doutrina para que o país sorteie outra. Isso acontece em média a cada 25 anos, e mais rápido se o país estiver endividado ou com estabilidade negativa, como se a corte percebesse que a estratégia atual está dando errado.

## O que o jogador vê

As doutrinas aparecem como modificadores no país da IA, com nome e descrição em português. Existe também uma decisão chamada **Consultar Embaixadores**, que custa 10 de poder diplomático e abre um relatório para cada vizinho e rival, explicando a doutrina deles e o que esperar. Um exemplo de relatório: *"Os generais de Castela seguem a doutrina do Abutre Oportunista. Se entrarmos em guerra com outra nação, eles podem nos atacar pelas costas."*

A partir do segundo ano de campanha, você também recebe um relatório automático sempre que um vizinho ou rival troca de doutrina. No primeiro ano esses avisos ficam desligados, para que a tela não encha de janelas logo no início. Se os avisos incomodarem, a decisão **Ignorar Relatórios Automáticos** os desliga, e **Receber Relatórios Automáticos** os liga de novo.

Como o EU4 não tem opção de idioma português, os textos ficam no arquivo de inglês (`l_english`). Com o jogo em inglês, os textos do mod aparecem em português.

## Como os arquivos se encaixam

O evento `smartai.1`, em `events/ia_inteligente_events.txt`, é a porta de entrada. Ele é um evento oculto com `mean_time_to_happen = { days = 1 }` e a condição "sou IA e ainda não tenho doutrina". Escolhi esse caminho em vez de usar `on_actions` porque mexer em `on_actions` costuma quebrar a compatibilidade com outros mods. Como a condição é verificada continuamente, o evento também funciona para países que surgem no meio da partida, como uma nação libertada ou uma colônia que vira independente.

Quando `smartai.1` dispara, ele chama o efeito `smartai_roll_playstyle`, que está em `common/scripted_effects/ia_inteligente_effects.txt`. Esse efeito faz o sorteio com `random_list` e, se o país for elegível, chama `smartai_apply_playstyle`. Esse último efeito é parametrizado: a linha `smartai_apply_playstyle = { style = conqueror personality = ai_militarist }` vira, por substituição de texto, `set_country_flag = smartai_ps_conqueror`, `add_country_modifier = { name = smartai_ps_conqueror ... }` e `set_ai_personality = { personality = ai_militarist locked = yes }`. Esse truque com `$style$` evita escrever doze blocos quase iguais.

O evento `smartai.10` faz a revisão estratégica. Primeiro ele chama `smartai_reset_attitudes`, que destrava as atitudes travadas na revisão anterior; sem isso, uma IA poderia ficar presa para sempre a um alvo que hoje é mais forte que ela. Depois ele aplica a lógica da doutrina atual. As condições reutilizáveis, como `smartai_is_easy_prey` ("este vizinho é uma presa fácil?") e `smartai_no_special_relation` ("não estamos em guerra e não somos suserano e vassalo"), ficam em `common/scripted_triggers/ia_inteligente_triggers.txt`. Essa segunda condição existe para que a revisão nunca mexa na relação entre um vassalo e o seu suserano.

## Como criar sua própria doutrina

Suponha que você queira um estilo **Mercenário**, que gosta de guerra mas não de conquista. São cinco passos, todos pequenos.

O primeiro é criar o modificador em `common/event_modifiers/ia_inteligente_modifiers.txt`:

```
smartai_ps_mercenary = {
	mercenary_cost = -0.20
	land_morale = 0.05
	global_tax_modifier = -0.10
}
```

O segundo é criar a condição de elegibilidade em `common/scripted_triggers/ia_inteligente_triggers.txt`:

```
smartai_can_be_mercenary = {
	smartai_is_independent = yes
}
```

O terceiro é adicionar uma entrada ao `random_list` de `smartai_roll_playstyle`, e as linhas `clr_country_flag = smartai_ps_mercenary` e `remove_country_modifier = smartai_ps_mercenary` em `smartai_clear_playstyle`:

```
6 = {
	if = {
		limit = { smartai_can_be_mercenary = yes }
		smartai_apply_playstyle = { style = mercenary personality = ai_militarist }
	}
}
```

O quarto é escrever o comportamento dentro de `smartai_strategy_review`, num bloco `if = { limit = { has_country_flag = smartai_ps_mercenary } ... }`. O quinto é adicionar ao evento `smartai.3` uma linha `desc = { trigger = { FROM = { has_country_flag = smartai_ps_mercenary } } desc = smartai.3.d.mercenary }` e, no arquivo de localização, as chaves `smartai_ps_mercenary`, `desc_smartai_ps_mercenary` e `smartai.3.d.mercenary`.

## Como testar

Abra o console do jogo com a tecla `º` (ou `~`, dependendo do teclado) e digite `debug_mode`. Com isso, passar o mouse sobre um país mostra as flags dele, e você confere qual doutrina ele recebeu. O comando `observe` permite assistir à IA jogando sozinha, que é a melhor forma de comparar uma campanha com e sem o mod. Com `tag FRA` você assume a França e, com o comando `event smartai.10 FRA`, força uma revisão estratégica nela. Se algo der errado, o arquivo `Documentos\Paradox Interactive\Europa Universalis IV\logs\error.log` lista erros de script com o nome do arquivo e o número da linha, e as linhas que mencionam `smartai` são deste mod.

## Limitações honestas

Grande parte do comportamento da IA do EU4 está no código do jogo (em C++), e mods não têm acesso a ele. Um mod consegue mexer nos parâmetros que a Paradox expôs (os defines), na personalidade e nas atitudes da IA, nos modificadores e em eventos. Ele não consegue reescrever, por exemplo, o algoritmo que move os exércitos de província em província. Por isso a "inteligência" deste mod é uma inteligência de decisões: escolher melhor alvos, batalhas, tratados de paz e gastos. A microgestão de tropas continua sendo a do jogo.

Os scripts foram conferidos contra a sintaxe usada por mods grandes e atuais do EU4, e os nomes dos 22 defines foram checados um a um contra um `defines.lua` real. Mesmo assim, o mod ainda não foi rodado dentro do jogo. Na primeira partida vale a pena olhar o `error.log`. Outro ponto: os valores originais citados nos comentários do arquivo `.lua` vêm de um defines recente, e um patch futuro do jogo pode mudá-los.

Os arquivos de script (`.txt` e `.lua`) foram escritos sem acentos de propósito, porque o EU4 lê esses arquivos com uma codificação antiga e caracteres acentuados podem gerar erros. Só o arquivo de localização (`.yml`) tem acentos, e ele precisa estar em UTF-8 com BOM, como o jogo exige.
