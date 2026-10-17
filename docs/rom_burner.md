# ROM burner workflow

1. Put a blank ROM chip in your player inventory and right-click a ROM Burner.
2. Browse the saved ROM library, or enter an upload name and description and choose **Choose local file and upload**.
3. The desktop file picker runs in a small separate Swing JVM using the same Java runtime as Minecraft. Select a readable image of exactly 256 KiB. Selecting it uploads the image; it does not burn the chip yet.
4. The server validates and saves the ROM, then selects its library entry. Choose **Burn blank chip** to turn one blank inventory chip into a permanent reference to that image.
5. Insert the burned chip into a powered-off mainframe controller.

The library displays stable ROM UUIDs, names, descriptions and uploader names and supports paging. Each image records the server-authenticated uploader's UUID and name at upload time. Uploading identical bytes selects the existing entry and rejects replacement metadata. Its first name, description and uploader remain authoritative. Multiple chips may reference that entry. Burned chips cannot be rewritten, including after their file is removed.

Enter a query in **Search name or description** and press **Search** to filter the library. Matching is a case-insensitive literal substring of either the name or the full description, with surrounding query whitespace ignored. Pages retain the applied search; applying a search starts at the first page. Clear the field and press **Search** to show all ROMs again.

Administrative ROM listings also show the uploader's UUID. Player-issued administrative imports record that player; console and internal imports display **Server import**. Administrative imports do not use the burner's upload cooldown.

The library index uses format v2. Older formats are rejected without modifying their files; no migration is provided.

Only admins can delete saved images. Files remain registered when chips are removed or lost. A chip whose image disappears is flagged invalid when its inventory/controller state is refreshed, and a running CPU using an unusable ROM powers off.

## Upload and helper lifetime

The server allows one bounded 256 KiB in-flight image per player, assembled from sixteen ordered 16 KiB packets. Uploads require the same player token, dimension, nearby burner and surviving block. Cancellation, disconnect, bad sequence or an unavailable burner discards the incomplete transfer. No partial world image is created.

Each player may start one upload every 30 real seconds across all burners. The cooldown starts after validating burner access and metadata, before opening the picker or allocating the image. Cancellation, invalid transfer data, duplicates and save failures do not refund it. Early retries report the remaining wait rounded up to whole seconds and do not extend it. Closing the menu, changing burners and reconnecting do not reset the cooldown; restarting the server does. Existing upload chunks can continue during the cooldown.

The picker sends a bounded size-and-bytes response over private process pipes; the client filesystem path is never sent to the server. The helper exits after selection/error/cancellation, when the Minecraft connection closes, or when its parent pipe closes. Its independent JVM permits Swing while Minecraft keeps its headless AWT setting.

The burner's existing powered animation runs during upload and briefly after work. Scheduled block ticks clear stale activity after chunk/world reload.

## Verification boundary

Native world checks exercise upload context/order/lifetime, real-time cooldown boundaries and session changes, uploader attribution, deduplication, chip immutability, missing references, packet size bounds and powered-state cleanup. Runtime checks validate uploader persistence and validation, unsupported index rejection, picker files/protocol, cancellation, error handling and helper process shutdown. Actual desktop dialog interaction, live client-to-server delivery and GUI presentation remain to be checked with a client.
