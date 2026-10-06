package com.wirelesseu.client;

import com.wirelesseu.gui.WirelessEuContainer;
import com.wirelesseu.machine.TargetKind;
import com.wirelesseu.machine.WirelessEuBroadcasterMachine;
import com.wirelesseu.machine.WirelessEuMachines;
import com.wirelesseu.machine.WirelessRemoteTarget;
import com.wirelesseu.machine.WirelessTarget;
import com.wirelesseu.network.BroadcasterState;
import com.wirelesseu.network.WirelessEuNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Terminal style broadcaster screen. Geometry is a direct port of the 1.20.1 screen: a 430x300 base layout that is
 * scaled down to fit the current GUI resolution.
 */
public final class WirelessBroadcasterScreen extends GuiScreen {
    private static final int BASE_WIDTH = 430;
    private static final int BASE_HEIGHT = 300;
    private static final int LIST_VISIBLE_ROWS = 11;
    private static final int LIST_ROW_HEIGHT = 18;
    private static final int LIST_TOP = 60;
    private static final long DETAIL_TOOLTIP_DELAY_MILLIS = 500L;

    private final BlockPos pos;
    private final WirelessEuContainer clientContainer;
    private final Set<WirelessEuNetwork.TargetReference> selectedTargets = new HashSet<>();
    private int scrollOffset;
    private int leftPos;
    private int topPos;
    private float uiScale = 1.0F;
    private GuiTextField searchBox;
    private TargetFilter targetFilter = TargetFilter.UNCONNECTED;
    private String rejectionMessage;
    private boolean migrationMenu;
    private WirelessEuNetwork.HigherBroadcasterInfo pendingMigration;
    private DetailTarget hoveredDetailTarget;
    private long detailHoverStartMillis;
    private String frozenDetailTooltip;

    public WirelessBroadcasterScreen(BlockPos pos) {
        this.pos = pos;
        this.clientContainer = new WirelessEuContainer(pos);
        // Only ESC closes this screen; the inventory key must not trigger the ingame key handling.
        this.allowUserInput = false;
    }

    @Override
    public void initGui() {
        if (mc.player != null && mc.player.openContainer != clientContainer) {
            // Container screens hand their own container to openContainer (AE2 does this in initGui too), so Forge's
            // OpenGuiHandler stamps the server window id onto it. Without this the player's inventory container would
            // be stamped instead, and every later inventory click would be silently dropped by the server.
            mc.player.openContainer = clientContainer;
        }
        float widthScale = (width - 16) / (float) BASE_WIDTH;
        float heightScale = (height - 16) / (float) BASE_HEIGHT;
        uiScale = Math.min(1.0F, Math.min(widthScale, heightScale));
        leftPos = Math.round((width - BASE_WIDTH * uiScale) / 2.0F);
        topPos = Math.round((height - BASE_HEIGHT * uiScale) / 2.0F);
        searchBox = new GuiTextField(0, fontRenderer, 126, 10, 174, 18);
        searchBox.setMaxStringLength(64);
        searchBox.setTextColor(0xE0E0E0);
        searchBox.setFocused(false);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchBox != null) {
            searchBox.updateCursorCounter();
        }
        WirelessEuNetwork.ConnectionRejection rejection = ClientBroadcasterState.takeConnectionRejection(pos);
        if (rejection != null) {
            rejectionMessage = rejectionText(rejection);
            migrationMenu = false;
            pendingMigration = null;
            clearDetailTooltip();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawWorldBackground(0);
        int baseMouseX = toBaseX(mouseX);
        int baseMouseY = toBaseY(mouseY);
        GlStateManager.pushMatrix();
        GlStateManager.translate(leftPos, topPos, 0.0F);
        GlStateManager.scale(uiScale, uiScale, 1.0F);
        renderTerminal(baseMouseX, baseMouseY);
        if (rejectionMessage != null) {
            renderRejectionDialog(baseMouseX, baseMouseY);
        } else if (migrationMenu) {
            renderMigrationModal(baseMouseX, baseMouseY);
        }
        GlStateManager.popMatrix();
        if (rejectionMessage == null && !migrationMenu) {
            renderDetailTooltip(mouseX, mouseY, baseMouseX, baseMouseY);
        }
    }

    private void renderTerminal(int mouseX, int mouseY) {
        drawPanelShell();
        BroadcasterState state = state();
        boolean canConfigure = state.canConfigure;
        boolean busy = rejectionMessage != null;
        boolean connectActive = canConfigure && !selectedTargets.isEmpty() && !busy;
        boolean migrateActive = canConfigure && !state.higherBroadcasters.isEmpty() && !busy;

        drawInset(124, 8, 302, 30, 0xFF979797);
        drawInset(28, 52, 302, 263, 0xFF8F8F8F);
        drawInset(306, 52, 423, 263, 0xFFA9A9A9);

        drawString(machineTitle(), 31, 14, 0xFF333333);
        drawTerminalButton(28, 10, 82, 18, I18n.format("gui.wireless_eu.scan"), false, canConfigure, mouseX, mouseY);
        drawTerminalButton(28, 32, 128, 18, I18n.format("gui.wireless_eu.filter_unconnected"),
                targetFilter == TargetFilter.UNCONNECTED, true, mouseX, mouseY);
        drawTerminalButton(160, 32, 142, 18, I18n.format("gui.wireless_eu.filter_connected"),
                targetFilter == TargetFilter.CONNECTED, true, mouseX, mouseY);
        drawTerminalButton(306, 32, 117, 18, I18n.format("gui.wireless_eu.migrate"), false, migrateActive, mouseX,
                mouseY);
        drawTerminalButton(28, 270, 128, 20, I18n.format("gui.wireless_eu.connect"), false, connectActive, mouseX,
                mouseY);
        drawTerminalButton(160, 270, 142, 20, I18n.format("gui.wireless_eu.disconnect"), false, connectActive, mouseX,
                mouseY);
        drawTerminalButton(306, 270, 117, 20, rangePreviewLabel(), WirelessEuRangePreview.isEnabled(pos), true, mouseX,
                mouseY);

        renderSearchBox();
        renderTargetList(state, mouseX, mouseY);
        renderMachineInfo(state);
    }

    private void drawPanelShell() {
        drawRect(0, 6, BASE_WIDTH, BASE_HEIGHT - 6, 0xFF111111);
        drawRect(6, 0, BASE_WIDTH - 6, BASE_HEIGHT, 0xFF111111);
        drawRect(2, 8, BASE_WIDTH - 2, BASE_HEIGHT - 8, 0xFFF1F1F1);
        drawRect(8, 2, BASE_WIDTH - 8, BASE_HEIGHT - 2, 0xFFF1F1F1);
        drawRect(4, 9, BASE_WIDTH - 4, BASE_HEIGHT - 9, 0xFFC6C6C6);
        drawRect(9, 4, BASE_WIDTH - 9, BASE_HEIGHT - 4, 0xFFC6C6C6);
    }

    private void renderSearchBox() {
        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty() && !searchBox.isFocused()) {
            drawString(I18n.format("gui.wireless_eu.search"), 130, 15, 0xFF7F7F7F);
        }
    }

    private void renderTargetList(BroadcasterState state, int mouseX, int mouseY) {
        List<Row> rows = displayRows(state);
        int maximumOffset = Math.max(0, rows.size() - LIST_VISIBLE_ROWS);
        scrollOffset = Math.min(scrollOffset, maximumOffset);
        enableScissor(33, 58, 298, 263);
        try {
            for (int row = 0; row < LIST_VISIBLE_ROWS; row++) {
                int index = scrollOffset + row;
                if (index >= rows.size()) {
                    break;
                }
                Row entry = rows.get(index);
                int y = LIST_TOP + row * LIST_ROW_HEIGHT;
                boolean selected = selectedTargets.contains(entry.reference);
                boolean hovered = mouseX >= 33 && mouseX < 298 && mouseY >= y && mouseY < y + LIST_ROW_HEIGHT;
                int background = selected ? 0xFF84AB8B : hovered ? 0xFFB4B4B4 : 0xFF969696;
                drawRect(36, y, 294, y + 16, background);
                drawRect(36, y + 16, 294, y + 17, 0xFF666666);
                drawSelectionIndicator(40, y, selected);
                drawTargetLabel(state, entry, 60, y + 4);
                drawLocateIcon(276, y + 8, 0xFF303030);
            }
        } finally {
            disableScissor();
        }
    }

    private void renderMachineInfo(BroadcasterState state) {
        int x = 314;
        int y = 61;
        drawString(I18n.format("gui.wireless_eu.buffer"), x, y, 0xFF3C3C3C);
        String buffer = WirelessEuBroadcasterMachine.formatCompactEu(state.storedEu) + " / "
                + WirelessEuBroadcasterMachine.formatCompactEu(state.capacityEu);
        drawFittedString(buffer, x, y + 13, 101, 0xFF202020);
        drawBar(x, y + 27, 101, 7, state.capacityEu.signum() <= 0 ? 0.0D
                : state.storedEu.doubleValue() / state.capacityEu.doubleValue(), 0xFF5A9E7A);

        drawString(I18n.format("gui.wireless_eu.input"), x, y + 45, 0xFF3C3C3C);
        BigInteger inputEuPerTick = toEuPerTick(state.inputEuPerSecond);
        String input = WirelessEuBroadcasterMachine.formatCompactEu(inputEuPerTick) + " EU/t  "
                + WirelessEuBroadcasterMachine.formatInputEquivalent(inputEuPerTick);
        drawString(trim(input, 101), x, y + 58, 0xFF202020);

        drawString(I18n.format("gui.wireless_eu.time_to_full"), x, y + 78, 0xFF3C3C3C);
        drawFittedString(WirelessEuBroadcasterMachine.formatDuration(state.fillTimeSeconds), x, y + 91, 101, 0xFF202020);
        drawString(I18n.format("gui.wireless_eu.time_to_empty"), x, y + 108, 0xFF3C3C3C);
        drawFittedString(WirelessEuBroadcasterMachine.formatDuration(state.depletionTimeSeconds), x, y + 121, 101, 0xFF202020);

        drawString(I18n.format("gui.wireless_eu.connection_amperage"), x, y + 138, 0xFF3C3C3C);
        String load = WirelessEuBroadcasterMachine.formatAmperageLoad(state.connectedLoadEuPerTick, state.voltage)
                + " " + I18n.format("gui.wireless_eu.used_of") + " " + I18n.format("gui.wireless_eu.unlimited");
        drawString(trim(load, 101), x, y + 151, 0xFF202020);
        drawString(I18n.format(state.canConfigure ? "gui.wireless_eu.owner" : "gui.wireless_eu.read_only"),
                x, y + 168, state.canConfigure ? 0xFF2D7D49 : 0xFF9A3333);
    }

    private void drawTargetLabel(BroadcasterState state, Row row, int x, int y) {
        String details = "  " + row.amperage + "A " + WirelessEuBroadcasterMachine.tierName(row.tier)
                + " [" + shortKind(row.kind) + "]";
        boolean crossDimension = row.remote && state.dimension != row.reference.dimension;
        String marker = row.remote ? "[" + (crossDimension ? dimensionMarker(row.reference.dimension)
                : I18n.format("gui.wireless_eu.marker_far")) + "]" : "";
        int detailsWidth = fontRenderer.getStringWidth(details);
        int markerWidth = fontRenderer.getStringWidth(marker);
        int nameWidth = Math.max(1, 207 - detailsWidth - markerWidth);
        String name = trim(I18n.format(row.name + ".name"), nameWidth);
        int color = row.connected ? 0xFF245D3A : 0xFF303030;
        drawString(name, x, y, color);
        drawString(details, x + fontRenderer.getStringWidth(name), y, color);
        if (row.remote) {
            drawString(marker, x + fontRenderer.getStringWidth(name) + detailsWidth, y,
                    crossDimension ? 0xFFFF55FF : 0xFFFFFF55);
        }
    }

    private String dimensionMarker(int dimension) {
        DimensionType type = DimensionType.getById(dimension);
        String name = type == null ? String.valueOf(dimension) : type.getName();
        String key = "gui.wireless_eu.dimension." + name;
        String translated = I18n.format(key);
        if (!translated.isEmpty() && !translated.equals(key)) {
            name = translated;
        }
        return name.isEmpty() ? "?" : name.substring(0, 1);
    }

    private void renderMigrationModal(int mouseX, int mouseY) {
        BroadcasterState state = state();
        int modalLeft = 82;
        int modalTop = 48;
        int modalRight = modalLeft + 260;
        int rowCount = Math.min(5, state.higherBroadcasters.size());
        int modalBottom = modalTop + (pendingMigration == null ? 72 + rowCount * 26 : 160);
        drawRect(0, 0, BASE_WIDTH, BASE_HEIGHT, 0x99000000);
        drawRect(modalLeft, modalTop, modalRight, modalBottom, 0xFF252525);
        drawRect(modalLeft + 2, modalTop + 2, modalRight - 2, modalBottom - 2, 0xFFF2F2F2);
        drawRect(modalLeft + 5, modalTop + 5, modalRight - 5, modalBottom - 5, 0xFFC2C2C2);
        drawString(I18n.format("gui.wireless_eu.migration_targets"), modalLeft + 12, modalTop + 13, 0xFF303030);
        drawTerminalButton(modalRight - 35, modalTop + 7, 28, 26, "X", false, true, mouseX, mouseY);
        if (pendingMigration != null) {
            drawString(I18n.format("gui.wireless_eu.migration_confirm"), modalLeft + 18, modalTop + 58, 0xFF303030);
            drawString(WirelessEuBroadcasterMachine.tierName(pendingMigration.tier) + " ["
                    + pendingMigration.pos.getX() + ", " + pendingMigration.pos.getY() + ", "
                    + pendingMigration.pos.getZ() + "]", modalLeft + 18, modalTop + 82, 0xFF303030);
            drawTerminalButton(modalLeft + 50, modalTop + 120, 75, 24, I18n.format("gui.wireless_eu.confirm"), false,
                    true, -1, -1);
            drawTerminalButton(modalLeft + 135, modalTop + 120, 75, 24, I18n.format("gui.wireless_eu.cancel"), false,
                    true, -1, -1);
            return;
        }
        for (int index = 0; index < state.higherBroadcasters.size() && index < 5; index++) {
            WirelessEuNetwork.HigherBroadcasterInfo target = state.higherBroadcasters.get(index);
            int rowTop = modalTop + 50 + index * 26;
            drawRect(modalLeft + 12, rowTop, modalRight - 12, rowTop + 22, 0xFF3A3A3A);
            drawRect(modalLeft + 14, rowTop + 2, modalRight - 14, rowTop + 20,
                    (index & 1) == 0 ? 0xFFA8A8A8 : 0xFF9C9C9C);
            drawString((index + 1) + ". " + WirelessEuBroadcasterMachine.tierName(target.tier), modalLeft + 20,
                    rowTop + 7, 0xFF303030);
            drawString(Math.round(Math.sqrt(target.distanceSquared)) + " blocks", modalRight - 90, rowTop + 7,
                    0xFF303030);
        }
    }

    private void renderRejectionDialog(int mouseX, int mouseY) {
        int dialogLeft = 95;
        int dialogTop = 103;
        int dialogRight = dialogLeft + 240;
        int dialogBottom = dialogTop + 94;
        drawRect(0, 0, BASE_WIDTH, BASE_HEIGHT, 0xB0000000);
        drawRect(dialogLeft, dialogTop, dialogRight, dialogBottom, 0xFF292929);
        drawRect(dialogLeft + 2, dialogTop + 2, dialogRight - 2, dialogBottom - 2, 0xFFF1F1F1);
        drawRect(dialogLeft + 5, dialogTop + 5, dialogRight - 5, dialogBottom - 5, 0xFFC1C1C1);
        drawCenteredString(rejectionMessage, BASE_WIDTH / 2, dialogTop + 27, 0xFF4A2020);
        drawTerminalButton(174, 162, 82, 20, "OK", false, true, mouseX, mouseY);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int baseMouseX = toBaseX(mouseX);
        int baseMouseY = toBaseY(mouseY);
        if (rejectionMessage != null) {
            if (mouseButton == 0 && inRect(baseMouseX, baseMouseY, 174, 162, 82, 20)) {
                rejectionMessage = null;
                playClick();
            }
            return;
        }
        if (migrationMenu) {
            handleMigrationClick(baseMouseX, baseMouseY, mouseButton);
            return;
        }
        searchBox.setFocused(false);
        searchBox.mouseClicked(baseMouseX, baseMouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }
        if (handleTerminalClick(baseMouseX, baseMouseY)) {
            return;
        }
        handleListClick(baseMouseX, baseMouseY);
    }

    private boolean handleTerminalClick(int mouseX, int mouseY) {
        BroadcasterState state = state();
        if (inRect(mouseX, mouseY, 28, 10, 82, 18)) {
            if (state.canConfigure) {
                WirelessEuNetwork.sendAction(pos, WirelessEuNetwork.ServerAction.SCAN,
                        new ArrayList<WirelessEuNetwork.TargetReference>());
                playClick();
            }
            return true;
        }
        if (inRect(mouseX, mouseY, 28, 32, 128, 18)) {
            targetFilter = TargetFilter.UNCONNECTED;
            scrollOffset = 0;
            playClick();
            return true;
        }
        if (inRect(mouseX, mouseY, 160, 32, 142, 18)) {
            targetFilter = TargetFilter.CONNECTED;
            scrollOffset = 0;
            playClick();
            return true;
        }
        if (inRect(mouseX, mouseY, 306, 32, 117, 18)) {
            if (state.canConfigure && !state.higherBroadcasters.isEmpty()) {
                migrationMenu = true;
                pendingMigration = null;
                playClick();
            }
            return true;
        }
        if (mouseY >= 270 && mouseY < 290) {
            if (inRect(mouseX, mouseY, 28, 270, 128, 20)) {
                if (state.canConfigure && !selectedTargets.isEmpty()) {
                    sendSelected(WirelessEuNetwork.ServerAction.CONNECT_SELECTED);
                }
                return true;
            }
            if (inRect(mouseX, mouseY, 160, 270, 142, 20)) {
                if (state.canConfigure && !selectedTargets.isEmpty()) {
                    sendSelected(WirelessEuNetwork.ServerAction.DISCONNECT_SELECTED);
                }
                return true;
            }
            if (inRect(mouseX, mouseY, 306, 270, 117, 20)) {
                WirelessEuRangePreview.toggle(pos);
                playClick();
                return true;
            }
        }
        return false;
    }

    private void handleListClick(int mouseX, int mouseY) {
        BroadcasterState state = state();
        List<Row> rows = displayRows(state);
        if (mouseX < 34 || mouseX >= 298 || mouseY < LIST_TOP
                || mouseY >= LIST_TOP + LIST_VISIBLE_ROWS * LIST_ROW_HEIGHT) {
            return;
        }
        int index = (mouseY - LIST_TOP) / LIST_ROW_HEIGHT + scrollOffset;
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        if (mouseX >= 263) {
            WirelessEuNetwork.sendAction(pos, WirelessEuNetwork.ServerAction.LOCATE_TARGET,
                    Arrays.asList(row.reference));
            playClick();
            return;
        }
        if (!state.canConfigure) {
            return;
        }
        if (!selectedTargets.add(row.reference)) {
            selectedTargets.remove(row.reference);
        }
        playClick();
    }

    private void handleMigrationClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int modalLeft = 82;
        int modalTop = 48;
        if (inRect(mouseX, mouseY, modalLeft + 225, modalTop + 8, 28, 26)) {
            closeMigrationMenu();
            return;
        }
        if (pendingMigration != null) {
            if (inRect(mouseX, mouseY, modalLeft + 15, modalTop + 68, 230, 34)) {
                WirelessEuNetwork.sendAction(pos, WirelessEuNetwork.ServerAction.LOCATE_BROADCASTER,
                        Arrays.asList(new WirelessEuNetwork.TargetReference(state().dimension, pendingMigration.pos)));
                playClick();
                return;
            }
            if (inRect(mouseX, mouseY, modalLeft + 50, modalTop + 120, 75, 24)) {
                WirelessEuNetwork.sendAction(pos, WirelessEuNetwork.ServerAction.MIGRATE_TO_HIGHER,
                        Arrays.asList(new WirelessEuNetwork.TargetReference(state().dimension, pendingMigration.pos)));
                closeMigrationMenu();
                return;
            }
            if (inRect(mouseX, mouseY, modalLeft + 135, modalTop + 120, 75, 24)) {
                pendingMigration = null;
                playClick();
            }
            return;
        }
        List<WirelessEuNetwork.HigherBroadcasterInfo> targets = state().higherBroadcasters;
        for (int index = 0; index < targets.size() && index < 5; index++) {
            if (inRect(mouseX, mouseY, modalLeft + 12, modalTop + 50 + index * 26, 236, 24)) {
                pendingMigration = targets.get(index);
                playClick();
                return;
            }
        }
    }

    private void closeMigrationMenu() {
        migrationMenu = false;
        pendingMigration = null;
        playClick();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 || keyCode == mc.gameSettings.keyBindInventory.getKeyCode()) {
            // Same as vanilla GuiContainer.keyTyped (ESC or inventory key): closeScreen() sends CPacketCloseWindow so
            // the server drops this slot-less container. Without that packet the server keeps it open and silently
            // discards every following inventory click (window id mismatch), desyncing items until the player leaves
            // the 8-block range. The inventory key still only closes, it does not open the inventory.
            if (mc.player != null) {
                mc.player.closeScreen();
            } else {
                mc.displayGuiScreen(null);
            }
            if (mc.currentScreen == null) {
                mc.setIngameFocus();
            }
            return;
        }
        if (searchBox != null && searchBox.isFocused() && searchBox.textboxKeyTyped(typedChar, keyCode)) {
            scrollOffset = 0;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Drop a pressed inventory key so it cannot reopen anything after this screen closes.
        KeyBinding.unPressAllKeys();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || rejectionMessage != null || migrationMenu) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int baseMouseX = toBaseX(mouseX);
        int baseMouseY = toBaseY(mouseY);
        if (baseMouseX >= 28 && baseMouseX < 302 && baseMouseY >= 54 && baseMouseY < 263) {
            int maximumOffset = Math.max(0, displayRows(state()).size() - LIST_VISIBLE_ROWS);
            scrollOffset = Math.max(0, Math.min(maximumOffset,
                    scrollOffset - (int) Math.signum(wheel)));
        }
    }

    private void sendSelected(WirelessEuNetwork.ServerAction action) {
        WirelessEuNetwork.sendAction(pos, action, new ArrayList<>(selectedTargets));
        playClick();
    }

    private void renderDetailTooltip(int mouseX, int mouseY, int baseMouseX, int baseMouseY) {
        DetailTarget target = detailTargetAt(baseMouseX, baseMouseY);
        long now = System.currentTimeMillis();
        if (target != hoveredDetailTarget) {
            hoveredDetailTarget = target;
            detailHoverStartMillis = target == null ? 0L : now;
            frozenDetailTooltip = null;
        } else if (target != null && frozenDetailTooltip == null
                && now - detailHoverStartMillis >= DETAIL_TOOLTIP_DELAY_MILLIS) {
            BroadcasterState state = state();
            BigInteger inputEuPerTick = toEuPerTick(state.inputEuPerSecond);
            frozenDetailTooltip = target == DetailTarget.BUFFER
                    ? WirelessEuBroadcasterMachine.formatEu(state.storedEu) + " EU\n"
                    + WirelessEuBroadcasterMachine.formatEu(state.capacityEu) + " EU"
                    : WirelessEuBroadcasterMachine.formatEu(inputEuPerTick) + " EU/t ("
                    + WirelessEuBroadcasterMachine.formatInputEquivalent(inputEuPerTick) + ")";
        }
        if (frozenDetailTooltip != null) {
            drawHoveringText(Arrays.asList(frozenDetailTooltip.split("\n")), mouseX, mouseY);
        }
    }

    private DetailTarget detailTargetAt(int mouseX, int mouseY) {
        if (mouseX < 306 || mouseX >= 423) {
            return null;
        }
        if (mouseY >= 56 && mouseY < 100) {
            return DetailTarget.BUFFER;
        }
        if (mouseY >= 100 && mouseY < 140) {
            return DetailTarget.INPUT;
        }
        return null;
    }

    private void clearDetailTooltip() {
        hoveredDetailTarget = null;
        detailHoverStartMillis = 0L;
        frozenDetailTooltip = null;
    }

    private List<Row> displayRows(BroadcasterState state) {
        String query = searchBox == null ? "" : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        Set<WirelessEuNetwork.TargetReference> included = new HashSet<>();
        for (WirelessTarget target : state.scanResults) {
            WirelessEuNetwork.TargetReference reference =
                    new WirelessEuNetwork.TargetReference(state.dimension, target.pos);
            boolean connected = state.isConnected(reference);
            if (included.add(reference) && connected == (targetFilter == TargetFilter.CONNECTED)
                    && matches(target.name, target.tier, target.kind, query)) {
                rows.add(new Row(reference, target.name, target.kind, target.tier, target.amperage, false, connected));
            }
        }
        for (WirelessRemoteTarget target : state.remoteConnections) {
            WirelessEuNetwork.TargetReference reference =
                    new WirelessEuNetwork.TargetReference(target.dimension, target.pos);
            if (included.add(reference) && targetFilter == TargetFilter.CONNECTED
                    && matches(target.name, target.tier, target.kind, query)) {
                rows.add(new Row(reference, target.name, target.kind, target.tier, target.amperage, true, true));
            }
        }
        return rows;
    }

    private boolean matches(String name, int tier, TargetKind kind, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String translated = I18n.format(name + ".name").toLowerCase(Locale.ROOT);
        String metadata = (name + " " + WirelessEuBroadcasterMachine.tierName(tier) + " " + shortKind(kind))
                .toLowerCase(Locale.ROOT);
        return translated.contains(query) || metadata.contains(query);
    }

    private String machineTitle() {
        if (mc.world != null) {
            WirelessEuBroadcasterMachine machine = WirelessEuMachines.getMachine(mc.world, pos);
            if (machine != null) {
                return I18n.format(machine.getMetaName() + ".name");
            }
        }
        return I18n.format("gui.wireless_eu.title");
    }

    private String rangePreviewLabel() {
        return I18n.format(WirelessEuRangePreview.isEnabled(pos)
                ? "gui.wireless_eu.range_preview_hide" : "gui.wireless_eu.range_preview_show");
    }

    private BroadcasterState state() {
        return ClientBroadcasterState.get(pos);
    }

    private static String rejectionText(WirelessEuNetwork.ConnectionRejection rejection) {
        switch (rejection) {
            case OUTPUT_FULL:
                return I18n.format("gui.wireless_eu.connection_rejected_full");
            case BROADCASTER_LIMIT:
                return I18n.format("gui.wireless_eu.connection_rejected_broadcaster_limit");
            case MIGRATION_WIRELESS_UPGRADE_REQUIRED:
                return I18n.format("gui.wireless_eu.migration_wireless_upgrade_required");
            default:
                return I18n.format("gui.wireless_eu.connection_rejected_increase");
        }
    }

    private static String shortKind(TargetKind kind) {
        switch (kind) {
            case LASER_STORAGE:
                return "LASER";
            case SINGLEBLOCK_MACHINE:
                return "MACHINE";
            default:
                return "ENERGY";
        }
    }

    private void drawTerminalButton(int left, int top, int width, int height, String label, boolean selected,
                                    boolean active, int mouseX, int mouseY) {
        boolean hovered = active && inRect(mouseX, mouseY, left, top, width, height);
        int face = !active ? 0xFF8D8D8D : selected ? 0xFF6FAF7D : hovered ? 0xFFD0D0D0 : 0xFFB8B8B8;
        drawRect(left, top, left + width, top + height, 0xFF2D2D2D);
        drawRect(left + 2, top + 2, left + width - 2, top + height - 2, 0xFFF6F6F6);
        drawRect(left + 4, top + 4, left + width - 4, top + height - 4, face);
        int color = !active ? 0xFF6A6A6A : selected ? 0xFF173C25 : 0xFF303030;
        drawCenteredString(label, left + width / 2, top + (height - 8) / 2, color);
    }

    private void drawInset(int left, int top, int right, int bottom, int fill) {
        drawRect(left, top, right, bottom, 0xFF333333);
        drawRect(left + 2, top + 2, right - 2, bottom - 2, 0xFFFFFFFF);
        drawRect(left + 4, top + 4, right - 4, bottom - 4, fill);
    }

    private void drawSelectionIndicator(int left, int top, boolean selected) {
        int border = 0xFF3E3E3E;
        int fill = selected ? 0xFF4FB56A : 0xFF707C84;
        drawRect(left + 4, top, left + 12, top + 1, border);
        drawRect(left + 2, top + 1, left + 14, top + 3, border);
        drawRect(left, top + 3, left + 16, top + 13, border);
        drawRect(left + 2, top + 13, left + 14, top + 15, border);
        drawRect(left + 4, top + 15, left + 12, top + 16, border);
        drawRect(left + 5, top + 3, left + 11, top + 13, fill);
        drawRect(left + 3, top + 5, left + 13, top + 11, fill);
    }

    private void drawLocateIcon(int centerX, int centerY, int color) {
        drawRect(centerX - 1, centerY - 6, centerX + 1, centerY + 7, color);
        drawRect(centerX - 6, centerY - 1, centerX + 7, centerY + 1, color);
        drawRect(centerX - 3, centerY - 3, centerX + 3, centerY + 3, 0xFF969696);
        drawRect(centerX - 2, centerY - 2, centerX + 2, centerY + 2, color);
    }

    private void drawBar(int left, int top, int width, int height, double ratio, int color) {
        drawRect(left, top, left + width, top + height, 0xFF484848);
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, ratio)) * (width - 2));
        drawRect(left + 1, top + 1, left + 1 + filled, top + height - 1, color);
    }

    private void drawString(String text, int x, int y, int color) {
        fontRenderer.drawString(text, x, y, color);
    }

    private void drawFittedString(String text, int x, int y, int width, int color) {
        float scale = Math.min(1.0F, (float) width / Math.max(1, fontRenderer.getStringWidth(text)));
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            fontRenderer.drawString(text, 0, 0, color);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawCenteredString(String text, int centerX, int y, int color) {
        fontRenderer.drawString(text, centerX - fontRenderer.getStringWidth(text) / 2, y, color);
    }

    private String trim(String text, int maxWidth) {
        return fontRenderer.trimStringToWidth(text, maxWidth);
    }

    private void playClick() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void enableScissor(int left, int top, int right, int bottom) {
        double guiScale = mc.displayWidth / (double) width;
        int x = (int) Math.round((leftPos + left * uiScale) * guiScale);
        int y = (int) Math.round((topPos + top * uiScale) * guiScale);
        int maximumX = (int) Math.round((leftPos + right * uiScale) * guiScale);
        int maximumY = (int) Math.round((topPos + bottom * uiScale) * guiScale);
        int scaledWidth = Math.max(0, maximumX - x);
        int scaledHeight = Math.max(0, maximumY - y);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, mc.displayHeight - y - scaledHeight, scaledWidth, scaledHeight);
    }

    private void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private int toBaseX(int value) {
        return Math.round((value - leftPos) / uiScale);
    }

    private int toBaseY(int value) {
        return Math.round((value - topPos) / uiScale);
    }

    private static boolean inRect(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static BigInteger toEuPerTick(BigInteger euPerSecond) {
        return euPerSecond.divide(BigInteger.valueOf(20L));
    }

    private enum DetailTarget {
        BUFFER,
        INPUT
    }

    private enum TargetFilter {
        UNCONNECTED,
        CONNECTED
    }

    private static final class Row {
        private final WirelessEuNetwork.TargetReference reference;
        private final String name;
        private final TargetKind kind;
        private final int tier;
        private final long amperage;
        private final boolean remote;
        private final boolean connected;

        private Row(WirelessEuNetwork.TargetReference reference, String name, TargetKind kind, int tier, long amperage,
                    boolean remote, boolean connected) {
            this.reference = reference;
            this.name = name;
            this.kind = kind;
            this.tier = tier;
            this.amperage = amperage;
            this.remote = remote;
            this.connected = connected;
        }
    }
}
