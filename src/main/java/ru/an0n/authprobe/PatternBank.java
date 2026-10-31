package ru.an0n.authprobe;

import java.util.List;
import java.util.Locale;

/**
 * Substring banks used to read the server's answer out of chat and the action bar.
 * Every real server words these differently, so both banks and the live chat pane
 * are editable in the UI instead of being baked in.
 *
 * <p>Short Latin tokens such as {@code ip} or {@code vpn} are matched as whole words, so a
 * join message about a {@code VIP} player never reads as an IP ban.
 *
 * @param success Substrings marking a successful login
 * @param fail    Substrings marking a rejected password
 * @param pause   Substrings marking a server-requested pause
 * @param kick    Substrings marking an attempt-limit or flood kick
 */
public record PatternBank(List<String> success, List<String> fail, List<String> pause, List<String> kick) {
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
                        "неверный пароль", "неправильный пароль", "неверный логин", "неправильный логин",
                        "неправильно указан логин", "неправильно указан пароль",
                        "неверно указан логин", "неверно указан пароль"),
                List.of("подождите", "подожди", "не так быстро", "проверка пароля",
                        "please wait", "wait before", "slow down", "too fast", "not so fast"),
                List.of("too many", "слишком много", "kicked", "flood", "spam", "попыток")
        );
    }

    /**
     * Gets the default reasons that mean the nickname is taken and the account is already
     * online somewhere.
     *
     * @return Default busy-nickname reasons
     */
    public static List<String> defaultNickOnline() {
        return List.of(
                "already logged in", "already connected", "already online", "already in game",
                "already playing", "already on the server", "player is already", "name is already",
                "nickname is already", "you are already", "name is already in use",
                "уже на сервере", "уже в игре", "уже авторизован", "уже находится", "уже онлайн",
                "ник уже", "ник занят", "ник в онлайне"
        );
    }

    /**
     * Gets the default reasons that mean the server rejected the name itself.
     *
     * @return Default invalid-nickname reasons
     */
    public static List<String> defaultNickInvalid() {
        return List.of(
                "invalid nickname", "invalid username", "bad nickname", "illegal nickname",
                "failed to verify username", "failed to verify authentication",
                "неверный ник", "неправильный ник", "недопустимый ник",
                "неправильно указан ник", "неправильно указали ник", "неверно указан ник"
        );
    }

    /**
     * Stems that made a phrase permanent in the shared nickname list of older versions.
     * Used only to split that list during a config migration.
     *
     * @param phrase Phrase to inspect
     * @return Whether the phrase is about an invalid name
     */
    public static boolean looksLikeInvalidNick(String phrase) {
        if (phrase == null) return false;
        String value = phrase.toLowerCase(Locale.ROOT);
        for (String stem : List.of("invalid", "неверн", "неправильн", "недопустим",
                "bad nickname", "illegal nickname")) {
            if (value.contains(stem)) return true;
        }
        return false;
    }

    /**
     * Gets the default reasons that mean the server filtered the IP or the proxy.
     *
     * @return Default proxy kick reasons
     */
    public static List<String> defaultProxyKicks() {
        return List.of(
                "ip ban", "ip-ban", "banned your ip", "banned by ip", "your ip", "ip address", "ip",
                "ip-адрес", "ip адрес", "ваш ip", "бан по ip",
                "vpn", "proxy", "прокси", "suspicious", "blacklist", "blacklisted", "blocked",
                "timeout", "timed out", "connection timed out",
                "превышено время ожидания", "время ожидания", "тайм-аут", "таймаут", "заблокирован"
        );
    }

    /**
     * Gets the default two-factor phrases.
     *
     * @return Default 2FA phrases
     */
    public static List<String> defaultTwoFactor() {
        return List.of(
                "2fa", "two-factor", "two factor", "authenticator",
                "подтвердите", "подтвердить вход", "подтвердите вход", "подтверди вход"
        );
    }

    /**
     * Gets the default captcha phrases. Context words only: "не бот" instead of a bare "бот",
     * because 2FA lives "в боте" too.
     *
     * @return Default captcha phrases
     */
    public static List<String> defaultCaptcha() {
        return List.of(
                "капча", "капч", "captcha",
                "не робот", "не бот", "not a robot", "not a bot",
                "вы человек", "you are human", "human verification",
                "антибот", "antibot", "проверка на бота", "бот-проверка", "bot check"
        );
    }

    /**
     * Checks whether the text is a two-factor confirmation request.
     *
     * @param twoFactor Two-factor phrases
     * @param captcha   Captcha phrases, which always win over 2FA
     * @param text      Message text
     * @return Whether the text asks for a two-factor confirmation
     */
    public static boolean matchesTwoFactor(List<String> twoFactor, List<String> captcha, String text) {
        if (!matches(twoFactor, text)) return false;
        return !matches(captcha, text);
    }

    /**
     * Checks whether the text is a captcha prompt.
     *
     * @param captcha Captcha phrases
     * @param text    Message text
     * @return Whether the text asks for a captcha
     */
    public static boolean matchesCaptcha(List<String> captcha, String text) {
        return matches(captcha, text);
    }

    /**
     * Checks whether the text contains any of the patterns, case-insensitively.
     *
     * @param patterns Target patterns
     * @param text     Target text
     * @return Whether a pattern matched
     */
    public static boolean matches(List<String> patterns, String text) {
        return !matchesAny(patterns, text).isEmpty();
    }

    /**
     * Replaces non-breaking and zero-width spaces with a regular space, so a line made only of
     * them counts as blank and a pattern made only of them is ignored.
     *
     * @param value Source text, may be {@code null}
     * @return Normalised text, never {@code null}
     */
    public static String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace('\u200B', ' ')
                .replace('\uFEFF', ' ');
    }

    /**
     * Finds the first pattern the text contains, so the decision can be logged and shown.
     *
     * @param patterns Target patterns
     * @param text     Target text
     * @return The pattern that matched, empty when none did
     */
    public static String matchesAny(List<String> patterns, String text) {
        String normalized = normalize(text);
        if (patterns == null || normalized.isBlank()) return "";
        String haystack = normalized.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern == null) continue;
            String needle = normalize(pattern).trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) continue;
            if (isShortLatinToken(needle) ? containsWord(haystack, needle) : haystack.contains(needle)) {
                return pattern.trim();
            }
        }
        return "";
    }

    /**
     * Checks whether a needle is a short Latin token that has to be matched as a whole word.
     *
     * @param needle Lowercase pattern
     * @return Whether whole-word matching applies
     */
    private static boolean isShortLatinToken(String needle) {
        if (needle.length() > 3) return false;
        for (int i = 0; i < needle.length(); i++) {
            char c = needle.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!allowed) return false;
        }
        return true;
    }

    /**
     * Checks whether the haystack contains the needle with word boundaries on both sides.
     *
     * @param haystack Lowercase text
     * @param needle   Lowercase token
     * @return Whether the token appears as a whole word
     */
    private static boolean containsWord(String haystack, String needle) {
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            int end = index + needle.length();
            boolean leftEdge = index == 0 || !isWordChar(haystack.charAt(index - 1));
            boolean rightEdge = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftEdge && rightEdge) return true;
            index = haystack.indexOf(needle, index + 1);
        }
        return false;
    }

    /**
     * Checks whether a character belongs to a word.
     *
     * @param c Character
     * @return Whether the character continues a word
     */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}