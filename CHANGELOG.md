# Changelog

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
