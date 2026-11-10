"""
Gera as texturas das nuvens do mod Clima Realista, em pixel art 16x16 no estilo do Minecraft.

Uso:  python3 tools/gerar_texturas_nuvens.py
Saída: src/client/resources/assets/climamod/textures/environment/clouds_atlas.png
       (um "atlas" de 4x4 quadros de 16x16; a ordem está em TILES abaixo e precisa bater
       com as constantes TILE_* de CloudRenderer.java e TornadoRenderer.java)

As texturas são quase brancas/cinzentas: a cor final vem do vértice (tipo de nuvem, hora
do dia, sombra de cada face), como nas nuvens do próprio Minecraft. A textura só acrescenta
o "desenho" em pixels: tufos claros, sombras e fiapos. A semente é fixa, então rodar de
novo gera exatamente as mesmas imagens.
"""
import random
from pathlib import Path

from PIL import Image

T = 16
TILES = ["topo", "lateral", "base", "lateral_tempestade",
         "base_tempestade", "cirro", "cortina_de_chuva", "bigorna",
         "mammatus", "prateleira", "funil", "poeira",
         "pileus", "chuva_2", "chuva_3", "chuva_4"]
OUT = Path(__file__).resolve().parent.parent / "src/client/resources/assets/climamod/textures/environment/clouds_atlas.png"


def tile():
    return [[(0, 0, 0, 0) for _ in range(T)] for _ in range(T)]


def grey(v, a=255):
    v = max(0, min(255, int(v)))
    return (v, v, v, a)


def blob(img, rnd, cx, cy, size, value, alpha=255):
    """Mancha arredondada de pixels (tufo), com borda irregular como no pixel art."""
    for y in range(T):
        for x in range(T):
            dx, dy = (x - cx), (y - cy)
            # distância "toroidal" para a textura repetir sem emenda
            dx = min(abs(dx), T - abs(dx))
            dy = min(abs(dy), T - abs(dy))
            if dx * dx + dy * dy <= size * size + rnd.uniform(-1.5, 1.5):
                img[y][x] = grey(value, alpha)


def topo(rnd):
    img = [[grey(246) for _ in range(T)] for _ in range(T)]
    for _ in range(5):                                    # tufos iluminados pelo sol
        blob(img, rnd, rnd.randrange(T), rnd.randrange(T), rnd.uniform(1.5, 2.6), 255)
    for _ in range(4):                                    # pequenas sombras entre os tufos
        x, y = rnd.randrange(T), rnd.randrange(T)
        img[y][x] = grey(234)
        img[y][(x + 1) % T] = grey(238)
    return img


def lateral(rnd, claro, escuro, realces):
    """Gradiente vertical (mais escuro embaixo) com 'bolhas' arredondadas no alto."""
    img = tile()
    for y in range(T):
        v = claro + (escuro - claro) * (y / (T - 1)) ** 1.3
        for x in range(T):
            img[y][x] = grey(v)
    for _ in range(realces):                               # topos de bolhas, mais claros
        cx, cy = rnd.randrange(T), rnd.randrange(0, 7)
        blob(img, rnd, cx, cy, rnd.uniform(1.2, 2.2), claro + 9)
    for _ in range(6):                                     # sombras sob as bolhas
        x, y = rnd.randrange(T), rnd.randrange(6, T)
        img[y][x] = grey(escuro - 8)
    return img


def base(rnd, v, varia):
    img = [[grey(v) for _ in range(T)] for _ in range(T)]
    for _ in range(6):
        blob(img, rnd, rnd.randrange(T), rnd.randrange(T), rnd.uniform(1.0, 2.0), v + rnd.choice([-varia, varia]))
    return img


def lateral_tempestade(rnd):
    img = lateral(rnd, 168, 112, 3)
    for _ in range(4):                                      # estrias verticais escuras
        x = rnd.randrange(T)
        for y in range(rnd.randrange(4, 9), T):
            img[y][x] = grey(img[y][x][0] - 12)
    return img


def cirro(rnd):
    """Faixa translúcida com fios mais densos e pontas desfiadas (visível também de longe)."""
    img = [[(255, 255, 255, 0) for _ in range(T)] for _ in range(T)]
    for y in range(2, 14):                                 # véu de fundo, mais forte no meio
        a = 70 if 4 <= y <= 11 else 40
        for x in range(T):
            img[y][x] = (255, 255, 255, a)
    for row in (4, 7, 10):                                 # fios longos e brilhantes
        start = rnd.randrange(T)
        for i in range(rnd.randrange(11, 16)):
            x = (start + i) % T
            img[row][x] = (255, 255, 255, 235)
            if rnd.random() < 0.4:
                img[row + 1][x] = (255, 255, 255, 170)
    for _ in range(6):                                     # falhas no véu: aspecto de fiapo
        x, y = rnd.randrange(T), rnd.choice([2, 3, 12, 13])
        img[y][x] = (255, 255, 255, 0)
    return img


def cortina_de_chuva(rnd):
    """Riscos verticais tracejados, azul-acinzentados e translúcidos."""
    img = tile()
    for x in range(T):
        if rnd.random() < 0.45:
            continue
        y0 = rnd.randrange(T)
        for i in range(rnd.randrange(5, 12)):
            img[(y0 + i) % T][(x + rnd.choice([0, 0, 1])) % T] = (196, 208, 224, rnd.choice([110, 140, 170]))
    return img


def desce(img, passo):
    """O mesmo quadro deslocado para baixo (com volta): quadros da animação da chuva."""
    return [img[(y - passo) % T][:] for y in range(T)]


def mammatus(rnd):
    """Base da bigorna com bolsas penduradas: cada bolsa é clara na borda de cima (luz
    rasante) e escura no fundo, como os mammatus vistos de baixo no fim de tarde."""
    img = [[grey(196) for _ in range(T)] for _ in range(T)]
    for cy in (4, 12):
        for cx in ((0, 8) if cy == 4 else (4, 12)):
            for y in range(T):
                for x in range(T):
                    dx = min(abs(x - cx), T - abs(x - cx))
                    dy = min(abs(y - cy), T - abs(y - cy))
                    d = (dx * dx + dy * dy) ** 0.5
                    if d < 3.6:
                        img[y][x] = grey(168 + 7 * d)           # fundo da bolsa, mais escuro
                    elif d < 4.4:
                        img[y][x] = grey(214)                  # aro iluminado entre as bolsas
    return img


def prateleira(rnd):
    """Lateral da nuvem-prateleira: faixas horizontais em degraus, a borda de cada camada
    mais clara, como o ar frio levantando o ar quente em lâminas."""
    img = tile()
    for y in range(T):
        banda = y % 5
        v = 150 - 14 * (y / T) + (22 if banda == 0 else 8 if banda == 1 else 0)
        for x in range(T):
            img[y][x] = grey(v + rnd.choice([-3, 0, 0, 3]))
    for _ in range(5):                                       # rasgos escuros sob as lâminas
        x, y = rnd.randrange(T), rnd.choice([3, 4, 8, 9, 13, 14])
        img[y][x] = grey(110)
    return img


def funil(rnd):
    """Funil do tornado: estrias em diagonal (a nuvem girando) sobre cinza-chumbo."""
    img = tile()
    for y in range(T):
        for x in range(T):
            faixa = (x + y // 2) % 6
            img[y][x] = grey(152 + (16 if faixa == 0 else 6 if faixa == 1 else 0) - 10 * (y / T))
    for _ in range(8):
        x, y = rnd.randrange(T), rnd.randrange(T)
        img[y][x] = grey(132)
    return img


def poeira(rnd):
    """Nuvem de detritos: grãos marrons e folhas soltas, translúcida nas bordas."""
    img = tile()
    for y in range(T):
        for x in range(T):
            if rnd.random() < 0.62:
                tom = rnd.choice([(150, 122, 92), (128, 102, 76), (170, 146, 112), (96, 120, 70)])
                img[y][x] = tom + (rnd.choice([150, 190, 230]),)
    return img


def pileus(rnd):
    """Capuz liso e brilhante sobre a torre que cresce: quase sem textura, com brilho sedoso."""
    img = [[(255, 255, 255, 150) for _ in range(T)] for _ in range(T)]
    for y in (3, 4, 9, 10):
        off = rnd.randrange(T)
        for i in range(rnd.randrange(8, 14)):
            img[y][(off + i) % T] = (255, 255, 255, 200)
    return img


def bigorna(rnd):
    img = [[grey(250) for _ in range(T)] for _ in range(T)]
    for y in range(0, T, 4):                                # estrias horizontais: o topo se espalha
        off = rnd.randrange(T)
        for i in range(rnd.randrange(6, 12)):
            img[y][(off + i) % T] = grey(241)
    return img


def main():
    rnd = random.Random(1968)
    quadros = [topo(rnd), lateral(rnd, 246, 214, 5), base(rnd, 206, 8), lateral_tempestade(rnd),
               base(rnd, 104, 7), cirro(rnd), cortina_de_chuva(rnd), bigorna(rnd)]
    chuva = quadros[6]                                      # os quadros 13 a 15 animam a chuva
    quadros += [mammatus(rnd), prateleira(rnd), funil(rnd), poeira(rnd),
                pileus(rnd), desce(chuva, 4), desce(chuva, 8), desce(chuva, 12)]
    atlas = Image.new("RGBA", (4 * T, 4 * T), (0, 0, 0, 0))
    for n, q in enumerate(quadros):
        ox, oy = (n % 4) * T, (n // 4) * T
        for y in range(T):
            for x in range(T):
                atlas.putpixel((ox + x, oy + y), q[y][x])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT)
    print(f"gravado {OUT} ({len(quadros)} quadros: {', '.join(TILES)})")


if __name__ == "__main__":
    main()
