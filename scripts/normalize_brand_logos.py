#!/usr/bin/env python3
"""Normalize audited brand logos into calendar-safe transparent 512px canvases.

The script intentionally never downloads, redraws, recolors, or stretches artwork. It
may derive a display asset from the user-approved calendar preview by using documented
pixel coordinates and removing only edge-connected pale preview background pixels.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image


CANVAS_SIZE = 512
STANDARD_ARTWORK_EDGE = 430  # 84.0%; preserves a >= 41px outer safety edge.
WIDE_WORDMARK_ARTWORK_EDGE = 450  # 87.9%; keeps short horizontal marks readable.
WIDE_WORDMARKS = {"brand_logo_cotti.png", "brand_logo_kcoffee.png"}
PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIRECTORY = PROJECT_ROOT / "assets/brand-logos/source"
REFERENCE_PREVIEW = PROJECT_ROOT / "assets/brand-logos/reference/calendar-logo-reference.png"
APPROVED_MANNER = PROJECT_ROOT / "assets/brand-logos/reference/manner-user-approved.jpeg"
OUTPUT_DIRECTORY = PROJECT_ROOT / "app/src/main/res/drawable-nodpi"

# (left, top, right, bottom) in the 853x1843 user-approved old preview.
# These are intentionally narrow around the four confirmed display marks, not calendar UI.
REFERENCE_DERIVATIVES = {
    "brand_logo_arabica.png": (24, 1387, 125, 1484),
    "brand_logo_luckin.png": (140, 1378, 237, 1480),
    "brand_logo_cotti.png": (485, 1035, 580, 1098),
    "brand_logo_kcoffee.png": (711, 1400, 810, 1470),
    "brand_logo_manner.png": (26, 1556, 124, 1640),
    "brand_logo_hucoffee.png": (370, 1205, 468, 1300),
    "brand_logo_nowwa.png": (598, 1020, 694, 1120),
    "brand_logo_peets.png": (712, 1212, 810, 1305),
}


def artwork_edge(filename: str) -> int:
    return WIDE_WORDMARK_ARTWORK_EDGE if filename in WIDE_WORDMARKS else STANDARD_ARTWORK_EDGE


def trim_transparent_outer_padding(image: Image.Image) -> Image.Image:
    """Return the full non-transparent artwork without altering any non-transparent pixel."""
    rgba = image.convert("RGBA")
    bounds = rgba.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("Logo must contain non-transparent artwork")
    return rgba.crop(bounds)


def remove_edge_connected_pale_background(image: Image.Image) -> Image.Image:
    """Make only pale pixels connected to the crop edge transparent.

    This removes the preview's white calendar card, while retaining non-edge white logo
    details such as the lettering inside the dark Hu Coffee sign.
    """
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    seen: set[tuple[int, int]] = set()
    pending: list[tuple[int, int]] = []

    def pale(x: int, y: int) -> bool:
        red, green, blue, alpha = pixels[x, y]
        return alpha > 0 and red >= 205 and green >= 205 and blue >= 200

    for x in range(width):
        pending.extend(((x, 0), (x, height - 1)))
    for y in range(1, height - 1):
        pending.extend(((0, y), (width - 1, y)))
    while pending:
        x, y = pending.pop()
        if (x, y) in seen or not pale(x, y):
            continue
        seen.add((x, y))
        pixels[x, y] = (0, 0, 0, 0)
        if x > 0:
            pending.append((x - 1, y))
        if x < width - 1:
            pending.append((x + 1, y))
        if y > 0:
            pending.append((x, y - 1))
        if y < height - 1:
            pending.append((x, y + 1))
    return rgba


def audited_input(filename: str) -> tuple[Image.Image, str]:
    if filename == "brand_logo_manner.png":
        if not APPROVED_MANNER.exists():
            raise ValueError(f"Missing user-approved MANNER input: {APPROVED_MANNER}")
        return remove_edge_connected_pale_background(Image.open(APPROVED_MANNER)), "user-approved MANNER JPEG"
    if filename not in REFERENCE_DERIVATIVES:
        return Image.open(SOURCE_DIRECTORY / filename), "audited source"
    if not REFERENCE_PREVIEW.exists():
        raise ValueError(f"Missing user-approved reference preview: {REFERENCE_PREVIEW}")
    crop = Image.open(REFERENCE_PREVIEW).crop(REFERENCE_DERIVATIVES[filename])
    return remove_edge_connected_pale_background(crop), "user-approved preview derivative"


def normalized_logo(image: Image.Image, maximum_edge: int) -> Image.Image:
    artwork = trim_transparent_outer_padding(image)
    scale = min(maximum_edge / artwork.width, maximum_edge / artwork.height)
    scaled_size = (round(artwork.width * scale), round(artwork.height * scale))
    artwork = artwork.resize(scaled_size, Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    origin = ((CANVAS_SIZE - artwork.width) // 2, (CANVAS_SIZE - artwork.height) // 2)
    canvas.alpha_composite(artwork, origin)
    return canvas


def main() -> None:
    source_logos = sorted(SOURCE_DIRECTORY.glob("brand_logo_*.png"))
    if len(source_logos) != 12:
        raise ValueError(f"Expected 12 audited source logos, found {len(source_logos)}")

    for source_logo in source_logos:
        output_logo = OUTPUT_DIRECTORY / source_logo.name
        input_image, input_kind = audited_input(source_logo.name)
        output = normalized_logo(input_image, artwork_edge(source_logo.name))
        existing_pixels = (
            Image.open(output_logo).convert("RGBA").tobytes()
            if output_logo.exists()
            else None
        )
        if existing_pixels != output.tobytes():
            output.save(output_logo, format="PNG", optimize=False)
        input_path = APPROVED_MANNER if source_logo.name == "brand_logo_manner.png" else (
            REFERENCE_PREVIEW if source_logo.name in REFERENCE_DERIVATIVES else source_logo
        )
        source_digest = hashlib.sha256(input_path.read_bytes()).hexdigest()
        output_digest = hashlib.sha256(output_logo.read_bytes()).hexdigest()
        alpha_bounds = output.getchannel("A").getbbox()
        print(
            f"{source_logo.name} {output.size[0]}x{output.size[1]} alpha={alpha_bounds} "
            f"input={input_kind} source_sha256={source_digest} output_sha256={output_digest}",
        )


if __name__ == "__main__":
    main()
