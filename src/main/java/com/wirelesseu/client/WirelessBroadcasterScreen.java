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
    private static final int LIST_VISIBLE_ROWS = 7;
    private static final long DETAIL_TOOLTIP_DELAY_MILLIS = 1_500L;
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
        imageWidth = 410;
        imageHeight = 250;
        inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();
        scanButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.scan"), button ->
                        WirelessEuNetwork.sendAction(menu.getPos(), WirelessEuNetwork.ServerAction.SCAN, 0, List.of()))
                .bounds(leftPos + 10, topPos + 29, 82, 18)
                .build());

        searchBox = addRenderableWidget(new EditBox(font,
                leftPos + 10, topPos + 53, 237, 18,
                Component.translatable("gui.wireless_eu.search")));
        searchBox.setHint(Component.translatable("gui.wireless_eu.search"));
        searchBox.setResponder(ignored -> scrollOffset = 0);
        unconnectedButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.wireless_eu.filter_unconnected"), button -> {
                            targetFilter = TargetFilter.UNCONNECTED;
                            scrollOffset = 0;
                        })
                .bounds(leftPos + 10, topPos + 75, 112, 18)
                .build());
        connectedButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.wireless_eu.filter_connected"), button -> {
                            targetFilter = TargetFilter.CONNECTED;
                            scrollOffset = 0;
                        })
                .bounds(leftPos + 128, topPos + 75, 112, 18)
                .build());

        connectButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.connect"), button ->
                        WirelessEuNetwork.sendAction(menu.getPos(),
                                WirelessEuNetwork.ServerAction.CONNECT_SELECTED, 0, selectedPositions))
                .bounds(leftPos + 10, topPos + 220, 112, 18)
                .build());
        disconnectButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.disconnect"),
                        button -> WirelessEuNetwork.sendAction(menu.getPos(),
                                WirelessEuNetwork.ServerAction.DISCONNECT_SELECTED, 0, selectedPositions))
                .bounds(leftPos + 128, topPos + 220, 112, 18)
                .build());
        rangePreviewButton = addRenderableWidget(Button.builder(rangePreviewLabel(), button -> {
                    WirelessEuRangePreview.toggle(menu.getPos());
                    button.setMessage(rangePreviewLabel());
                })
                .bounds(leftPos + 246, topPos + 220, 154, 18)
                .build());
        migrationButton = addRenderableWidget(Button.builder(Component.translatable("gui.wireless_eu.migrate"), button -> {
                    migrationMenu = true;
                    pendingMigration = null;
                    setPrimaryWidgetsVisible(false);
                })
                .bounds(leftPos + 256, topPos + 20, 144, 18)
                .build());
        dismissalButton = addRenderableWidget(Button.builder(Component.literal("OK"), button -> dismissRejection())
                .bounds(leftPos + 165, topPos + 148, 80, 20)
                .build());
        dismissalButton.visible = false;
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
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0161B20);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xF02A3038);
        graphics.fill(leftPos + 7, topPos + 95, leftPos + 250, topPos + 215, 0xD0111519);
        graphics.fill(leftPos + 256, topPos + 53, leftPos + imageWidth - 7, topPos + 215, 0xD0111519);

        ClientBroadcasterState.State state = state();
        scanButton.active = state.canConfigure();
        unconnectedButton.active = targetFilter != TargetFilter.UNCONNECTED;
        connectedButton.active = targetFilter != TargetFilter.CONNECTED;
        connectButton.active = state.canConfigure() && !selectedPositions.isEmpty() && rejectionMessage == null;
        disconnectButton.active = state.canConfigure() && !selectedPositions.isEmpty() && rejectionMessage == null;
        rangePreviewButton.setMessage(rangePreviewLabel());
        migrationButton.active = state.canConfigure() && !state.higherBroadcasters().isEmpty() && rejectionMessage == null;

        graphics.drawString(font, title, leftPos + 10, topPos + 11, 0xFFFFFF, false);
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
            if (dismissalButton.isMouseOver(mouseX, mouseY)) {
                return dismissalButton.mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }
        if (migrationMenu) {
            int modalLeft = leftPos + 75;
            int modalTop = topPos + 25;
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
        int listTop = topPos + 95;
        List<DisplayRow> rows = displayRows(state);
        if (button == 0 && mouseX >= leftPos + 10 && mouseX < leftPos + 247
                && mouseY >= listTop && mouseY < listTop + LIST_VISIBLE_ROWS * 16) {
            int row = (int) ((mouseY - listTop) / 16);
            int index = row + scrollOffset;
            if (index >= 0 && index < rows.size() && !rows.get(index).sectionHeader()) {
                WirelessTarget target = rows.get(index).target();
                if (mouseX >= leftPos + 204) {
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
        int modalLeft = leftPos + 75;
        int modalTop = topPos + 25;
        int modalRight = modalLeft + 260;
        int rowCount = Math.min(5, state.higherBroadcasters().size());
        int modalBottom = modalTop + (pendingMigration == null ? 72 + rowCount * 26 : 160);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 100.0D);
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(modalLeft, modalTop, modalRight, modalBottom, 0xFF151A20);
        graphics.fill(modalLeft + 3, modalTop + 3, modalRight - 3, modalBottom - 3, 0xFF303945);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.migration_targets"), modalLeft + 12,
                modalTop + 13, 0xB8D6FF, false);
        graphics.fill(modalRight - 35, modalTop + 7, modalRight - 7, modalTop + 33, 0xFF633D44);
        graphics.drawCenteredString(font, Component.literal("X"), modalRight - 21, modalTop + 13, 0xFFFFFF);
        if (pendingMigration != null) {
            graphics.drawString(font, Component.translatable("gui.wireless_eu.migration_confirm"), modalLeft + 18, modalTop + 58, 0xFFFFFF, false);
            graphics.drawString(font, WirelessEuBroadcasterMachine.tierName(pendingMigration.tier()) + " ["
                    + pendingMigration.pos().getX() + ", " + pendingMigration.pos().getY() + ", " + pendingMigration.pos().getZ() + "]",
                    modalLeft + 18, modalTop + 82, 0xB8D6FF, false);
            graphics.fill(modalLeft + 50, modalTop + 120, modalLeft + 125, modalTop + 144, 0xFF4B6B52);
            graphics.fill(modalLeft + 135, modalTop + 120, modalLeft + 210, modalTop + 144, 0xFF633D44);
            graphics.drawCenteredString(font, Component.translatable("gui.wireless_eu.confirm"), modalLeft + 87, modalTop + 127, 0xFFFFFF);
            graphics.drawCenteredString(font, Component.translatable("gui.wireless_eu.cancel"), modalLeft + 172, modalTop + 127, 0xFFFFFF);
            graphics.pose().popPose();
            return;
        }
        for (int i = 0; i < state.higherBroadcasters().size() && i < 5; i++) {
            var target = state.higherBroadcasters().get(i);
            int rowTop = modalTop + 50 + i * 26;
            graphics.fill(modalLeft + 12, rowTop, modalRight - 12, rowTop + 22,
                    (i & 1) == 0 ? 0xFF3A4654 : 0xFF343E4A);
            graphics.drawString(font, (i + 1) + ". " + WirelessEuBroadcasterMachine.tierName(target.tier()),
                    modalLeft + 20, rowTop + 7, 0xFFFFFF, false);
            graphics.drawString(font, Math.round(Math.sqrt(target.distanceSquared())) + " blocks",
                    modalRight - 90, rowTop + 7, 0xFFB8D6FF, false);
        }
        graphics.pose().popPose();
    }

    private void setPrimaryWidgetsVisible(boolean visible) {
        scanButton.visible = visible;
        searchBox.visible = visible;
        unconnectedButton.visible = visible;
        connectedButton.visible = visible;
        connectButton.visible = visible;
        disconnectButton.visible = visible;
        rangePreviewButton.visible = visible;
        migrationButton.visible = visible;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (rejectionMessage != null) {
            return true;
        }
        ClientBroadcasterState.State state = state();
        int maximumOffset = Math.max(0, displayRows(state).size() - LIST_VISIBLE_ROWS);
        if (mouseX >= leftPos + 7 && mouseX < leftPos + 250 && mouseY >= topPos + 95 && mouseY < topPos + 215) {
            scrollOffset = Math.max(0, Math.min(maximumOffset, scrollOffset - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void drawTargets(GuiGraphics graphics, ClientBroadcasterState.State state, int mouseX, int mouseY) {
        List<DisplayRow> rows = displayRows(state);
        int maximumOffset = Math.max(0, rows.size() - LIST_VISIBLE_ROWS);
        scrollOffset = Math.min(scrollOffset, maximumOffset);
        for (int row = 0; row < LIST_VISIBLE_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= rows.size()) {
                return;
            }
            DisplayRow displayRow = rows.get(index);
            int y = topPos + 95 + row * 16;
            if (displayRow.sectionHeader()) {
                graphics.drawString(font,
                        Component.translatable(displayRow.connected()
                                ? "gui.wireless_eu.connected" : "gui.wireless_eu.unconnected"),
                        leftPos + 12, y + 3, 0xFFB8D6FF, false);
                continue;
            }
            WirelessTarget target = displayRow.target();
            boolean selected = selectedPositions.contains(target.pos().asLong());
            boolean connected = displayRow.connected();
            int background = selected ? 0xFF294966 : (row & 1) == 0 ? 0x90333A43 : 0x90252B31;
            graphics.fill(leftPos + 10, y, leftPos + 247, y + 14, background);
            graphics.fill(leftPos + 13, y + 3, leftPos + 21, y + 11, selected ? 0xFF81D4FA : 0xFF5B6773);

            String label = Component.translatable(target.name()).getString() + "  " + target.amperage() + "A "
                    + WirelessEuBroadcasterMachine.tierName(target.tier()) + " [" + shortKind(target) + "]";
            label = font.plainSubstrByWidth(label, 177);
            graphics.drawString(font, label, leftPos + 26, y + 3, connected ? 0xFF80E89A : 0xFFE0E6EB, false);
            graphics.drawString(font, Component.translatable("gui.wireless_eu.locate"), leftPos + 207, y + 3,
                    0xFFB8D6FF, false);
            if (mouseX >= leftPos + 10 && mouseX < leftPos + 247 && mouseY >= y && mouseY < y + 14) {
                graphics.fill(leftPos + 10, y, leftPos + 247, y + 1, 0xFFB8D6FF);
            }
        }
    }

    private void drawMachineInfo(GuiGraphics graphics, ClientBroadcasterState.State state) {
        int x = leftPos + 263;
        int y = topPos + 59;
        graphics.drawString(font, Component.translatable("gui.wireless_eu.buffer"), x, y, 0xB8D6FF, false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatCompactEu(state.storedEu()) + " / "
                + WirelessEuBroadcasterMachine.formatCompactEu(state.capacityEu()) + " EU", x, y + 13, 0xFFFFFF,
                false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.input"), x, y + 28, 0xB8D6FF, false);
        long inputEuPerTick = toEuPerTick(state.inputEuPerSecond());
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatCompactEu(inputEuPerTick) + " EU/t  "
                + WirelessEuBroadcasterMachine.formatInputEquivalent(inputEuPerTick), x, y + 41, 0xFFFFFF, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.time_to_full"), x, y + 56, 0xB8D6FF, false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatDuration(state.fillTimeSeconds()), x, y + 69,
                0xFFFFFF, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.time_to_empty"), x, y + 84, 0xB8D6FF,
                false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatDuration(state.depletionTimeSeconds()), x, y + 97,
                0xFFFFFF, false);
        graphics.drawString(font, Component.translatable("gui.wireless_eu.connection_amperage"), x, y + 112,
                0xB8D6FF, false);
        graphics.drawString(font, WirelessEuBroadcasterMachine.formatAmperageLoad(state.connectedLoadEuPerTick(),
                        state.voltage()) + " "
                + Component.translatable("gui.wireless_eu.used_of").getString() + " " + state.outputAmperage() + "A",
                x, y + 125, 0xFFFFFF, false);
        graphics.drawString(font, state.canConfigure()
                ? Component.translatable("gui.wireless_eu.owner")
                : Component.translatable("gui.wireless_eu.read_only"), x, y + 139,
                state.canConfigure() ? 0xFF80E89A : 0xFFFFA0A0, false);
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
            dismissalButton.visible = true;
            clearDetailTooltip();
        }
    }

    private void renderRejectionDialog(GuiGraphics graphics, int mouseX, int mouseY) {
        int dialogLeft = leftPos + 90;
        int dialogTop = topPos + 78;
        int dialogRight = dialogLeft + 230;
        int dialogBottom = dialogTop + 94;
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xB0000000);
        graphics.fill(dialogLeft, dialogTop, dialogRight, dialogBottom, 0xFF1A2028);
        graphics.fill(dialogLeft + 2, dialogTop + 2, dialogRight - 2, dialogBottom - 2, 0xFF303945);
        graphics.drawCenteredString(font, rejectionMessage, leftPos + imageWidth / 2, dialogTop + 27, 0xFFFFFF);
        dismissalButton.render(graphics, mouseX, mouseY, 0.0F);
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
        if (mouseX < leftPos + 263 || mouseX >= leftPos + imageWidth - 7) {
            return null;
        }
        if (mouseY >= topPos + 70 && mouseY < topPos + 86) {
            return DetailTarget.BUFFER;
        }
        if (mouseY >= topPos + 98 && mouseY < topPos + 114) {
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
