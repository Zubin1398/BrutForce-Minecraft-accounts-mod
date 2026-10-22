package ru.an0n.authprobe;

import java.util.Locale;

/**
 * The login command shape sent on every attempt.
 *
 * @author AN0N
 */
public enum LoginMode {
    /**
     * {@code /l <password>}
     */
    SLASH_L("/l %s"),

    /**
     * {@code /login <password>}
     */
    SLASH_LOGIN("/login %s");

    /**
     * Command template, {@code %s} is the password.
     */
    private final String template;

    /**
     * Creates a new mode.
     *
     * @param template Command template
     */
    LoginMode(String template) {
        this.template = template;
    }

    /**
     * Gets the command template.
     *
     * @return Template
     */
    public String template() {
        return this.template;
    }

    /**
     * Gets the template for display.
     *
     * @return Display template
     */
    public String display() {
        return this.template.replace("%s", "<password>");
    }

    /**
     * Builds the packet text for the password.
     * {@code ServerboundChatCommandPacket} carries the command WITHOUT the leading slash,
     * passing the slash through would reach the server as a literal command name.
     *
     * @param password Target password
     * @return Packet command text
     */
    public String packet(String password) {
        String formatted = String.format(Locale.ROOT, this.template, password);
        return formatted.startsWith("/") ? formatted.substring(1) : formatted;
    }

    /**
     * Gets the next mode, for cycling in the UI.
     *
     * @return Next mode
     */
    public LoginMode next() {
        return this == SLASH_L ? SLASH_LOGIN : SLASH_L;
    }
}