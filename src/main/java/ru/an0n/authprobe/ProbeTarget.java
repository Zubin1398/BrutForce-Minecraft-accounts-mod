package ru.an0n.authprobe;

import java.util.List;

/**
 * One nickname and the password list to probe for it, plus the mark left by the last
 * nickname-level kick. The mark survives restarts so a "already online" nick can be
 * retried hours later; it is cleared on the next successful join or when the nick is edited.
 *
 * @param nick      Nickname to join as
 * @param passwords Passwords to try, in order
 * @param skipReason Reason of the last nickname-level kick, empty when none
 * @param skipAt    Time of that kick, milliseconds since the epoch, zero when none
 * @param skipKind  {@link #KIND_ONLINE} for a temporary kick, {@link #KIND_INVALID} for a
 *                  nickname the server will never accept
 */
public record ProbeTarget(String nick, List<String> passwords, String skipReason, long skipAt, String skipKind) {
    /**
     * Temporary nickname kick: the nick exists but was busy.
     */
    public static final String KIND_ONLINE = "online";

    /**
     * Permanent nickname kick: the server rejects the name itself.
     */
    public static final String KIND_INVALID = "invalid";

    /**
     * Creates a target without a skip mark.
     *
     * @param nick      Nickname
     * @param passwords Passwords
     */
    public ProbeTarget(String nick, List<String> passwords) {
        this(nick, passwords, "", 0L, "");
    }

    /**
     * Gets the amount of passwords.
     *
     * @return Password amount
     */
    public int passwordCount() {
        return this.passwords.size();
    }

    /**
     * Checks whether the nickname carries a skip mark.
     *
     * @return Whether a mark is present
     */
    public boolean hasSkip() {
        return !this.skipKind.isBlank() && this.skipAt > 0L;
    }

    /**
     * Checks whether the skip mark is permanent.
     *
     * @return Whether the server rejected the nickname itself
     */
    public boolean skipIsInvalid() {
        return KIND_INVALID.equals(this.skipKind);
    }

    /**
     * Copies the target with the same passwords and a new skip mark.
     *
     * @param reason Skip reason
     * @param at     Time of the kick
     * @param kind   {@link #KIND_ONLINE} or {@link #KIND_INVALID}
     * @return New target
     */
    public ProbeTarget withSkip(String reason, long at, String kind) {
        return new ProbeTarget(this.nick, this.passwords,
                reason == null ? "" : reason, at, kind == null ? KIND_ONLINE : kind);
    }

    /**
     * Copies the target without a skip mark.
     *
     * @return New target
     */
    public ProbeTarget withoutSkip() {
        return new ProbeTarget(this.nick, this.passwords);
    }

    /**
     * Copies the target with new passwords, keeping the mark.
     *
     * @param newPasswords Replacement passwords
     * @return New target
     */
    public ProbeTarget withPasswords(List<String> newPasswords) {
        return new ProbeTarget(this.nick, newPasswords, this.skipReason, this.skipAt, this.skipKind);
    }

    /**
     * Copies the target with another nickname. Changing the name drops the mark: it described
     * the old name.
     *
     * @param newNick New nickname
     * @return New target
     */
    public ProbeTarget withNick(String newNick) {
        String value = newNick == null ? "" : newNick;
        boolean same = value.equals(this.nick);
        return same
                ? new ProbeTarget(value, this.passwords, this.skipReason, this.skipAt, this.skipKind)
                : new ProbeTarget(value, this.passwords);
    }
}