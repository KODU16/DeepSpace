"""Compress and validate the six 4K faces produced from NASA's 16K star map."""

from __future__ import annotations

import argparse
import itertools
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageFilter


SOURCE_NAMES = {
    "east": "right.png",
    "west": "left.png",
    "up": "up.png",
    "down": "down.png",
    "south": "front.png",
    "north": "back.png",
}

TRANSFORMS = (
    None,
    Image.Transpose.FLIP_LEFT_RIGHT,
    Image.Transpose.FLIP_TOP_BOTTOM,
    Image.Transpose.ROTATE_90,
    Image.Transpose.ROTATE_180,
    Image.Transpose.ROTATE_270,
    Image.Transpose.TRANSPOSE,
    Image.Transpose.TRANSVERSE,
)

# Each pair describes the same geometric cube edge in the renderer's UV layout.
SEAMS = (
    ("north", "left", "west", "left"),
    ("north", "right", "east", "left"),
    ("north", "top", "up", "top"),
    ("north", "bottom", "down", "top"),
    ("south", "left", "west", "right"),
    ("south", "right", "east", "right"),
    ("south", "top", "up", "bottom"),
    ("south", "bottom", "down", "bottom"),
    ("west", "top", "up", "left"),
    ("west", "bottom", "down", "left"),
    ("east", "top", "up", "right"),
    ("east", "bottom", "down", "right"),
)


def edge(image: np.ndarray, side: str) -> np.ndarray:
    return {
        "left": image[:, 0],
        "right": image[:, -1],
        "top": image[0],
        "bottom": image[-1],
    }[side]


def print_seam_report(images: dict[str, Image.Image], label: str) -> float:
    arrays = {
        name: np.asarray(image.convert("RGB"), dtype=np.int16)
        for name, image in images.items()
    }
    worst = 0.0
    print(f"{label} seam differences:")
    for first, first_edge, second, second_edge in SEAMS:
        a = edge(arrays[first], first_edge)
        b = edge(arrays[second], second_edge)
        direct = float(np.abs(a - b).mean())
        reverse = float(np.abs(a - b[::-1]).mean())
        worst = max(worst, direct)
        direction = "direct" if direct <= reverse else "reversed-mismatch"
        print(
            f"  {first}.{first_edge} <-> {second}.{second_edge}: "
            f"{direct:.4f} ({direction})"
        )
    return worst


def solve_orientations(images: dict[str, Image.Image]) -> dict[str, Image.Image]:
    names = tuple(SOURCE_NAMES)
    previews = {
        name: image.resize((96, 96), Image.Resampling.LANCZOS)
        for name, image in images.items()
    }
    candidates: dict[str, list[Image.Image]] = {}
    candidate_edges = {}
    for name, preview in previews.items():
        candidates[name] = [
            preview.copy() if transform is None else preview.transpose(transform)
            for transform in TRANSFORMS
        ]
        for index, candidate in enumerate(candidates[name]):
            array = np.asarray(candidate.convert("RGB"), dtype=np.int16)
            for side in ("left", "right", "top", "bottom"):
                candidate_edges[name, index, side] = edge(array, side)

    # Fix north to remove equivalent whole-cube rotations from the search space.
    best_score = float("inf")
    best = None
    variable_names = tuple(name for name in names if name != "north")
    for choices in itertools.product(range(len(TRANSFORMS)), repeat=len(variable_names)):
        selected = {"north": 0, **dict(zip(variable_names, choices))}
        score = 0.0
        for first, first_edge, second, second_edge in SEAMS:
            a = candidate_edges[first, selected[first], first_edge]
            b = candidate_edges[second, selected[second], second_edge]
            score += float(np.abs(a - b).mean())
            if score >= best_score:
                break
        if score < best_score:
            best_score = score
            best = selected

    assert best is not None
    print("Selected face transforms:", best)
    return {
        name: image.copy()
        if TRANSFORMS[best[name]] is None
        else image.transpose(TRANSFORMS[best[name]])
        for name, image in images.items()
    }


def set_edge(image: np.ndarray, side: str, values: np.ndarray) -> None:
    if side == "left":
        image[:, 0] = values
    elif side == "right":
        image[:, -1] = values
    elif side == "top":
        image[0] = values
    else:
        image[-1] = values


def unify_edges(images: dict[str, Image.Image]) -> dict[str, Image.Image]:
    arrays = {
        name: np.asarray(image.convert("RGB"), dtype=np.uint8).copy()
        for name, image in images.items()
    }
    # Iterate twice so the three-face cube corners converge to one identical value.
    for _ in range(2):
        for first, first_edge, second, second_edge in SEAMS:
            a = edge(arrays[first], first_edge).astype(np.uint16)
            b = edge(arrays[second], second_edge).astype(np.uint16)
            shared = ((a + b + 1) // 2).astype(np.uint8)
            set_edge(arrays[first], first_edge, shared)
            set_edge(arrays[second], second_edge, shared)
    return {name: Image.fromarray(array, "RGB") for name, array in arrays.items()}


def adjust_luminance(image: Image.Image) -> Image.Image:
    # Lift the photographic midtones while retaining a nearly black deep-space floor.
    lut = [
        min(255, round(255.0 * ((value / 255.0) ** 0.78) * 1.04))
        for value in range(256)
    ]
    return image.convert("RGB").point(lut * 3)


def enlarge_stars(image: Image.Image, diameter: int) -> Image.Image:
    """Expand only compact high-contrast lights while leaving the Milky Way scale unchanged."""
    if diameter <= 1:
        return image
    if diameter % 2 == 0:
        raise ValueError("Star diameter must be an odd number")

    source = np.asarray(image.convert("RGB"), dtype=np.int16)
    background = np.asarray(
        image.filter(ImageFilter.GaussianBlur(radius=2.0)),
        dtype=np.int16,
    )
    brightness = source.max(axis=2)
    local_background = background.max(axis=2)
    seeds = (brightness >= 24) & ((brightness - local_background) >= 9)

    star_pixels = np.where(seeds[:, :, None], source, 0).astype(np.uint8)
    expanded = Image.fromarray(star_pixels, "RGB")
    expanded = expanded.filter(ImageFilter.MaxFilter(diameter))
    expanded = expanded.filter(ImageFilter.GaussianBlur(radius=0.55))
    expanded = ImageChops.lighter(image, expanded)

    mask = Image.fromarray((seeds.astype(np.uint8) * 255), "L")
    mask = mask.filter(ImageFilter.MaxFilter(diameter))
    mask = mask.filter(ImageFilter.GaussianBlur(radius=0.45))

    # Keep an unmodified border so a star cannot be clipped into a cube-shaped seam.
    edge_width = diameter + 2
    height, width = seeds.shape
    y_distance = np.minimum(np.arange(height), np.arange(height)[::-1])
    x_distance = np.minimum(np.arange(width), np.arange(width)[::-1])
    edge_fade = np.minimum(y_distance[:, None], x_distance[None, :])
    edge_fade = np.clip(edge_fade / edge_width, 0.0, 1.0)
    faded_mask = (
        np.asarray(mask, dtype=np.float32) * edge_fade
    ).round().astype(np.uint8)
    return Image.composite(expanded, image, Image.fromarray(faded_mask, "L"))


def build_shared_palette(images: dict[str, Image.Image], colors: int) -> Image.Image:
    previews = [image.resize((768, 768), Image.Resampling.LANCZOS) for image in images.values()]
    sample = Image.new("RGB", (768 * 3, 768 * 2))
    for index, preview in enumerate(previews):
        sample.paste(preview, ((index % 3) * 768, (index // 3) * 768))
    return sample.quantize(colors=colors, method=Image.Quantize.MEDIANCUT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--colors", type=int, default=160)
    parser.add_argument("--format", choices=("png", "jpg"), default="jpg")
    parser.add_argument("--quality", type=int, default=72)
    parser.add_argument("--star-diameter", type=int, default=5)
    args = parser.parse_args()

    images = {
        face: Image.open(args.input / filename).convert("RGB")
        for face, filename in SOURCE_NAMES.items()
    }
    if any(image.size != (4096, 4096) for image in images.values()):
        raise ValueError("Every skybox face must remain 4096x4096")

    images = solve_orientations(images)
    images = {name: adjust_luminance(image) for name, image in images.items()}
    images = {
        name: enlarge_stars(image, args.star_diameter)
        for name, image in images.items()
    }
    images = unify_edges(images)
    raw_worst = print_seam_report(images, "Oriented raw")

    args.output.mkdir(parents=True, exist_ok=True)
    compressed = {}
    if args.format == "png":
        palette = build_shared_palette(images, args.colors)
        for name, image in images.items():
            result = image.quantize(palette=palette, dither=Image.Dither.NONE)
            target = args.output / f"space_skybox_{name}.png"
            result.save(target, optimize=True, compress_level=9)
            compressed[name] = Image.open(target).convert("RGB")
    else:
        for name, image in images.items():
            target = args.output / f"space_skybox_{name}.jpg"
            image.save(
                target,
                quality=args.quality,
                subsampling=0,
                optimize=True,
                progressive=True,
            )
            compressed[name] = Image.open(target).convert("RGB")

    compressed_worst = print_seam_report(compressed, "Compressed")
    total = sum(
        (args.output / f"space_skybox_{name}.{args.format}").stat().st_size
        for name in SOURCE_NAMES
    )
    print(f"Total texture bytes: {total}")
    if raw_worst > 0.05 or compressed_worst > 3.0:
        raise RuntimeError("Cube edge mismatch exceeds the accepted threshold")


if __name__ == "__main__":
    main()
