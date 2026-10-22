#!/usr/bin/env python3
"""Insere a linha de demografia do mod na janela de província do EU4.

Para mostrar algo dentro da janela da província, o EU4 exige que o
elemento de interface esteja DENTRO do windowType "province_window", que
fica no arquivo interface/provinceview.gui do jogo base. Não dá para
distribuir esse arquivo (ele é da Paradox e muda a cada patch), então este
script lê a SUA cópia, insere o nosso bloco e grava o resultado na pasta
interface/ do mod.

Rode de novo sempre que o EU4 receber um patch.

Exemplo (Windows, Steam):
    python instalar_painel_provincia.py ^
        "C:/Program Files (x86)/Steam/steamapps/common/Europa Universalis IV"

Exemplo mudando a posição do texto dentro da janela:
    python instalar_painel_provincia.py "<pasta do EU4>" --x 40 --y 420
"""

import argparse
import re
import sys
from pathlib import Path

PASTA_FERRAMENTAS = Path(__file__).resolve().parent
PASTA_MOD = PASTA_FERRAMENTAS.parent / "populacao_hibrida"
MOLDE = PASTA_FERRAMENTAS / "painel_provincia.gui"
MARCA_INICIO = "### >>> Populacao Hibrida: inicio"
JANELA_ALVO = re.compile(r'name\s*=\s*"province_window"')


def montar_bloco(x: int, y: int) -> str:
    texto = MOLDE.read_bytes().decode("latin-1")
    return texto.replace("%X%", str(x)).replace("%Y%", str(y))


def inserir(conteudo: str, bloco: str) -> str:
    if MARCA_INICIO in conteudo:
        raise ValueError("o arquivo já contém o painel da População Híbrida")
    achado = JANELA_ALVO.search(conteudo)
    if not achado:
        raise ValueError('não encontrei name = "province_window" no arquivo')
    if "\r\n" in conteudo:
        bloco = bloco.replace("\r\n", "\n").replace("\n", "\r\n")
    # Insere logo depois da linha do nome: a ordem dos filhos dentro de um
    # windowType não importa para o EU4.
    fim_da_linha = conteudo.index("\n", achado.end()) + 1
    return conteudo[:fim_da_linha] + bloco + conteudo[fim_da_linha:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pasta_eu4", type=Path, help="pasta de instalação do EU4")
    parser.add_argument("--x", type=int, default=30, help="posição horizontal (padrão 30)")
    parser.add_argument("--y", type=int, default=440, help="posição vertical (padrão 440)")
    parser.add_argument("--saida", type=Path, default=PASTA_MOD / "interface",
                        help="pasta onde gravar o provinceview.gui modificado")
    args = parser.parse_args()

    original = args.pasta_eu4 / "interface" / "provinceview.gui"
    if not original.is_file():
        print(f"Arquivo não encontrado: {original}", file=sys.stderr)
        return 1

    # Os .gui do EU4 não são UTF-8; latin-1 lê e regrava cada byte igual.
    # Lemos em bytes para o Python não trocar as quebras de linha (CRLF).
    conteudo = original.read_bytes().decode("latin-1")
    try:
        novo = inserir(conteudo, montar_bloco(args.x, args.y))
    except ValueError as erro:
        print(f"Erro: {erro}", file=sys.stderr)
        return 1

    args.saida.mkdir(parents=True, exist_ok=True)
    destino = args.saida / "provinceview.gui"
    destino.write_bytes(novo.encode("latin-1"))
    print(f"Pronto: {destino}")
    print("Atenção: outros mods que também alteram provinceview.gui vão conflitar com este.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
