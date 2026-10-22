package ru.an0n.authprobe;

import java.util.List;

/**
 * One nickname and the password list to probe for it.
 * The probe drives a single target today; the list shape is here so multi-nick
 * support is a UI addition rather than a rewrite.
 *
 * @param nick      Nickname to join as
 * @param passwords Passwords to try, in order
 */
public record ProbeTarget(String nick, List<String> passwords) {
    /**
     * Gets the amount of passwords.
     *
     * @return Password amount
     */
    public int passwordCount() {
        return this.passwords.size();
    }
}