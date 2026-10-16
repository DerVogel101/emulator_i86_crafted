# Remote Swing debugger

The existing launcher views control Minecraft's server-owned CPU through the authenticated player connection. Each viewed mainframe has a separate external window and private child pipe. The helper holds copied views; it never creates a local CPU.

## Execution and ownership

`DebuggerBackend` separates the reused Swing views from the standalone launcher's local controller. The shared debugger source set packages the views, protocol and JDK-only helper in the mod JAR. The standalone launcher still uses its local backend and the same CPU/device/scheduling rules.

The controller's **Open debugger** action creates a session bound to the player, connection, mainframe and random token. The first opener controls that mainframe; observers remain read-only. Closing a window or losing its helper releases only that session's lease, passing control in opener order. Disconnect releases all of that player's sessions. Reopening the same mainframe preserves its session and queue position. Different mainframes can remain open simultaneously.

Server actions execute on the existing worker: run, pause, step, step-over, run-to-cursor, set PC, skip fault, reset, power cycle, NMI and permitted memory/I/O/sign/timer/IRQ edits. Cursor targets survive chunk suspension while their debugger remains open. The last debugger closing removes interception/targets. Ordinary CPU execution without a debugger retains its existing behavior.

ROM/storage files, hardware/shard configuration, local session files and simulated world inputs are unavailable in remote mode. Minecraft's custom mod screens and server configuration own those operations. While a lease exists, block GUI mutations and manual controls also require its owner.

## State and transport

The client starts a separate desktop Java process with Swing enabled and relays stdin/stdout frames through Fabric packets. There is no additional network listener. Closing the window, pipe EOF, process failure and Minecraft disconnect clean up the helper/session. Epoch and connection checks prevent stale helpers sending into a replacement connection.

Frames are versioned and bounded at 128 KiB; client requests have a 4 KiB bound and a per-session limit of 32 requests per server tick. Strings, arrays, enum values and trailing/truncated input are validated. Requests cannot inject paths or serialized Java objects. Session tokens alone cannot authorize another player's request.

Paused, powered, loaded CPUs send detailed registers, disassembly, memory, I/O and device state. Running CPUs send status, faults and terminal output without CPU details. Off/unloaded updates can omit detail while preserving pause status. Swing retains the previous view read-only when detail is unavailable. Publication occurs on changes or every four server ticks; client snapshot coalescing is bounded and errors are not silently replaced.

The client prevents vanilla's automatic focus-loss pause while any debugger session is open, allowing the external window to be used in singleplayer. It preserves the player's option and leaves explicit pause controls available.

## Verification

Protocol/runtime checks cover framing, malformed requests, owner/observer controls, detail exclusion, command forwarding, retained views and EOF cleanup. Native server tests use three authenticated player connections to check token binding, FIFO handover, edits, floods and independent concurrent sessions.

A packaged headed Swing smoke check verifies owner Step forwarding, read-only observer controls and Windows-1252 rendering. The actual Minecraft client opens the helper through the custom controller screen, edits the real server CPU, keeps two windows alive independently, verifies focus behavior and releases leases after helper termination. Powered save/reopen also passes in a fresh JVM. See [native_tests.md](native_tests.md).

Sibling changes are recorded in `emulator-debugger-backend.patch`, `emulator-remote-debugger-ui.patch`, `emulator-remote-debugger-guards.patch` and `emulator-remote-debugger-status.patch` under [patches](../patches/).
