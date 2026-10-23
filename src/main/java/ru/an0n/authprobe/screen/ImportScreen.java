package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.an0n.authprobe.AuthProbe;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.utils.NickImport;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.EventQueue;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Import screen with a path field and a native file picker.
 *
 * <p>The path field is the fallback: even if AWT cannot show a dialog, the user can paste
 * a path and press Import.
 *
 * @author AN0N
 */
public final class ImportScreen extends Screen {
    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Whether the file picker should open on the first tick.
     */
    private boolean browsePending;

    /**
     * Path field.
     */
    private EditBox pathBox;

    /**
     * Last error, empty when none.
     */
    private String error = "";

    /**
     * Creates a new import screen.
     *
     * @param parent         Parent screen
     * @param browseOnStart  Whether to open the native picker immediately
     */
    public ImportScreen(Screen parent, boolean browseOnStart) {
        super(Component.translatable("authprobe.import.title"));
        this.parent = parent;
        this.browsePending = browseOnStart;
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        this.pathBox = new EditBox(this.font, 20, 58, this.width - 40, 20,
                Component.translatable("authprobe.import.path"));
        this.pathBox.setHint(Component.translatable("authprobe.import.path.hint"));
        this.pathBox.setMaxLength(1024);
        this.addRenderableWidget(this.pathBox);

        int buttonY = this.height - 26;
        int buttonWidth = Math.max(70, (this.width - 40 - 8) / 3);
        int buttonX = (this.width - (buttonWidth * 3 + 8)) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.import.browse"),
                        pressed -> this.browse())
                .bounds(buttonX, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.import.run"),
                        pressed -> this.importFromField())
                .bounds(buttonX + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.import.back"),
                        pressed -> this.onClose())
                .bounds(buttonX + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        if (!this.browsePending) return;
        this.browsePending = false;
        this.browse();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 12, 0xFF_FF_D7_00);
        graphics.drawString(this.font, Component.translatable("authprobe.import.hint"),
                20, 30, 0xFF_A0A0A0, false);
        graphics.drawString(this.font, Component.translatable("authprobe.import.path"),
                20, 48, 0xFF_FF_D7_00, false);
        if (!this.error.isEmpty()) {
            graphics.drawString(this.font, this.error, 20, 86, 0xFFFF6666, false);
        }
        graphics.drawString(this.font, Component.translatable("authprobe.import.example"),
                20, 108, 0xFF_88CCFF, false);
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Opens the native file picker and fills the path field.
     */
    private void browse() {
        try {
            String[] selected = chooseFile(this.title.getString());
            if (selected != null) {
                this.pathBox.setValue(Paths.get(selected[0], selected[1]).toString());
                this.error = "";
                this.importFromField();
            }
        } catch (Throwable throwable) {
            AuthProbe.logger().warn("AuthProbe: file dialog failed.", throwable);
            this.error = Component.translatable("authprobe.import.dialogFailed", String.valueOf(throwable.getMessage())).getString();
        }
    }

    /**
     * Reads and applies the file from the path field.
     */
    private void importFromField() {
        String raw = this.pathBox.getValue().trim();
        if (raw.isEmpty()) {
            this.error = Component.translatable("authprobe.import.empty").getString();
            return;
        }
        try {
            Path path = Paths.get(raw);
            List<ProbeTarget> imported = NickImport.parse(readText(path));
            if (imported.isEmpty()) {
                this.error = Component.translatable("authprobe.import.none").getString();
                return;
            }
            ProbeConfig.INSTANCE.replaceTargets(imported);
            ProbeConfig.INSTANCE.save();
            int passwords = 0;
            for (ProbeTarget target : imported) passwords += target.passwordCount();
            BotController.announce("imported " + imported.size() + " nicks, " + passwords + " passwords");
            AuthProbe.logger().info("AuthProbe: imported {} nicks and {} passwords from {}.",
                    imported.size(), passwords, path);
            assert this.minecraft != null;
            this.minecraft.setScreen(this.parent);
        } catch (Throwable throwable) {
            AuthProbe.logger().warn("AuthProbe: import failed.", throwable);
            this.error = Component.translatable("authprobe.import.failed", String.valueOf(throwable.getMessage())).getString();
        }
    }

    /**
     * Shows a native file dialog on the AWT thread and waits for the selection.
     *
     * @param title Dialog title
     * @return {@code {directory, file}}, or {@code null} on cancel/failure
     * @throws Exception When AWT cannot open the dialog
     */
    private static String[] chooseFile(String title) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("AWT is headless");
        }
        AtomicReference<String[]> result = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame owner = new Frame();
            owner.setAlwaysOnTop(true);
            FileDialog dialog = new FileDialog(owner, title, FileDialog.LOAD);
            dialog.setFilenameFilter((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            dialog.setFile("*.txt");
            dialog.setMultipleMode(false);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
            String file = dialog.getFile();
            String directory = dialog.getDirectory();
            dialog.dispose();
            owner.dispose();
            if (file != null && directory != null) {
                result.set(new String[]{directory, file});
            }
        });
        return result.get();
    }

    /**
     * Reads UTF-8, falling back to windows-1251 when the file is not valid UTF-8.
     *
     * @param path File path
     * @return File text
     * @throws Exception When the file cannot be read
     */
    private static String readText(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) text = new String(bytes, Charset.forName("windows-1251"));
        return text;
    }
}