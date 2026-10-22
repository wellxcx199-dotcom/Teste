#!/usr/bin/env python3
"""Validador estático da População Híbrida.

Não substitui testar no jogo, mas pega antes do carregamento os erros que
mais quebram mods do EU4 sem aviso claro:

  1. chaves { } desbalanceadas nos arquivos de script e interface;
  2. arquivo de localização sem BOM UTF-8, sem cabeçalho ou com chave
     duplicada (o jogo usa uma das duas e ignora a outra em silêncio);
  3. textos referenciados (eventos, opções, decisões, modificadores,
     tooltips, textos dinâmicos) que não existem na localização;
  4. eventos chamados que não existem e scripted effects/triggers
     chamados com "= yes" que não foram definidos;
  5. modificadores aplicados que não foram definidos;
  6. variáveis lidas que nunca são escritas (geralmente erro de digitação).

Uso:  python ferramentas/validar_mod.py
Sai com código 1 se encontrar erro.
"""

import re
import sys
from pathlib import Path

MOD = Path(__file__).resolve().parent.parent / "populacao_hibrida"

erros: list[str] = []
avisos: list[str] = []


def sem_comentarios(texto: str) -> str:
    """Remove comentários '#' fora de aspas, linha a linha."""
    linhas = []
    for linha in texto.splitlines():
        dentro_aspas = False
        for i, c in enumerate(linha):
            if c == '"':
                dentro_aspas = not dentro_aspas
            elif c == "#" and not dentro_aspas:
                linha = linha[:i]
                break
        linhas.append(linha)
    return "\n".join(linhas)


def ler_script(caminho: Path) -> str:
    return sem_comentarios(caminho.read_text(encoding="utf-8-sig"))


def checar_chaves(caminho: Path, texto: str) -> None:
    nivel = 0
    dentro_aspas = False
    for n, linha in enumerate(texto.splitlines(), 1):
        for c in linha:
            if c == '"':
                dentro_aspas = not dentro_aspas
            elif not dentro_aspas and c == "{":
                nivel += 1
            elif not dentro_aspas and c == "}":
                nivel -= 1
                if nivel < 0:
                    erros.append(f"{caminho}:{n}: '}}' sobrando")
                    nivel = 0
    if nivel != 0:
        erros.append(f"{caminho}: {nivel} chave(s) '{{' sem fechar")


def blocos_de_topo(texto: str) -> list[tuple[str, str]]:
    """Devolve (nome, corpo) de cada bloco 'nome = { ... }' no nível 0."""
    resultado = []
    for m in re.finditer(r"^([A-Za-z_][\w.]*)\s*=\s*\{", texto, re.M):
        inicio = m.end()
        nivel, i = 1, inicio
        while i < len(texto) and nivel:
            nivel += {"{": 1, "}": -1}.get(texto[i], 0)
            i += 1
        resultado.append((m.group(1), texto[inicio:i - 1]))
    return resultado


def carregar_localizacao() -> set[str]:
    chaves: set[str] = set()
    for arq in sorted((MOD / "localisation").rglob("*.yml")):
        bruto = arq.read_bytes()
        if not bruto.startswith(b"\xef\xbb\xbf"):
            erros.append(f"{arq}: falta o BOM UTF-8 (o EU4 vai ignorar o arquivo)")
        if not re.search(r"_l_english\.yml$", arq.name):
            erros.append(f"{arq}: o nome deve terminar em _l_english.yml")
        linhas = bruto.decode("utf-8-sig").splitlines()
        if not linhas or linhas[0].strip() != "l_english:":
            erros.append(f"{arq}: a primeira linha deve ser 'l_english:'")
        for n, linha in enumerate(linhas[1:], 2):
            if not linha.strip() or linha.strip().startswith("#"):
                continue
            m = re.match(r'^ ([\w.\-]+):\d* "(.*)"\s*$', linha)
            if not m:
                erros.append(f"{arq}:{n}: linha fora do formato ' chave:0 \"texto\"'")
                continue
            chave, valor = m.groups()
            if chave in chaves:
                erros.append(f"{arq}:{n}: chave duplicada '{chave}'")
            chaves.add(chave)
            if '"' in valor:
                erros.append(f"{arq}:{n}: aspas dentro do texto de '{chave}'")
    return chaves


def main() -> int:
    arquivos = [p for p in MOD.rglob("*") if p.suffix in (".txt", ".gui", ".gfx")]
    textos = {p: ler_script(p) for p in arquivos}
    for p, t in textos.items():
        checar_chaves(p, t)

    loc = carregar_localizacao()

    def pasta(*partes: str) -> dict[Path, str]:
        base = MOD.joinpath(*partes)
        return {p: t for p, t in textos.items() if base in p.parents}

    # ---- definições ----
    efeitos = {n for t in pasta("common", "scripted_effects").values() for n, _ in blocos_de_topo(t)}
    gatilhos = {n for t in pasta("common", "scripted_triggers").values() for n, _ in blocos_de_topo(t)}
    modificadores = {n for t in pasta("common", "event_modifiers").values() for n, _ in blocos_de_topo(t)}
    # Definição: bloco de topo country_event/province_event cujo "id =" abre
    # a linha. Chamadas ficam na mesma linha ("country_event = { id = x }").
    eventos = set()
    for t in pasta("events").values():
        for nome, corpo in blocos_de_topo(t):
            if nome in ("country_event", "province_event"):
                m = re.search(r"^\s*id\s*=\s*([\w]+\.\d+)", corpo, re.M)
                if m:
                    eventos.add(m.group(1))
    textos_dinamicos = {m for t in pasta("customizable_localization").values()
                        for m in re.findall(r"\bname\s*=\s*(\w+)", t)}
    tudo = "\n".join(textos.values())

    # ---- localização referenciada ----
    precisa_loc: set[str] = set()
    for t in pasta("events").values():
        precisa_loc |= set(re.findall(r"\b(?:title|desc|name)\s*=\s*([\w.]+)", t))
    for t in pasta("decisions").values():
        for bloco_raiz, corpo in blocos_de_topo(t):
            for m in re.finditer(r"^\t([A-Za-z_]\w*)\s*=\s*\{", corpo, re.M):
                precisa_loc |= {f"{m.group(1)}_title", f"{m.group(1)}_desc"}
    precisa_loc |= modificadores
    precisa_loc |= set(re.findall(r"\b(?:custom_tooltip|tooltip)\s*=\s*(\w+)", tudo))
    precisa_loc |= set(re.findall(r"\blocalisation_key\s*=\s*(\w+)", tudo))
    for t in pasta("common", "custom_gui").values():
        precisa_loc |= set(re.findall(r"\bname\s*=\s*(\w+)", t))
    for chave in sorted(precisa_loc - loc):
        erros.append(f"localização faltando: '{chave}'")

    # ---- textos dinâmicos usados na localização ----
    for arq in (MOD / "localisation").rglob("*.yml"):
        for m in set(re.findall(r"\[Root\.(Get\w+)\]", arq.read_text(encoding="utf-8-sig"))):
            if m not in textos_dinamicos and m not in ("GetName", "GetAdjective"):
                erros.append(f"{arq.name}: texto dinâmico '{m}' não definido")

    # ---- eventos chamados ----
    for m in set(re.findall(r"(?:country|province)_event\s*=\s*\{\s*id\s*=\s*([\w]+\.\d+)", tudo)):
        if m not in eventos:
            erros.append(f"evento chamado mas não definido: {m}")

    # ---- scripted effects / triggers chamados ----
    nativos_yes = {"is_city", "is_capital", "is_prosperous", "has_port", "has_siege",
                   "is_looted", "is_colony", "ai", "has_owner_culture",
                   "has_owner_religion", "always", "hidden"}
    for nome in set(re.findall(r"\b(pop_\w+)\s*=\s*(?:yes|no)\b", tudo)) | \
            set(re.findall(r"\b(pop_\w+)\s*=\s*\{\s*lote\b", tudo)):
        if nome not in efeitos | gatilhos | nativos_yes:
            erros.append(f"scripted effect/trigger chamado mas não definido: {nome}")

    # ---- modificadores aplicados ----
    usados = set(re.findall(r"add_(?:province|country)_modifier\s*=\s*\{\s*name\s*=\s*(\w+)", tudo))
    usados |= set(re.findall(r"remove_(?:province|country)_modifier\s*=\s*(\w+)", tudo))
    for m in sorted(usados - modificadores):
        erros.append(f"modificador usado mas não definido: {m}")
    for m in sorted(modificadores - usados):
        avisos.append(f"modificador definido mas nunca aplicado: {m}")

    # ---- variáveis ----
    escritas = set(re.findall(
        r"(?:set_variable|change_variable|subtract_variable|multiply_variable|"
        r"divide_variable|export_to_variable)\s*=\s*\{\s*which\s*=\s*(\w+)", tudo))
    lidas = set(re.findall(r"which\s*=\s*(pop_\w+)", tudo))
    for arq in (MOD / "localisation").rglob("*.yml"):
        lidas |= set(re.findall(r"\.(pop_\w+)\.GetValue", arq.read_text(encoding="utf-8-sig")))
    for v in sorted(lidas - escritas):
        erros.append(f"variável lida mas nunca escrita: {v}")

    for a in avisos:
        print(f"aviso: {a}")
    for e in erros:
        print(f"ERRO: {e}")
    print(f"\n{len(arquivos)} arquivos de script, {len(loc)} chaves de localização, "
          f"{len(efeitos)} efeitos, {len(gatilhos)} gatilhos, {len(eventos)} eventos, "
          f"{len(modificadores)} modificadores.")
    print("OK: nenhum erro encontrado." if not erros else f"{len(erros)} erro(s).")
    return 1 if erros else 0


if __name__ == "__main__":
    sys.exit(main())
