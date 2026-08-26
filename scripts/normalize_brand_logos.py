#!/usr/bin/env python3
"""Normalize audited brand logos into calendar-safe transparent 512px canvases.

The script intentionally never downloads, redraws, recolors, or crops non-transparent
artwork. It only removes transparent outer padding, scales proportionally with Lanczos,
and centers the existing mark on a transparent canvas.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image


CANVAS_SIZE = 512
STANDARD_ARTWORK_EDGE = 400  # 78.1%; preserves a >= 56px outer safety edge.
WIDE_WORDMARK_ARTWORK_EDGE = 430  # 84.0%; for the two approved horizontal wordmarks.
WIDE_WORDMARKS = {"brand_logo_cotti.png", "brand_logo_kcoffee.png"}
PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIRECTORY = PROJECT_ROOT / "assets/brand-logos/source"
OUTPUT_DIRECTORY = PROJECT_ROOT / "app/src/main/res/drawable-nodpi"


def artwork_edge(filename: str) -> int:
    return WIDE_WORDMARK_ARTWORK_EDGE if filename in WIDE_WORDMARKS else STANDARD_ARTWORK_EDGE


def trim_transparent_outer_padding(image: Image.Image) -> Image.Image:
    """Return the full non-transparent artwork without altering any non-transparent pixel."""
    rgba = image.convert("RGBA")
    bounds = rgba.getchannel("A").getbbox()
    if bounds is None:
        raise ValueError("Logo must contain non-transparent artwork")
    return rgba.crop(bounds)


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
        output = normalized_logo(Image.open(source_logo), artwork_edge(source_logo.name))
        output.save(output_logo, format="PNG", optimize=False)
        source_digest = hashlib.sha256(source_logo.read_bytes()).hexdigest()
        output_digest = hashlib.sha256(output_logo.read_bytes()).hexdigest()
        alpha_bounds = output.getchannel("A").getbbox()
        print(
            f"{source_logo.name} {output.size[0]}x{output.size[1]} alpha={alpha_bounds} "
            f"source_sha256={source_digest} output_sha256={output_digest}",
        )


if __name__ == "__main__":
    main()
