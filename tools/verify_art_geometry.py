"""Check sealed casing edges, visible coplanar faces and exported light motion."""
import itertools
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/emulator_i86_crafted"
AXES = {
    "west": (0, -1, 1, 2), "east": (0, 1, 1, 2),
    "down": (1, -1, 0, 2), "up": (1, 1, 0, 2),
    "north": (2, -1, 0, 1), "south": (2, 1, 0, 1),
}
EPSILON = 0.000001


def model_elements(reference):
    data = json.loads((ASSETS / "models" / (reference.split(":")[1] + ".json")).read_text())
    if "elements" in data:
        return data["elements"]
    return model_elements(data["parent"])


def faces(elements, direction):
    axis, sign, a, b = AXES[direction]
    for index, element in enumerate(elements):
        if direction not in element["faces"] or "rotation" in element:
            continue
        lo, hi = element["from"], element["to"]
        yield index, (lo if sign < 0 else hi)[axis], (lo[a], lo[b], hi[a], hi[b])


def intersection(a, b):
    rect = (max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3]))
    return rect if rect[2] - rect[0] > EPSILON and rect[3] - rect[1] > EPSILON else None


def exposed(rect, plane, direction, elements):
    axis, sign, a, b = AXES[direction]
    outward = plane + sign * 0.00001
    occluders = []
    for element in elements:
        if "rotation" in element or direction not in element["faces"]:
            continue
        lo, hi = element["from"], element["to"]
        if lo[axis] < outward < hi[axis]:
            overlap = intersection(rect, (lo[a], lo[b], hi[a], hi[b]))
            if overlap:
                occluders.append(overlap)
    xs = sorted({rect[0], rect[2], *(value for box in occluders for value in (box[0], box[2]))})
    ys = sorted({rect[1], rect[3], *(value for box in occluders for value in (box[1], box[3]))})
    for left, right in itertools.pairwise(xs):
        for bottom, top in itertools.pairwise(ys):
            x, y = (left + right) / 2, (bottom + top) / 2
            if not any(lo_x < x < hi_x and lo_y < y < hi_y for lo_x, lo_y, hi_x, hi_y in occluders):
                return True
    return False


def overlaps(elements):
    issues = []
    for direction in AXES:
        for first, second in itertools.combinations(faces(elements, direction), 2):
            i, plane, rect = first
            j, other_plane, other_rect = second
            if abs(plane - other_plane) > EPSILON:
                continue
            shared = intersection(rect, other_rect)
            if shared and exposed(shared, plane, direction, elements):
                issues.append(f"{direction} faces {i}/{j} at {plane:g}")
    return issues


def sealed_edges(elements):
    for direction, (_, sign, _, _) in AXES.items():
        boundary = 0 if sign < 0 else 16
        rectangles = [rect for _, plane, rect in faces(elements, direction) if abs(plane - boundary) < EPSILON]
        for i in range(64):
            along = (i + 0.5) / 4
            for x, y in ((0.01, along), (15.99, along), (along, 0.01), (along, 15.99)):
                if not any(lo_x <= x <= hi_x and lo_y <= y <= hi_y for lo_x, lo_y, hi_x, hi_y in rectangles):
                    return direction
    return None


def check():
    issues = []
    checked = 0
    for path in sorted((ASSETS / "blockstates").glob("*.json")):
        data = json.loads(path.read_text())
        facings = ("north", "east", "south", "west", "up", "down") if path.stem in ("sign_port", "redstone_port") else ("north",)
        for facing in facings:
            for powered, active, input_mode in itertools.product((False, True), repeat=3):
                state = dict(facing=facing, powered=str(powered).lower(), active=str(active).lower(), input=str(input_mode).lower())
                selected = [part for part in data["multipart"] if all(state.get(key) == value for key, value in part["when"].items())]
                elements = [element for part in selected for element in model_elements(part["apply"]["model"])]
                label = f"{path.stem}/{facing}/power={powered}/active={active}/input={input_mode}"
                if not powered and not active:
                    gap = sealed_edges(elements)
                    if gap:
                        issues.append(label + ": open casing perimeter on " + gap)
                issues.extend(label + ": overlapping " + message for message in overlaps(elements))
                checked += 1
    if issues:
        summary = sorted({issue.split("/")[0] + ": " + issue.split(": ", 1)[1] for issue in issues})
        for issue in summary:
            print(issue)
        raise AssertionError(f"{len(issues)} geometry failures")
    for path in sorted((ASSETS / "textures/block").glob("light_*.png")):
        with Image.open(path) as image:
            size = image.width
            frames = image.height // size
            values = [sum(image.getpixel((size // 2, frame * size + size // 2))) for frame in range(frames)]
            span = max(values) - min(values)
            assert max(values) >= min(values) * 2, f"{path.name}: light motion must remain visible"
            assert max(abs(values[(i + 1) % frames] - values[i]) for i in range(frames)) < span / 2, f"{path.name}: abrupt animation step"
            peaks = [max(range(size), key=lambda y: sum(image.getpixel((size // 2, frame * size + y)))) for frame in range(frames)]
            assert max(peaks) - min(peaks) >= size * 0.75, f"{path.name}: scan band does not travel"
    print(f"Geometry checks passed: {checked} state samples; sealed casing edges and no exposed coplanar overlaps")
    print("Light checks passed: visible travelling bands with bounded frame changes and a continuous loop")


if __name__ == "__main__":
    check()
