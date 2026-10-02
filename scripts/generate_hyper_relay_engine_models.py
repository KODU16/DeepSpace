"""Generate emissive engine model faces from the existing atlas without editing the texture."""

import colorsys
import copy
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/deepspace"
MODEL = ASSETS / "models/block/hyper_relay_engine.json"


def rotated_uv(a, b, rotation):
    # Match Minecraft BlockFaceUV's clockwise corner-index rotation.
    return ((a, b), (b, 1 - a), (1 - a, 1 - b), (1 - b, a))[rotation // 90]


def color_group(rgba):
    if not rgba[3]:
        return None
    hue, saturation, _ = colorsys.rgb_to_hsv(*(value / 255 for value in rgba[:3]))
    # Saturation distinguishes the colored indicators from the blue-grey metal.
    if saturation < 0.45:
        return None
    if 0.45 <= hue < 0.55:
        return "cyan"
    if 0.55 <= hue <= 0.72:
        return "blue"
    return None


def rectangles(mask):
    """Merge equally lit pixels so selective full-bright faces remain inexpensive."""
    height, width = len(mask), len(mask[0])
    used = [[False] * width for _ in range(height)]
    for y in range(height):
        for x in range(width):
            if used[y][x]:
                continue
            bright = mask[y][x]
            right = x + 1
            while right < width and not used[y][right] and mask[y][right] == bright:
                right += 1
            bottom = y + 1
            while bottom < height and all(
                not used[bottom][column] and mask[bottom][column] == bright
                for column in range(x, right)
            ):
                bottom += 1
            for row in range(y, bottom):
                for column in range(x, right):
                    used[row][column] = True
            yield x / width, y / height, right / width, bottom / height, bright


def face_position(element, direction, a, b):
    """Use the six FaceInfo corner orientations, including mirrored north/east/down."""
    low, high = element["from"], element["to"]
    x = low[0] + a * (high[0] - low[0])
    y = high[1] - b * (high[1] - low[1])
    z = low[2] + a * (high[2] - low[2])
    return {
        "north": (high[0] - a * (high[0] - low[0]), y, low[2]),
        "south": (x, y, high[2]),
        "west": (low[0], y, z),
        "east": (high[0], y, high[2] - a * (high[2] - low[2])),
        "up": (x, high[1], low[2] + b * (high[2] - low[2])),
        "down": (x, low[1], high[2] - b * (high[2] - low[2])),
    }[direction]


def generate(source, atlas, groups):
    result = copy.deepcopy(source)
    result["elements"] = []
    emissive_count = 0
    for element in source["elements"]:
        assert "rotation" not in element, "Rotated elements require an updated face-position mapping"
        remaining = copy.deepcopy(element)
        split_faces = []
        for direction, face in element["faces"].items():
            u0, v0, u1, v1 = face["uv"]
            rotation = face.get("rotation", 0)
            columns = round(abs(u1 - u0) * atlas.width / 16)
            rows = round(abs(v1 - v0) * atlas.height / 16)
            if rotation in (90, 270):
                columns, rows = rows, columns
            assert columns > 0 and rows > 0
            mask = []
            for row in range(rows):
                values = []
                for column in range(columns):
                    a, b = rotated_uv((column + 0.5) / columns, (row + 0.5) / rows, rotation)
                    pixel = (int((u0 + a * (u1 - u0)) * atlas.width / 16),
                             int((v0 + b * (v1 - v0)) * atlas.height / 16))
                    values.append(color_group(atlas.getpixel(pixel)) in groups)
                mask.append(values)
            if not any(any(row) for row in mask):
                continue
            del remaining["faces"][direction]
            area = 0.0
            for a0, b0, a1, b1, bright in rectangles(mask):
                area += (a1 - a0) * (b1 - b0)
                positions = [face_position(element, direction, a, b)
                             for a in (a0, a1) for b in (b0, b1)]
                tile = copy.deepcopy(face)
                uv_corners = [rotated_uv(a, b, rotation) for a in (a0, a1) for b in (b0, b1)]
                ua, ub = min(p[0] for p in uv_corners), max(p[0] for p in uv_corners)
                va, vb = min(p[1] for p in uv_corners), max(p[1] for p in uv_corners)
                tile["uv"] = [u0 + ua * (u1 - u0), v0 + va * (v1 - v0),
                              u0 + ub * (u1 - u0), v0 + vb * (v1 - v0)]
                if bright:
                    # Quad lightmap values affect the texture only, never the world's light engine.
                    tile["neoforge_data"] = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
                    emissive_count += 1
                split_faces.append({
                    "from": [min(point[axis] for point in positions) for axis in range(3)],
                    "to": [max(point[axis] for point in positions) for axis in range(3)],
                    "shade": False if bright else element.get("shade", True),
                    "faces": {direction: tile},
                })
            assert abs(area - 1.0) < 1e-9, "Split faces must cover their original face exactly"
        if remaining["faces"]:
            result["elements"].append(remaining)
        result["elements"].extend(split_faces)
    assert emissive_count > 0
    return result, emissive_count


def main():
    source = json.loads(MODEL.read_text(encoding="utf-8"))
    with Image.open(ASSETS / "textures/block/hyper_relay_engine.png") as image:
        atlas = image.convert("RGBA")
    for suffix, groups in (("ready", {"cyan"}), ("preparing", {"cyan", "blue"})):
        model, emissive_count = generate(source, atlas, groups)
        output = MODEL.with_name(f"hyper_relay_engine_{suffix}.json")
        # Compact elements retain readable JSON without expanding each UV number onto its own line.
        header = {key: value for key, value in model.items() if key != "elements"}
        text = json.dumps(header, ensure_ascii=False, indent=2).rstrip()[:-1]
        elements = ",\n".join("    " + json.dumps(element, separators=(",", ":")) for element in model["elements"])
        output.write_text(text + ',\n  "elements": [\n' + elements + '\n  ]\n}\n', encoding="utf-8")
        print(f"{output.name}: {len(model['elements'])} elements, {emissive_count} emissive faces")


if __name__ == "__main__":
    main()
