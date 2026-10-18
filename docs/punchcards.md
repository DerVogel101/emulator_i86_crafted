# Punchcard devices

One optional Punchcard Controller enables 16 readers and 16 writers per mainframe.
Reader and writer slot numbers are independent. Binding uses the same dimension,
duplicate-slot, debugger-lease, and power-off rules as external ports. The controller
occupies one of the mainframe's existing 21 positions.

## Media and handling

A card has eight 32-byte rows, or 256 bytes. A deck grows to at most 256 cards
(65,536 bytes). Starting a card allocates the whole card with zero-filled unused
rows. The optional name and description are independent 32-byte Windows-1252 fields,
separate from data capacity. Each may be written once before the first data row;
empty fields are valid. The header name supplies the item's initial/default name.
Anvil custom names override that default and survive reading, ejection, hoppers,
and save/reload. Renaming an item does not change its media header or description.

Blank deck items stack to four. Inserting one in a writer gives it a fresh UUID;
named, active, and finished decks are unstackable. Writers accept blanks only and
append sequentially. Ejecting or manually removing a writer deck seals it forever,
including an empty deck. Readers accept sealed decks and start at card/row zero.
Reinsert to rewind; there is no seek or erase command.

Normal right-click inserts a held deck or, with an empty hand, collects the output
deck first and otherwise removes the active deck. Manual removal cancels an
unfinished operation but retains completed rows. Shift-right-click opens binding,
status, progress, and eject controls. Breaking the block drops both decks separately.

Top and side hoppers insert one deck into the active position. Bottom hoppers can
extract only ejected output. Active and output positions can both be occupied.
Eject waits if output is occupied; no media is thrown away. Blank decks do not
consume further materials as they grow. Decks, readers, writers, and their
controller have [survival crafting recipes](crafting.md).

## I/O contract

Reader slot `n`: `0x8200 + 64*n`; writer slot `n`: `0x8600 + 64*n`.
Word registers are little-endian. Reserved reads are zero and writes are ignored.

| Offset | Register |
|---|---|
| `00–1F` | 32-byte row/metadata buffer |
| `20` | Command; reads pending/requested command |
| `21` | Status |
| `22` | Latched event reasons; write one to clear each reason |
| `23` | Error code |
| `24–25` | Total cards, 0–256 |
| `26–27` | Reader unread cards / writer unused card capacity |
| `28–29` | Current card index, zero-based |
| `2A` | Row position, 0–8; 8 is end-of-card |
| `2B` | Bit 0 sealed, bit 1 name written, bit 2 description written |
| `2C` | Metadata selector: 0 name (default), 1 description |
| `2D–3F` | Reserved |

Command masks: `01` read row, `02` write row, `04` read name, `08` write name,
`10` next card, `20` eject, `40` ACK result, `80` clear error. Submit one command
at a time. Write commands capture the buffer when submitted. Commands are admitted
at instruction boundaries and finish at the next eligible game tick. Only one
operation can be pending. No progress occurs while disconnected, unloaded, or
unpowered; CPU RESET preserves transport state.

Status masks: `01` connected, `02` active deck, `04` busy, `08` completed result,
`10` end-of-card, `20` end-of-deck/full, `40` output occupied, `80` error.
Copy any returned bytes before ACKing the result. A ready buffer cannot be
overwritten, and another operation requires ACK. Name commands operate on the field selected by `2C`; the selector is captured
when the command is submitted. They share the buffer, timing, `NAME_DONE` event,
and ACK rules, and do not move the card cursor. Select 0 for the name, 1 for the
description. Each field locks independently after its write or the first data row. Every eighth row latches end-of-card; next-card requires a separate
one-tick command. It fails at the last reader card or after the writer's 256th card.

Unread cards includes the current card until all eight rows have been read. Writer
remaining capacity counts cards not yet allocated, so a partially written 256th
card has zero additional-card capacity while its remaining rows are still writable.
Calculate byte size with **32-bit** `cards * 256`; a full deck does not fit in 16 bits.

Event masks: `01` inserted, `02` row completed, `04` name completed, `08` card
switched, `10` ejected, `20` removed, `40` error. Each event raises reader IRQ
`0x0500 | slot` or writer IRQ `0x0600 | slot`. Reasons may coalesce while their
bits remain set. Global IRQ ACK, event clearing, and result ACK are independent.
Polling works without an Interrupt Controller. Without the Punchcard Controller,
all punchcard ports read zero and ignore writes.

Errors: 1 conflicting/invalid command or wrong device direction; 2 busy/unacknowledged
result; 3 no media; 4 card boundary/invalid next-card position; 5 end of deck;
6 sealed media; 7 locked metadata field; 8 media I/O failure. Failures do not alter card data.

## Files and persistence

World-owned files are `data/emulator_i86_crafted/punchcards/<uuid>.pdeck`.
Items carry UUID plus tooltip metadata, not the data bytes. The persistent library
index records sealed-content hashes and exclusive unfinished-deck owners. Missing,
corrupt, or mismatched media reports an error and is never replaced with blanks.
Sealed readers may share a UUID with independent transport positions. Identical
finished decks reuse one canonical UUID; provisional files are removed on deduplication.

The shared `.pdeck` v2 format uses Java/DataInput big-endian integers (I/O registers
remain little-endian):

| File offset | Contents |
|---|---|
| `0–3` | ASCII `PDEK` |
| `4–7` | Unsigned format version, currently 2 |
| `8–23` | UUID, most-significant 64 bits then least-significant 64 bits |
| `24` | Flags: bit 0 sealed, bit 1 name written, bit 2 description written; other bits rejected |
| `25–56` | Exact 32 name bytes, zero padded |
| `57–88` | Exact 32 description bytes, zero padded |
| `89–90` | Card count, 0–256 |
| `91…` | Exactly `card_count * 256` data bytes |

Version 1 remains readable: it omits the description and its flag, stores the
card count at 57–58 and data at 59, and loads with an empty unwritten description.

Trailing, truncated, oversized, and unsupported files are rejected. SHA-256 covers
the name, two-byte big-endian card count, and padded data, excluding UUID and flags. A nonzero description appends byte `02` and its exact 32 bytes
to the hashed input. An all-zero description retains the v1 hash, preserving
existing indexes and transport checkpoints. Writers emit v2 files; old tools
that only support v1 must be updated before opening new files. File replacement and index replacement use temporary files plus atomic rename
where supported. Transport cursors, captured writes, results, pending operations,
and output references are host state, outside CPU snapshots.
Transport checkpoints also retain each referenced deck's content hash. A changed
file is reported explicitly rather than resuming an old cursor over different data.

## Standalone and debugger

The Punchcards panel selects a reader/writer and slot, displays live status, and
provides paused-debugger row/name/description/next/eject/ACK operations. One command with its
32-byte buffer travels as one bounded debugger request.

Standalone mode also creates blanks, imports `.pdeck` snapshots, collects media,
and exports sealed snapshots. Import never grants permission to overwrite the
source file. Unsaved written media prompts Save/Discard/Cancel before collection
or closing/replacing its CPU/session. CPU ejection retains the deck in output.
Session files preserve media, progress, and dirty-state baselines. Connected
Minecraft debuggers have no file or attachment API; use the blocks for physical media.

## Visuals and sounds

Four horizontal facings use authored graphite machine models. Input and output
trays display their decks independently; an active feed card appears during work.
The four LEDs show status, transfer activity, deck present, and card movement/eject.
Completed row/metadata transfers play a dispenser click; card movement/eject uses a
piston clack. Reading status registers is silent. The controller aggregates activity.

The C API is `lib/punchcard.h` in the IA-16 ROM project. The excluded
`punchcard_test` ROM exercises names, nine writes across a card switch, ejection,
sixteen reads including zero padding, counters, and IRQ-manager callbacks.
