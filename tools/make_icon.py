"""Genera los iconos adaptativos de FeedVibe a partir de la ilustración original.

Separa los dibujos del fondo azul, rellena el fondo con un degradado azul que
cubre todo el lienzo y escala los dibujos para que queden dentro de la zona
segura (círculo de 66dp sobre 108dp), así ningún launcher los recorta.
"""
import sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

SRC = sys.argv[1]
OUT = sys.argv[2]  # app/src/main/res

im = np.asarray(Image.open(SRC).convert("RGB")).astype(np.float32)
H, W, _ = im.shape
r, g, b = im[..., 0], im[..., 1], im[..., 2]

# Píxeles de fondo azul (incluye sombras azul oscuro).
blue = (r < 30) & (b > 170) & (g < 165)
# Exterior blanco del cuadrado redondeado original.
white = (r > 235) & (g > 235) & (b > 235)
lbl, _ = ndimage.label(white)
outside = np.isin(lbl, np.unique(np.concatenate([lbl[0], lbl[-1], lbl[:, 0], lbl[:, -1]])))
outside &= lbl > 0
outside = ndimage.binary_dilation(outside, iterations=12)

# Fondo = azul conectado con el borde del cuadrado.
blbl, _ = ndimage.label(blue)
ring = ndimage.binary_dilation(outside, iterations=20) & ~outside
edge_ids = np.unique(blbl[ring & blue])
edge_ids = edge_ids[edge_ids > 0]
background = np.isin(blbl, edge_ids)
content = ~background & ~outside
content = ndimage.binary_opening(content, iterations=2)
# Quitamos motas pequeñas.
clbl, n = ndimage.label(content)
sizes = ndimage.sum(content, clbl, range(1, n + 1))
content = np.isin(clbl, 1 + np.nonzero(sizes > 400)[0])
content = ndimage.binary_fill_holes(content)

ys, xs = np.nonzero(content)
cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
rmax = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()
print("bbox", xs.min(), xs.max(), ys.min(), ys.max(), "center", cx, cy, "rmax", rmax)

# Degradado de fondo: ajuste lineal (plano) del azul limpio, lejos de las sombras.
clean = background & ~ndimage.binary_dilation(content, iterations=60)
yy, xx = np.nonzero(clean)
A = np.stack([xx, yy, np.ones_like(xx)], 1).astype(np.float64)
coef = [np.linalg.lstsq(A, im[yy, xx, c], rcond=None)[0] for c in range(3)]

# Alfa suave alrededor de los dibujos (conserva una sombra suave).
mask = Image.fromarray((content * 255).astype(np.uint8))
soft = np.asarray(mask.filter(ImageFilter.GaussianBlur(14))).astype(np.float32) / 255
alpha = np.clip(soft * 2.2, 0, 1)
alpha[outside] = 0

rgba = np.dstack([im, alpha * 255]).astype(np.uint8)
fg_src = Image.fromarray(rgba, "RGBA")

# Radio seguro: 66dp de diámetro -> 33dp; dejamos 2dp extra de margen.
SAFE_R_DP = 31.0

def bg_color(px, py):
    return np.stack([np.clip(c[0] * px + c[1] * py + c[2], 0, 255) for c in coef], -1)

def render(size_px):
    """Devuelve (fondo, primer plano) de size_px x size_px (108dp)."""
    dp = size_px / 108.0
    scale = (SAFE_R_DP * dp) / rmax  # px de salida por px de origen
    # coordenadas de salida -> origen
    oy, ox = np.mgrid[0:size_px, 0:size_px].astype(np.float64) + 0.5
    sx = (ox - size_px / 2) / scale + cx
    sy = (oy - size_px / 2) / scale + cy
    bg = Image.fromarray(bg_color(sx, sy).astype(np.uint8), "RGB")
    fw, fh = int(round(W * scale)), int(round(H * scale))
    fg_scaled = fg_src.resize((fw, fh), Image.LANCZOS)
    fg = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    fg.paste(fg_scaled, (int(round(size_px / 2 - cx * scale)), int(round(size_px / 2 - cy * scale))))
    return bg, fg

def rounded_mask(size, radius_frac):
    from PIL import ImageDraw
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * radius_frac), fill=255)
    return m.resize((size, size), Image.LANCZOS)

import os
densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, d in densities.items():
    folder = os.path.join(OUT, f"mipmap-{name}")
    os.makedirs(folder, exist_ok=True)
    bg, fg = render(int(108 * d))
    bg.save(os.path.join(folder, "ic_launcher_background.png"), optimize=True)
    fg.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
    # Iconos heredados (48dp): la capa de 108dp recortada a los 72dp centrales.
    full = bg.convert("RGBA"); full.alpha_composite(fg)
    c = int(18 * d); legacy = full.crop((c, c, full.width - c, full.height - c)).resize((int(48 * d),) * 2, Image.LANCZOS)
    sq = legacy.copy(); sq.putalpha(rounded_mask(legacy.width, 0.18)); sq.save(os.path.join(folder, "ic_launcher.png"), optimize=True)
    rd = legacy.copy()
    from PIL import ImageDraw
    m = Image.new("L", (legacy.width * 4,) * 2, 0); ImageDraw.Draw(m).ellipse([0, 0, legacy.width * 4 - 1, legacy.width * 4 - 1], fill=255)
    rd.putalpha(m.resize(legacy.size, Image.LANCZOS)); rd.save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)

# Icono de tienda 512x512 (cuadrado completo, sin transparencia).
bg, fg = render(768)
full = bg.convert("RGBA"); full.alpha_composite(fg)
full.crop((128, 128, 640, 640)).convert("RGB").save(os.path.join(OUT, "..", "..", "..", "..", "docs", "icon.png"))

# Vista previa con varias máscaras para comprobar que no se corta nada.
prev = Image.new("RGB", (4 * 300 + 50, 330), (240, 240, 240))
from PIL import ImageDraw
bg, fg = render(432)
full = bg.convert("RGBA"); full.alpha_composite(fg)
masks = []
for kind in ["circle", "squircle", "rounded", "teardrop"]:
    m = Image.new("L", (432, 432), 0); dr = ImageDraw.Draw(m)
    inset = 18 * 4  # el launcher muestra los 72dp centrales
    box = [inset, inset, 432 - inset, 432 - inset]
    if kind == "circle": dr.ellipse(box, fill=255)
    elif kind == "squircle": dr.rounded_rectangle(box, radius=110, fill=255)
    elif kind == "rounded": dr.rounded_rectangle(box, radius=50, fill=255)
    else:
        dr.rounded_rectangle(box, radius=144, fill=255); dr.rectangle([216, 216, 432 - inset, 432 - inset], fill=255)
    masks.append(m)
for i, m in enumerate(masks):
    t = Image.new("RGBA", (432, 432), (240, 240, 240, 255)); t.paste(full, (0, 0), m)
    prev.paste(t.crop((72, 72, 360, 360)).convert("RGB"), (20 + i * 300, 20))
prev.save(sys.argv[3] if len(sys.argv) > 3 else "icon_preview.png")
