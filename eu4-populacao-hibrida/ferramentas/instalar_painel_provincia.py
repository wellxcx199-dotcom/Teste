#!/usr/bin/env python3
"""Insere a linha de demografia do mod na janela de província do EU4.

Para mostrar algo dentro da janela da província, o EU4 exige que o
elemento de interface esteja DENTRO do windowType "province_window", que
fica no arquivo interface/provinceview.gui do jogo base. Não dá para
distribuir esse arquivo (ele é da Paradox e muda a cada patch), então este
script lê a SUA cópia, insere o nosso bloco e grava o resultado na pasta
interface/ do mod. Ele também copia a definição do texto (custom_gui), que
só é instalada junto com o elemento.

Rode de novo sempre que o EU4 receber um patch.

Exemplo (Windows, Steam):
    python instalar_painel_provincia.py ^
        "C:/Program Files (x86)/Steam/steamapps/common/Europa Universalis IV"

Exemplo mudando a posição do texto dentro da janela:
    python instalar_painel_provincia.py "<pasta do EU4>" --x 40 --y 420

Por padrão grava no mod instalado em Documentos/Paradox Interactive/
Europa Universalis IV/mod/populacao_hibrida. Use --mod para outra pasta.
"""

import argparse
import re
import sys
from pathlib import Path

PASTA_FERRAMENTAS = Path(__file__).resolve().parent
MOD_INSTALADO = (Path.home() / "Documents" / "Paradox Interactive"
                 / "Europa Universalis IV" / "mod" / "populacao_hibrida")
MOLDE = PASTA_FERRAMENTAS / "instalador_painel" / "moldes" / "painel_provincia.gui"
MOLDE_CUSTOM_GUI = PASTA_FERRAMENTAS / "instalador_painel" / "moldes" / "painel_provincia_custom_gui.txt"
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
    parser.add_argument("--mod", type=Path, default=MOD_INSTALADO,
                        help="pasta do mod instalado (padrão: Documents/.../mod/populacao_hibrida)")
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

    if not (args.mod / "descriptor.mod").is_file():
        print(f"Não achei o mod instalado em {args.mod}. Use --mod com a pasta certa "
              "(com OneDrive ela costuma ficar em OneDrive/Documentos/...).", file=sys.stderr)
        return 1
    destino = args.mod / "interface" / "provinceview.gui"
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_bytes(novo.encode("latin-1"))
    definicao = args.mod / "common" / "custom_gui" / "pop_painel_provincia.txt"
    definicao.parent.mkdir(parents=True, exist_ok=True)
    definicao.write_bytes(MOLDE_CUSTOM_GUI.read_bytes())
    print(f"Pronto: {destino}")
    print(f"        {definicao}")
    print("Atenção: outros mods que também alteram provinceview.gui vão conflitar com este.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
