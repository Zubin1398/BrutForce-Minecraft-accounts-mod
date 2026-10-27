package ru.an0n.authprobe;

import java.util.ArrayList;
import java.util.List;

/**
 * One server address with its own nickname profiles. Every address keeps its own active
 * nickname, its own password lists and its own skip marks; only the proxy list and the
 * last-used report are shared between servers.
 *
 * @author AN0N
 */
public final class ProbeServer {
    /**
     * Server address, {@code host} or {@code host:port}.
     */
    private String address;

    /**
     * Nickname profiles of this server. Always holds at least one entry.
     */
    private final List<ProbeTarget> targets = new ArrayList<>(List.of(new ProbeTarget("", List.of())));

    /**
     * Index of the profile currently edited and probed.
     */
    private int activeIndex;

    /**
     * Creates a server entry.
     *
     * @param address Address, may be empty
     */
    public ProbeServer(String address) {
        this.address = address == null ? "" : address.trim();
    }

    /**
     * Gets the address.
     *
     * @return Address
     */
    public String address() {
        return this.address;
    }

    /**
     * Sets the address.
     *
     * @param value New address
     */
    public void address(String value) {
        this.address = value == null ? "" : value.trim();
    }

    /**
     * Gets the nickname profiles.
     *
     * @return Profiles
     */
    public List<ProbeTarget> targets() {
        return this.targets;
    }

    /**
     * Gets the active profile index, clamped into range.
     *
     * @return Active index
     */
    public int activeIndex() {
        return clamp(this.activeIndex, 0, this.targets.size() - 1);
    }

    /**
     * Sets the active profile index, clamped into range.
     *
     * @param index New index
     */
    public void activeIndex(int index) {
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
    }

    /**
     * Gets the profile the probe drives.
     *
     * @return Primary profile
     */
    public ProbeTarget primary() {
        return this.targets.get(activeIndex());
    }

    /**
     * Replaces the profile the probe drives.
     *
     * @param target New primary profile
     */
    public void primary(ProbeTarget target) {
        this.targets.set(activeIndex(), target == null ? new ProbeTarget("", List.of()) : target);
    }

    /**
     * Adds an empty profile and makes it active.
     */
    public void addTarget() {
        this.targets.add(new ProbeTarget("", List.of()));
        this.activeIndex = this.targets.size() - 1;
    }

    /**
     * Removes the active profile, keeping at least one. The previous profile becomes active.
     */
    public void removeActiveTarget() {
        if (this.targets.size() <= 1) return;
        int index = activeIndex();
        this.targets.remove(index);
        this.activeIndex = clamp(index, 0, this.targets.size() - 1);
    }

    /**
     * Replaces every profile with imported ones and selects the first.
     *
     * @param imported Imported profiles
     * @return Whether the list was replaced
     */
    public boolean replaceTargets(List<ProbeTarget> imported) {
        if (imported == null || imported.isEmpty()) return false;
        this.targets.clear();
        this.targets.addAll(imported);
        this.activeIndex = 0;
        return true;
    }

    /**
     * Checks whether another profile follows the active one.
     *
     * @return Whether a next profile exists
     */
    public boolean hasNextTarget() {
        return activeIndex() + 1 < this.targets.size();
    }

    /**
     * Moves to the next profile.
     *
     * @return Whether the move happened
     */
    public boolean nextTarget() {
        if (!hasNextTarget()) return false;
        this.activeIndex = activeIndex() + 1;
        return true;
    }

    /**
     * Clamps the value between the bounds.
     *
     * @param value Target value
     * @param min   Minimum
     * @param max   Maximum
     * @return Clamped value
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}