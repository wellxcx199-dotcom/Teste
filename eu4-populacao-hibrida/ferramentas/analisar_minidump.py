#!/usr/bin/env python3
"""Procura a causa de um travamento do EU4 num minidump.dmp.

O exception.txt do EU4 quase nunca diz o que travou, porque o jogo não
vem com nomes de funções. O minidump.dmp (na pasta de logs/crashes do
jogo) guarda a memória das threads no instante do travamento, e às vezes
o texto que o jogo estava processando fica preso na pilha.

O script:
  1. acha a thread com a pilha mais cheia (num estouro de pilha é ela a
     culpada; a thread "da exceção" costuma ser a do relatório de erro);
  2. conta os endereços de retorno repetidos, que formam o ciclo da
     recursão;
  3. lista os textos mais repetidos na pilha dessa thread.

Foi assim que descobrimos que um log com colchetes dentro de um scripted
effect travava o jogo: o texto "oot.GetName" aparecia 14 886 vezes.

Requer a biblioteca minidump:  pip install minidump
Uso:  python analisar_minidump.py caminho/do/minidump.dmp
"""

import collections
import logging
import re
import struct
import sys

from minidump.minidumpfile import MinidumpFile

logging.disable(logging.CRITICAL)  # a biblioteca reclama do PEB ausente; não importa aqui


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    caminho = sys.argv[1]
    dump = MinidumpFile.parse(caminho)
    with open(caminho, "rb") as f:
        bruto = f.read()

    exe = next(m for m in dump.modules.modules if m.name.lower().endswith("eu4.exe"))
    regioes = dump.memory_info.infos

    def pilha_usada(thread):
        sp = thread.Stack.StartOfMemoryRange
        base = next((r.AllocationBase for r in regioes
                     if r.BaseAddress <= sp < r.BaseAddress + r.RegionSize), None)
        topo = max((r.BaseAddress + r.RegionSize for r in regioes if r.AllocationBase == base), default=sp)
        return topo - sp

    culpada = max(dump.threads.threads, key=pilha_usada)
    print(f"Thread com a pilha mais cheia: {culpada.ThreadId} "
          f"({pilha_usada(culpada) // 1024} KB usados)")
    print(f"Thread registrada na exceção: {dump.exception.exception_records[0].ThreadId}\n")

    loc = culpada.Stack.MemoryLocation
    dados = bruto[loc.Rva: loc.Rva + loc.DataSize]
    retornos = collections.Counter()
    for pos in range(0, len(dados) - 8, 8):
        valor = struct.unpack_from("<Q", dados, pos)[0]
        if exe.baseaddress <= valor < exe.baseaddress + exe.size:
            retornos[valor - exe.baseaddress] += 1
    print("Endereços mais repetidos (o ciclo da recursão):")
    for rva, vezes in retornos.most_common(6):
        print(f"   eu4.exe+{rva:#x}  x{vezes}")

    textos = collections.Counter(m.group().decode() for m in re.finditer(rb"[ -~]{6,}", dados))
    print("\nTextos mais repetidos na pilha (procure pedaços do seu mod):")
    for texto, vezes in textos.most_common(15):
        print(f"   x{vezes:<6d} {texto[:90]!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
