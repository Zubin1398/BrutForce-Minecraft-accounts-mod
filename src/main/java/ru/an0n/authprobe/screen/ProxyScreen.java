package ru.an0n.authprobe.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.an0n.authprobe.ProbeConfig;
import ru.an0n.authprobe.proxy.ProxyEntry;
import ru.an0n.authprobe.proxy.ProxyManager;
import ru.an0n.authprobe.proxy.ProxyTunnel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proxy list editor and batch checker.
 *
 * <p>Accepts one proxy per line; the format is detected by {@link ProxyEntry}. Checking opens a
 * real tunnel to the configured Minecraft server through each proxy, so a green result means the
 * proxy can carry this exact game connection.
 *
 * @author AN0N
 */
public final class ProxyScreen extends Screen {
    /**
     * Concurrent check workers.
     */
    private static final int WORKERS = 8;

    /**
     * Per-proxy check timeout.
     */
    private static final int TIMEOUT_MS = 6000;

    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Proxy list editor.
     */
    private MultilineTextBox box;

    /**
     * Check results, updated from worker threads.
     */
    private final List<Result> results = Collections.synchronizedList(new ArrayList<>());

    /**
     * Check executor.
     */
    private ExecutorService executor;

    /**
     * Whether a check batch is running.
     */
    private volatile boolean checking;

    /**
     * Amount of lines in the current batch.
     */
    private final AtomicInteger total = new AtomicInteger();

    /**
     * Amount of finished lines in the current batch.
     */
    private final AtomicInteger done = new AtomicInteger();

    /**
     * Batch-level error, empty when there is none.
     */
    private volatile String error = "";

    /**
     * Gets the status key for the batch-level error.
     */
    private String errorKey = "";

    /**
     * Creates a new proxy screen.
     *
     * @param parent Parent screen
     */
    public ProxyScreen(Screen parent) {
        super(Component.translatable("authprobe.proxy.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        assert this.minecraft != null;
        int boxWidth = Math.max(120, (this.width - 30) * 55 / 100);
        int boxHeight = Math.max(40, this.height - 74);
        this.box = new MultilineTextBox(this.font, 10, 34, boxWidth, boxHeight,
                Component.translatable("authprobe.proxy.paste"));
        this.box.setMaxLines(4096);
        this.box.setMaxLength(1_000_000);
        this.box.setHint(Component.translatable("authprobe.proxy.hint").getString());
        this.box.setValue(String.join("\n", ProbeConfig.INSTANCE.proxies()));
        this.addRenderableWidget(this.box);

        int buttonY = this.height - 26;
        int buttonWidth = Math.max(50, (this.width - 20 - 12) / 4);
        int buttonX = (this.width - (buttonWidth * 4 + 12)) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.proxy.check"),
                        pressed -> this.check())
                .bounds(buttonX, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.proxy.save"),
                        pressed -> this.save())
                .bounds(buttonX + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.proxy.dead"),
                        pressed -> this.removeDead())
                .bounds(buttonX + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("authprobe.patterns.back"),
                        pressed -> this.onClose())
                .bounds(buttonX + (buttonWidth + 4) * 3, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int boxWidth = Math.max(120, (this.width - 30) * 55 / 100);
        int resultsX = 10 + boxWidth + 10;
        int resultsWidth = Math.max(60, this.width - resultsX - 10);

        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFF_FF_D7_00);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(
                        Component.translatable("authprobe.proxy.hint").getString(), this.width - 20),
                10, 20, 0xFF_A0_A0_A0, false);

        // Summary.
        int ok = 0;
        int fail = 0;
        List<Result> snapshot;
        synchronized (this.results) {
            snapshot = new ArrayList<>(this.results);
        }
        for (Result result : snapshot) {
            if (result.ok()) ok++;
            else fail++;
        }
        Component summary = this.checking
                ? Component.translatable("authprobe.proxy.checking", this.done.get(), this.total.get())
                : Component.translatable("authprobe.proxy.summary", ok, fail);
        graphics.drawString(this.font, summary, resultsX, 34, 0xFF_FF_FF_FF, false);

        if (!this.error.isEmpty()) {
            graphics.drawString(this.font, Component.translatable(this.errorKey), resultsX, 46, 0xFF_FF_66_66, false);
        }

        int y = 58;
        int bottom = this.height - 32;
        for (Result result : snapshot) {
            if (y > bottom) break;
            String prefix = result.ok()
                    ? Component.translatable("authprobe.proxy.ok", result.millis()).getString()
                    : Component.translatable("authprobe.proxy.fail").getString();
            String line = prefix + " " + result.line();
            if (!result.ok() && !result.error().isEmpty()) line += " - " + result.error();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(line, resultsWidth), resultsX, y,
                    result.ok() ? 0xFF_66_FF_66 : 0xFF_FF_66_66, false);
            y += 10;
        }
    }

    @Override
    public void onClose() {
        assert this.minecraft != null;
        this.stopChecker();
        this.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Runs a batch check against the configured server address.
     */
    private void check() {
        if (this.checking) return;
        this.save(); // Persist the pasted list before spending time on it.
        this.results.clear();
        this.error = "";
        this.errorKey = "";

        List<String> lines = lines(this.box.getValue());
        if (lines.isEmpty()) {
            this.errorKey = "authprobe.proxy.none";
            this.error = "none";
            return;
        }

        InetSocketAddress target = ProxyManager.checkTarget();
        if (target == null) {
            this.errorKey = "authprobe.proxy.needAddress";
            this.error = "address";
            return;
        }

        List<ProxyEntry> valid = new ArrayList<>();
        List<String> validRaw = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String line : lines) {
            ProxyEntry entry = ProxyEntry.parse(line);
            if (entry == null) invalid.add(line);
            else {
                valid.add(entry);
                validRaw.add(line);
            }
        }
        for (String line : invalid) {
            this.results.add(new Result(line, line, false, 0L,
                    Component.translatable("authprobe.proxy.badFormat").getString()));
        }

        this.total.set(lines.size());
        this.done.set(invalid.size());
        if (valid.isEmpty()) {
            this.checking = false;
            return;
        }

        this.stopChecker();
        this.executor = Executors.newFixedThreadPool(Math.min(WORKERS, valid.size()));
        this.checking = true;
        for (int i = 0; i < valid.size(); i++) {
            ProxyEntry entry = valid.get(i);
            String raw = validRaw.get(i);
            this.executor.submit(() -> {
                ProxyTunnel.Check check = ProxyTunnel.check(entry, target, TIMEOUT_MS);
                this.results.add(new Result(raw, entry.display(), check.ok(), check.millis(), check.error()));
                if (this.done.incrementAndGet() >= this.total.get()) this.checking = false;
            });
        }
    }

    /**
     * Writes the editor into the config and saves it.
     */
    private void save() {
        ProbeConfig.INSTANCE.proxies(lines(this.box.getValue()));
        ProbeConfig.INSTANCE.save();
    }

    /**
     * Removes lines whose check failed.
     */
    private void removeDead() {
        List<Result> snapshot;
        synchronized (this.results) {
            snapshot = new ArrayList<>(this.results);
        }
        if (snapshot.isEmpty()) return;
        List<String> dead = new ArrayList<>();
        for (Result result : snapshot) {
            if (!result.ok()) dead.add(result.raw());
        }
        if (dead.isEmpty()) return;
        List<String> kept = new ArrayList<>();
        for (String line : lines(this.box.getValue())) {
            if (!dead.contains(line)) kept.add(line);
        }
        this.box.setValue(String.join("\n", kept));
        this.results.clear();
        this.error = "";
        this.errorKey = "";
    }

    /**
     * Stops the worker pool.
     */
    private void stopChecker() {
        this.checking = false;
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    /**
     * Splits the editor value into non-blank lines.
     *
     * @param raw Raw value
     * @return Lines
     */
    private static List<String> lines(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null) return lines;
        for (String line : raw.split("\n", -1)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            lines.add(value);
        }
        return lines;
    }

    /**
     * One check result.
     *
     * @param line  Display line
     * @param entry Parsed entry, {@code null} for bad format
     * @param ok    Whether the check passed
     * @param millis Time spent, milliseconds
     * @param error Failure reason
     */
    private record Result(String raw, String line, boolean ok, long millis, String error) {
    }
}
