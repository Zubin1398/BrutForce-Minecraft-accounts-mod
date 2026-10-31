package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import ru.an0n.authprobe.AuthProbe;
import ru.an0n.authprobe.BotController;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.utils.NickImport;
import org.lwjgl.glfw.GLFW;

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
     * Manual paste area, alternative to a file path.
     */
    private MultilineTextBox textBox;

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

        int textTop = 106;
        int textHeight = Math.max(30, this.height - textTop - 34);
        this.textBox = new MultilineTextBox(this.font, 20, textTop, this.width - 40, textHeight,
                Component.translatable("authprobe.import.paste"));
        this.textBox.setHint(Component.translatable("authprobe.import.paste.hint").getString());
        this.textBox.setMaxLines(4096);
        this.textBox.setMaxLength(1_000_000);
        this.addRenderableWidget(this.textBox);

        int buttonY = this.height - 26;
        int buttonWidth = Math.max(60, (this.width - 40 - 12) / 4);
        int buttonX = (this.width - (buttonWidth * 4 + 12)) / 2;
        Button browseButton = Button.builder(Component.translatable("authprobe.import.browse"),
                        pressed -> this.browse())
                .bounds(buttonX, buttonY, buttonWidth, 20).build();
        browseButton.setTooltip(Tooltip.create(Component.translatable("authprobe.import.browse.tip")));
        this.addRenderableWidget(browseButton);

        Button replaceButton = Button.builder(
                        Component.translatable("authprobe.import.replace").withStyle(ChatFormatting.RED),
                        pressed -> this.importFromField(false))
                .bounds(buttonX + (buttonWidth + 4), buttonY, buttonWidth, 20).build();
        replaceButton.setTooltip(Tooltip.create(Component.translatable("authprobe.import.replace.tip")));
        this.addRenderableWidget(replaceButton);

        Button addButton = Button.builder(
                        Component.translatable("authprobe.import.add").withStyle(ChatFormatting.GREEN),
                        pressed -> this.importFromField(true))
                .bounds(buttonX + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build();
        addButton.setTooltip(Tooltip.create(Component.translatable("authprobe.import.add.tip")));
        this.addRenderableWidget(addButton);

        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.import.back"),
                        pressed -> this.onClose())
                .bounds(buttonX + (buttonWidth + 4) * 3, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        if (!this.browsePending) return;
        this.browsePending = false;
        this.browse();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
                && this.textBox != null && this.textBox.isFocused()) {
            this.setFocused(this.textBox);
            return this.textBox.keyPressed(key, scan, mods);
        }
        return super.keyPressed(key, scan, mods);
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
            graphics.drawString(this.font, this.error, 20, 84, 0xFFFF6666, false);
        }
        graphics.drawString(this.font, Component.translatable("authprobe.import.paste"),
                20, 96, 0xFF_FF_D7_00, false);
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
                Path path = Paths.get(selected[0], selected[1]);
                this.pathBox.setValue(path.toString());
                this.error = "";
            }
        } catch (Throwable throwable) {
            AuthProbe.logger().warn("AuthProbe: file dialog failed.", throwable);
            this.error = Component.translatable("authprobe.import.dialogFailed", String.valueOf(throwable.getMessage())).getString();
        }
    }

    /**
     * Imports pasted text when present, otherwise the file from the path field.
     *
     * @param merge Whether to add and merge instead of replacing every profile
     */
    private void importFromField(boolean merge) {
        String pasted = this.textBox.getValue();
        if (!pasted.isBlank()) {
            this.apply(NickImport.parse(pasted), "pasted text", merge);
            return;
        }

        String raw = this.pathBox.getValue().trim();
        if (raw.isEmpty()) {
            this.error = Component.translatable("authprobe.import.empty").getString();
            return;
        }
        this.importPath(Paths.get(raw), merge);
    }

    /**
     * Reads and applies a file.
     *
     * @param path  File path
     * @param merge Whether to add and merge instead of replacing every profile
     */
    private void importPath(Path path, boolean merge) {
        try {
            this.apply(NickImport.parse(readText(path)), path.toString(), merge);
        } catch (Throwable throwable) {
            AuthProbe.logger().warn("AuthProbe: import failed.", throwable);
            this.error = Component.translatable("authprobe.import.failed", String.valueOf(throwable.getMessage())).getString();
        }
    }

    /**
     * Applies parsed profiles, either replacing the list or adding to it.
     *
     * @param imported Imported targets
     * @param source   Source description for the log
     * @param merge    Whether to add and merge instead of replacing every profile
     */
    private void apply(List<ProbeTarget> imported, String source, boolean merge) {
        if (imported.isEmpty()) {
            this.error = Component.translatable("authprobe.import.none").getString();
            return;
        }

        ProbeConfig config = ProbeConfig.INSTANCE;
        if (merge) {
            var result = config.mergeTargets(imported);
            if (!result.changed()) {
                this.error = Component.translatable("authprobe.import.duplicates").getString();
                return;
            }
            config.save();
            String message = Component.translatable("authprobe.import.done.merge",
                    result.added(), result.merged(), result.skipped()).getString();
            BotController.announce(message);
            BotController.notify(Component.literal(message));
            AuthProbe.logger().info("AuthProbe: merged {} nicks from {}: {} added, {} merged, {} skipped, {} passwords added.",
                    imported.size(), source, result.added(), result.merged(), result.skipped(),
                    result.passwordsAdded());
        } else {
            config.replaceTargets(imported);
            config.save();
            int passwords = 0;
            for (ProbeTarget target : imported) passwords += target.passwordCount();
            String message = Component.translatable("authprobe.import.done.replace",
                    imported.size(), passwords).getString();
            BotController.announce(message);
            BotController.notify(Component.literal(message));
            AuthProbe.logger().info("AuthProbe: replaced with {} nicks and {} passwords from {}.",
                    imported.size(), passwords, source);
        }

        // The setup screen is reused, so reload the imported list before going back.
        if (this.parent instanceof ProbeSetupScreen setup) setup.reloadFromConfig();
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
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