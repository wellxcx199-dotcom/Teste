# Missões do Destino (versão 0.2, mod de estudo para EU4)

A partida vira uma sequência de capítulos. A decisão **Consultar o Destino**
sorteia até três "cartas" de destino, com chances que dependem da região, da era
e da situação do país, e o jogador escolhe uma num evento. Cada destino traz uma
árvore de missões. Ao cumprir a última missão, o capítulo termina, a recompensa
final é paga e a decisão volta a aparecer para o próximo capítulo. Um destino
cumprido não volta a ser sorteado.

| Destino    | Quem pode receber                                         | Particularidade                                        |
|------------|-----------------------------------------------------------|--------------------------------------------------------|
| Comércio   | qualquer país (mais provável com porto e nos Descobrimentos) | domínio do nó comercial de origem                   |
| Colonial   | capital na Europa e um porto (dobro de chance nos Descobrimentos) | evento divide a árvore em ramo do ouro ou do povoamento |
| Unificação | capital na Itália ou Ibéria, Itália/Espanha ainda não existe | termina formando a Itália ou a Espanha              |
| Conquista  | só o jogador, com um vizinho de 3 a 15 províncias          | alvo escolhido na hora (rival tem prioridade), províncias marcadas no mapa e reivindicadas |
| Fé         | qualquer país (mais provável sem unidade religiosa e na Reforma) | unidade religiosa, estabilidade e prestígio       |
| Súditos    | qualquer país (mais provável se já tem súdito)            | 1, 2 e 4 súditos                                       |

A recompensa final de cada capítulo (`rmt_encerrar_capitulo`) inclui um ano de
renda, que já cresce com a economia do país. Ela dá poder extra se o capítulo
durou menos de 20 anos e estabilidade extra a países com menos de 150 de
desenvolvimento. A partir do terceiro capítulo, a nação ganha o modificador
"Nação Predestinada".

A IA fica de fora por padrão. A decisão **Destinos para a IA: ligar** permite
que países da IA com 150 ou mais de desenvolvimento também consultem o destino.

## Arquivos

- `decisions/rmt_decisoes.txt`: a decisão de sorteio e o interruptor da IA.
- `events/rmt_eventos.txt`: o evento das cartas e a bifurcação colonial.
- `missions/rmt_missoes.txt`: as oito séries (seis destinos + dois ramos coloniais).
- `common/scripted_triggers/rmt_gatilhos.txt`: quem pode receber cada destino.
- `common/scripted_effects/rmt_efeitos.txt`: sorteio, início e fim de capítulo, marcação do alvo.
- `common/event_modifiers/rmt_modificadores.txt`: bônus temporários.
- `localisation/rmt_missoes_l_english.yml`: textos (UTF-8 **com BOM**).
- `ferramentas/validar.py` (fora da pasta do mod): verificador de erros de digitação.
  Rode `python3 ferramentas/validar.py mod/missoes_aleatorias` depois de cada mudança.

## Instalação

Copie `missoes_aleatorias.mod` e a pasta `missoes_aleatorias/` para
`Documentos/Paradox Interactive/Europa Universalis IV/mod/` e ative o mod no
launcher. Se a sua versão do jogo não for 1.37, ajuste `supported_version` nos
dois arquivos `.mod`. **Comece um jogo novo**: saves da versão 0.1 não são compatíveis.

## Roteiro de testes

Abra o console com a tecla `º` (ou `'`, conforme o teclado) para acelerar os testes.

1. Abra o jogo e procure linhas com `rmt_` em `Documentos/.../Europa Universalis IV/logs/error.log`.
2. Jogue com um país pequeno que use as missões genéricas do jogo (a tela de
   missões dele mostra objetivos comuns, como montar um exército e unir a região
   natal) e tome a decisão. Confira se o evento mostra até três cartas.
3. Escolha cada destino em partidas diferentes e veja se a árvore aparece na coluna 5.
4. **Conquista**: confira se as províncias do alvo ficam destacadas e reivindicadas.
   Use `own <id da província>` no console para tomar províncias e testar as missões.
5. **Colonial**: use `ti` (revela o mapa) e `cash` para colonizar rápido. Ao
   cumprir "Colônias Florescentes", o evento de bifurcação deve aparecer e a
   coluna 4 deve mostrar o ramo escolhido.
6. Cumpra a última missão de um destino e confira se a decisão volta a aparecer
   e se o destino cumprido não é mais sorteado.
7. Jogue com Castela ou França (que têm árvore própria) e veja o que acontece
   nas colunas 4 e 5. **Este é o ponto mais incerto do mod.**
8. Ligue os destinos para a IA, avance alguns anos e use `tag` para entrar num
   país da IA e ver se ele recebeu missões.

Anote o que acontecer em cada passo, principalmente mensagens do `error.log`.
