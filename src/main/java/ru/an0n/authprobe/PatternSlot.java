package ru.an0n.authprobe;

/**
 * One editable pattern bank, used by the "add line to patterns" picker.
 *
 * @author AN0N
 */
public enum PatternSlot {
    /**
     * Chat answers.
     */
    SUCCESS("authprobe.patterns.success", true),
    FAIL("authprobe.patterns.fail", true),
    PAUSE("authprobe.patterns.wait", true),
    KICK("authprobe.patterns.kick", true),
    TWO_FACTOR("authprobe.patterns.twofactor", true),
    CAPTCHA("authprobe.patterns.captcha", true),
    NICK_ONLINE("authprobe.patterns.nickonline", false),
    NICK_INVALID("authprobe.patterns.nickinvalid", false),
    PROXY_KICK("authprobe.patterns.proxykicks", false);

    /**
     * Translation key of the bank label.
     */
    private final String labelKey;

    /**
     * Whether the bank belongs to a server, not to the shared kick lists.
     */
    private final boolean serverBank;

    PatternSlot(String labelKey, boolean serverBank) {
        this.labelKey = labelKey;
        this.serverBank = serverBank;
    }

    /**
     * Gets the translation key of the bank label.
     *
     * @return Label key
     */
    public String labelKey() {
        return this.labelKey;
    }

    /**
     * Checks whether the bank belongs to a server.
     *
     * @return Whether the bank is per server
     */
    public boolean serverBank() {
        return this.serverBank;
    }
}