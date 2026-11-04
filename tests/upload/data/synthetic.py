"""How synthetic-stage.png was made (NOT run by the gate; needs Pillow).

A deterministic test picture for readability measure(), moved here from v1's
tests/photo-upload/synthetic.py (2026-10-31) with v1's stage() added: picture() is 2000x1200, so
v1 scaled it (x0.96, LANCZOS) and centre-cropped it to the 1920x1080 stage before measuring.
synthetic-stage.png is that stage, saved losslessly, so tests/java/ReadabilityTest.java measures
exactly the pixels v1 measured without Pillow's resampling. synthetic-measure.json holds what the
Space gallery's numpy measure.py (a working copy, not in this repository)
gave for picture() on 2026-10-31 (Pillow 13.0.0); v1's readability.measure() equalled it, and so
did v1's measure() of this PNG (checked on 2026-10-31 with Pillow 12.2.0 before v1 was removed).

    python3 synthetic.py synthetic-stage.png
"""
import sys


def picture():
    from PIL import Image, ImageDraw
    w, h = 2000, 1200   # not 1920x1080: measure() must scale (x0.96) and centre-crop
    r = Image.linear_gradient('L').resize((w, h))
    g = Image.linear_gradient('L').rotate(90).resize((w, h))
    b = Image.radial_gradient('L').resize((w, h))
    im = Image.merge('RGB', (r, g, b))
    d = ImageDraw.Draw(im)
    for i in range(40):   # hard edges and near-black / near-white patches (both sRGB branches)
        x, y = i * 50, (i * 37) % 1100
        d.rectangle([x, y, x + 40, y + 60], fill=((i * 61) % 256, (i * 97) % 256, (i * 13) % 256))
    d.rectangle([1400, 0, 2000, 200], fill=(250, 250, 248))
    d.rectangle([0, 1000, 500, 1200], fill=(3, 6, 9))
    return im


def stage(img):
    """v1's readability.stage(): the 1920x1080 centred cover crop the TV shows."""
    from PIL import Image
    img = img.convert('RGB')
    w, h = img.size
    s = max(1920 / w, 1080 / h)
    if (round(w * s), round(h * s)) != (w, h):
        img = img.resize((round(w * s), round(h * s)), Image.Resampling.LANCZOS)
    x0, y0 = (img.width - 1920) // 2, (img.height - 1080) // 2
    return img.crop((x0, y0, x0 + 1920, y0 + 1080))


if __name__ == '__main__':
    stage(picture()).save(sys.argv[1], optimize=True)
