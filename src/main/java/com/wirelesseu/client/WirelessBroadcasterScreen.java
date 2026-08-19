package com.wirelesseu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessTarget;
import com.wirelesseu.menu.WirelessBroadcasterMenu;
import com.wirelesseu.network.ClientBroadcasterState;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Set;

public final class WirelessBroadcasterScreen extends AbstractContainerScreen<WirelessBroadcasterMenu> {
    private static final int LIST_VISIBLE_ROWS = 11;
    private static final int LIST_ROW_HEIGHT = 18;
    private static final long DETAIL_TOOLTIP_DELAY_MILLIS = 500L;
    private final Set<Long> selectedPositions = new HashSet<>();
    private int scrollOffset;
    private Button scanButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button rangePreviewButton;
    private Button migrationButton;
    private Button dismissalButton;
    private Button unconnectedButton;
    private Button connectedButton;
    private EditBox searchBox;
    private TargetFilter targetFilter = TargetFilter.UNCONNECTED;
    private Component rejectionMessage;
    private DetailTarget hoveredDetailTarget;
    private long detailHoverStartMillis;
    private Component frozenDetailTooltip;
    private boolean migrationMenu;
    private WirelessEuNetwork.HigherBroadcasterInfo pendingMigration;

    public WirelessBroadcasterScreen(WirelessBroadcasterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 430;
        imageHeight = 300;
        inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();
        scanButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.scan"), button ->
                        WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.SCAN, 0, List.of()))
                .bounds(leftPos + 30, topPos + 32, 82, 18)
                .build());

        searchBox = addRenderableWidget(new EditBox(font,
                leftPos + 126, topPos + 10, 174, 18,
                Component.translatable("gui.wireless_eu.search")));
        searchBox.setHint(Component.translatable("gui.wireless_eu.search"));
        searchBox.setResponder(ignored -> scrollOffset = 0);
        unconnectedButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.wireless_eu.filter_unconnected"), button -> {
                            targetFilter = TargetFilter.UNCONNECTED;
                            scrollOffset = 0;
                        })
                .bounds(leftPos + 30, topPos + 32, 126, 18)
                .build());
        connectedButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.wireless_eu.filter_connected"), button -> {
                            targetFilter = TargetFilter.CONNECTED;
                            scrollOffset = 0;
                        })
                .bounds(leftPos + 162, topPos + 32, 138, 18)
                .build());

        connectButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.connect"), button ->
                        WirelessEuNetwork.sendAction(menu.getPos(),
                                WirelessEuNetwork.ServerAction.CONNECT_SELECTED, 0, selectedPositions))
                .bounds(leftPos + 30, topPos + 272, 126, 18)
                .build());
        disconnectButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.disconnect"),
                        button -> WirelessEuNetwork.sendAction(menu.getPos(),
                                WirelessEuNetwork.ServerAction.DISCONNECT_SELECTED, 0, selectedPositions))
                .bounds(leftPos + 162, topPos + 272, 138, 18)
                .build());
        rangePreviewButton = addRenderableWidget(Button.builder(rangePreviewLabel(), button -> {
                    WirelessEuRangePreview.toggle(menu.getPos());
                    button.setMessage(rangePreviewLabel());
                })
                .bounds(leftPos + 306, topPos + 272, 117, 18)
                .build());
        migrationButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.migrate"), button -> {
                    migrationMenu = true;
                    pendingMigration = null;
                    setPrimaryWidgetsVisible(false);
                })
                .bounds(leftPos + 306, topPos + 32, 117, 18)
                .build());
        dismissalButton = addRenderableWidget(Button.builder(Component.literal("OK"), button -> dismissRejection())
                .bounds(leftPos + 174, topPos + 162, 82, 20)
                .build());
        dismissalButton.visible = false;
        setPrimaryWidgetsVisible(true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (migrationMenu && rejectionMessage == null) {
            renderMigrationModal(graphics);
        }
        if (rejectionMessage != null) {
            renderRejectionDialog(graphics, mouseX, mouseY);
            clearDetailTooltip();
        } else {
            renderTooltip(graphics, mouseX, mouseY);
            renderDetailTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.enableBlend();
        int left = leftPos;
        int top = topPos;

        // AE2-inspired stepped terminal shell, drawn from independent Wireless EU colors.
        graphics.fill(left, top + 6, left + imageWidth, top + imageHeight - 6, 0xFF111111);
        graphics.fill(left + 6, top, left + imageWidth - 6, top + imageHeight, 0xFF111111);
        graphics.fill(left + 2, top + 8, left + imageWidth - 2, top + imageHeight - 8, 0xFFF1F1F1);
        graphics.fill(left + 8, top + 2, left + imageWidth - 8, top + imageHeight - 2, 0xFFF1F1F1);
        graphics.fill(left + 4, top + 9, left + imageWidth - 4, top + imageHeight - 9, 0xFFC6C6C6);
        graphics.fill(left + 9, top + 4, left + imageWidth - 9, top + imageHeight - 4, 0xFFC6C6C6);
        drawInset(graphics, left + 124, top + 8, left + 302, top + 30, 0xFF979797);
        drawInset(graphics, left + 28, top + 52, left + 302, top + 263, 0xFF8F8F8F);
        drawInset(graphics, left + 306, top + 52, left + 423, top + 263, 0xFFA9A9A9);

        ClientBroadcasterState.State state = state();
        scanButton.active = state.canConfigure();
        unconnectedButton.active = targetFilter != TargetFilter.UNCONNECTED;
        connectedButton.active = targetFilter != TargetFilter.CONNECTED;
        connectButton.active = state.canConfigure() && !selectedPositions.isEmpty() && rejectionMessage == null;
        disconnectButton.active = state.canConfigure() && !selectedPositions.isEmpty() && rejectionMessage == null;
        rangePreviewButton.setMessage(rangePreviewLabel());
        migrationButton.active = state.canConfigure() && !state.higherBroadcasters().isEmpty() && rejectionMessage == null;

        graphics.drawString(font, title, left + 31, top + 14, 0xFF333333, false);
        drawTerminalButton(graphics, left + 28, top + 10, 82, 18,
                Component.translatable("gui.wireless_eu.scan"), false, scanButton.active, mouseX, mouseY);
        drawTerminalButton(graphics, left + 28, top + 32, 128, 18,
                Component.translatable("gui.wireless_eu.filter_unconnected"),
                targetFilter == TargetFilter.UNCONNECTED, true, mouseX, mouseY);
        drawTerminalButton(graphics, left + 160, top + 32, 142, 18,
                Component.translatable("gui.wireless_eu.filter_connected"),
                targetFilter == TargetFilter.CONNECTED, true, mouseX, mouseY);
        drawTerminalButton(graphics, left + 306, top + 32, 117, 18,
                Component.translatable("gui.wireless_eu.migrate"), false, migrationButton.active, mouseX, mouseY);
        drawTerminalButton(graphics, left + 28, top + 270, 128, 20,
                Component.translatable("gui.wireless_eu.connect"), false, connectButton.active, mouseX, mouseY);
        drawTerminalButton(graphics, left + 160, top + 270, 142, 20,
                Component.translatable("gui.wireless_eu.disconnect"), false, disconnectButton.active, mouseX, mouseY);
        drawTerminalButton(graphics, left + 306, top + 270, 117, 20,
                rangePreviewLabel(), WirelessEuRangePreview.isEnabled(menu.getPos()), true, mouseX, mouseY);
        drawTargets(graphics, state, mouseX, mouseY);
        drawMachineInfo(graphics, state);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // All text is placed in renderBg because this screen has no player inventory.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (rejectionMessage != null) {
            if (button == 0 && mouseX >= leftPos + 174 && mouseX < leftPos + 256
                    && mouseY >= topPos + 162 && mouseY < topPos + 182) {
                dismissRejection();
            }
            return true;
        }
        if (migrationMenu) {
            int modalLeft = leftPos + 82;
            int modalTop = topPos + 48;
            if (mouseX >= modalLeft + 225 && mouseX < modalLeft + 253
                    && mouseY >= modalTop + 8 && mouseY < modalTop + 34) {
                migrationMenu = false;
                pendingMigration = null;
                setPrimaryWidgetsVisible(true);
                return true;
            }
            if (pendingMigration != null) {
                if (mouseX >= modalLeft + 15 && mouseX < modalLeft + 245
                        && mouseY >= modalTop + 68 && mouseY < modalTop + 102) {
                    WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.LOCATE_BROADCASTER, 0,
                            List.of(pendingMigration.pos().asLong()));
                    return true;
                }
                if (mouseX >= modalLeft + 50 && mouseX < modalLeft + 125
                        && mouseY >= modalTop + 120 && mouseY < modalTop + 144) {
                    WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.MIGRATE_TO_HIGHER, 0,
                            List.of(pendingMigration.pos().asLong()));
                    migrationMenu = false; pendingMigration = null; setPrimaryWidgetsVisible(true); return true;
                }
                if (mouseX >= modalLeft + 135 && mouseX < modalLeft + 210
                        && mouseY >= modalTop + 120 && mouseY < modalTop + 144) {
                    pendingMigration = null; return true;
                }
            } else {
                List<WirelessEuNetwork.HigherBroadcasterInfo> targets = state().higherBroadcasters();
                for (int i = 0; i < targets.size() && i < 5; i++) {
                    if (mouseX >= modalLeft + 12 && mouseX < modalLeft + 248
                            && mouseY >= modalTop + 50 + i * 26 && mouseY < modalTop + 74 + i * 26) {
                        pendingMigration = targets.get(i); return true;
                    }
                }
            }
            return true;
        }
        ClientBroadcasterState.State state = state();
        int listTop = topPos + 60;
        List<DisplayRow> rows = displayRows(state);
        if (button == 0 && handleTerminalClick(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && mouseX >= leftPos + 34 && mouseX < leftPos + 298
                && mouseY >= listTop && mouseY < listTop + LIST_VISIBLE_ROWS * LIST_ROW_HEIGHT) {
            int row = (int) ((mouseY - listTop) / LIST_ROW_HEIGHT);
            int index = row + scrollOffset;
            if (index >= 0 && index < rows.size()) {
                WirelessTarget target = rows.get(index).target();
                if (mouseX >= leftPos + 263) {
                    WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.LOCATE_TARGET, 0,
                            List.of(target.pos().asLong()));
                    return true;
                }
                if (!state.canConfigure()) {
                    return true;
                }
                long pos = target.pos().asLong();
                if (!selectedPositions.add(pos)) {
                    selectedPositions.remove(pos);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void renderMigrationModal(GuiGraphics graphics) {
        ClientBroadcasterState.State state = state();
        int modalLeft = leftPos + 82;
        int modalTop = topPos + 48;
        int modalRight = modalLeft + 260;
        int rowCount = Math.min(5, state.higherBroadcasters().size());
        int modalBottom = modalTop + (pendingMigration == null ? 72 + rowCount * 26 : 160);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 100.0D);
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(modalLeft, modalTop, modalRight, modalBottom, 0xFF252525);
        graphics.fill(modalLeft + 2, modalTop + 2, modalRight - 2, modalBottom - 2, 0xFFF2F2F2);
        graphics.fill(modalLeft + 5, modalTop + 5, modalRight - 5, modalBottom - 5, 0xFFC2C2C2);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.migration_targets"), modalLeft + 12,
                modalTop + 13, 0xFF303030, false);
        drawTerminalButton(graphics, modalRight - 35, modalTop + 7, 28, 26,
                Component.literal("X"), false, true, -1, -1);
        if (pendingMigration != null) {
            graphics.drawString(font, Component.translatable("gui.wireless_eu.migration_confirm"), modalLeft + 18, modalTop + 58, 0xFF303030, false);
            graphics.drawString(font, WirelessEuBroadcasterMachine.tierName(pendingMigration.tier()) + " ["
                    + pendingMigration.pos().getX() + ", " + pendingMigration.pos().getY() + ", " + pendingMigration.pos().getZ() + "]",
                    modalLeft + 18, modalTop + 82, 0xFF303030, false);
            drawTerminalButton(graphics, modalLeft + 50, modalTop + 120, 75, 24,
                    Component.translatable("gui.wireless_eu.confirm"), false, true, -1, -1);
            drawTerminalButton(graphics, modalLeft + 135, modalTop + 120, 75, 24,
                    Component.translatable("gui.wireless_eu.cancel"), false, true, -1, -1);
            graphics.pose().popPose();
            return;
        }
        for (int i = 0; i < state.higherBroadcasters().size() && i < 5; i++) {
            var target = state.higherBroadcasters().get(i);
            int rowTop = modalTop + 50 + i * 26;
            graphics.fill(modalLeft + 12, rowTop, modalRight - 12, rowTop + 22, 0xFF3A3A3A);
            graphics.fill(modalLeft + 14, rowTop + 2, modalRight - 14, rowTop + 20,
                    (i & 1) == 0 ? 0xFFA8A8A8 : 0xFF9C9C9C);
            graphics.drawString(font, (i + 1) + ". " + WirelessEuBroadcasterMachine.tierName(target.tier()),
                    modalLeft + 20, rowTop + 7, 0xFF303030, false);
            graphics.drawString(font, Math.round(Math.sqrt(target.distanceSquared())) + " blocks",
                    modalRight - 90, rowTop + 7, 0xFF303030, false);
        }
        graphics.pose().popPose();
    }

    private void setPrimaryWidgetsVisible(boolean visible) {
        searchBox.visible = visible;
        // The action widgets are retained for their enabled state and narration,
        // while their pixels are rendered by the terminal skin below.
        scanButton.visible = false;
        unconnectedButton.visible = false;
        connectedButton.visible = false;
        connectButton.visible = false;
        disconnectButton.visible = false;
        rangePreviewButton.visible = false;
        migrationButton.visible = false;
    }

    private boolean handleTerminalClick(double mouseX, double mouseY) {
        if (mouseX >= leftPos + 28 && mouseX < leftPos + 110 && mouseY >= topPos + 10 && mouseY < topPos + 28) {
            if (scanButton.active) {
                WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.SCAN, 0, List.of());
            }
            return true;
        }
        if (mouseX >= leftPos + 28 && mouseX < leftPos + 156 && mouseY >= topPos + 32 && mouseY < topPos + 50) {
            targetFilter = TargetFilter.UNCONNECTED;
            scrollOffset = 0;
            return true;
        }
        if (mouseX >= leftPos + 160 && mouseX < leftPos + 302 && mouseY >= topPos + 32 && mouseY < topPos + 50) {
            targetFilter = TargetFilter.CONNECTED;
            scrollOffset = 0;
            return true;
        }
        if (mouseX >= leftPos + 306 && mouseX < leftPos + 423 && mouseY >= topPos + 32 && mouseY < topPos + 50) {
            if (migrationButton.active) {
                migrationMenu = true;
                pendingMigration = null;
                setPrimaryWidgetsVisible(false);
            }
            return true;
        }
        if (mouseY >= topPos + 270 && mouseY < topPos + 290) {
            if (mouseX >= leftPos + 28 && mouseX < leftPos + 156) {
                if (connectButton.active) {
                    WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.CONNECT_SELECTED, 0,
                            selectedPositions);
                }
                return true;
            }
            if (mouseX >= leftPos + 160 && mouseX < leftPos + 302) {
                if (disconnectButton.active) {
                    WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.DISCONNECT_SELECTED, 0,
                            selectedPositions);
                }
                return true;
            }
            if (mouseX >= leftPos + 306 && mouseX < leftPos + 423) {
                WirelessEuRangePreview.toggle(menu.getPos());
                return true;
            }
        }
        return false;
    }

    private void drawInset(GuiGraphics graphics, int left, int top, int right, int bottom, int fill) {
        graphics.fill(left, top, right, bottom, 0xFF333333);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, 0xFFFFFFFF);
        graphics.fill(left + 4, top + 4, right - 4, bottom - 4, fill);
    }

    private void drawSelectionIndicator(GuiGraphics graphics, int left, int top, boolean selected) {
        int border = 0xFF3E3E3E;
        int fill = selected ? 0xFF4FB56A : 0xFF707C84;
        graphics.fill(left + 4, top, left + 12, top + 1, border);
        graphics.fill(left + 2, top + 1, left + 14, top + 3, border);
        graphics.fill(left, top + 3, left + 16, top + 13, border);
        graphics.fill(left + 2, top + 13, left + 14, top + 15, border);
        graphics.fill(left + 4, top + 15, left + 12, top + 16, border);
        graphics.fill(left + 5, top + 3, left + 11, top + 13, fill);
        graphics.fill(left + 3, top + 5, left + 13, top + 11, fill);
    }

    private void drawLocateIcon(GuiGraphics graphics, int centerX, int centerY, int color) {
        graphics.fill(centerX - 1, centerY - 6, centerX + 1, centerY + 7, color);
        graphics.fill(centerX - 6, centerY - 1, centerX + 7, centerY + 1, color);
        graphics.fill(centerX - 3, centerY - 3, centerX + 3, centerY + 3, 0xFF969696);
        graphics.fill(centerX - 2, centerY - 2, centerX + 2, centerY + 2, color);
    }

    private void drawBar(GuiGraphics graphics, int left, int top, int width, int height, double ratio, int color) {
        graphics.fill(left, top, left + width, top + height, 0xFF484848);
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, ratio)) * (width - 2));
        graphics.fill(left + 1, top + 1, left + 1 + filled, top + height - 1, color);
    }

    private void drawTerminalButton(GuiGraphics graphics, int left, int top, int width, int height,
                                    Component label, boolean selected, boolean active, int mouseX, int mouseY) {
        boolean hovered = active && mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < top + height;
        int face = !active ? 0xFF8D8D8D : selected ? 0xFF6FAF7D : hovered ? 0xFFD0D0D0 : 0xFFB8B8B8;
        graphics.fill(left, top, left + width, top + height, 0xFF2D2D2D);
        graphics.fill(left + 2, top + 2, left + width - 2, top + height - 2, 0xFFF6F6F6);
        graphics.fill(left + 4, top + 4, left + width - 4, top + height - 4, face);
        int color = !active ? 0xFF6A6A6A : selected ? 0xFF173C25 : 0xFF303030;
        int textLeft = left + (width - font.width(label)) / 2;
        graphics.drawString(font, label, textLeft, top + (height - 8) / 2, color, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (rejectionMessage != null) {
            return true;
        }
        ClientBroadcasterState.State state = state();
        int maximumOffset = Math.max(0, displayRows(state).size() - LIST_VISIBLE_ROWS);
        if (mouseX >= leftPos + 28 && mouseX < leftPos + 302 && mouseY >= topPos + 54 && mouseY < topPos + 263) {
            scrollOffset = Math.max(0, Math.min(maximumOffset, scrollOffset - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void drawTargets(GuiGraphics graphics, ClientBroadcasterState.State state, int mouseX, int mouseY) {
        List<DisplayRow> rows = displayRows(state);
        int maximumOffset = Math.max(0, rows.size() - LIST_VISIBLE_ROWS);
        scrollOffset = Math.min(scrollOffset, maximumOffset);
        graphics.enableScissor(leftPos + 33, topPos + 58, leftPos + 298, topPos + 263);
        try {
            for (int row = 0; row < LIST_VISIBLE_ROWS; row++) {
                int index = scrollOffset + row;
                if (index >= rows.size()) {
                    break;
                }
                DisplayRow displayRow = rows.get(index);
                int y = topPos + 60 + row * LIST_ROW_HEIGHT;
                WirelessTarget target = displayRow.target();
                boolean selected = selectedPositions.contains(target.pos().asLong());
                boolean connected = displayRow.connected();
                boolean hovered = mouseX >= leftPos + 33 && mouseX < leftPos + 297
                        && mouseY >= y && mouseY < y + LIST_ROW_HEIGHT;
                int background = selected ? 0xFF84AB8B : hovered ? 0xFFB4B4B4 : 0xFF969696;
                graphics.fill(leftPos + 36, y, leftPos + 294, y + 16, background);
                graphics.fill(leftPos + 36, y + 16, leftPos + 294, y + 17, 0xFF666666);
                drawSelectionIndicator(graphics, leftPos + 40, y, selected);

                String label = Component.translatable(target.name()).getString() + "  " + target.amperage() + "A "
                        + WirelessEuBroadcasterMachine.tierName(target.tier()) + " [" + shortKind(target) + "]";
                label = font.plainSubstrByWidth(label, 207);
                graphics.drawString(font, label, leftPos + 60, y + 4, connected ? 0xFF245D3A : 0xFF303030, false);
                drawLocateIcon(graphics, leftPos + 276, y + 8, 0xFF303030);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private void drawMachineInfo(GuiGraphics graphics, ClientBroadcasterState.State state) {
        int x = leftPos + 314;
        int y = topPos + 61;
        graphics.drawString(font, Component.translatable("gui.wireless_eu.buffer"), x, y, 0xFF3C3C3C, false);
        String buffer = WirelessEuBroadcasterMachine.formatCompactEu(state.storedEu()) + " / "
                + WirelessEuBroadcasterMachine.formatCompactEu(state.capacityEu());
        graphics.drawString(font, font.plainSubstrByWidth(buffer, 101), x, y + 13, 0xFF202020, false);
        drawBar(graphics, x, y + 27, 101, 7, state.capacityEu() <= 0 ? 0.0D
                : (double) state.storedEu() / (double) state.capacityEu(), 0xFF5A9E7A);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.input"), x, y + 45, 0xFF3C3C3C, false);
        long inputEuPerTick = toEuPerTick(state.inputEuPerSecond());
        String input = WirelessEuBroadcasterMachine.formatCompactEu(inputEuPerTick) + " EU/t  "
                + WirelessEuBroadcasterMachine.formatInputEquivalent(inputEuPerTick);
        graphics.drawString(font, font.plainSubstrByWidth(input, 101), x, y + 58, 0xFF202020, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.time_to_full"), x, y + 78, 0xFF3C3C3C, false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatDuration(state.fillTimeSeconds()), x, y + 91,
                0xFF202020, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.time_to_empty"), x, y + 108, 0xFF3C3C3C,
                false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatDuration(state.depletionTimeSeconds()), x, y + 121,
                0xFF202020, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.connection_amperage"), x, y + 138,
                0xFF3C3C3C, false);
        String load = WirelessEuBroadcasterMachine.formatAmperageLoad(state.connectedLoadEuPerTick(),
                        state.voltage()) + " "
                + Component.translatable("gui.wireless_eu.used_of").getString() + " " + state.outputAmperage() + "A";
        graphics.drawString(font, font.plainSubstrByWidth(load, 101), x, y + 151, 0xFF202020, false);
        graphics.drawString(font, state.canConfigure()
                ? Component.translatable("gui.wireless_eu.owner")
                : Component.translatable("gui.wireless_eu.read_only"), x, y + 168,
                state.canConfigure() ? 0xFF2D7D49 : 0xFF9A3333, false);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        WirelessEuNetwork.ConnectionRejection rejection = ClientBroadcasterState.takeConnectionRejection(menu.getPos());
        if (rejection != null) {
            rejectionMessage = switch (rejection) {
                case OUTPUT_FULL -> Component.translatable("gui.wireless_eu.connection_rejected_full");
                case BROADCASTER_LIMIT -> Component.translatable("gui.wireless_eu.connection_rejected_broadcaster_limit");
                default -> Component.translatable("gui.wireless_eu.connection_rejected_increase");
            };
            dismissalButton.visible = false;
            clearDetailTooltip();
        }
    }

    private void renderRejectionDialog(GuiGraphics graphics, int mouseX, int mouseY) {
        int dialogLeft = leftPos + 95;
        int dialogTop = topPos + 103;
        int dialogRight = dialogLeft + 240;
        int dialogBottom = dialogTop + 94;
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xB0000000);
        graphics.fill(dialogLeft, dialogTop, dialogRight, dialogBottom, 0xFF292929);
        graphics.fill(dialogLeft + 2, dialogTop + 2, dialogRight - 2, dialogBottom - 2, 0xFFF1F1F1);
        graphics.fill(dialogLeft + 5, dialogTop + 5, dialogRight - 5, dialogBottom - 5, 0xFFC1C1C1);
        int messageLeft = leftPos + (imageWidth - font.width(rejectionMessage)) / 2;
        graphics.drawString(font, rejectionMessage, messageLeft, dialogTop + 27, 0xFF4A2020, false);
        drawTerminalButton(graphics, leftPos + 174, topPos + 162, 82, 20,
                Component.literal("OK"), false, true, mouseX, mouseY);
    }

    private Component rangePreviewLabel() {
        return Component.translatable(WirelessEuRangePreview.isEnabled(menu.getPos())
                ? "gui.wireless_eu.range_preview_hide"
                : "gui.wireless_eu.range_preview_show");
    }

    private void dismissRejection() {
        rejectionMessage = null;
        dismissalButton.visible = false;
    }

    private void renderDetailTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        DetailTarget target = detailTargetAt(mouseX, mouseY);
        long now = System.currentTimeMillis();
        if (target != hoveredDetailTarget) {
            hoveredDetailTarget = target;
            detailHoverStartMillis = target == null ? 0L : now;
            frozenDetailTooltip = null;
        } else if (target != null && frozenDetailTooltip == null
                && now - detailHoverStartMillis >= DETAIL_TOOLTIP_DELAY_MILLIS) {
            ClientBroadcasterState.State state = state();
            frozenDetailTooltip = target == DetailTarget.BUFFER
                    ? Component.literal(WirelessEuBroadcasterMachine.formatEu(state.storedEu()) + " / "
                    + WirelessEuBroadcasterMachine.formatEu(state.capacityEu()) + " EU")
                    : Component.literal(WirelessEuBroadcasterMachine.formatEu(toEuPerTick(state.inputEuPerSecond()))
                    + " EU/t (" + WirelessEuBroadcasterMachine.formatInputEquivalent(
                    toEuPerTick(state.inputEuPerSecond())) + ")");
        }
        if (frozenDetailTooltip != null) {
            graphics.renderTooltip(font, frozenDetailTooltip, mouseX, mouseY);
        }
    }

    private DetailTarget detailTargetAt(int mouseX, int mouseY) {
        if (mouseX < leftPos + 306 || mouseX >= leftPos + 423) {
            return null;
        }
        if (mouseY >= topPos + 56 && mouseY < topPos + 100) {
            return DetailTarget.BUFFER;
        }
        if (mouseY >= topPos + 100 && mouseY < topPos + 140) {
            return DetailTarget.INPUT;
        }
        return null;
    }

    private void clearDetailTooltip() {
        hoveredDetailTarget = null;
        detailHoverStartMillis = 0L;
        frozenDetailTooltip = null;
    }

    private static long toEuPerTick(long euPerSecond) {
        return euPerSecond / 20L;
    }

    private static String shortKind(WirelessTarget target) {
        return switch (target.kind()) {
            case ENERGY_STORAGE -> "ENERGY";
            case LASER_STORAGE -> "LASER";
            case SINGLEBLOCK_MACHINE -> "MACHINE";
        };
    }

    private List<DisplayRow> displayRows(ClientBroadcasterState.State state) {
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<DisplayRow> rows = new ArrayList<>();
        for (WirelessTarget target : state.scanResults()) {
            boolean connected = state.isConnected(target);
            if (connected == (targetFilter == TargetFilter.CONNECTED) && matches(target, query)) {
                rows.add(new DisplayRow(target, false, connected));
            }
        }
        return rows;
    }

    private boolean matches(WirelessTarget target, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String translated = Component.translatable(target.name()).getString().toLowerCase(Locale.ROOT);
        String metadata = (target.name() + " " + WirelessEuBroadcasterMachine.tierName(target.tier()) + " "
                + shortKind(target)).toLowerCase(Locale.ROOT);
        return translated.contains(query) || metadata.contains(query);
    }

    private ClientBroadcasterState.State state() {
        return ClientBroadcasterState.get(menu.getPos());
    }

    private enum DetailTarget {
        BUFFER,
        INPUT
    }

    private enum TargetFilter {
        UNCONNECTED,
        CONNECTED
    }

    private record DisplayRow(WirelessTarget target, boolean sectionHeader, boolean connected) {
    }
}
