# ROM burner workflow

1. Put a blank ROM chip in your player inventory and right-click a ROM Burner.
2. Browse the saved ROM library, or enter an upload name and description and choose **Choose local file and upload**.
3. The desktop file picker runs in a small separate Swing JVM using the same Java runtime as Minecraft. Select a readable image of exactly 256 KiB. Selecting it uploads the image; it does not burn the chip yet.
4. The server validates and saves the ROM, then selects its library entry. Choose **Burn blank chip** to turn one blank inventory chip into a permanent reference to that image.
5. Insert the burned chip into a powered-off mainframe controller.

The library displays stable ROM UUIDs, names and descriptions and supports paging. Uploading identical bytes selects the existing entry and rejects replacement metadata. Its first name and description remain authoritative. Multiple chips may reference that entry. Burned chips cannot be rewritten, including after their file is removed.

Only admins can delete saved images. Files remain registered when chips are removed or lost. A chip whose image disappears is flagged invalid when its inventory/controller state is refreshed, and a running CPU using an unusable ROM powers off.

## Upload and helper lifetime

The server allows one bounded 256 KiB in-flight image per player, assembled from sixteen ordered 16 KiB packets. Uploads require the same player token, dimension, nearby burner and surviving block. Cancellation, disconnect, bad sequence or an unavailable burner discards the incomplete transfer. No partial world image is created.

The picker sends a bounded size-and-bytes response over private process pipes; the client filesystem path is never sent to the server. The helper exits after selection/error/cancellation, when the Minecraft connection closes, or when its parent pipe closes. Its independent JVM permits Swing while Minecraft keeps its headless AWT setting.

The burner's existing powered animation runs during upload and briefly after work. Scheduled block ticks clear stale activity after chunk/world reload.

## Verification boundary

Native world checks exercise upload context/order/lifetime, deduplication, chip immutability, missing references, packet size bounds and powered-state cleanup. Runtime checks validate picker files/protocol, cancellation, error handling and helper process shutdown. Actual desktop dialog interaction, live client-to-server delivery and GUI presentation remain to be checked with a client.
