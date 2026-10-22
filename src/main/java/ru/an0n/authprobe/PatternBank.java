package ru.an0n.authprobe;

import java.util.List;
import java.util.Locale;

/**
 * Substring banks used to read the server's answer out of chat and the action bar.
 * Every real server words these differently, so both banks and the live chat pane
 * are editable in the UI instead of being baked in.
 *
 * @param success Substrings marking a successful login
 * @param fail    Substrings marking a rejected password
 * @param kick    Substrings marking an attempt-limit or flood kick
 */
public record PatternBank(List<String> success, List<String> fail, List<String> kick) {
    /**
     * Gets the default banks. Covers stock AuthMe wording in English and Russian.
     *
     * @return Default banks
     */
    public static PatternBank defaults() {
        return new PatternBank(
                List.of("logged in", "successfully logged", "you are now logged",
                        "успешно", "вы вошли", "авторизован", "вход выполнен", "успешный"),
                List.of("wrong password", "incorrect password", "invalid password", "password is wrong",
                        "неверный пароль", "неправильный пароль", "неверный логин", "неправильный логин"),
                List.of("too many", "слишком много", "kicked", "flood", "spam", "подождите",
                        "попыток", "ip", "IP", "заблокирован")
        );
    }

    /**
     * Checks whether the text contains any of the patterns, case-insensitively.
     *
     * @param patterns Target patterns
     * @param text     Target text
     * @return Whether a pattern matched
     */
    public static boolean matches(List<String> patterns, String text) {
        if (patterns == null || text == null || text.isBlank()) return false;
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern == null) continue;
            String needle = pattern.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) continue;
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}