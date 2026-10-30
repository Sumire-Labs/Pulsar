package com.sumirelabs.pulsar.client;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.config.LightingModeSelection;
import com.sumirelabs.pulsar.config.PulsarConfig;
import com.sumirelabs.pulsar.core.RgbMixinSelection;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.io.File;
import java.io.IOException;

/** First-menu setup only; mode changes take effect on the next process startup. */
public final class GuiLightingModeSelection extends GuiScreen {
    private final GuiScreen mainMenu;
    private PulsarConfig.LightingMode pendingRestart;
    private boolean saveFailed;

    public GuiLightingModeSelection(GuiScreen mainMenu) {
        this.mainMenu = mainMenu;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        final int buttonWidth = Math.min(260, width - 40);
        final int x = (width - buttonWidth) / 2;
        if (pendingRestart != null) {
            buttonList.add(new GuiButton(2, x, height / 2 + 35, buttonWidth, 20,
                    I18n.format("pulsar.mode.quit")));
        } else {
            buttonList.add(new GuiButton(0, x, height / 2 - 20, buttonWidth, 20,
                    I18n.format("pulsar.mode.scalar")));
            buttonList.add(new GuiButton(1, x, height / 2 + 45, buttonWidth, 20,
                    I18n.format("pulsar.mode.rgb")));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 2) {
            mc.shutdown();
            return;
        }
        if (pendingRestart != null || (button.id != 0 && button.id != 1)) return;
        final PulsarConfig.LightingMode selected = button.id == 0
                ? PulsarConfig.LightingMode.SCALAR : PulsarConfig.LightingMode.RGB;
        try {
            LightingModeSelection.save(new File(mc.gameDir, "config/pulsar.cfg"), selected);
        } catch (IOException failure) {
            Pulsar.LOGGER.error("Cannot save first-run lighting selection", failure);
            saveFailed = true;
            return;
        }
        PulsarConfig.lightingMode = selected;
        PulsarConfig.lightingModeSelected = true;
        // Keep Forge's cached GUI properties consistent with the saved selection.
        net.minecraftforge.common.config.ConfigManager.sync(PulsarConfig.class);
        saveFailed = false;
        if ((selected == PulsarConfig.LightingMode.RGB) != RgbMixinSelection.rgbEnabled()) {
            pendingRestart = selected;
            initGui();
        } else {
            mc.displayGuiScreen(mainMenu);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        final int titleY = Math.max(15, height / 2 - 105);
        drawCenteredString(fontRenderer, I18n.format("pulsar.mode.title"), width / 2, titleY, 0xffffff);
        if (pendingRestart != null) {
            text(I18n.format("pulsar.mode.restart", pendingRestart.name()), height / 2 - 35, 0xdddddd);
            text(I18n.format("pulsar.mode.relaunch"), height / 2, 0xaaaaaa);
        } else {
            text(I18n.format("pulsar.mode.intro"), titleY + 25, 0xdddddd);
            text(I18n.format("pulsar.mode.scalar.description"), height / 2 + 5, 0xaaaaaa);
            text(I18n.format("pulsar.mode.rgb.description"), height / 2 + 70, 0xaaaaaa);
            if (saveFailed) text(I18n.format("pulsar.mode.saveFailed"), titleY + 55, 0xff7777);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void text(String value, int y, int color) {
        final int textWidth = Math.min(360, width - 40);
        fontRenderer.drawSplitString(value, (width - textWidth) / 2, y, textWidth, color);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // ESC must not skip setup or enter a world with a newly saved but inactive mode.
    }
}
