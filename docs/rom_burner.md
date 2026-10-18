# ROM burner workflow

1. Put a blank ROM chip in your inventory and right-click the ROM Burner.
2. Browse the saved ROM library, or choose **Create ROM** to open a draft.
3. Enter a name and description, choose **Public** or **Private** under **Visibility**, and use **Upload** to select a local image of exactly 256 KiB. The desktop file picker runs in a separate Swing JVM using Minecraft's Java runtime. Uploading stages the file without saving a library entry or burning a chip.
4. Choose **Done** to save the image with the current name, description, and visibility. **Cancel** or closing the menu discards the draft. Uploads and validation errors retain the visibility selection. Each new draft defaults to Public.
5. Choose **Burn blank chip** to turn one blank inventory chip into a permanent reference to the selected ROM. Insert the chip into a powered-off mainframe controller.

Visibility cannot change after creation. Public ROMs appear to everyone. Private ROMs appear only to their uploader, identified by the server-authenticated player UUID. Only that player can browse, select, or burn the private entry through the burner. Burned private chips can be given to other players and used in their mainframes.

The library displays stable ROM UUIDs, names, descriptions, uploader names, and visibility, with paging. Each image records its uploader's server-authenticated UUID and name; its UUID records upload time. Public uploads with identical contents select the existing public entry, retaining its original name, description, and uploader. Every private upload creates a separate ROM ID, file, and metadata record, even if an identical public or private image exists. Private copies do not prevent a public upload. Multiple chips may reference an entry. Burned chips cannot be rewritten, including when their saved file has been removed.

Enter a query in **Search name or description** and press **Search** to filter the library. Matching is a case-insensitive literal substring of the name or full description, with surrounding whitespace ignored. Only visible entries contribute to results, counts, and pages. Pages retain the applied search; applying a search starts at the first page. Clear the field and press **Search** to show all ROMs visible to you again.

Administrative ROM listings include all entries, their visibility, and uploader UUIDs. Player-issued administrative imports record that player; console and internal imports display **Server import**. Administrative imports create public ROMs and do not use the burner's cooldown. Administrative listing, chip recovery, and deletion retain access to private entries.

Library index v3 automatically migrates valid v2 indexes on load, marking every existing ROM public while preserving IDs, image files, ordering, metadata, uploaders, and chip references. The complete index is validated before replacement. Unsupported formats, including v1, and corrupt indexes are rejected without modifying the index.

Only admins can delete saved images. Files remain registered when chips are removed or lost. A chip whose image disappears is flagged invalid when its inventory/controller state is refreshed, and a running CPU using an unusable ROM powers off.

## Upload and helper lifetime

The server allows one bounded 256 KiB in-flight image per player, assembled from sixteen ordered 16 KiB packets. Uploads require the same player token, dimension, nearby burner and surviving block. Cancellation, disconnect, bad sequence or an unavailable burner discards the incomplete transfer. No partial world image is created.

Each player may start one upload every 30 real seconds across all burners. The cooldown starts after validating burner access and metadata, before opening the picker or allocating the image. Cancellation, invalid transfer data, duplicates and save failures do not refund it. Early retries report the remaining wait rounded up to whole seconds and do not extend it. Closing the menu, changing burners and reconnecting do not reset the cooldown; restarting the server does. Existing upload chunks can continue during the cooldown.

The picker sends a bounded size-and-bytes response over private process pipes; the client filesystem path is never sent to the server. The helper exits after selection/error/cancellation, when the Minecraft connection closes, or when its parent pipe closes. Its independent JVM permits Swing while Minecraft keeps its headless AWT setting.

The burner's existing powered animation runs during upload and briefly after work. Scheduled block ticks clear stale activity after chunk/world reload.

## Verification boundary

Native world checks exercise upload context/order/lifetime, real-time cooldown boundaries and session changes, uploader attribution, deduplication, chip immutability, missing references, packet size bounds and powered-state cleanup. Runtime checks validate uploader persistence and validation, unsupported index rejection, picker files/protocol, cancellation, error handling and helper process shutdown. Actual desktop dialog interaction, live client-to-server delivery and GUI presentation remain to be checked with a client.
