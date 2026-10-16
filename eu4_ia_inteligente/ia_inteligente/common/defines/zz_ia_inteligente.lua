-- ============================================================================
-- IA Inteligente e Estilos de Jogo - ajustes globais da IA (NDefines.NAI)
--
-- Este arquivo NAO substitui o defines.lua do jogo: ele so altera algumas
-- entradas da tabela NDefines depois que o jogo carrega os valores originais.
-- O prefixo "zz_" garante que ele seja lido depois de outros arquivos.
--
-- Os comentarios indicam o valor original (vanilla) e o motivo da mudanca.
-- Para desfazer um ajuste basta apagar a linha correspondente.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Guerra: escolher melhor QUANDO e CONTRA QUEM lutar
-- ----------------------------------------------------------------------------

-- Chance base (em 10000) de a IA aceitar comecar uma guerra a cada "tick"
-- diplomatico. Vanilla: 200. A IA fica um pouco mais ativa...
NDefines.NAI.AGGRESSIVENESS = 260

-- ...mas o bonus para guerras FACEIS sobe bem mais (vanilla: 300).
-- Resultado: a IA prefere atacar alvos fracos ou odiados em vez de se
-- jogar contra vizinhos fortes.
NDefines.NAI.AGGRESSIVENESS_BONUS_EASY_WAR = 650

-- Vantagem minima que a IA quer antes de entrar numa batalha (vanilla: 1.2).
-- Com 1.35 ela evita batalhas equilibradas que destroem o exercito.
NDefines.NAI.ACCEPTABLE_BALANCE_DEFAULT = 1.35

-- Multiplicador quando um aliado ja esta lutando no local (vanilla: 0.8).
-- Valor menor = a IA reforca aliados com mais vontade (melhor coordenacao).
NDefines.NAI.ACCEPTABLE_BALANCE_MULT_FRIEND_IN_COMBAT = 0.7

-- A IA nao tenta invasao naval se (forca do defensor / forca do invasor)
-- passar deste valor (vanilla: 1.3). Menos desembarques suicidas.
NDefines.NAI.INVADING_BRAVERY = 1.0

-- Provincias que valem mais que esta fracao do desenvolvimento total sao
-- consideradas importantes para defender (vanilla: 0.035).
NDefines.NAI.IMPORANT_PROVINCE_THRESHOLD = 0.025

-- Prioridade de defender o territorio natal quando ameacado (vanilla: 7.5).
NDefines.NAI.REGION_PLANNING_HOMELAND_PRIORIZATION = 9.0

-- Um general para cada X regimentos. Generais dao "pips" de graca, entao a
-- IA deve usa-los em pilhas menores.
NDefines.NAI.REGIMENTS_PER_GENERAL = 10

-- ----------------------------------------------------------------------------
-- 2. Paz: exigir terras que fazem sentido
-- ----------------------------------------------------------------------------

-- Multiplicador de desejo por provincia que NAO faz fronteira com nada da IA
-- (vanilla: 0.5). Menor = menos "fronteiras malucas" e enclaves.
NDefines.NAI.PEACE_TERMS_PROVINCE_NOT_ADJACENT_MULT = 0.25

-- Bonus por cada provincia propria adjacente (vanilla: 0.5).
-- Maior = a IA prefere consolidar blocos de territorio.
NDefines.NAI.PEACE_TERMS_PROVINCE_REAL_ADJACENT_MULT = 0.75

-- Desejo por provincia quando a IA ja esta com 99% de sobre-extensao
-- (vanilla: 0.75). Menor = ela para de engolir terra que nao consegue digerir.
NDefines.NAI.PEACE_TERMS_PROVINCE_OVEREXTENSION_MIN_MULT = 0.4

-- Desejo por provincia com core proprio (vanilla: 3.0): retomar cores primeiro.
NDefines.NAI.PEACE_TERMS_PROVINCE_CORE_MULT = 4.0

-- Desejo por provincia de cultura diferente (vanilla: 0.75).
NDefines.NAI.PEACE_TERMS_PROVINCE_NOT_CULTURE_MULT = 0.6

-- Aleatoriedade na escolha dos termos de paz (vanilla: 0.75).
NDefines.NAI.PEACE_RANDOM_FACTOR = 0.4

-- Guerras "travadas" (sem mudanca de warscore) terminam mais rapido
-- (vanilla: 0.34).
NDefines.NAI.PEACE_STALLED_WAR_TIME_FACTOR = 0.5

-- Peso da exaustao de guerra na vontade de fazer paz (vanilla: 1.0).
NDefines.NAI.PEACE_WAR_EXHAUSTION_FACTOR = 1.25

-- Peso de ter o proprio territorio ocupado (vanilla: 40).
NDefines.NAI.PEACE_DESPERATION_FACTOR = 50

-- ----------------------------------------------------------------------------
-- 3. Diplomacia: menos sorteio, mais calculo
-- ----------------------------------------------------------------------------

-- Quanto da pontuacao das acoes diplomaticas e aleatoria (vanilla: 1.0, ou
-- seja, metade aleatoria). Com 0.5 so 1/3 e sorte e 2/3 e calculo.
NDefines.NAI.DIPLOMATIC_ACTION_RANDOM_FACTOR = 0.5

-- ----------------------------------------------------------------------------
-- 4. Economia: menos vicio em emprestimos, mais desenvolvimento
-- ----------------------------------------------------------------------------

-- Fracao maxima da renda que a IA aceita gastar com juros (vanilla: 0.3).
NDefines.NAI.ACCEPTABLE_FRACTION_OF_INTEREST_PAYMENTS = 0.2

-- Vontade de quitar emprestimos (vanilla: 2000).
NDefines.NAI.REPAY_LOAN_BASE_AI_DESIRE = 3000.0

-- A IA nao desenvolve provincias acima de DEVELOPMENT_CAP_BASE ou de
-- DEVELOPMENT_CAP_MULT x desenvolvimento original (o que for maior).
-- Subir esses valores deixa a IA investir mais nas melhores provincias.
NDefines.NAI.DEVELOPMENT_CAP_BASE = 25
NDefines.NAI.DEVELOPMENT_CAP_MULT = 2.5
