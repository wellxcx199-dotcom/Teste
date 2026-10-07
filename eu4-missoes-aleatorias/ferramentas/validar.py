"""Verificador do mod Missoes do Destino.

Confere, sem abrir o jogo, erros comuns de digitacao entre os arquivos:
chaves desbalanceadas, flags verificadas mas nunca criadas, efeitos e
condicoes chamados mas nao definidos, missoes, eventos e modificadores
sem texto na localizacao, e o BOM do arquivo .yml.

Uso: python3 ferramentas/validar.py mod/missoes_aleatorias
"""
import re, sys, glob, os
root = sys.argv[1]
errs = []
def strip(s): return re.sub(r'#[^\n]*', '', s)
txt = {}
for p in glob.glob(os.path.join(root, '**', '*.txt'), recursive=True):
    raw = open(p, 'rb').read()
    if any(b > 127 for b in raw): errs.append(f'nao-ASCII em {p}')
    s = strip(raw.decode('latin-1'))
    if s.count('{') != s.count('}'): errs.append(f'chaves desbalanceadas em {p}')
    txt[os.path.relpath(p, root)] = s
loc = {}
for p in glob.glob(os.path.join(root, 'localisation', '*.yml')):
    raw = open(p, 'rb').read()
    if not raw.startswith(b'\xef\xbb\xbf'): errs.append(f'sem BOM: {p}')
    for line in raw.decode('utf-8-sig').splitlines()[1:]:
        m = re.match(r'\s+([\w.]+):\d+ "(.*)"\s*$', line)
        if m: loc[m.group(1)] = m.group(2)
        elif line.strip(): errs.append(f'linha de loc invalida: {line}')
alltxt = '\n'.join(txt.values())
def top_keys(s):
    keys, depth = [], 0
    for m in re.finditer(r'([\w.]+)\s*=\s*\{|\{|\}', s):
        if m.group(1) and depth == 0: keys.append(m.group(1))
        if m.group(0).endswith('{'): depth += 1
        else: depth -= 1
    return keys
st = top_keys(txt['common/scripted_triggers/rmt_gatilhos.txt'])
se = top_keys(txt['common/scripted_effects/rmt_efeitos.txt'])
mods = top_keys(txt['common/event_modifiers/rmt_modificadores.txt'])
# expand $destino$
destinos = set(re.findall(r'destino = (\w+)', alltxt))
def expand(names):
    out = set()
    for n in names:
        if '$destino$' in n: out |= {n.replace('$destino$', d) for d in destinos}
        else: out.add(n)
    return out
setf = expand(re.findall(r'set_country_flag = ([\w$]+)', alltxt))
for f in expand(re.findall(r'(?:has|clr)_country_flag = ([\w$]+)', alltxt)) | expand(re.findall(r'(?<![_\w])flag = ([\w$]+)', alltxt)):
    if f not in setf: errs.append(f'flag verificada mas nunca criada: {f}')
for f in set(re.findall(r'(?:has|clr)_province_flag = (\w+)', alltxt)):
    if f not in re.findall(r'set_province_flag = (\w+)', alltxt): errs.append(f'flag de provincia nunca criada: {f}')
for f in set(re.findall(r'has_global_flag = (\w+)', alltxt)):
    if f not in re.findall(r'set_global_flag = (\w+)', alltxt): errs.append(f'flag global nunca criada: {f}')
# scripted calls
for n in set(re.findall(r'\b(rmt_\w+) = (?:yes|\{ destino)', alltxt)):
    if n not in st + se: errs.append(f'efeito/condicao nao definido: {n}')
for n in st + se:
    if not re.search(rf'\b{n} = (?:yes|\{{)', alltxt.replace(f'\n{n} = {{', '')): errs.append(f'definido mas nunca usado: {n}')
# missions
ms = txt['missions/rmt_missoes.txt']
missions = set()
depth = 0
for m in re.finditer(r'(\w+)\s*=\s*\{|\{|\}', ms):
    if m.group(1) and depth == 1 and m.group(1) not in ('potential',): missions.add(m.group(1))
    depth += 1 if m.group(0).endswith('{') else -1
for mi in missions:
    for suf in ('_title', '_desc'):
        if mi + suf not in loc: errs.append(f'loc faltando: {mi}{suf}')
for req in re.findall(r'required_missions = \{([^}]*)\}', ms):
    for r in req.split():
        if r not in missions: errs.append(f'required_missions inexistente: {r}')
# decisions
d = txt['decisions/rmt_decisoes.txt']
depth = 0
for m in re.finditer(r'(\w+)\s*=\s*\{|\{|\}', d):
    if m.group(1) and depth == 1:
        for suf in ('_title', '_desc'):
            if m.group(1) + suf not in loc: errs.append(f'loc faltando: {m.group(1)}{suf}')
    depth += 1 if m.group(0).endswith('{') else -1
# events
ev = txt['events/rmt_eventos.txt']
ids = set(re.findall(r'\bid = ([\w.]+)', ev))
for e in set(re.findall(r'country_event = \{ id = ([\w.]+)', alltxt)):
    if e not in ids: errs.append(f'evento inexistente: {e}')
for k in re.findall(r'(?:title|desc|name) = ([\w.]+)', ev):
    if k not in loc: errs.append(f'loc faltando: {k}')
for k in re.findall(r'(?:custom_tooltip|tooltip) = (\w+)', alltxt):
    if k not in loc: errs.append(f'loc faltando: {k}')
for k in re.findall(r'name = "(\w+)"', alltxt):
    if k not in mods: errs.append(f'modificador nao definido: {k}')
for k in mods:
    if k not in loc: errs.append(f'loc faltando: {k}')
print(f'{len(missions)} missoes, {len(ids)} eventos, {len(st)} condicoes, {len(se)} efeitos, {len(mods)} modificadores, {len(loc)} textos')
print('\n'.join(errs) if errs else 'OK: nenhum problema encontrado')
