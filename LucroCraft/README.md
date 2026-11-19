# LucroCraft (WoW 3.3.5a)

Addon que cruza os preços da Casa de Leilões com as receitas das suas profissões
e mostra, numa tabela ordenável, quais crafts dão mais lucro.

## Instalação

1. Copie a pasta `LucroCraft` (a que contém `LucroCraft.toc`) para
   `World of Warcraft/Interface/AddOns/`. O caminho final deve ser
   `Interface/AddOns/LucroCraft/LucroCraft.toc`.
2. Na tela de personagens, clique em **AddOns** e confirme que o LucroCraft está
   habilitado (marque "Carregar addons desatualizados" se for preciso).

## Primeiro uso

1. **Abra a janela de cada profissão uma vez** (inclusive Culinária, Primeiros
   Socorros etc.). O addon registra todas as receitas e materiais. Desmarque o
   filtro "Tenho materiais" antes, senão ele só enxerga parte das receitas.
2. **Visite um vendedor de materiais** (o NPC que vende linhas, frascos, fluxo,
   pergaminho). O addon aprende sozinho o preço dos itens vendidos por NPC.
3. **Vá à Casa de Leilões** e clique no botão **LucroCraft** no topo da janela
   (ou digite `/lucro`). Clique em **Scan receitas**.
4. Pronto: a tabela mostra custo, preço de venda, lucro, margem, número de
   leilões concorrentes e quantas vezes você consegue craftar com o que tem na bolsa.

## Colunas

| Coluna  | Significado |
|---------|-------------|
| Custo   | Soma dos materiais pelo menor preço entre NPC e AH (preço de mercado) |
| Venda   | Preço de mercado do produto na AH (`*` = usando média histórica, sem leilões agora) |
| Lucro   | Venda × quantidade produzida − corte da AH − custo |
| Margem  | Lucro ÷ custo |
| Leilões | Quantos leilões do produto existem (concorrência). `0` em verde = ninguém vendendo |
| Fazer   | Quantas vezes você pode craftar agora com os materiais da bolsa |

Passe o mouse sobre uma linha para ver o detalhamento: cada material e seu preço,
corte da AH, tendência de preço em relação aos últimos dias e se vender ao NPC
compensa mais.

**Clique** numa linha para buscar o item na AH, **Shift+clique** para colocar o
link no chat e **botão direito** para imprimir a lista de compras.

## Modos de scan

- **Scan receitas** (recomendado): pesquisa pelo nome só os produtos e materiais
  das suas receitas. Leva de segundos a poucos minutos.
- **Scan completo**: percorre a AH inteira, página por página.
- **GetAll**: baixa a AH inteira de uma vez. O jogo só permite a cada 15 minutos
  e alguns servidores privados desativam. Em AHs muito grandes pode demorar a
  responder.

## Comandos

```
/lucro                 abre/fecha a janela
/lucro scan            scan das receitas
/lucro full            scan completo
/lucro getall          scan GetAll
/lucro stop            interrompe o scan (o que já foi lido é salvo)
/lucro corte 15        corte da AH em % (5 na AH da facção, 15 na neutra)
/lucro modo min        usa o menor buyout como preço de venda
/lucro modo mercado    usa o preço de mercado (padrão)
/lucro vendor <itemID> <cobre>   registra manualmente um preço de NPC
/lucro reset precos    apaga preços e histórico
/lucro reset receitas  apaga as receitas do personagem atual
```

## Como o preço de "mercado" é calculado

Para cada item, os leilões são ordenados do mais barato para o mais caro e o
addon calcula a média ponderada das unidades mais baratas que somam 25% da
quantidade total à venda. Assim, um único leilão absurdamente barato (ou caro)
não distorce o resultado, e o número reflete o que você pagaria comprando um lote.

## Limitações conhecidas

- **Encantamentos** não aparecem: no 3.3.5 a receita aponta para um feitiço, não
  para um item vendável (os pergaminhos de encantamento precisariam de uma tabela
  de correspondência feitiço → pergaminho).
- Procs de maestria (ex.: Alquimia produzindo extras) e recargas (transmutações)
  não entram no cálculo.
- O depósito da AH não é descontado (só é perdido se o item não vender).
- Materiais que você mesmo pode craftar são avaliados pelo preço da AH, não pelo
  custo de produzi-los.
