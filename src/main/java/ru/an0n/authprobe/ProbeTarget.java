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
 * @param result    {@link #RESULT_EXHAUSTED} when every password failed, {@link #RESULT_FOUND}
 *                  when one was accepted, empty otherwise
 */
public record ProbeTarget(String nick, List<String> passwords, String skipReason, long skipAt, String skipKind,
                          String result) {
    /**
     * Temporary nickname kick: the nick exists but was busy.
     */
    public static final String KIND_ONLINE = "online";

    /**
     * Permanent nickname kick: the server rejects the name itself.
     */
    public static final String KIND_INVALID = "invalid";

    /**
     * The account needs a two-factor confirmation before the login can finish.
     */
    public static final String KIND_2FA = "2fa";

    /**
     * Every password of this nickname was spent and none worked.
     */
    public static final String RESULT_EXHAUSTED = "exhausted";

    /**
     * A password of this nickname was accepted.
     */
    public static final String RESULT_FOUND = "found";

    /**
     * Creates a target without a skip mark or a result.
     *
     * @param nick      Nickname
     * @param passwords Passwords
     */
    public ProbeTarget(String nick, List<String> passwords) {
        this(nick, passwords, "", 0L, "", "");
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
     * Gets the profile order rank used by the sort button.
     *
     * @return Rank, 1 (2FA) to 6 (unmarked)
     */
    public int sortRank() {
        if (RESULT_FOUND.equals(this.result)) return 4;
        if (hasSkip() && isTwoFactor()) return 1;
        if (hasSkip() && skipIsInvalid()) return 2;
        if (isExhausted()) return 3;
        if (hasSkip()) return 5;
        return 6;
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
     * Checks whether the skip mark means a two-factor confirmation is required.
     *
     * @return Whether the mark is a 2FA mark
     */
    public boolean isTwoFactor() {
        return KIND_2FA.equals(this.skipKind);
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
        // A found password is the final result: a later nickname kick must not overwrite it.
        if (RESULT_FOUND.equals(this.result)) return this;
        return new ProbeTarget(this.nick, this.passwords,
                reason == null ? "" : reason, at, kind == null ? KIND_ONLINE : kind, this.result);
    }

    /**
     * Checks whether every password of this nickname was spent without success.
     *
     * @return Whether the nickname is exhausted
     */
    public boolean isExhausted() {
        return RESULT_EXHAUSTED.equals(this.result);
    }

    /**
     * Copies the target with another result.
     *
     * @param value {@link #RESULT_EXHAUSTED}, {@link #RESULT_FOUND} or empty
     * @return New target
     */
    public ProbeTarget withResult(String value) {
        return new ProbeTarget(this.nick, this.passwords, this.skipReason, this.skipAt, this.skipKind,
                value == null ? "" : value);
    }

    /**
     * Copies the target without a skip mark, keeping the result.
     *
     * @return New target
     */
    public ProbeTarget withoutSkip() {
        return new ProbeTarget(this.nick, this.passwords, "", 0L, "", this.result);
    }

    /**
     * Copies the target with new passwords, keeping the mark and the result.
     *
     * @param newPasswords Replacement passwords
     * @return New target
     */
    public ProbeTarget withPasswords(List<String> newPasswords) {
        return new ProbeTarget(this.nick, newPasswords, this.skipReason, this.skipAt, this.skipKind, this.result);
    }

    /**
     * Copies the target with another nickname. Changing the name drops the mark and the result:
     * they described the old name.
     *
     * @param newNick New nickname
     * @return New target
     */
    public ProbeTarget withNick(String newNick) {
        String value = newNick == null ? "" : newNick;
        boolean same = value.equals(this.nick);
        return same
                ? new ProbeTarget(value, this.passwords, this.skipReason, this.skipAt, this.skipKind, this.result)
                : new ProbeTarget(value, this.passwords);
    }
}