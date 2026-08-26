# Changelog

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
