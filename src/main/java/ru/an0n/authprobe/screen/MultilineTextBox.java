/*
 * In-Game Account Switcher is a mod for Minecraft that allows you to change your logged in account in-game, without restarting Minecraft.
 * Copyright (C) 2015-2022 The_Fireplace
 * Copyright (C) 2021-2026 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>
 */

package ru.an0n.authprobe.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Multiline text box with the popup design.
 * Minecraft 1.20.1 ships no multiline text field, and the vanilla {@link net.minecraft.client.gui.components.EditBox}
 * strips line breaks on paste, so this widget handles its own clipboard and line handling.
 * @author VidTu
 */
final class MultilineTextBox extends AbstractWidget {
    /**
     * Text padding, in pixels.
     */
    private static final int PADDING = 2;

    /**
     * Normal text color.
     */
    private static final int COLOR_TEXT = 0xFF_E0_E0_E0;

    /**
     * Text color for flagged lines.
     */
    private static final int COLOR_FLAGGED = 0xFF_FF_D0_00;

    /**
     * Text color for the hint.
     */
    private static final int COLOR_HINT = 0xFF_55_55_55;

    /**
     * Selection color.
     */
    private static final int COLOR_SELECTION = 0x80_50_50_A0;

    /**
     * Caret color.
     */
    private static final int COLOR_CARET = 0xFF_FF_FF_FF;

    /**
     * Scrollbar color.
     */
    private static final int COLOR_BAR = 0x80_80_80_80;

    /**
     * Font renderer.
     */
    private final Font font;

    /**
     * Text lines. ({@code size} is always at least one.)
     */
    private final List<String> lines = new ArrayList<>(1);

    /**
     * Cursor line.
     */
    private int cursorLine;

    /**
     * Cursor column.
     */
    private int cursorCol;

    /**
     * Selection anchor line, {@code -1} if there's no selection.
     */
    private int anchorLine = -1;

    /**
     * Selection anchor column.
     */
    private int anchorCol;

    /**
     * Whether the selection anchor is currently behind the cursor.
     */
    private boolean anchorBefore;

    /**
     * First visible line.
     */
    private int scroll;

    /**
     * Maximum amount of characters.
     */
    private int maxLength = 200_000;

    /**
     * Maximum amount of lines.
     */
    private int maxLines = 1024;

    /**
     * Value change callback.
     */
    private Consumer<String> responder = value -> {
    };

    /**
     * Line warning filter. Lines it accepts are rendered yellow.
     */
    private Predicate<String> lineFilter = line -> false;

    /**
     * Placeholder rendered while the value is empty.
     */
    private String hint = "";

    /**
     * Whether the caret is being placed by a mouse drag.
     */
    private boolean dragging;

    /**
     * Caret blink counter.
     */
    private int blink;

    /**
     * Creates a new multiline text box.
     *
     * @param font   Font renderer
     * @param x      Box X
     * @param y      Box Y
     * @param width  Box width
     * @param height Box height
     * @param title  Box title, used for narration
     */
    MultilineTextBox(Font font, int x, int y, int width, int height, Component title) {
        super(x, y, width, height, title);
        this.font = font;
        this.lines.add("");
    }

    /**
     * Sets the maximum amount of characters.
     *
     * @param length Character limit
     */
    void setMaxLength(int length) {
        this.maxLength = Math.max(1, length);
    }

    /**
     * Sets the maximum amount of lines.
     *
     * @param lines Line limit
     */
    void setMaxLines(int lines) {
        this.maxLines = Math.max(1, lines);
    }

    /**
     * Sets the value change callback.
     *
     * @param responder Callback to run on every change
     */
    void setResponder(Consumer<String> responder) {
        this.responder = responder;
    }

    /**
     * Sets the line warning filter.
     *
     * @param filter Predicate, {@code true} renders the line yellow
     */
    void setLineFilter(Predicate<String> filter) {
        this.lineFilter = filter;
    }

    /**
     * Sets the placeholder rendered while the value is empty.
     *
     * @param hint Placeholder lines, separated with {@code \n}
     */
    void setHint(String hint) {
        this.hint = hint;
    }

    /**
     * Sets the value, moving the cursor to the end.
     *
     * @param value New value
     */
    void setValue(String value) {
        this.lines.clear();
        this.lines.addAll(split(value));
        this.cursorLine = this.lines.size() - 1;
        String last = this.lines.get(this.cursorLine);
        this.cursorCol = last.length();
        this.anchorLine = -1;
        this.anchorBefore = false;
        this.scroll = this.maxScroll();
        this.responder.accept(this.getValue());
    }

    /**
     * Gets the value.
     *
     * @return Value, lines joined with {@code \n}
     */
    String getValue() {
        return String.join("\n", this.lines);
    }

    /**
     * Gets the amount of currently visible lines.
     *
     * @return Visible line amount
     */
    int visibleLineCount() {
        return Math.max(1, (this.getHeight() - PADDING * 2) / this.font.lineHeight);
    }

    /**
     * Ticks the caret blink.
     */
    void tick() {
        this.blink++;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.setFocused(true);
        int[] position = this.positionAt(mouseX, mouseY);
        this.cursorLine = position[0];
        this.cursorCol = position[1];
        this.anchorLine = this.cursorLine;
        this.anchorCol = this.cursorCol;
        this.dragging = true;
        this.ensureCursorVisible();
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        this.dragging = false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (!this.dragging) return;
        int[] position = this.positionAt(mouseX, mouseY);
        this.cursorLine = position[0];
        this.cursorCol = position[1];
        this.ensureCursorVisible();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.active || !this.visible || !this.isMouseOver(mouseX, mouseY)) return false;
        int max = this.maxScroll();
        if (max <= 0) return false;
        this.scroll = clamp(this.scroll - (int) Math.signum(delta), 0, max);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!this.active || !this.visible) return false;
        // Clipboard. (Our own paste, the vanilla one strips line breaks.)
        if (Screen.hasControlDown()) {
            if (Screen.isCopy(key)) {
                this.copy();
                return true;
            }
            if (Screen.isCut(key)) {
                this.copy();
                this.deleteSelection();
                return true;
            }
            if (key == GLFW.GLFW_KEY_V) {
                this.paste();
                return true;
            }
            if (key == GLFW.GLFW_KEY_A) {
                this.anchorLine = 0;
                this.anchorCol = 0;
                this.cursorLine = this.lines.size() - 1;
                this.cursorCol = this.lines.get(this.cursorLine).length();
                this.anchorBefore = true;
                this.ensureCursorVisible();
                return true;
            }
        }
        // Line break.
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            this.insertRaw("\n");
            return true;
        }
        // Backspace.
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            this.backspace(Screen.hasShiftDown());
            return true;
        }
        // Delete.
        if (key == GLFW.GLFW_KEY_DELETE) {
            this.forwardDelete(Screen.hasShiftDown());
            return true;
        }
        // Horizontal movement.
        if (key == GLFW.GLFW_KEY_LEFT) {
            this.moveHorizontal(-1, Screen.hasShiftDown(), Screen.hasControlDown());
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            this.moveHorizontal(1, Screen.hasShiftDown(), Screen.hasControlDown());
            return true;
        }
        // Vertical movement.
        if (key == GLFW.GLFW_KEY_UP) {
            this.moveVertical(-1, Screen.hasShiftDown());
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            this.moveVertical(1, Screen.hasShiftDown());
            return true;
        }
        // Line start and end.
        if (key == GLFW.GLFW_KEY_HOME) {
            this.moveTo(0, Screen.hasControlDown() ? 0 : this.cursorCol, Screen.hasShiftDown());
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            int line = Screen.hasControlDown() ? this.lines.size() - 1 : this.cursorLine;
            this.moveTo(line, this.lines.get(line).length(), Screen.hasShiftDown());
            return true;
        }
        // Paging.
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            this.moveVertical(-this.visibleLineCount(), Screen.hasShiftDown());
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            this.moveVertical(this.visibleLineCount(), Screen.hasShiftDown());
            return true;
        }
        // Not handled, let the screen take it.
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int mods) {
        if (!this.active || !this.visible) return false;
        // Line breaks are handled as keys, tabs as paste, formatting codes are never allowed.
        if (codePoint < 32 || codePoint == 127 || codePoint == 167) return false;
        this.insertRaw(String.valueOf(codePoint));
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Get values.
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();
        int lineHeight = this.font.lineHeight;
        int textX = x + PADDING;
        int innerWidth = width - PADDING * 2;

        // Render background. (Same frame as the popup boxes.)
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF_00_00_00);
        graphics.fill(x + 1, y, x + width - 1, y + 1, 0xFF_FF_FF_FF);
        graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, 0xFF_FF_FF_FF);
        graphics.fill(x, y + 1, x + 1, y + height - 1, 0xFF_FF_FF_FF);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, 0xFF_FF_FF_FF);

        // Clip the text to the box.
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);

        // Render the hint, if there's nothing.
        if (this.lines.size() == 1 && this.lines.get(0).isEmpty() && !this.hint.isEmpty()) {
            String[] hint = this.hint.split("\n", Math.max(1, this.visibleLineCount()));
            for (int i = 0; i < hint.length; i++) {
                graphics.drawString(this.font, hint[i], textX, y + PADDING + i * lineHeight, COLOR_HINT, false);
            }
            graphics.disableScissor();
            return;
        }

        // Get the selection.
        int[] selection = this.selectionRange();
        boolean hasSelection = selection != null;

        // Render every visible line.
        int visible = this.visibleLineCount();
        for (int i = 0; i < visible; i++) {
            int index = this.scroll + i;
            if (index >= this.lines.size()) break;
            String line = this.lines.get(index);
            int lineY = y + PADDING + i * lineHeight;

            // Render the selection, if any.
            if (hasSelection && index >= selection[0] && index <= selection[2]) {
                int start = index == selection[0] ? selection[1] : 0;
                int end = index == selection[2] ? selection[3] : line.length();
                start = Math.min(start, line.length());
                end = clamp(end, start, line.length());
                int selX = textX + this.font.width(line.substring(0, start));
                int selWidth = this.font.width(line.substring(start, end));
                if (selWidth > 0) graphics.fill(selX, lineY, selX + selWidth, lineY + lineHeight, COLOR_SELECTION);
            }

            // Render the line itself, clipped to the box.
            graphics.drawString(this.font, this.font.plainSubstrByWidth(line, innerWidth), textX, lineY,
                    this.lineFilter.test(line) ? COLOR_FLAGGED : COLOR_TEXT, false);
        }

        // Render the caret.
        if (this.isFocused() && this.blink / 6 % 2 == 0) {
            int caretLine = this.cursorLine - this.scroll;
            if (caretLine >= 0 && caretLine < visible && caretLine < this.lines.size()) {
                String line = this.lines.get(this.cursorLine);
                int caretX = textX + this.font.width(line.substring(0, Math.min(this.cursorCol, line.length())));
                int caretY = y + PADDING + caretLine * lineHeight;
                if (caretX <= x + width - 1) graphics.fill(caretX, caretY, caretX + 1, caretY + lineHeight - 1, COLOR_CARET);
            }
        }

        graphics.disableScissor();

        // Render the scrollbar.
        int max = this.maxScroll();
        if (max > 0) {
            int trackHeight = height - PADDING * 2;
            int barHeight = Math.max(8, trackHeight * visible / this.lines.size());
            int barY = y + PADDING + (trackHeight - barHeight) * this.scroll / max;
            graphics.fill(x + width - 3, y + PADDING, x + width - 2, y + height - PADDING, 0x40_FF_FF_FF);
            graphics.fill(x + width - 3, barY, x + width - 2, barY + barHeight, COLOR_BAR);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    /**
     * Copies the selection to the clipboard.
     */
    private void copy() {
        int[] selection = this.selectionRange();
        if (selection == null) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(this.selectedText(selection));
    }

    /**
     * Pastes the clipboard content into the box.
     */
    private void paste() {
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clipboard == null || clipboard.isEmpty()) return;
        // Keep line breaks, expand tabs, drop formatting and control characters.
        StringBuilder builder = new StringBuilder(clipboard.length());
        for (int i = 0; i < clipboard.length(); i++) {
            char c = clipboard.charAt(i);
            if (c == '\r') {
                // CRLF counts as one break.
                if (i + 1 < clipboard.length() && clipboard.charAt(i + 1) == '\n') i++;
                builder.append('\n');
                continue;
            }
            if (c == '\n') {
                builder.append('\n');
                continue;
            }
            if (c == '\t') {
                builder.append("    ");
                continue;
            }
            if (c < 32 || c == 127 || c == 167) continue;
            builder.append(c);
        }
        this.insertRaw(builder.toString());
    }

    /**
     * Inserts the raw text at the cursor, replacing the selection.
     *
     * @param text Text to insert
     */
    private void insertRaw(String text) {
        if (!this.active || text.isEmpty()) return;
        // Delete the selection first.
        this.deleteSelection();
        // Clamp by the line limit, cutting on line boundaries so nothing is half-written.
        int room = this.maxLines - this.lines.size();
        int breaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') breaks++;
        }
        if (breaks > room) {
            int cut = -1;
            for (int i = 0; i < room; i++) cut = text.indexOf('\n', cut + 1);
            text = cut < 0 ? "" : text.substring(0, cut + 1);
            if (text.isEmpty()) return;
        }
        // Clamp by the character limit.
        int roomChars = this.maxLength - this.totalLength();
        if (text.length() > roomChars) {
            text = text.substring(0, Math.max(0, roomChars));
            if (text.isEmpty()) return;
        }
        // Split into lines.
        List<String> parts = split(text);
        this.cursorLine = clamp(this.cursorLine, 0, this.lines.size() - 1);
        this.cursorCol = clamp(this.cursorCol, 0, this.lines.get(this.cursorLine).length());
        String line = this.lines.get(this.cursorLine);
        String head = line.substring(0, this.cursorCol);
        String tail = line.substring(this.cursorCol);
        // Single line, the cheap path.
        if (parts.size() == 1) {
            this.lines.set(this.cursorLine, head + parts.get(0) + tail);
            this.cursorCol += parts.get(0).length();
            this.afterChange();
            return;
        }
        // Multiple lines, splice them in.
        this.lines.set(this.cursorLine, head + parts.get(0));
        int last = parts.size() - 1;
        for (int i = 1; i < last; i++) this.lines.add(this.cursorLine + i, parts.get(i));
        this.lines.add(this.cursorLine + last, parts.get(last) + tail);
        this.cursorLine += last;
        this.cursorCol = parts.get(last).length();
        this.afterChange();
    }

    /**
     * Deletes the character before the cursor, or joins with the previous line.
     *
     * @param select Whether to keep the selection
     */
    private void backspace(boolean select) {
        if (!select && this.hasSelection()) {
            this.deleteSelection();
            return;
        }
        this.cursorLine = clamp(this.cursorLine, 0, this.lines.size() - 1);
        this.cursorCol = clamp(this.cursorCol, 0, this.lines.get(this.cursorLine).length());
        if (this.cursorCol > 0) {
            String line = this.lines.get(this.cursorLine);
            this.lines.set(this.cursorLine, line.substring(0, this.cursorCol - 1) + line.substring(this.cursorCol));
            this.cursorCol--;
            this.afterChange();
            return;
        }
        if (this.cursorLine <= 0) return;
        String previous = this.lines.get(this.cursorLine - 1);
        this.cursorCol = previous.length();
        this.lines.set(this.cursorLine - 1, previous + this.lines.remove(this.cursorLine));
        this.cursorLine--;
        this.afterChange();
    }

    /**
     * Deletes the character after the cursor, or joins with the next line.
     *
     * @param select Whether to keep the selection
     */
    private void forwardDelete(boolean select) {
        if (!select && this.hasSelection()) {
            this.deleteSelection();
            return;
        }
        this.cursorLine = clamp(this.cursorLine, 0, this.lines.size() - 1);
        this.cursorCol = clamp(this.cursorCol, 0, this.lines.get(this.cursorLine).length());
        String line = this.lines.get(this.cursorLine);
        if (this.cursorCol < line.length()) {
            this.lines.set(this.cursorLine, line.substring(0, this.cursorCol) + line.substring(this.cursorCol + 1));
            this.afterChange();
            return;
        }
        if (this.cursorLine >= this.lines.size() - 1) return;
        this.lines.set(this.cursorLine, line + this.lines.remove(this.cursorLine + 1));
        this.afterChange();
    }

    /**
     * Deletes the selected text, if any.
     */
    private void deleteSelection() {
        int[] selection = this.selectionRange();
        if (selection == null) return;
        String head = this.lines.get(selection[0]).substring(0, selection[1]);
        String tail = this.lines.get(selection[2]).substring(selection[3]);
        this.lines.subList(selection[0], selection[2] + 1).clear();
        this.lines.add(selection[0], head + tail);
        this.cursorLine = selection[0];
        this.cursorCol = selection[1];
        this.anchorLine = -1;
        this.afterChange();
    }

    /**
     * Moves the cursor horizontally.
     *
     * @param direction {@code -1} for left, {@code 1} for right
     * @param select    Whether to extend the selection
     * @param toEdge    Whether to jump to the line start or end instead of one character
     */
    private void moveHorizontal(int direction, boolean select, boolean toEdge) {
        if (!select && this.hasSelection()) {
            int[] selection = this.selectionRange();
            if (!this.anchorBefore) {
                this.cursorLine = selection[0];
                this.cursorCol = selection[1];
            } else {
                this.cursorLine = selection[2];
                this.cursorCol = selection[3];
            }
            this.anchorLine = -1;
            this.afterChange();
            return;
        }
        int col = this.cursorCol;
        int line = this.cursorLine;
        if (toEdge) {
            col = direction < 0 ? 0 : this.lines.get(line).length();
        } else if (direction > 0 && col < this.lines.get(line).length()) {
            col++;
        } else if (direction < 0 && line > 0) {
            line--;
            col = this.lines.get(line).length();
        } else if (direction > 0 && line < this.lines.size() - 1) {
            line++;
            col = 0;
        } else {
            // Hit an edge, remember it so a repeated press doesn't extend the selection.
            this.anchorLine = select ? this.cursorLine : -1;
            this.anchorCol = this.cursorCol;
            this.anchorBefore = false;
            return;
        }
        this.moveTo(line, col, select);
    }

    /**
     * Moves the cursor vertically, keeping the column where possible.
     *
     * @param rows   Rows to move, may be negative
     * @param select Whether to extend the selection
     */
    private void moveVertical(int rows, boolean select) {
        int wanted = this.cursorLine + rows;
        int line = clamp(wanted, 0, this.lines.size() - 1);
        int col = clamp(this.cursorCol, 0, this.lines.get(line).length());
        this.moveTo(line, col, select);
    }

    /**
     * Moves the cursor to the target position.
     *
     * @param line   Target line
     * @param col    Target column
     * @param select Whether to extend the selection
     */
    private void moveTo(int line, int col, boolean select) {
        if (select) {
            if (this.anchorLine < 0) {
                this.anchorLine = this.cursorLine;
                this.anchorCol = this.cursorCol;
            } else {
                this.anchorLine = -1;
            }
        }
        this.cursorLine = clamp(line, 0, this.lines.size() - 1);
        this.cursorCol = clamp(col, 0, this.lines.get(this.cursorLine).length());
        this.ensureCursorVisible();
        this.blink = 0;
    }

    /**
     * Called after every value change.
     */
    private void afterChange() {
        this.cursorLine = clamp(this.cursorLine, 0, this.lines.size() - 1);
        this.cursorCol = clamp(this.cursorCol, 0, this.lines.get(this.cursorLine).length());
        this.ensureCursorVisible();
        this.blink = 0;
        this.responder.accept(this.getValue());
    }

    /**
     * Gets the total amount of characters in the box.
     *
     * @return Character amount
     */
    private int totalLength() {
        int total = 0;
        for (String line : this.lines) total += line.length();
        return total + this.lines.size() - 1;
    }

    /**
     * Gets the first line that may be scrolled to.
     *
     * @return Maximum scroll
     */
    private int maxScroll() {
        return Math.max(0, this.lines.size() - this.visibleLineCount());
    }

    /**
     * Scrolls so the cursor is visible.
     */
    private void ensureCursorVisible() {
        int visible = this.visibleLineCount();
        if (this.cursorLine < this.scroll) this.scroll = this.cursorLine;
        else if (this.cursorLine >= this.scroll + visible) this.scroll = this.cursorLine - visible + 1;
        this.scroll = clamp(this.scroll, 0, this.maxScroll());
    }

    /**
     * Checks whether there's a selection.
     *
     * @return Whether the selection is not empty
     */
    private boolean hasSelection() {
        return this.selectionRange() != null;
    }

    /**
     * Gets the normalized selection range.
     *
     * @return {@code {startLine, startCol, endLine, endCol}}, or {@code null} if there's no selection
     */
    private int[] selectionRange() {
        if (this.anchorLine < 0) return null;
        // The anchor may outlive a shrink of its line (Shift+Backspace does not clear it),
        // so clamp both ends before any substring is taken.
        int anchorLine = clamp(this.anchorLine, 0, this.lines.size() - 1);
        int cursorLine = clamp(this.cursorLine, 0, this.lines.size() - 1);
        int anchorCol = clamp(this.anchorCol, 0, this.lines.get(anchorLine).length());
        int cursorCol = clamp(this.cursorCol, 0, this.lines.get(cursorLine).length());
        this.anchorLine = anchorLine;
        this.anchorCol = anchorCol;
        this.cursorLine = cursorLine;
        this.cursorCol = cursorCol;
        if (anchorLine == cursorLine && anchorCol == cursorCol) return null;
        int anchor = anchorLine * 100_000 + anchorCol;
        int cursor = cursorLine * 100_000 + cursorCol;
        if (cursor < anchor) {
            this.anchorBefore = false;
            return new int[]{cursorLine, cursorCol, anchorLine, anchorCol};
        }
        this.anchorBefore = true;
        return new int[]{anchorLine, anchorCol, cursorLine, cursorCol};
    }

    /**
     * Gets the text inside the selection.
     *
     * @param selection Selection range
     * @return Selected text
     */
    private String selectedText(int[] selection) {
        if (selection[0] == selection[2]) return this.lines.get(selection[0]).substring(selection[1], selection[3]);
        StringBuilder builder = new StringBuilder();
        builder.append(this.lines.get(selection[0]), selection[1], this.lines.get(selection[0]).length());
        for (int i = selection[0] + 1; i < selection[2]; i++) {
            builder.append('\n').append(this.lines.get(i));
        }
        return builder.append('\n').append(this.lines.get(selection[2]), 0, selection[3]).toString();
    }

    /**
     * Converts the mouse position into a text position.
     *
     * @param mouseX Mouse X
     * @param mouseY Mouse Y
     * @return {@code {line, column}}
     */
    private int[] positionAt(double mouseX, double mouseY) {
        int line = this.scroll + (int) Math.floor((mouseY - this.getY() - PADDING) / this.font.lineHeight);
        line = clamp(line, 0, this.lines.size() - 1);
        String text = this.lines.get(line);
        int offset = (int) Math.floor(mouseX - this.getX() - PADDING);
        if (offset <= 0) return new int[]{line, 0};
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int next = width + this.font.width(String.valueOf(text.charAt(i)));
            if (next > offset) return new int[]{line, i};
            width = next;
        }
        return new int[]{line, text.length()};
    }

    /**
     * Splits the text into lines, keeping at least one line.
     *
     * @param value Target value
     * @return Split lines
     */
    private static List<String> split(String value) {
        if (value == null || value.isEmpty()) return new ArrayList<>(List.of(""));
        return new ArrayList<>(List.of(value.split("\n", -1)));
    }

    /**
     * Clamps the value between the bounds.
     *
     * @param value Target value
     * @param min   Minimum value
     * @param max   Maximum value
     * @return Clamped value
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "MultilineTextBox{" +
                "lines=" + this.lines.size() +
                ", cursorLine=" + this.cursorLine +
                ", cursorCol=" + this.cursorCol +
                '}';
    }
}
