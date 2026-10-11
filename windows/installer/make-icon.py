"""Regenerates windows/TapDrop.Tray/TapDrop.ico (needs Pillow)."""
from pathlib import Path

from PIL import Image, ImageDraw

SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]
OUT = Path(__file__).resolve().parents[1] / "TapDrop.Tray" / "TapDrop.ico"


def render(size: int) -> Image.Image:
    scale = 8
    s = size * scale
    top, bottom = (59, 130, 246), (79, 70, 229)
    gradient = Image.new("RGBA", (s, s))
    pixels = gradient.load()
    for y in range(s):
        t = y / (s - 1)
        color = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,)
        for x in range(s):
            pixels[x, y] = color
    mask = Image.new("L", (s, s), 0)
    inset = round(s * (0.02 if size >= 32 else 0.0))
    ImageDraw.Draw(mask).rounded_rectangle(
        (inset, inset, s - 1 - inset, s - 1 - inset), radius=round(s * 0.22), fill=255)
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    image.paste(gradient, (0, 0), mask)

    draw = ImageDraw.Draw(image)
    white = (255, 255, 255, 255)
    stroke = s * (0.13 if size <= 24 else 0.11)
    cx = s / 2
    # Arrow shaft and head pointing down.
    draw.rectangle((cx - stroke / 2, s * 0.18, cx + stroke / 2, s * 0.52), fill=white)
    head = s * 0.22
    draw.polygon([(cx - head, s * 0.42), (cx + head, s * 0.42), (cx, s * 0.66)], fill=white)
    # Tray the arrow drops into.
    left, right, base, lip = s * 0.22, s * 0.78, s * 0.80, s * 0.66
    draw.line([(left, lip), (left, base), (right, base), (right, lip)], fill=white,
              width=round(stroke), joint="curve")
    for x in (left, right):
        r = stroke / 2
        draw.ellipse((x - r, lip - r, x + r, lip + r), fill=white)
        draw.ellipse((x - r, base - r, x + r, base + r), fill=white)
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    images = [render(size) for size in SIZES]
    images[-1].save(OUT, format="ICO", sizes=[(n, n) for n in SIZES], append_images=images[:-1])
    print(OUT)


if __name__ == "__main__":
    main()
