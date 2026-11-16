#!/usr/bin/env python3
"""Render every raster app icon from one master design ("Sonar" brand mark).

Ink ground, soft mint glow, a radar-sweep wedge, two faint sonar rings, and the
brand mark: a ring + centre dot in the in-app mark's 24/2/8 proportions.

    python3 -m venv .venv && .venv/bin/pip install numpy pillow
    .venv/bin/python tools/icons/render_icons.py        # from the repo root (macOS: needs iconutil)

Writes: iOS AppIcon + LaunchMark, desktop window/tray PNGs + .icns/.ico, the MSIX
tile logos (Microsoft Store), and the Play Store icon. The Android launcher icon is vector (app/src/main/res/drawable/
ic_launcher_*.xml) and is maintained by hand with the same geometry.
"""
import os
import shutil
import subprocess
import tempfile

import numpy as np
from PIL import Image

INK = (6, 8, 12)
MINT = (92, 242, 192)
SWEEP = 0.34


def _grid(n):
    y, x = np.mgrid[0:n, 0:n].astype(np.float64)
    c = (n - 1) / 2.0
    return x - c, y - c


def _ring(r, rad, half, aa):
    return np.clip(half - np.abs(r - rad) + aa, 0, aa * 2) / (aa * 2)


def _disc(r, rad, aa):
    return np.clip(rad - r + aa, 0, aa * 2) / (aa * 2)


def _over(dst, rgb, a):
    for i in range(3):
        dst[..., i] = dst[..., i] * (1 - a) + rgb[i] * a


def render(size, *, ground=True, sweep=True, echoes=True, mark_scale=1.0, shape=None, pad=0.0):
    """shape=None: full-bleed square; 'squircle': macOS-style rounded square inside `pad`."""
    ss = 4
    n = size * ss
    x, y = _grid(n)
    r = np.hypot(x, y)
    S = n * (1 - 2 * pad)
    aa = 0.75 * ss
    img = np.zeros((n, n, 3))
    alpha = np.ones((n, n)) if ground else np.zeros((n, n))
    if ground:
        img[...] = INK
        _over(img, MINT, np.clip(1 - r / (0.58 * S), 0, 1) ** 2.2 * 0.30)          # glow
        if sweep:                                                                  # radar sweep
            ang = (np.degrees(np.arctan2(y, x)) + 90) % 360                         # 0 = up, clockwise
            lead = 52.0
            t = np.where(ang <= lead, ang / lead, 0.0)
            w = t ** 1.4 * SWEEP * np.clip(1.1 - r / (0.47 * S), 0, 1) * np.clip((r - 0.22 * S) / (0.03 * S), 0, 1)
            _over(img, MINT, w)
        if echoes:                                                                 # sonar rings
            _over(img, (255, 255, 255), _ring(r, 0.36 * S, 0.0035 * S, aa) * 0.10)
            _over(img, (255, 255, 255), _ring(r, 0.47 * S, 0.0035 * S, aa) * 0.06)
    D = 0.50 * S * mark_scale                                                      # ring diameter
    stroke = D / 12
    ring_a = _ring(r, D / 2 - stroke / 2, stroke / 2, aa)
    dot_a = _disc(r, D / 6, aa)
    if ground:
        _over(img, MINT, np.clip(1 - r / (0.20 * S * mark_scale), 0, 1) ** 2 * 0.35)
    _over(img, MINT, ring_a)
    _over(img, MINT, dot_a)
    if not ground:
        alpha = np.clip(ring_a + dot_a, 0, 1)
    if shape == 'squircle':
        half, e = S / 2, 5.0
        m = (np.abs(x) / half) ** e + (np.abs(y) / half) ** e
        alpha = alpha * np.clip((1 - m) * half / (e * aa) + 0.5, 0, 1)
    rgba = np.dstack([img, alpha * 255]).clip(0, 255).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA').resize((size, size), Image.LANCZOS)


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    p = lambda *a: os.path.join(root, *a)
    xc = p('ios', 'CrossDrop', 'Resources', 'Assets.xcassets')

    render(1024).convert('RGB').save(os.path.join(xc, 'AppIcon.appiconset', 'icon-1024.png'))   # no alpha
    for scale, px in [(1, 88), (2, 176), (3, 264)]:
        render(px, ground=False).save(os.path.join(xc, 'LaunchMark.imageset', f'LaunchMark@{scale}x.png'))
    render(512).convert('RGB').save(p('store-assets', 'crossdrop_icon.png'))
    render(256, shape='squircle', pad=0.06).save(p('desktop', 'src', 'main', 'resources', 'icon.png'))
    render(44, shape='squircle', pad=0.02, echoes=False, sweep=False, mark_scale=1.2).save(
        p('desktop', 'src', 'main', 'resources', 'tray.png'))

    # Microsoft Store (MSIX) tile logos: opaque, full bleed. The wide tile centres the square art on ink.
    msix = p('desktop', 'packaging', 'msix', 'Assets')
    render(150).convert('RGB').save(os.path.join(msix, 'Square150x150Logo.png'))
    render(44, echoes=False, sweep=False, mark_scale=1.15).convert('RGB').save(os.path.join(msix, 'Square44x44Logo.png'))
    render(50, echoes=False, sweep=False, mark_scale=1.15).convert('RGB').save(os.path.join(msix, 'StoreLogo.png'))
    wide = Image.new('RGB', (310, 150), INK)
    wide.paste(render(150).convert('RGB'), (80, 0))
    wide.save(os.path.join(msix, 'Wide310x150Logo.png'))

    sizes = [16, 24, 32, 48, 64, 128, 256]
    ico = [render(s, shape='squircle', pad=0.03, echoes=s >= 48, sweep=s >= 48,
                  mark_scale=1.15 if s <= 32 else 1.0) for s in sizes]
    ico[-1].save(p('desktop', 'packaging', 'CrossDrop.ico'), sizes=[(s, s) for s in sizes], append_images=ico[:-1])

    if shutil.which('iconutil'):
        with tempfile.TemporaryDirectory() as tmp:
            iconset = os.path.join(tmp, 'CrossDrop.iconset')
            os.mkdir(iconset)
            for base in [16, 32, 128, 256, 512]:
                for scale in [1, 2]:
                    px = base * scale
                    small = px <= 32
                    name = f'icon_{base}x{base}' + ('@2x' if scale == 2 else '') + '.png'
                    render(px, shape='squircle', pad=0.098, echoes=not small, sweep=not small,
                           mark_scale=1.15 if small else 1.0).save(os.path.join(iconset, name))
            subprocess.run(['iconutil', '-c', 'icns', iconset, '-o', p('desktop', 'packaging', 'CrossDrop.icns')], check=True)
    else:
        print('iconutil not found (macOS only) — skipped CrossDrop.icns')
    print('icons written')


if __name__ == '__main__':
    main()
