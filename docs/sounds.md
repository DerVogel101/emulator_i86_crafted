# Machine sounds

All 16 cues use bundled mono Ogg Vorbis clips generated with Stable Audio 3 Small SFX.
The mod gives the events machine-specific subtitles and uses the Blocks volume slider.
Fan, startup, shutdown, fault and ROM completion each have one clip. The other cues
have two variations selected by Minecraft. The preloaded fan clip loops continuously;
the remaining clips have short tails for repeated activity. The fan plays near its
natural pitch; one-shot pitch changes, playback gains, timing, distance limits and
packet handling are preserved.
The keyboard's FIFO overflow notification also uses the generated fault clip.
See [generation and provenance](../art/audio/machine-sounds/README.md) for the fixed
prompts, seeds and regeneration steps. The generated [sound demo](../build/sound-demo/index.html)
plays every clip and repeated activity for review.

The client derives playback from the existing batched indicator updates. No sound
packets are sent. The old punchcard `ServerLevel.playSound` calls are gone. Updates
use the existing five LED bits plus an audio-only sign-write flag in the same
byte; sign writes do not flash the click LED. The batch codec rejects the remaining
reserved bits.
Activity bits are coalesced locally, including brief pulses between playback slots. Repeated
activity has a per-device cooldown, with at most six one-shot layers per client tick
and three nearby fan loops. Activity outside 16 blocks is discarded rather than
queued. The initial status snapshot does not replay boot or fault alerts.

| Machine | Sound and cadence |
| --- | --- |
| Mainframe Controller | Quiet fan; relay/chime on startup, shutdown click, one fault alert |
| Power Supply | Quiet fan and power relay |
| Harddisk Array | Quiet motor/fan; read/write/save seeks, at most every 6 ticks |
| GPU Array | Quiet fan; completed sign transfers, at most every 12 ticks |
| Ender Tunnel Array | Quiet hum and transfer chatter, at most every 16 ticks |
| Access Terminal | Soft print ticks on new output or exception, at most every 6 ticks; viewers are silent |
| Sign Port | Soft click when text reaches the sign or it is clicked, at most every 8 ticks; reads are silent |
| GPIO Controller / Redstone Port | Quiet output/IRQ and signal-change relays, every 12 / 6 ticks at most |
| Interrupt Controller | Quiet accepted/acknowledged IRQ relay, at most every 16 ticks |
| Atomic Clock | Subtle configuration/expiry ticks, at most every 30 ticks; clock reads are silent |
| Integrated Storage | Quiet persistence click, at most every 20 ticks; RAM accesses are silent |
| RESET / NMI / TEST | Quiet event relays, every 10 / 10 / 12 ticks at most; a held pin is silent |
| Keyboard Controller / Keyboard | Quiet command/key clicks, every 12 / 3 ticks at most |
| Punchcard Controller | Quiet card-change relay, at most every 12 ticks |
| Punchcard Reader / Writer | Layered scan/punch and relay sounds; feed/eject adds a motor stroke and paper; at most every 4 ticks |
| ROM Burner | Upload/selection relays and a completion tone, at most every 12 ticks |
| Structural Filler | Silent |

Fan loops fade in and stop on power loss, failure, removal, chunk unload, leaving
range, dimension change, or disconnect. Muting Blocks stops them; unmuting and
resource reloads can restart them. Startup/shutdown chatter is coalesced to at most
one state cue per machine per 10 ticks. Busy racks rotate activity priority so one
device cannot use the whole local sound budget indefinitely.
Frozen game ticks keep fan ambience but suppress repeated activity cues.

`SoundActivitySelfTest` checks coalescing, sustained every-tick activity, cooldowns,
power chatter, short terminal pulses, and silent initial/viewer/deck/fault states.
Native tests cover the extended batch codec and the sign-write/click distinction.
`tools/verify_resources.mjs` checks that every registered cue resolves to the expected
bundled variants, with mono Ogg Vorbis headers and no missing or unused clips.
`MachineSoundClientGameTest` exercises real packet-driven playback, resolved bundled
audio, all device profiles, real boot/terminal/sign/disk/shutdown events, layered
punchcard bursts, fan and one-shot limits, and freeze/mute/reload/removal/power/
range/disconnect cleanup in a running client.
