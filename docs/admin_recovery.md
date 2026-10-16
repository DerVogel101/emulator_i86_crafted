# Admin recovery

`/i86` displays help. Recovery, library administration and retained-data listings require Minecraft's admin command permission. A recovered item keeps its original identity and data; it is a replacement for a lost item, rather than a new independent device.

| Command | Result |
| --- | --- |
| `/i86 list [page]` | Mainframe IDs, friendly names, status, controller placements, retained recovery/NVRAM and ROM availability. |
| `/i86 disks [page]` | Retained disk UUIDs, data availability and referencing mainframe locations where recorded. |
| `/i86 rom list [page]` | ROM UUIDs, names, descriptions and file availability. |
| `/i86 recover controller <id> [player]` | Recreates the latest retained controller item with identity, friendly name, ROM and speed shard. |
| `/i86 recover disk <uuid> [player]` | Recreates a portable disk with its identity and latest retained bytes; active disk data is captured first. |
| `/i86 rom chip <uuid> [player]` | Creates another permanent reference to an existing ROM library entry. Missing ROM bytes produce an invalid chip. |
| `/i86 rom import <file> <name> [description]` | Imports an exactly 256 KiB ROM from the world's `data/emulator_i86_crafted/uploads` directory. Quote filenames/names containing spaces. |
| `/i86 rom delete <uuid>` | Deletes the ROM and shuts down CPUs referencing it. Existing chips become invalid. |
| `/i86 memory list [page]` | Retained shared-region IDs, byte capacity and powered CPU count. |
| `/i86 memory delete <id>` | Deletes a named shared region and shuts down connected CPUs until a region is explicitly selected again. |

For recovery commands, an in-game admin may omit `player` to receive the item personally. The server console must supply an online player name or a selector matching exactly one player. For example:

```text
i86 recover controller 12 Alex
i86 recover disk 123e4567-e89b-12d3-a456-426614174000 Alex
i86 rom chip 019a0000-0000-7000-8000-000000000001 @a[name=Alex,limit=1]
```

The item is added to that player's inventory. If it does not fit, it drops at that player. An unavailable recipient receives nothing.

Controller recovery is refused while that ID is still recorded as placed, with its locations reported. Remove the original controller before recreating a lost item. Copied controller items and duplicate disk activations still trigger the normal duplicate conflict rules. Controller UIs and machine status identify the conflicting placements. Recovering an item does not erase or replace world storage, ROM metadata or port bindings.

Ordinary players can use `/i86 memory select <id>` while looking at an Ender Tunnel Array; this follows the same powered-off and debugger ownership checks as its block dialog.
