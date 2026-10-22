import ru.an0n.authprobe.LoginMode;
import ru.an0n.authprobe.PatternBank;
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
        eq("defaults.success.notFail", false, PatternBank.matches(defaults.success(), "Wrong password!"));

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