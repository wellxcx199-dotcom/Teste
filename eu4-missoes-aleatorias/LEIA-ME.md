# Missões do Destino (protótipo de mod para EU4)

O jogador recebe a decisão "Consultar o Destino". Ao tomá-la, o jogo sorteia
uma árvore de missões cuja probabilidade depende da situação do país:

| Destino     | Quem pode receber                                     | Objetivo final                          |
|-------------|-------------------------------------------------------|-----------------------------------------|
| Comércio    | qualquer país (mais provável com porto)               | 75% do comércio do nó de origem         |
| Colonial    | capital na Europa e pelo menos um porto               | 15 províncias nas Américas              |
| Unificação  | capital na Itália ou na Ibéria, Itália/Espanha ainda não existe | formar a Itália ou a Espanha  |

## Arquivos

- `decisions/rmt_decisoes.txt`: a decisão, o sorteio (`random_list`) e a troca de missões (`swap_non_generic_missions`).
- `missions/rmt_missoes.txt`: as três séries de missões, cada uma liberada por uma flag de país.
- `common/event_modifiers/rmt_modificadores.txt`: bônus temporários dados como recompensa.
- `localisation/rmt_missoes_l_english.yml`: textos exibidos no jogo (UTF-8 **com BOM**, exigência do EU4).

## Instalação

Copie `missoes_aleatorias.mod` e a pasta `missoes_aleatorias/` para
`Documentos/Paradox Interactive/Europa Universalis IV/mod/` e ative o mod no launcher.

## O que testar primeiro

1. Abrir o jogo e conferir `logs/error.log` por erros com `rmt_`.
2. Jogar com um país sem árvore própria (ex.: um duque do Sacro Império) e tomar a decisão.
3. Jogar com um país que tem árvore própria (ex.: Castela) e ver o que acontece com a coluna 5.
   Este é o ponto mais incerto do protótipo.
4. Usar o console (`event`, `tag`, `add_core`) para completar missões rapidamente.
