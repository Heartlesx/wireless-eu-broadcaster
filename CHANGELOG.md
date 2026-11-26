# Changelog

## 2.1.4-forge-1.20.1

Synchronises the 1.20.1 build with the 1.12.2 2.1.4-dev.12 feature line and targets the Sky of Grind pack.

### Compatibility

- Compiled against Forge 47.4.3 and GTCEu 7.4.1; `mods.toml` now accepts `[47.4.3,)` / `[7.4.1,)`, so the same jar also loads on Monifactory (47.4.13 / 7.5.3).
- Network protocol raised to 10.

### New features

- **Energy Binding Card**: sneak-right-click a broadcaster to bind, then every compatible GT machine the carrying player places is connected automatically. Targets placed while the broadcaster chunk is unloaded, while a same-tier broadcaster is in range, or before the cross-dimension upgrade are queued (64 per card) and committed automatically.
- **Big EU buffer**: `2^126 - 1` EU through a big-integer container, with unlimited output amperage.
- **14 tiers**: UIV, UXV and OpV added, matching 1.12.2.
- **Server config**: `enableEnergyAmplification` (2.5x / 1x) and `enableAutoConnect`, both restart-scoped.
- **Laser input**: the broadcaster accepts normal EU and GT laser energy.
- **Migration parity**: circuits, unlock state, EU and connections move to the higher broadcaster; the destination must be owned by the requesting player.
- **Artwork**: 14 tiers x 5 faces (70 textures) with six-face block models and blockstate rotation.
- **Jade**: tier, amplified input rate, local/remote connection counts, buffer percentage and ETAs.

### Fixes

- Upgrade circuits are returned by `IMachineLife#onMachineRemoved`, so wands, building gadgets and chain breaks no longer lose them.
- Auto-connect also enforces the "one same-tier broadcaster per range" rule and queues the target on conflict.
- Broadcaster targets reject `gtmthings` wireless energy/laser hatches to prevent amplification loops.
- Client state is keyed by dimension + position; the connected list no longer requires a scan first.
- Depletion time shows "Infinite" while the buffer grows or no target is connected.
- The full-test broadcaster only appears in GTCEu's Machine creative tab.

### Incompatible changes

- **Link Card removed** (`wireless_eu:link_card`, its recipe, model, language keys and clear-card recipe). The Energy Binding Card inherits its recipe.
- The fixed 65,536A output limit and `SET_OUTPUT_AMPERAGE` action are gone; output is limited only by the targets.
- Teleport commands only accept coordinates the server handed to that player within the last five minutes.

## 2.0.1

- Added a server-side right-click fallback for tagged circuit upgrades and aligned tag lookup with Forge's live item registry.

## 2.0.0

- Fixed Shift-right-click circuit upgrades by handling tagged circuits through GTCEu's machine interaction hook for every broadcaster tier.

## 1.2.10

- Fixed tier-circuit upgrade matching for pack-provided circuits, including any circuit carrying the broadcaster's `gtceu:circuits/<tier>` tag.

## 1.2.9

- Dynamically scales the broadcaster UI while preserving its layout and synchronized mouse hitboxes on smaller displays.
- Added same-tier mixed-circuit upgrades for LV through MAX broadcasters. Upgrades unlock unlimited same-dimension links and link-card cross-dimension targets.
- Added owner-only Shift-right-click upgrades, progress messages, and return of the exact installed circuit stacks when a broadcaster is broken.
- Added the creative-only Link Card with broadcaster binding, batches of up to 32 remote or out-of-range targets, and a special crafting reset that clears card data.
- Cross-dimension targets use GTCEu public energy and laser APIs and are only powered while their chunks are already loaded.

## 1.2.8

- Explicitly added all broadcaster item stacks to GTCEu's Machine creative tab through Forge's public creative-tab event.
- Improved compatibility with JEI clients where Registrate creative-tab mapping is not visible during item indexing.

## 1.2.7

- Registered broadcaster machines in GTCEu's Machine creative tab through the public GTRegistrate API.
- This makes broadcasters visible in vanilla Creative inventory and JEI, including packs using JEI instead of EMI.

## 1.2.6

- Added the vanilla Minecraft menu click sound to all active broadcaster UI actions and modal controls.

## 1.2.5

- Increased the scanned-machine list from 9 to 11 visible rows to use the full panel height.
- Aligned list drawing and click hitboxes for the additional rows.

## 1.2.4

- Removed the offset terminal drop shadow so the outer black border has consistent thickness on all sides.

## 1.2.3

- Rounded displayed connection amperage to two decimal places while preserving exact internal load calculations.

## 1.2.2

- Fixed battery-buffer output direction detection for direct broadcaster connections.
- Hardened cable-network battery-buffer endpoint detection to block amplified EU feedback.

## 1.1.7

- Added clickable migration destination coordinates that send a teleport link to chat.
- Added server-side validation for migration broadcaster location links.

## 1.1.6

- Prevented primary broadcaster controls from rendering through the migration modal.
- Added a top-layer modal render pass with an adaptive compact layout.
- Fixed modal close and confirmation hitboxes after GUI scaling.

## 1.1.5

- Reworked the migration target selector as a centered modal window.
- Added isolated target rows, explicit confirmation controls, and a visible close button.
- Prevented the migration menu from overlapping the main broadcaster status UI.

## 1.1.4

- Reworked broadcaster migration into a modal secondary menu with a close button.
- Migration targets are shown separately without covering broadcaster status information.
- Migration targets are listed by distance and require explicit confirmation.

## 1.1.3

- Removed the selectable output amperage tiers.
- Broadcasters now use a fixed 65536A total output limit.
- Existing broadcaster data is normalized to the fixed output limit.

## 1.1.2

- Release build for Minecraft 1.20.1 Forge.
- Includes tiered Wireless EU Broadcaster machines and the current broadcaster UI, target controls, range preview, and optional Jade integration.
