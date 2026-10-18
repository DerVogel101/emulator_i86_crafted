"""Export the hand-authored cuboids and pixel palette; no image-generation service."""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/emulator_i86_crafted"
NS = "emulator_i86_crafted:"
BLOCKS = (
    "mainframe_controller", "atomic_clock", "interrupt_controller", "access_terminal",
    "gpu_array", "gpio_controller", "harddisk_array", "ender_tunnel_array",
    "integrated_storage", "power_supply", "reset_port", "nmi_port", "test_port",
    "super_structure", "redstone_port", "sign_port", "rom_burner", "keyboard_controller", "keyboard",
)
COLORS = {
    "cyan": (85, 218, 225), "mint": (121, 227, 179), "amber": (244, 181, 86),
    "violet": (179, 133, 239), "red": (242, 98, 93), "blue": (111, 173, 244),
}
ACCENTS = dict(zip(BLOCKS, (
    "cyan", "cyan", "amber", "mint", "blue", "cyan", "amber", "violet",
    "mint", "amber", "red", "amber", "blue", "cyan", "cyan", "mint", "amber", "mint", "cyan",
)))
MATERIALS = {
    "body": (31, 38, 47), "frame": (55, 65, 76), "trim": (105, 119, 130),
    "black": (10, 15, 22), "panel": (40, 49, 59), "copper": (159, 106, 69),
    "gold": (196, 157, 82), "ceramic": (155, 170, 173), "pcb": (25, 67, 65),
    "screen": (12, 28, 35), "cyan_dim": (35, 82, 87), "violet_dim": (55, 39, 76),
    "red_dim": (111, 43, 46), "amber_dim": (123, 83, 39), "blue_dim": (46, 70, 105),
    "screw": (78, 91, 102),
}
UV = {name: [(i % 4) * 4, (i // 4) * 4, (i % 4 + 1) * 4, (i // 4 + 1) * 4]
      for i, name in enumerate(MATERIALS)}


def export_json(folder, name, data):
    path = ASSETS / folder / (name + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def pixel_textures():
    atlas = Image.new("RGB", (64, 64))
    draw = ImageDraw.Draw(atlas)
    for i, (name, color) in enumerate(MATERIALS.items()):
        x, y = i % 4 * 16, i // 4 * 16
        draw.rectangle((x, y, x + 15, y + 15), fill=color)
        light = tuple(min(255, c + 10) for c in color)
        shade = tuple(max(0, c - 9) for c in color)
        draw.line((x, y, x + 15, y), fill=light)
        draw.line((x, y, x, y + 15), fill=light)
        draw.line((x + 15, y, x + 15, y + 15), fill=shade)
        draw.line((x, y + 15, x + 15, y + 15), fill=shade)
        if name in ("body", "panel", "frame"):
            draw.line((x + 3, y + 5, x + 6, y + 5), fill=light)
            draw.point((x + 12, y + 12), fill=shade)
        if name == "screw":
            draw.rectangle((x + 4, y + 7, x + 11, y + 8), fill=MATERIALS["black"])
        if name == "pcb":
            draw.line((x + 2, y + 3, x + 12, y + 3, x + 12, y + 12), fill=(49, 98, 84), width=1)
    atlas.save(ASSETS / "textures/block/materials.png")
    frame = Image.new("RGB", (32, 32), MATERIALS["frame"])
    draw = ImageDraw.Draw(frame)
    draw.rectangle((0, 0, 31, 31), outline=MATERIALS["trim"])
    draw.rectangle((3, 3, 28, 28), outline=MATERIALS["black"])
    for x in (1, 29):
        for y in (1, 29):
            draw.rectangle((x, y, x + 1, y + 1), fill=MATERIALS["screw"])
            draw.point((x, y), fill=MATERIALS["black"])
    frame.save(ASSETS / "textures/block/frame.png")
    for side in ("side", "back"):
        image = Image.new("RGB", (32, 32), MATERIALS["body"])
        draw = ImageDraw.Draw(image)
        draw.rectangle((1, 1, 30, 30), outline=MATERIALS["frame"])
        draw.rectangle((5, 5, 26, 26), fill=MATERIALS["panel"], outline=MATERIALS["black"])
        for y in (9, 13, 17, 21):
            draw.rectangle((9, y, 22, y + 1), fill=MATERIALS["black"])
            draw.line((9, y - 1, 22, y - 1), fill=MATERIALS["frame"])
        if side == "side":
            draw.line((4, 27, 27, 27, 27, 4), fill=MATERIALS["copper"])
        image.save(ASSETS / f"textures/block/casing_{side}.png")
    for name, color in COLORS.items():
        strip = Image.new("RGB", (16, 256))
        for frame in range(16):
            for y in range(16):
                wave = (0.5 + 0.5 * math.cos((frame - y) * math.tau / 16)) ** 3
                brightness = 0.25 + 0.75 * wave
                for x in range(16):
                    bevel = 0.82 if x in (0, 15) or y in (0, 15) else 1
                    strip.putpixel((x, y + frame * 16), tuple(round(c * brightness * bevel) for c in color))
        strip.save(ASSETS / f"textures/block/light_{name}.png")
        (ASSETS / f"textures/block/light_{name}.png.mcmeta").write_text(json.dumps({
            "animation": {"width": 16, "height": 16, "frametime": 3, "interpolate": True}
        }, indent=2) + "\n", encoding="utf-8")
    for style in ("terminal", "clock", "tunnel", "controller"):
        image = Image.new("RGB", (32, 512), MATERIALS["screen"])
        draw = ImageDraw.Draw(image)
        color = COLORS["violet" if style == "tunnel" else "cyan"]
        dim = tuple(c // 4 for c in color)
        for frame in range(16):
            y0 = frame * 32
            for y in range(3, 31, 4):
                draw.line((2, y0 + y, 29, y0 + y), fill=(15, 34, 42))
            if style == "terminal":
                for row, width in enumerate((17, 10, 21, 14)):
                    draw.line((4, y0 + 5 + row * 4, width, y0 + 5 + row * 4), fill=color if row == 0 else dim)
                cursor = tuple(round(c * (0.25 + 0.75 * math.sin(frame * math.pi / 16) ** 2)) for c in color)
                draw.rectangle((4, y0 + 24, 7, y0 + 25), fill=cursor)
                scan = 3 + frame * 26 // 16
                draw.line((3, y0 + scan, 28, y0 + scan), fill=tuple(c // 2 for c in cursor))
            elif style == "clock":
                draw.line([(3, y0 + 19), (8, y0 + 19), (8, y0 + 10), (13, y0 + 10),
                           (13, y0 + 19), (19, y0 + 19), (19, y0 + 10), (25, y0 + 10),
                           (25, y0 + 19), (29, y0 + 19)], fill=color, width=2)
                draw.rectangle((3 + frame * 26 // 16, y0 + 23, 5 + frame * 26 // 16, y0 + 25), fill=color)
            elif style == "tunnel":
                for inset in (3, 7, 11):
                    intensity = 0.15 + 0.85 * (1 - math.cos((frame - inset) * math.tau / 16)) / 2
                    draw.rectangle((inset, y0 + inset, 31 - inset, y0 + 31 - inset),
                                   outline=tuple(round(c * intensity) for c in color), width=1)
            else:
                draw.rectangle((8, y0 + 8, 23, y0 + 23), outline=color, width=2)
                draw.rectangle((12, y0 + 12, 19, y0 + 19), fill=dim, outline=color)
                for step in range(4):
                    p = 10 + step * 4
                    draw.line((p, y0 + 2, p, y0 + 7), fill=color if step == frame // 4 else dim)
                    draw.line((p, y0 + 24, p, y0 + 29), fill=color if step == (frame // 4 + 2) % 4 else dim)
                    draw.line((2, y0 + p, 7, y0 + p), fill=color if step == (frame // 4 + 1) % 4 else dim)
                    draw.line((24, y0 + p, 29, y0 + p), fill=color if step == (frame // 4 + 3) % 4 else dim)
        image.save(ASSETS / f"textures/block/display_{style}.png")
        (ASSETS / f"textures/block/display_{style}.png.mcmeta").write_text(json.dumps({
            "animation": {"width": 32, "height": 32, "frametime": 3, "interpolate": True}
        }, indent=2) + "\n", encoding="utf-8")


def box(lo, hi, material="panel", glow=False, rotation=None):
    face = {"texture": "#light" if glow else "#materials", "uv": [0, 0, 16, 16] if glow else UV[material]}
    element = {"from": list(lo), "to": list(hi), "faces": {d: dict(face) for d in ("north", "south", "east", "west", "up", "down")}}
    for direction, axis, coordinate in (("west", 0, 0), ("east", 0, 16), ("down", 1, 0),
                                        ("up", 1, 16), ("north", 2, 0), ("south", 2, 16)):
        if (lo if coordinate == 0 else hi)[axis] == coordinate:
            element["faces"][direction]["cullface"] = direction
    if glow:
        element["light_emission"] = 15
    if rotation:
        element["rotation"] = {"origin": rotation[0], "axis": rotation[1], "angle": rotation[2], "rescale": False}
    return element


def front(x, y, width, height, material="panel", z=0.75, depth=0.65):
    return box((x, y, z), (x + width, y + height, z + depth), material)


def shell():
    elements = [
        box((0, 0, 1.6), (16, 16, 16), "body"),
        front(1.5, 1.5, 13, 13, "black", 1.05, 0.55),
        front(2, 2, 12, 12, "panel", 1.0),
        front(0, 0, 16, 1.5, "frame", 0, 1.6), front(0, 14.5, 16, 1.5, "frame", 0, 1.6),
        front(0, 1.5, 1.5, 13, "frame", 0, 1.6), front(14.5, 1.5, 1.5, 13, "frame", 0, 1.6),
        front(1.6, 1.6, 0.3, 12.8, "trim", 0.5), front(14.1, 1.6, 0.3, 12.8, "trim", 0.5),
        front(2, 14.1, 12, 0.3, "trim", 0.5),
    ]
    # The opaque block shape requires a casing that reaches all outside edges.
    for direction in ("west", "east", "up", "down", "south"):
        elements[0]["faces"][direction].update(texture="#casing_" + ("back" if direction == "south" else "side"), uv=[0, 0, 16, 16])
    # Bolts are drawn into the frame instead of sharing its exact face plane.
    for element in elements[3:7]:
        lo, hi = element["from"], element["to"]
        element["faces"]["north"].update(texture="#frame", uv=[16 - hi[0], 16 - hi[1], 16 - lo[0], 16 - lo[1]])
    return elements


def screen(elements, x, y, width, height, z=0.43):
    elements += [front(x - 0.5, y - 0.5, width + 1, height + 1, "trim", z + 0.02),
                 front(x, y, width, height, "screen", z)]


def socket(elements, x, y, color="copper", width=3, height=3):
    elements += [front(x, y, width, height, color, 0.3), front(x + 0.45, y + 0.45, width - 0.9, height - 0.9, "black", 0.28)]


def arrow(elements, upwards, x, material, base_y=11.5):
    for i in range(3):
        y = base_y + (i if upwards else 2 - i) * 0.55
        elements.append(front(x + i * 0.55, y, 3.3 - i * 1.1, 0.5, material, 0.4, 0.3))


def cap_piece(lo, hi, mount, material="panel", glow=False):
    if mount == "up":
        lo, hi = (lo[0], lo[2], 16 - hi[1]), (hi[0], hi[2], 16 - lo[1])
    elif mount == "down":
        lo, hi = (lo[0], 16 - hi[2], lo[1]), (hi[0], 16 - lo[2], hi[1])
    return box(lo, hi, material, glow)


def sign_mount(elements, mount):
    target = {"wall": "south", "up": "up", "down": "down"}[mount]
    axis = {"wall": 2, "up": 1, "down": 1}[mount]
    boundary = 0 if mount == "down" else 16
    for element in elements:
        if (element["from"] if mount == "down" else element["to"])[axis] == boundary:
            element["faces"].pop(target, None)
    for lo, hi in (((0, 0, 15), (16, 2, 16)), ((0, 14, 15), (16, 16, 16)),
                   ((0, 2, 15), (2, 14, 16)), ((14, 2, 15), (16, 14, 16))):
        rim = cap_piece(lo, hi, mount, "frame")
        for direction in list(rim["faces"]):
            face_axis, sign = {"west": (0, -1), "east": (0, 1), "down": (1, -1),
                               "up": (1, 1), "north": (2, -1), "south": (2, 1)}[direction]
            edge = (rim["from"] if sign < 0 else rim["to"])[face_axis]
            if direction != target and edge in (0, 16):
                del rim["faces"][direction]
        elements.append(rim)
    elements.append(cap_piece((2, 2, 15.3), (14, 14, 15.6), mount, "panel"))
    if mount == "wall":
        for x in (5.5, 9.6):
            elements.append(cap_piece((x, 6.5, 15.6), (x + 0.9, 10, 16), mount, "copper"))
        elements.append(cap_piece((6.5, 7, 15.6), (9.5, 9.5, 15.8), mount, "black"))
    elif mount == "up":
        for lo, hi in (((6, 6, 15.6), (7, 10, 16)), ((9, 6, 15.6), (10, 10, 16)),
                       ((7, 6, 15.6), (9, 7, 16)), ((7, 9, 15.6), (9, 10, 16))):
            elements.append(cap_piece(lo, hi, mount, "copper"))
        elements.append(cap_piece((7, 7, 15.6), (9, 9, 15.85), mount, "black"))
    else:
        for x in (2, 12):
            elements.append(cap_piece((x, 7, 15.6), (x + 2, 9, 16), mount, "copper"))
    for y in (3, 12.4):
        elements.append(cap_piece((3, y, 15.6), (13, y + 0.6, 15.75), mount, "cyan_dim"))


def sign_lights(mount, active=False):
    rectangles = ((3, 4, 3.6, 12), (12.4, 4, 13, 12)) if active else ((3, 3, 13, 3.6), (3, 12.4, 13, 13))
    return [cap_piece((x0, y0, 15.8), (x1, y1, 15.95), mount, glow=True) for x0, y0, x1, y1 in rectangles]


def machine(id, input_mode=True, mount="wall"):
    e = shell()
    display = None
    if id == "mainframe_controller":
        e += [front(4.5, 6, 7, 7, "black", 0.4)]
        for y in (6.5, 8.2, 9.9, 11.6):
            for x in (3.7, 11.5):
                e += [front(x, y, 0.8, 0.7, "gold", 0.35)]
        screen(e, 6, 7.5, 4, 4, 0.25)
        display = (6, 7.5, 4, 4, "controller", 0.16)
        for x in (3.2, 6.7, 10.2):
            e += [front(x, 3.7, 2.5, 0.9, "copper", 0.35)]
    elif id == "access_terminal":
        screen(e, 3, 6, 10, 7)
        display = (3, 6, 10, 7, "terminal")
        for x in (3, 5.5, 8, 10.5):
            e += [front(x, 3.6, 1.8, 0.8, "ceramic", 0.3)]
    elif id == "atomic_clock":
        screen(e, 3, 8, 10, 4)
        display = (3, 8, 10, 4, "clock")
        e += [front(5, 3.5, 6, 2.8, "black", 0.5), front(6, 3.8, 4, 2, "ceramic", 0.2),
              front(4, 4.2, 1, 0.8, "gold", 0.3), front(11, 4.2, 1, 0.8, "gold", 0.3)]
    elif id == "interrupt_controller":
        for y in (4, 6.3, 8.6, 10.9):
            e += [front(3, y, 7.5, 1.2, "black", 0.6), front(3.4, y + 0.35, 4, 0.4, "copper", 0.5),
                  front(11.5, y + 0.25, 0.9, 0.7, "amber_dim", 0.45)]
    elif id == "gpu_array":
        e += [front(3, 4, 9.8, 8.6, "black", 0.5)]
        for x in (3.5, 4.8, 6.1, 7.4, 8.7):
            e += [front(x, 4.5, 0.65, 7.5, "trim", 0.1, 0.8)]
        e += [front(10.5, 5, 2, 6.5, "blue_dim", 0.2)]
    elif id == "gpio_controller":
        for x in (3, 8.5):
            for y in (4, 7.2, 10.4):
                socket(e, x, y, width=4.5, height=2.2)
                e += [front(x + 1, y + 0.8, 2.5, 0.45, "ceramic", 0.26)]
    elif id == "harddisk_array":
        for y in (4, 7.2, 10.4):
            e += [front(3, y, 10, 2.3, "black", 0.55), front(3.4, y + 0.2, 9.2, 1.8, "frame", 0.4),
                  front(4, y + 0.6, 4.5, 0.5, "trim", 0.15), front(10.5, y + 0.5, 1.1, 0.9, "ceramic", 0.35)]
    elif id == "ender_tunnel_array":
        for x, y, w, h in ((3, 4, 1.2, 9), (11.8, 4, 1.2, 9), (4.2, 4, 7.6, 1.2), (4.2, 11.8, 7.6, 1.2)):
            e += [front(x, y, w, h, "trim", 0.2)]
        e += [front(4.5, 5.5, 7, 6, "violet_dim", 0.6), front(5, 6, 6, 5, "black", 0.55)]
        display = (5, 6, 6, 5, "tunnel")
    elif id == "integrated_storage":
        for x in (3.5, 7.1, 10.7):
            e += [front(x, 5, 2, 7, "black", 0.4)]
            for y in (5.5, 7, 8.5, 10, 11.5):
                e += [front(x - 0.35, y, 0.35, 0.5, "gold", 0.35), front(x + 2, y, 0.4, 0.5, "gold", 0.35)]
    elif id == "power_supply":
        e += [front(3, 5, 9.5, 8, "black", 0.6), front(6.8, 8, 2, 2, "copper", 0.2)]
        for x in (3.4, 4.9, 6.4, 7.9, 9.4, 10.9):
            e += [front(x, 5.5, 0.55, 7, "frame", 0.15, 0.8)]
        e += [front(4, 3.4, 7.5, 0.7, "copper", 0.2)]
    elif id in ("reset_port", "nmi_port"):
        color = "red_dim" if id == "reset_port" else "amber_dim"
        e += [front(4, 5.5, 8, 6.5, "trim", 0.4), front(4.8, 6.2, 6.4, 5.1, "black", 0.25),
              front(5.5, 6.8, 5, 3.9, color, 0.05)]
        for x in (4, 11):
            e += [front(x, 4, 1, 0.5, "ceramic", 0.3)]
    elif id == "test_port":
        socket(e, 4, 5, "blue_dim", width=8, height=7)
        for x in (5.4, 7.6, 9.8):
            e += [front(x, 7, 0.8, 2.7, "trim", 0.24)]
    elif id == "super_structure":
        for y in (4.3, 6.1, 7.9, 9.7, 11.5):
            e += [front(3.1, y + 0.3, 9.8, 0.5, "black", 0.6), front(3, y, 10, 0.25, "trim", 0.3)]
    elif id == "redstone_port":
        e += [front(3.3, 5, 9.4, 5.5, "trim", 0.5)]
        socket(e, 4, 5.7, "copper", width=8, height=4)
        for x in (5, 9.5):
            e += [front(x, 6.5, 1.2, 2.3, "copper", 0.26)]
        arrow(e, not input_mode, 6.35, "cyan_dim" if input_mode else "amber_dim")
    elif id == "sign_port":
        e = [box((0, 0, 0), (16, 16, 16), "body")]
        for direction, face in e[0]["faces"].items():
            face.update(texture="#casing_back" if direction == "north" else "#casing_side", uv=[0, 0, 16, 16])
        sign_mount(e, mount)
        e.append(cap_piece((6.3, 2, 15.6), (9.7, 3, 15.8), mount, "trim"))
        for slot in range(4):
            x = 6.4 + slot * 0.8
            e.append(cap_piece((x, 2.1, 15.8), (x + 0.8, 2.9, 15.95), mount, "black"))
    elif id == "keyboard_controller":
        e += [front(3, 5, 10, 7.4, "black", 0.4)]
        for row in range(4):
            for column in range(2):
                socket(e, 3.6 + column * 4.8, 5.6 + row * 1.5, "copper", width=3.8, height=1.0)
    elif id == "keyboard":
        e += [front(2.8, 4.7, 10.7, 7.9, "black", 0.4)]
        for row in range(4):
            for column in range(9):
                e += [front(3.1 + column * .82, 6.3 + row * 1.35, .65, 1.0, "ceramic", .12)]
        for row in range(4):
            for column in range(3):
                e += [front(10.9 + column * .78, 6.3 + row * 1.35, .58, 1.0, "trim", .12)]
        e += [front(4.2, 5.1, 5.6, .85, "ceramic", .12), front(11.7, 5.1, 1.5, .85, "trim", .12)]
    elif id == "rom_burner":
        socket(e, 3, 6.2, "trim", width=10, height=5.6)
        for x in (4.2, 5.7, 7.2, 8.7, 10.2, 11.7):
            for y in (6.8, 10.4):
                e += [front(x, y, 0.4, 0.7, "gold", 0.24)]
        e += [front(5, 8, 6, 1.5, "black", 0.2), front(12.7, 7, 0.5, 3.5, "copper", 0.05)]
        screen(e, 4, 3.6, 6, 1.3)
        display = (4, 3.6, 6, 1.3, "terminal")
    else:
        raise AssertionError(id)
    if id not in ("super_structure", "sign_port"):
        e.append(front(2.9, 2.3, 3.4, 1, "trim", 0.4, 0.3))
        for slot in range(4):
            e.append(front(3 + slot * 0.8, 2.4, 0.8, 0.8, "black", 0.26, 0.14))
    return e, display


def model(elements, light="cyan", display=None):
    textures = {"materials": NS + "block/materials", "particle": NS + "block/materials",
                "frame": NS + "block/frame", "casing_side": NS + "block/casing_side", "casing_back": NS + "block/casing_back",
                "light": NS + "block/light_" + light}
    if display:
        textures["display"] = NS + "block/display_" + display
    return {"parent": "minecraft:block/block", "textures": textures, "elements": elements}


def lamps():
    # Side rails show power; the four diagnostic LEDs use real client activity.
    return [box((1.65, 3, 0.36), (2.15, 13.5, 0.40), glow=True),
            box((13.85, 3, 0.36), (14.35, 13.5, 0.40), glow=True)]


def powered_model(display, light):
    e = lamps()
    if display:
        x, y, w, h, style = display[:5]
        z = display[5] if len(display) > 5 else 0.34
        panel = box((x, y, z), (x + w, y + h, z + 0.04), glow=True)
        panel["faces"] = {"north": {"texture": "#display", "uv": [0, 0, 16, 16]}}
        e += [panel]
    return model(e, light, display[4] if display else None)


def orientation(facing):
    return {"y": {"north": 0, "east": 90, "south": 180, "west": 270}[facing]} if facing in ("north", "east", "south", "west") else {"x": 270 if facing == "up" else 90}


def block_models():
    Image.new("RGBA", (16, 16), (255, 255, 255, 255)).save(ASSETS / "textures/block/led.png")
    Image.new("RGBA", (16, 16), (0, 255, 255, 255)).save(ASSETS / "textures/block/signal_cyan.png")
    for id in BLOCKS:
        facings = ["north", "east", "south", "west"] + (["up", "down"] if id in ("redstone_port", "sign_port", "keyboard") else [])
        parts = []
        for input_mode in ([True, False] if id == "redstone_port" else [True]):
            suffix = ("_input" if input_mode else "_output") if id == "redstone_port" else ""
            name = id + suffix
            if id == "sign_port":
                for mount in ("wall", "up", "down"):
                    sign_name = "sign_port_" + mount
                    e, display = machine(id, mount=mount)
                    powered = model(sign_lights(mount), ACCENTS[id])
                    active = model([], "cyan")
                    export_json("models/block", sign_name + "_off", model(e))
                    export_json("models/block", sign_name + "_on", powered)
                    export_json("models/block", sign_name + "_active", active)
                for facing in facings:
                    mount = facing if facing in ("up", "down") else "wall"
                    rotation = {"y": 0} if mount != "wall" else {"y": (orientation(facing)["y"] + 180) % 360}
                    def sign_apply(s):
                        return {"model": NS + "block/sign_port_" + mount + s, **rotation}
                    parts += [{"when": {"facing": facing}, "apply": sign_apply("_off")},
                              {"when": {"facing": facing, "powered": "true"}, "apply": sign_apply("_on")},
                              {"when": {"facing": facing, "active": "true"}, "apply": sign_apply("_active")}]
                export_json("models/block", id + "_off", {"parent": NS + "block/sign_port_wall_off"})
                continue
            e, display = machine(id, input_mode)
            light = ("cyan" if input_mode else "amber") if id == "redstone_port" else ACCENTS[id]
            export_json("models/block", name + "_off", model(e))
            export_json("models/block", name + "_on", model([]) if id == "super_structure" else powered_model(display, light))
            active = model([], light)
            if id in ("reset_port", "nmi_port", "test_port"):
                active = model([box((4.6, 2.4, 0.25), (5.4, 3.2, 0.255), glow=True)], "cyan")
                active["textures"]["light"] = NS + "block/signal_cyan"
            export_json("models/block", name + "_active", active)
            for facing in facings:
                when = {"facing": facing}
                if id == "redstone_port":
                    when["input"] = str(input_mode).lower()
                def apply(s):
                    return {"model": NS + "block/" + name + s, **orientation(facing)}
                parts += [{"when": when, "apply": apply("_off")},
                          {"when": {**when, "powered": "true"}, "apply": apply("_on")},
                          {"when": {**when, "active": "true"}, "apply": apply("_active")}]
        if id == "redstone_port":
            export_json("models/block", id + "_off", {"parent": NS + "block/redstone_port_input_off"})
        export_json("blockstates", id, {"multipart": parts})
        item = {"parent": NS + "block/" + id + "_off"}
        if id == "sign_port":
            item["display"] = {"gui": {"rotation": [30, 45, 0], "scale": [0.625, 0.625, 0.625]}}
        export_json("models/item", id, item)
        export_json("items", id, {"model": {"type": "minecraft:model", "model": NS + "item/" + id}})


ITEM_DISPLAY = {
    "gui": {"rotation": [35, 225, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2, 0], "scale": [0.65, 0.65, 0.65]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 1, 0], "scale": [0.8, 0.8, 0.8]},
}


def item_models():
    for programmed in (False, True):
        e = [box((3, 5, 4), (13, 7, 12), "pcb"), box((4, 7, 5), (12, 9, 11), "black"),
             box((5, 9, 6), (11, 9.25, 10), "cyan_dim" if programmed else "ceramic")]
        for x in (3.5, 5.5, 7.5, 9.5, 11.5):
            for z in (2.5, 12):
                e += [box((x, 5.5, z), (x + 1, 6.3, z + 1.5), "gold")]
        e += [box((6, 9.25, 7), (9, 9.35, 7.5), "ceramic" if programmed else "frame")]
        data = model(e)
        data["display"] = ITEM_DISPLAY
        export_json("models/item", "rom_chip_programmed" if programmed else "rom_chip", data)
    export_json("items", "rom_chip", {"model": {"type": "minecraft:condition", "property": "minecraft:has_component",
                "component": NS + "rom_id", "on_true": {"type": "minecraft:model", "model": NS + "item/rom_chip_programmed"},
                "on_false": {"type": "minecraft:model", "model": NS + "item/rom_chip"}}})
    for tier, (color, trim) in enumerate((("cyan", "copper"), ("blue", "trim"), ("violet", "gold")), 1):
        e = [box((4, 2, 4), (12, 4, 12), "black"), box((4.4, 3, 4.4), (11.6, 4.5, 11.6), trim),
             box((6, 4, 6), (10, 12, 10), glow=True),
             box((6, 5, 6), (10, 11, 10), glow=True, rotation=([8, 8, 8], "y", 45)),
             box((7, 11, 7), (9, 13, 9), glow=True, rotation=([8, 12, 8], "z", 45))]
        for i in range(tier):
            e += [box((5 + i * 2, 2.5, 3.65), (6 + i * 2, 3.4, 4), trim)]
        data = model(e, color)
        data["display"] = ITEM_DISPLAY
        id = f"speed_shard_{tier}"
        export_json("models/item", id, data)
        export_json("items", id, {"model": {"type": "minecraft:model", "model": NS + "item/" + id}})


def material_items():
    folder = ASSETS / "textures/item"
    folder.mkdir(parents=True, exist_ok=True)
    for id in ("silicon_wafer_material", "pcb_material", "memory_chip_material",
               "blank_processing_material", "fpu_material", "antenna_array_material"):
        image = Image.new("RGBA", (16, 16))
        draw = ImageDraw.Draw(image)
        if id == "silicon_wafer_material":
            draw.ellipse((1, 1, 14, 14), fill="#435d87", outline="#a8c3d4")
            for x in (4, 7, 10):
                draw.line((x, 3, x, 12), fill="#718eaf")
                draw.line((3, x, 12, x), fill="#718eaf")
            draw.rectangle((7, 13, 8, 14), fill=(0, 0, 0, 0))
        elif id == "pcb_material":
            draw.rectangle((1, 2, 14, 13), fill="#236b48", outline="#143e31")
            for y in (4, 8, 11):
                draw.line((3, y, 12, y), fill="#d3b761")
            draw.line((4, 4, 4, 11), fill="#d3b761")
            draw.line((11, 4, 11, 11), fill="#d3b761")
            draw.rectangle((6, 5, 9, 9), fill="#283240", outline="#71858d")
        elif id == "antenna_array_material":
            draw.rectangle((3, 12, 12, 14), fill="#236b48", outline="#d3b761")
            draw.line((8, 1, 8, 12), fill="#b9c8d0", width=2)
            for y in (3, 6, 9):
                draw.line((3, y - 2, 12, y + 2), fill="#cf925c")
                draw.line((3, y + 2, 12, y - 2), fill="#b9c8d0")
        else:
            for n in (4, 7, 10):
                draw.rectangle((n, 1, n + 1, 14), fill="#cda25d")
                draw.rectangle((1, n, 14, n + 1), fill="#cda25d")
            draw.rectangle((3, 3, 12, 12), fill="#283240", outline="#657985")
            if id == "memory_chip_material":
                for y in (5, 8):
                    draw.rectangle((5, y, 10, y + 1), fill="#8099b0")
            elif id == "blank_processing_material":
                draw.rectangle((5, 5, 10, 10), fill="#536e95", outline="#dfb85e")
            else:
                draw.rectangle((5, 5, 10, 10), fill="#b9c8d0", outline="#dfb85e")
                draw.line((6, 7, 9, 7), fill="#435d87")
                draw.line((7, 6, 7, 9), fill="#435d87")
        image.save(folder / (id + ".png"))
        export_json("models/item", id, {"parent": "minecraft:item/generated", "textures": {"layer0": NS + "item/" + id}})
        export_json("items", id, {"model": {"type": "minecraft:model", "model": NS + "item/" + id}})


def main():
    (ASSETS / "textures/block").mkdir(parents=True, exist_ok=True)
    pixel_textures()
    block_models()
    item_models()
    material_items()
    # Delete only the explicitly replaced placeholder resources.
    for id in BLOCKS:
        for suffix in ("_off.png", "_on.png", "_on.png.mcmeta"):
            (ASSETS / "textures/block" / (id + suffix)).unlink(missing_ok=True)
    for id in ("rom_chip", "speed_shard_1", "speed_shard_2", "speed_shard_3"):
        (ASSETS / "textures/item" / (id + ".png")).unlink(missing_ok=True)
    for name in ("machine_panels.png", "rom_burner.png", "inventory_items.png"):
        (ROOT / "art" / name).unlink(missing_ok=True)
    (ASSETS / "models/block/redstone_port_on.json").unlink(missing_ok=True)
    for name in ("sign_port_on", "sign_port_active"):
        (ASSETS / "models/block" / (name + ".json")).unlink(missing_ok=True)
    print(f"Exported {len(BLOCKS)} modeled machines, 4 modeled items and 6 flat materials with authored pixel art.")


if __name__ == "__main__":
    main()
