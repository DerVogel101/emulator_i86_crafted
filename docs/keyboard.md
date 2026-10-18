# Keyboard

One optional **Keyboard Controller** enables eight external keyboard slots, numbered 0–7. It occupies one of the mainframe's existing 21 positions. Each keyboard has an independent eight-byte FIFO, including the visible character.

Shift+right-click opens the port-style mainframe/slot/name settings. Rebinding requires power off. Saved duplicate slots disable the conflicting keyboards, including unloaded assignments. The block supports all six facing directions.

Right-click an online, powered keyboard to capture input; a keyboard icon appears at the right edge. Escape releases capture without dropping queued input. Capture is exclusive to one player within block interaction reach. Death, disconnect, dimension change, leaving reach, or device loss ends capture. Unbinding, unloading, removing the device, or stopping its mainframe clears its FIFO. CPU RESET preserves input and pending ACKs.

## Characters

The active standard German or US layout is detected at capture start. Stateless SDL scancode maps support Shift, Caps Lock, German AltGr and normal key repeat. Dead accents type immediately without composing. Paste and IME text are ignored. Text is strictly encoded as Windows-1258 without replacement characters.

Unmodified Backspace is `0x08`, Tab is `0x09`, and Enter is `0x0A`. Modified control/navigation/numpad keys and Ctrl/Alt shortcuts are ignored. AltGr remains valid when Windows also reports Ctrl. Main-section operators remain ordinary text; euro stays `0x80`. Num Lock itself emits nothing and does not alter numpad codes.

| Bytes | Keys, in order |
| --- | --- |
| `0x81–0x8A` | Numpad 0–9 |
| `0x8B–0x91` | Numpad decimal, divide, multiply, subtract, add, Enter, equals |
| `0x92–0x95` | Left, Right, Up, Down |
| `0x96–0x9B` | Insert, Delete, Home, End, Page Up, Page Down |

These codes repurpose Windows-1258 characters that standard DE/US typing cannot generate. Normal vanilla shortcuts and mod keybindings are intercepted during capture; mods that bypass normal dispatch with raw input hooks require compatibility testing.

## I/O

The standalone launcher and remote Swing debugger have an **I/O → Devices → Keyboard** tab.
Select slot 0–7 to inspect the FIFO bytes, current head, READY, ACK-pending, ports and IRQ ID.
Click **Capture keys** to type; Escape or leaving that input area releases capture. Select DE or US
to match the active keyboard layout. Text composition and paste are ignored; arrows and numpad
keys use the same special codes as Minecraft input. Full queues beep and reject new input.

The launcher supplies eight virtual keyboards when its Keyboard Controller is installed and powered.
Power off to install the controller using **Hardware**; the legacy default profile does not include it.
The remote debugger uses loaded, connected keyboard blocks already bound to the selected mainframe.
Only the controlling player may send keys, while running or paused; observers can inspect slots.
Live FIFO snapshots continue while running without exposing the paused CPU's register/memory views.
**Request data ACK** uses the keyboard control port and remains separate from global IRQ ACK.
Remote manual ACK requires a paused CPU. Closing capture or the debugger does not discard input;
power-off or detaching the device clears it. Local virtual connections are rebuilt on session restore,
with their transient queues empty.

Slot `n` uses base port `0x8170 + 2*n`.

| Port | Read | Write |
| --- | --- | --- |
| Base | Visible byte, or zero when empty | Ignored |
| Base + 1 | Bit 0: ACK pending; bit 1: character ready | Bit 0: request ACK |

The first byte is published at the next instruction boundary, generating IRQ `0x0400 | n`. Waiting bytes generate no IRQ until presented. An ACK is confirmed at the next instruction boundary by setting ACK-pending; the byte remains readable. At the next game-tick boundary, exactly one acknowledged byte is removed, ACK-pending clears, and the next byte and its IRQ are published. Repeated ACKs while pending count once; an empty ACK does nothing. Instruction and batch boundaries do not consume the byte.

The global IRQ ACK is separate from the keyboard ACK. Without an Interrupt Controller, polling works but no IRQ is generated. Without a Keyboard Controller, ports read zero and ignore writes. A full FIFO drops the new byte and beeps locally for the capturing player.

The C library's `keyboard_try_read` accepts READY without ACK_PENDING, reads one byte and requests its ACK. Caller-owned `KeyboardBinding` records register exact IRQ IDs and receive `(slot, byte)` callbacks. Callbacks must return promptly without waiting, STI, registry mutation or global IRQ ACK; the IRQ manager handles that ACK.

World format 3 adds the controller count. Formats 1 and 2 load with keyboards absent and preserve previous hardware counts and bindings. CPU snapshot format 2 likewise accepts legacy format-1 execution state with the controller absent. Older mod builds cannot read the new snapshots. FIFO contents and capture sessions are transient and are not restored from world snapshots.
