import ru.an0n.authprobe.LoginMode;
import ru.an0n.authprobe.PatternBank;
import ru.an0n.authprobe.ProbeServer;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.ServerPause;
import ru.an0n.authprobe.utils.Nicks;
import ru.an0n.authprobe.utils.PasswordList;

import java.util.List;

/**
 * Standalone gate for the Minecraft-free logic: parsing, validation, pattern matching,
 * and command shaping. Compiled and run with plain javac/java, outside Gradle.
 */
public final class PureLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ---- PasswordList.parse ----
        eq("parse.null", List.of(), PasswordList.parse(null));
        eq("parse.empty", List.of(), PasswordList.parse(""));
        eq("parse.blankLinesDropped", List.of("a", "b"), PasswordList.parse("\n\na\n\nb\n"));
        eq("parse.crlf", List.of("alpha", "beta"), PasswordList.parse("alpha\r\nbeta"));
        eq("parse.orderKept", List.of("z", "a", "m"), PasswordList.parse("z\na\nm"));
        eq("parse.dedupKeepsOrder", List.of("b", "a"), PasswordList.parse("b\na\nb\na"));
        eq("parse.spacesPreserved", List.of(" pass word "), PasswordList.parse(" pass word "));
        eq("parse.commaNotSeparator", List.of("a,b"), PasswordList.parse("a,b"));
        eq("parse.semicolonNotSeparator", List.of("a;b"), PasswordList.parse("a;b"));
        eq("parse.tabPreserved", List.of("a\tb"), PasswordList.parse("a\tb"));
        eq("parse.count", 3, PasswordList.count("x\ny\nz"));

        // Cap.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < PasswordList.MAX + 500; i++) big.append("pw").append(i).append('\n');
        eq("parse.cappedAtMax", PasswordList.MAX, PasswordList.parse(big.toString()).size());

        // ---- Nicks ----
        eq("nick.tooShort", false, Nicks.isValid("ab"));
        eq("nick.min", true, Nicks.isValid("abc"));
        eq("nick.max", true, Nicks.isValid("abcdefghijklmnop"));
        eq("nick.tooLong", false, Nicks.isValid("abcdefghijklmnopq"));
        eq("nick.allowedChars", true, Nicks.isValid("a_B9z"));
        eq("nick.space", false, Nicks.isValid("a b"));
        eq("nick.dash", false, Nicks.isValid("a-b"));
        eq("nick.null", false, Nicks.isValid(null));
        eq("nick.cyrillic", false, Nicks.isValid("Вася"));

        // ---- PatternBank.matches ----
        List<String> bank = List.of("wrong password", "неверный пароль");
        eq("match.caseInsensitive", true, PatternBank.matches(bank, "WRONG PASSWORD!"));
        eq("match.substring", true, PatternBank.matches(bank, "[Auth] Wrong Password, try again"));
        eq("match.cyrillic", true, PatternBank.matches(bank, "Неверный пароль!"));
        eq("match.miss", false, PatternBank.matches(bank, "you are now logged in"));
        eq("match.nullText", false, PatternBank.matches(bank, null));
        eq("match.blankText", false, PatternBank.matches(bank, "   "));
        eq("match.nullBank", false, PatternBank.matches(null, "anything"));
        eq("match.blankPatternSkipped", false, PatternBank.matches(java.util.Arrays.asList("", "  ", null), "anything"));
        eq("match.partialToken", true, PatternBank.matches(List.of("logged in"), "You are now LOGGED IN!"));
        eq("match.noPartialWord", false, PatternBank.matches(List.of("login"), "you are now logged in"));

        // ---- PatternBank.defaults ----
        PatternBank defaults = PatternBank.defaults();
        eq("defaults.success.matchesEn", true, PatternBank.matches(defaults.success(), "You are now logged in!"));
        eq("defaults.success.matchesRu", true, PatternBank.matches(defaults.success(), "Вы успешно вошли"));
        eq("defaults.fail.matchesEn", true, PatternBank.matches(defaults.fail(), "Wrong password!"));
        eq("defaults.fail.matchesRu", true, PatternBank.matches(defaults.fail(), "Неверный пароль"));
        eq("defaults.kick.matchesEn", true, PatternBank.matches(defaults.kick(), "Too many failed attempts"));
        eq("defaults.kick.matchesRu", true, PatternBank.matches(defaults.kick(), "Слишком много попыток"));
        eq("defaults.wait.matchesRu", true, PatternBank.matches(defaults.pause(), "Подождите перед отправкой 30 сек"));
        eq("defaults.wait.matchesEn", true, PatternBank.matches(defaults.pause(), "Please wait 15s"));
        eq("defaults.wait.notFail", false, PatternBank.matches(defaults.pause(), "Неверный пароль"));
        eq("defaults.wait.notSuccess", false, PatternBank.matches(defaults.pause(), "You are now logged in"));
        eq("defaults.nickKicks.notProxy", false,
                PatternBank.matches(PatternBank.defaultNickKicks(), "Your IP is blocked"));
        eq("defaults.proxyKicks.bareIp", true, PatternBank.matches(PatternBank.defaultProxyKicks(), "IP"));
        eq("defaults.proxyKicks.vipNick", false,
                PatternBank.matches(PatternBank.defaultProxyKicks(), "VIP_Player joined the game"));
        eq("defaults.success.notFail", false, PatternBank.matches(defaults.success(), "Wrong password!"));

        // ---- Word boundaries for short Latin tokens ----
        eq("word.ipStandalone", true, PatternBank.matches(List.of("ip"), "Your IP is blocked"));
        eq("word.ipInVip", false, PatternBank.matches(List.of("ip"), "VIP_Player joined the game"));
        eq("word.ipInScript", false, PatternBank.matches(List.of("ip"), "kicked by script"));
        eq("word.ipHyphen", true, PatternBank.matches(List.of("ip"), "IP-адрес заблокирован"));
        eq("word.vpnStandalone", true, PatternBank.matches(List.of("vpn"), "VPN detected"));
        eq("word.longStaysSubstring", true, PatternBank.matches(List.of("kicked"), "You were kicked"));
        eq("word.matchesAny", "ip", PatternBank.matchesAny(List.of("vpn", "ip"), "Your IP is blocked"));
        eq("word.matchesAnyMiss", "", PatternBank.matchesAny(List.of("ip"), "VIP_Player joined"));

        // ---- Server pause parsing ----
        eq("pause.ru.seconds", 30_000L, ServerPause.parseMillis("Подождите перед отправкой 30 сек"));
        eq("pause.ru.secondsFull", 45_000L, ServerPause.parseMillis("Повторите через 45 секунд"));
        eq("pause.en.seconds", 15_000L, ServerPause.parseMillis("Please wait 15s before next attempt"));
        eq("pause.ru.minutes", 60_000L, ServerPause.parseMillis("Подождите 1 мин"));
        eq("pause.en.minutes", 120_000L, ServerPause.parseMillis("wait 2 minutes"));
        eq("pause.noNumber", -1L, ServerPause.parseMillis("Подождите перед отправкой"));
        eq("pause.noTimeUnit", -1L, ServerPause.parseMillis("У вас 3 попытки"));
        eq("pause.numbersInText", -1L, ServerPause.parseMillis("Онлайн 250 игроков"));
        eq("pause.cyrillicWordAfterNumber", -1L, ServerPause.parseMillis("5 сообщений отправлено"));
        eq("pause.clampMax", 300_000L, ServerPause.parseMillis("подождите 99999 сек"));
        eq("pause.clampMin", 1_000L, ServerPause.parseMillis("wait 500 ms"));
        eq("pause.zero", -1L, ServerPause.parseMillis("wait 0 sec"));
        eq("pause.null", -1L, ServerPause.parseMillis(null));

        // ---- Two-factor detection ----
        eq("2fa.plain", true, PatternBank.matchesTwoFactor("Введите код 2FA"));
        eq("2fa.twoFactor", true, PatternBank.matchesTwoFactor("Two-factor authentication required"));
        eq("2fa.twoFactorSpace", true, PatternBank.matchesTwoFactor("two factor code sent"));
        eq("2fa.authenticator", true, PatternBank.matchesTwoFactor("Open your authenticator app"));
        eq("2fa.confirmRu", true, PatternBank.matchesTwoFactor("Подтвердите вход в Telegram"));
        eq("2fa.confirmShort", true, PatternBank.matchesTwoFactor("подтверди вход"));
        eq("2fa.captchaRobot", false, PatternBank.matchesTwoFactor("Подтвердите, что вы не робот"));
        eq("2fa.captchaWord", false, PatternBank.matchesTwoFactor("Введите капчу и подтвердите вход"));
        eq("2fa.captchaBot", false, PatternBank.matchesTwoFactor("Подтвердите, что вы не бот"));
        eq("2fa.captchaEn", false, PatternBank.matchesTwoFactor("Confirm you are not a robot"));
        eq("2fa.notPresent", false, PatternBank.matchesTwoFactor("Неверный пароль"));
        eq("2fa.passwordPrompt", false, PatternBank.matchesTwoFactor("/login <пароль>"));

        // ---- Skip marks on a target ----
        ProbeTarget plainTarget = new ProbeTarget("Nick", List.of("a"));
        eq("target.noMark", false, plainTarget.hasSkip());
        ProbeTarget markedTarget = plainTarget.withSkip("already online", 123L, ProbeTarget.KIND_ONLINE);
        eq("target.marked", true, markedTarget.hasSkip());
        eq("target.kindOnline", false, markedTarget.skipIsInvalid());
        eq("target.invalidKind", true,
                plainTarget.withSkip("invalid nickname", 5L, ProbeTarget.KIND_INVALID).skipIsInvalid());
        eq("target.keepOnSameNick", true, markedTarget.withNick("Nick").hasSkip());
        eq("target.dropOnNewNick", false, markedTarget.withNick("Other").hasSkip());
        eq("target.keepOnPasswords", true, markedTarget.withPasswords(List.of("b")).hasSkip());
        eq("target.keepPasswordsOnSameNick", List.of("a"), markedTarget.withNick("Nick").passwords());
        eq("target.clear", false, markedTarget.withoutSkip().hasSkip());
        ProbeTarget twoFactorTarget = plainTarget.withSkip("2FA", 7L, ProbeTarget.KIND_2FA);
        eq("target.2fa.marked", true, twoFactorTarget.hasSkip());
        eq("target.2fa.kind", true, twoFactorTarget.isTwoFactor());
        eq("target.2fa.notInvalid", false, twoFactorTarget.skipIsInvalid());

        // ---- ProbeServer ----
        ProbeServer server = new ProbeServer(" play.example.com ");
        eq("server.addressTrimmed", "play.example.com", server.address());
        eq("server.startsOneTarget", 1, server.targets().size());
        server.addTarget();
        eq("server.afterAdd", 2, server.targets().size());
        eq("server.activeIsNew", 1, server.activeIndex());
        eq("server.nextDoesNotWrap", false, server.nextTarget());
        server.activeIndex(0);
        eq("server.nextMoves", true, server.nextTarget());
        server.removeActiveTarget();
        eq("server.removeTarget", 1, server.targets().size());
        server.removeActiveTarget();
        eq("server.keepsOneTarget", 1, server.targets().size());
        eq("server.replaceTargets", true,
                server.replaceTargets(List.of(new ProbeTarget("A", List.of("1")), new ProbeTarget("B", List.of("2")))));
        eq("server.replaceSelectsFirst", "A", server.primary().nick());
        eq("server.replaceEmptyRejected", false, server.replaceTargets(List.of()));

        // ---- LoginMode ----
        eq("mode.l.packet", "l hunter2", LoginMode.SLASH_L.packet("hunter2"));
        eq("mode.login.packet", "login hunter2", LoginMode.SLASH_LOGIN.packet("hunter2"));
        eq("mode.noLeadingSlash.l", false, LoginMode.SLASH_L.packet("pw").startsWith("/"));
        eq("mode.noLeadingSlash.login", false, LoginMode.SLASH_LOGIN.packet("pw").startsWith("/"));
        // A percent sign in the password must survive: the template is ours, the password is an argument.
        eq("mode.percentSafe.l", "l p%ss%n", LoginMode.SLASH_L.packet("p%ss%n"));
        eq("mode.percentSafe.login", "login 100%", LoginMode.SLASH_LOGIN.packet("100%"));
        eq("mode.cycle.lToLogin", LoginMode.SLASH_LOGIN, LoginMode.SLASH_L.next());
        eq("mode.cycle.loginToL", LoginMode.SLASH_L, LoginMode.SLASH_LOGIN.next());
        eq("mode.display.l", "/l <password>", LoginMode.SLASH_L.display());
        eq("mode.template.login", "/login %s", LoginMode.SLASH_LOGIN.template());

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }
}