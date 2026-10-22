#!/usr/bin/env python3
"""Simulador fora do jogo da conta de crescimento de uma província.

Reproduz, passo a passo, as mesmas operações de pop_atualizar_provincia
(crescimento logístico, choques e urbanização) usando a aritmética de
ponto fixo do EU4: cada resultado é truncado para 3 casas decimais.
Serve para ajustar números (taxa, capacidade, limiares das faixas) sem
precisar abrir o jogo, e para conferir que os modos Alto/Equilibrado/Leve
chegam a resultados parecidos no longo prazo.

Exemplo:
    python simular_provincia.py --dev 12 --anos 100
    python simular_provincia.py --dev 5 --prospera --guerra 1500-1505
"""

import argparse
import math

MESES_POR_PASSO = {"alto": 8, "equilibrado": 12, "leve": 24}
# Mesmos números de pop_calcular_taxa_natural e pop_calcular_choques.
TAXA_BASE = 15
BONUS_PROSPERA = 5
CHOQUE_GUERRA = -20  # cerco (-15) + ocupação (-5)


def fx(valor: float) -> float:
    """Trunca para 3 casas decimais, como uma variável do EU4."""
    return math.trunc(round(valor * 1000, 6)) / 1000


def simular(dev, prospera, guerra, anos, modo, alvo_urbano):
    meses = MESES_POR_PASSO[modo]
    fator = fx(meses / 12)
    capacidade = fx(dev * 15 * (1.15 if prospera else 1))
    pop = fx(capacidade * 0.85)
    taxa_urb = alvo_urbano
    historico = []
    mes = 0
    while mes < anos * 12:
        ano = 1444 + mes // 12
        em_guerra = guerra and guerra[0] <= ano <= guerra[1]
        razao = fx(pop / capacidade)
        logistico = fx(1 - razao)
        taxa_nat = TAXA_BASE + (BONUS_PROSPERA if prospera else 0)
        cresc = fx(fx(fx(pop * taxa_nat) / 1000) * logistico)
        choque = CHOQUE_GUERRA if em_guerra else 0
        cresc = fx(cresc + fx(fx(pop * choque) / 1000))
        cresc = fx(cresc * fator)
        pop = max(1.0, fx(pop + cresc))
        gap = fx(fx(fx(alvo_urbano - taxa_urb) * 0.1) * fator)
        taxa_urb = fx(taxa_urb + gap)
        historico.append((ano, pop, fx(pop * taxa_urb)))
        mes += meses
    return capacidade, historico


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--dev", type=int, default=10, help="desenvolvimento da província")
    p.add_argument("--anos", type=int, default=100)
    p.add_argument("--prospera", action="store_true")
    p.add_argument("--guerra", help="intervalo de anos em guerra, ex.: 1500-1505")
    p.add_argument("--alvo-urbano", type=float, default=0.15)
    a = p.parse_args()
    guerra = tuple(map(int, a.guerra.split("-"))) if a.guerra else None

    print(f"Província com desenvolvimento {a.dev}, {a.anos} anos"
          f"{', próspera' if a.prospera else ''}"
          f"{f', guerra em {a.guerra}' if guerra else ''}\n")
    resultados = {}
    for modo in MESES_POR_PASSO:
        cap, hist = simular(a.dev, a.prospera, guerra, a.anos, modo, a.alvo_urbano)
        resultados[modo] = hist
        print(f"{modo:12s} capacidade {cap:8.3f} | início {fx(cap * 0.85):8.3f} | "
              f"fim {hist[-1][1]:8.3f} mil (urbanos {hist[-1][2]:7.3f}) | "
              f"{len(hist)} processamentos")
    print("\nTrajetória no modo equilibrado (a cada 10 anos):")
    for ano, pop, urb in resultados["equilibrado"]:
        if (ano - 1444) % 10 == 0:
            barra = "#" * int(pop / 5)
            print(f"  {ano}: {pop:8.3f} mil  {barra}")


if __name__ == "__main__":
    main()
