import ru.an0n.authprobe.LoginMode;
import ru.an0n.authprobe.PatternBank;
import ru.an0n.authprobe.PatternSlot;
import ru.an0n.authprobe.ProbeServer;
import ru.an0n.authprobe.ProbeTarget;
import ru.an0n.authprobe.ProbeTiming;
import ru.an0n.authprobe.ServerPause;
import ru.an0n.authprobe.SuccessExport;
import ru.an0n.authprobe.utils.Nicks;
import ru.an0n.authprobe.utils.PasswordList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone gate for the Minecraft-free logic: parsing, validation, pattern matching,
 * and command shaping. Compiled and run with plain javac/java, outside Gradle.
 */
public final class PureLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
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
        eq("normalize.nbsp", " VK", PatternBank.normalize("\u00A0VK"));
        eq("normalize.null", "", PatternBank.normalize(null));
        eq("match.nbspText", true, PatternBank.matches(List.of("vip"), "\u00A0vip\u00A0"));
        eq("match.spaceOnlyPattern", false, PatternBank.matches(List.of("\u00A0"), "\u00A0\u00A0"));
        eq("match.spaceOnlyPatternRu", false, PatternBank.matches(List.of("\u200B"), "любой текст"));

        // ---- PatternBank.defaults ----
        PatternBank defaults = PatternBank.defaults();
        eq("defaults.success.matchesEn", true, PatternBank.matches(defaults.success(), "You are now logged in!"));
        eq("defaults.success.matchesRu", true, PatternBank.matches(defaults.success(), "Вы успешно вошли"));
        eq("defaults.fail.matchesEn", true, PatternBank.matches(defaults.fail(), "Wrong password!"));
        eq("defaults.fail.matchesRu", true, PatternBank.matches(defaults.fail(), "Неверный пароль"));
        eq("defaults.fail.wrongLoginDeclined", true, PatternBank.matches(defaults.fail(), "Неверно указан логин"));
        eq("defaults.fail.wrongPasswordDeclined", true, PatternBank.matches(defaults.fail(), "неправильно указан пароль"));
        eq("defaults.kick.matchesEn", true, PatternBank.matches(defaults.kick(), "Too many failed attempts"));
        eq("defaults.kick.matchesRu", true, PatternBank.matches(defaults.kick(), "Слишком много попыток"));
        eq("defaults.kick.notIp", false, PatternBank.matches(defaults.kick(), "IP"));
        eq("defaults.wait.matchesRu", true, PatternBank.matches(defaults.pause(), "Подождите перед отправкой 30 сек"));
        eq("defaults.wait.matchesEn", true, PatternBank.matches(defaults.pause(), "Please wait 15s"));
        eq("defaults.wait.notFail", false, PatternBank.matches(defaults.pause(), "Неверный пароль"));
        eq("defaults.wait.notSuccess", false, PatternBank.matches(defaults.pause(), "You are now logged in"));
        eq("defaults.nickKicks.notProxy", false,
                PatternBank.matches(PatternBank.defaultNickOnline(), "Your IP is blocked"));
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
        eq("pause.ru.secondsPlural", 10_000L, ServerPause.parseMillis("подождите 10 секунды"));
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

        // ---- Two-factor and captcha detection ----
        List<String> twoFactor = PatternBank.defaultTwoFactor();
        List<String> captcha = PatternBank.defaultCaptcha();
        eq("2fa.plain", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "Введите код 2FA"));
        eq("2fa.twoFactor", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "Two-factor authentication required"));
        eq("2fa.twoFactorSpace", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "two factor code sent"));
        eq("2fa.authenticator", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "Open your authenticator app"));
        eq("2fa.confirmRu", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "Подтвердите вход в Telegram"));
        eq("2fa.confirmBot", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "Подтвердите вход в боте"));
        eq("2fa.confirmShort", true, PatternBank.matchesTwoFactor(twoFactor, captcha, "подтверди вход"));
        eq("2fa.captchaRobot", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "Подтвердите, что вы не робот"));
        eq("2fa.captchaWord", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "Введите капчу и подтвердите вход"));
        eq("2fa.captchaBot", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "Подтвердите, что вы не бот"));
        eq("2fa.captchaEn", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "Confirm you are not a robot"));
        eq("2fa.notPresent", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "Неверный пароль"));
        eq("2fa.passwordPrompt", false, PatternBank.matchesTwoFactor(twoFactor, captcha, "/login <пароль>"));

        eq("captcha.word", true, PatternBank.matchesCaptcha(captcha, "Введите капчу"));
        eq("captcha.notRobot", true, PatternBank.matchesCaptcha(captcha, "Подтвердите, что вы не робот"));
        eq("captcha.notBot", true, PatternBank.matchesCaptcha(captcha, "Подтвердите, что вы не бот"));
        eq("captcha.en", true, PatternBank.matchesCaptcha(captcha, "Please prove you are human"));
        eq("captcha.bareBot", false, PatternBank.matchesCaptcha(captcha, "Подтвердите вход в боте"));
        eq("captcha.telegram", false, PatternBank.matchesCaptcha(captcha, "Подтвердите вход в Telegram"));

        // ---- Nickname kick split ----
        List<String> nickOnline = PatternBank.defaultNickOnline();
        List<String> nickInvalid = PatternBank.defaultNickInvalid();
        eq("nick.online.ru", true, PatternBank.matches(nickOnline, "Этот ник уже онлайн"));
        eq("nick.online.en", true, PatternBank.matches(nickOnline, "You are already connected"));
        eq("nick.invalid.case", true,
                PatternBank.matches(nickInvalid, "неправильно указали ник \"Perfecto\", а правильно \"perfecto\""));
        eq("nick.invalid.en", true, PatternBank.matches(nickInvalid, "Invalid username"));
        eq("nick.invalid.notOnline", false, PatternBank.matches(nickOnline, "неверный ник"));
        eq("nick.online.notInvalid", false, PatternBank.matches(nickInvalid, "уже онлайн"));
        eq("nick.split.onlineCount", 19, nickOnline.size());
        eq("nick.split.invalidCount", 12, nickInvalid.size());
        eq("nick.invalid.declined", true, PatternBank.matches(nickInvalid, "неверно указан ник"));
        eq("nick.invalid.declinedPast", true, PatternBank.matches(nickInvalid, "неправильно указали ник \"Perfecto\""));

        // ---- Server-side password check ----
        eq("hold.matchesPauseBank", true, PatternBank.matches(defaults.pause(), "Идёт проверка пароля"));
        eq("hold.noDuration", -1L, ServerPause.parseMillis("проверка пароля"));
        eq("hold.notSuccess", false, PatternBank.matches(defaults.success(), "проверка пароля"));

        // ---- Answer timeout with a server-side hold ----
        eq("holdTimeout.noHold.waits", false, ProbeTiming.answerExpired(false, 1_000L, 0L, 0L, 1_000, 15_000));
        eq("holdTimeout.noHold.expired", true, ProbeTiming.answerExpired(false, 1_001L, 0L, 0L, 1_000, 15_000));
        eq("holdTimeout.holdIgnoresShortWait", false,
                ProbeTiming.answerExpired(true, 6_000L, 5_000L, 0L, 1_000, 15_000));
        eq("holdTimeout.holdWaitsAtLimit", false,
                ProbeTiming.answerExpired(true, 15_000L, 15_000L, 0L, 1_000, 15_000));
        eq("holdTimeout.holdExpired", true,
                ProbeTiming.answerExpired(true, 15_001L, 15_000L, 0L, 1_000, 15_000));
        eq("holdTimeout.clampLow", 5_000, ProbeTiming.clampHoldLimit(1_000));
        eq("holdTimeout.clampHigh", 20_000, ProbeTiming.clampHoldLimit(60_000));
        eq("holdTimeout.clampKeeps", 15_000, ProbeTiming.clampHoldLimit(15_000));
        eq("holdTimeout.default", 15_000, ProbeTiming.DEFAULT_HOLD_MS);

        // ---- Successful-nick export ----
        ProbeServer exportOne = new ProbeServer("mc.politmine.ru");
        exportOne.replaceTargets(List.of(
                new ProbeTarget("feelsmango", List.of("12345678.")).withResult(ProbeTarget.RESULT_FOUND),
                new ProbeTarget("dead", List.of("x")).withResult(ProbeTarget.RESULT_EXHAUSTED),
                new ProbeTarget("virusbos", List.of("12345678.")).withResult(ProbeTarget.RESULT_FOUND)));
        ProbeServer exportTwo = new ProbeServer("play.example.com");
        exportTwo.replaceTargets(List.of(
                new ProbeTarget("Nick", List.of("pass")).withResult(ProbeTarget.RESULT_FOUND)));
        ProbeServer exportEmpty = new ProbeServer("empty.example.com");
        exportEmpty.replaceTargets(List.of(new ProbeTarget("none", List.of("pw"))));
        String exportExpected = "# AuthProbe — успешные ники\n"
                + "# 30.10.2026 23:20\n"
                + "\n"
                + "[mc.politmine.ru]\n"
                + "feelsmango:12345678.\n"
                + "virusbos:12345678.\n"
                + "\n"
                + "[play.example.com]\n"
                + "Nick:pass\n";
        eq("export.count", 3, SuccessExport.count(List.of(exportOne, exportTwo, exportEmpty)));
        eq("export.text", exportExpected,
                SuccessExport.build(List.of(exportOne, exportTwo, exportEmpty), "30.10.2026 23:20"));
        eq("export.emptyText", "# AuthProbe — успешные ники\n# 30.10.2026 23:20\n",
                SuccessExport.build(List.of(exportEmpty), "30.10.2026 23:20"));
        eq("export.emptyCount", 0, SuccessExport.count(List.of(exportEmpty)));
        ProbeServer exportNoPassword = new ProbeServer("no-password.example.com");
        exportNoPassword.replaceTargets(List.of(
                new ProbeTarget("Nick", List.of()).withResult(ProbeTarget.RESULT_FOUND)));
        eq("export.noPassword", "# AuthProbe — успешные ники\n# stamp\n\n[no-password.example.com]\nNick\n",
                SuccessExport.build(List.of(exportNoPassword), "stamp"));
        Path exportFile = Files.createTempFile("authprobe-export-", ".txt");
        SuccessExport.write(List.of(exportOne, exportTwo, exportEmpty), "30.10.2026 23:20", exportFile);
        eq("export.write", exportExpected, Files.readString(exportFile, StandardCharsets.UTF_8));
        Files.deleteIfExists(exportFile);

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
        eq("target.resultFound", false, plainTarget.withResult(ProbeTarget.RESULT_FOUND).isExhausted());
        eq("target.resultExhausted", true,
                plainTarget.withResult(ProbeTarget.RESULT_EXHAUSTED).isExhausted());
        eq("target.resultKeptOnSkip", true,
                plainTarget.withResult(ProbeTarget.RESULT_EXHAUSTED)
                        .withSkip("x", 1L, ProbeTarget.KIND_2FA).isExhausted());
        eq("target.resultClearedOnNewNick", false,
                plainTarget.withResult(ProbeTarget.RESULT_EXHAUSTED).withNick("Other").isExhausted());
        eq("target.resultKeptOnSameNick", true,
                plainTarget.withResult(ProbeTarget.RESULT_EXHAUSTED).withNick("Nick").isExhausted());

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

        // ---- Profile sorting ----
        ProbeTarget rankPlain = new ProbeTarget("plain", List.of("p"));
        eq("sort.rank.unmarked", 6, rankPlain.sortRank());
        eq("sort.rank.online", 5, rankPlain.withSkip("online", 1L, ProbeTarget.KIND_ONLINE).sortRank());
        eq("sort.rank.found", 4, rankPlain.withResult(ProbeTarget.RESULT_FOUND).sortRank());
        eq("sort.rank.exhausted", 3, rankPlain.withResult(ProbeTarget.RESULT_EXHAUSTED).sortRank());
        eq("sort.rank.invalid", 2, rankPlain.withSkip("invalid", 1L, ProbeTarget.KIND_INVALID).sortRank());
        eq("sort.rank.2fa", 1, rankPlain.withSkip("2fa", 1L, ProbeTarget.KIND_2FA).sortRank());
        eq("sort.rank.foundBeats2fa", 4, rankPlain.withSkip("2fa", 1L, ProbeTarget.KIND_2FA)
                .withResult(ProbeTarget.RESULT_FOUND).sortRank());
        eq("sort.rank.foundBeatsInvalid", 4, rankPlain.withSkip("invalid", 1L, ProbeTarget.KIND_INVALID)
                .withResult(ProbeTarget.RESULT_FOUND).sortRank());
        eq("sort.rank.exhaustedBeatsOnline", 3, rankPlain.withSkip("online", 1L, ProbeTarget.KIND_ONLINE)
                .withResult(ProbeTarget.RESULT_EXHAUSTED).sortRank());
        eq("sort.rank.foundBeatsOnline", 4, rankPlain.withSkip("online", 1L, ProbeTarget.KIND_ONLINE)
                .withResult(ProbeTarget.RESULT_FOUND).sortRank());

        // A found password must never be replaced by a later nickname kick.
        ProbeTarget foundTarget = rankPlain.withResult(ProbeTarget.RESULT_FOUND);
        eq("target.foundIgnoresOnlineResult", ProbeTarget.RESULT_FOUND,
                foundTarget.withSkip("online", 1L, ProbeTarget.KIND_ONLINE).result());
        eq("target.foundIgnoresOnlineMark", false,
                foundTarget.withSkip("online", 1L, ProbeTarget.KIND_ONLINE).hasSkip());
        eq("target.foundIgnores2faMark", false,
                foundTarget.withSkip("2fa", 1L, ProbeTarget.KIND_2FA).hasSkip());
        eq("target.foundIgnoresInvalidMark", false,
                foundTarget.withSkip("invalid", 1L, ProbeTarget.KIND_INVALID).hasSkip());

        ProbeServer sortServer = new ProbeServer("sort.example.com");
        ProbeTarget sortUnmarked = new ProbeTarget("unmarked", List.of("1"));
        ProbeTarget sortOnline = new ProbeTarget("online", List.of("1"))
                .withSkip("online", 1L, ProbeTarget.KIND_ONLINE);
        ProbeTarget sortFound = new ProbeTarget("found", List.of("1"))
                .withResult(ProbeTarget.RESULT_FOUND);
        ProbeTarget sortExhausted = new ProbeTarget("exhausted", List.of("1"))
                .withResult(ProbeTarget.RESULT_EXHAUSTED);
        ProbeTarget sortInvalid = new ProbeTarget("invalid", List.of("1"))
                .withSkip("invalid", 1L, ProbeTarget.KIND_INVALID);
        ProbeTarget sortTwoFactor = new ProbeTarget("twofa", List.of("1"))
                .withSkip("2fa", 1L, ProbeTarget.KIND_2FA);
        ProbeTarget sortFound2 = new ProbeTarget("found2", List.of("1"))
                .withResult(ProbeTarget.RESULT_FOUND);
        sortServer.replaceTargets(List.of(sortUnmarked, sortOnline, sortFound, sortExhausted,
                sortInvalid, sortTwoFactor, sortFound2));
        sortServer.activeIndex(2);
        eq("sort.changed", true, sortServer.sortTargetsByStatus());
        eq("sort.order", List.of("twofa", "invalid", "exhausted", "found", "found2", "online", "unmarked"),
                sortServer.targets().stream().map(ProbeTarget::nick).toList());
        eq("sort.activeKept", "found", sortServer.primary().nick());
        eq("sort.activeIndex", 3, sortServer.activeIndex());
        eq("sort.again", false, sortServer.sortTargetsByStatus());
        eq("sort.single", false, new ProbeServer("single").sortTargetsByStatus());

        // ---- Import merge ----
        ProbeServer mergeServer = new ProbeServer("merge.example.com");
        mergeServer.replaceTargets(List.of(
                new ProbeTarget("Nick", List.of("a")).withResult(ProbeTarget.RESULT_FOUND),
                new ProbeTarget("Online", List.of("x"))
                        .withSkip("online", 1L, ProbeTarget.KIND_ONLINE),
                new ProbeTarget("Plain", List.of("p"))));
        ProbeServer.MergeResult merge = mergeServer.mergeTargets(List.of(
                new ProbeTarget("nick", List.of("a", "b")),
                new ProbeTarget("ONLINE", List.of("x", "y")),
                new ProbeTarget("plain", List.of("p")),
                new ProbeTarget("new1", List.of("n1", "n2")),
                new ProbeTarget("new2", List.of())));
        eq("merge.added", 2, merge.added());
        eq("merge.merged", 1, merge.merged());
        eq("merge.skipped", 2, merge.skipped());
        eq("merge.passwordsAdded", 1, merge.passwordsAdded());
        eq("merge.changed", true, merge.changed());
        eq("merge.order", List.of("Nick", "Online", "Plain", "new1", "new2"),
                mergeServer.targets().stream().map(ProbeTarget::nick).toList());
        eq("merge.foundUnchanged", List.of("a"), mergeServer.targets().get(0).passwords());
        eq("merge.foundStillFound", ProbeTarget.RESULT_FOUND, mergeServer.targets().get(0).result());
        eq("merge.onlineMerged", List.of("x", "y"), mergeServer.targets().get(1).passwords());
        eq("merge.onlineKeptMark", true, mergeServer.targets().get(1).hasSkip());
        eq("merge.plainUnchanged", List.of("p"), mergeServer.targets().get(2).passwords());
        eq("merge.newEmptyPasswords", 0, mergeServer.targets().get(4).passwordCount());

        ProbeServer freshMerge = new ProbeServer("fresh.example.com");
        ProbeServer.MergeResult freshResult = freshMerge.mergeTargets(
                List.of(new ProbeTarget("A", List.of("1")), new ProbeTarget("B", List.of("2"))));
        eq("merge.freshRemovesBlank", 2, freshMerge.targets().size());
        eq("merge.freshFirst", "A", freshMerge.targets().get(0).nick());
        eq("merge.freshAdded", 2, freshResult.added());

        ProbeServer blankMerge = new ProbeServer("blank.example.com");
        ProbeServer.MergeResult blankResult = blankMerge.mergeTargets(
                List.of(new ProbeTarget("  ", List.of("1")), new ProbeTarget("Ok", List.of("2"))));
        eq("merge.blankSkipped", 1, blankResult.skipped());
        eq("merge.blankNotAdded", 1, blankMerge.targets().size());

        ProbeServer exhaustedMerge = new ProbeServer("exhausted.example.com");
        exhaustedMerge.replaceTargets(List.of(
                new ProbeTarget("Ex", List.of("a")).withResult(ProbeTarget.RESULT_EXHAUSTED)));
        ProbeServer.MergeResult exhaustedResult = exhaustedMerge.mergeTargets(
                List.of(new ProbeTarget("ex", List.of("b"))));
        eq("merge.exhaustedMerged", 1, exhaustedResult.merged());
        eq("merge.exhaustedCleared", "", exhaustedMerge.targets().get(0).result());
        eq("merge.exhaustedPasswords", List.of("a", "b"),
                exhaustedMerge.targets().get(0).passwords());

        // ---- Per-server pattern banks ----
        ProbeServer banks = new ProbeServer("one");
        ProbeServer other = new ProbeServer("two");
        eq("server.default2fa", true, banks.twoFactorDetection());
        eq("server.defaultBefore", false, banks.twoFactorBeforeStart());
        eq("server.addSuccess", true, banks.addPattern(PatternSlot.SUCCESS, "  тестовый вход ок  "));
        eq("server.addSuccessDup", false, banks.addPattern(PatternSlot.SUCCESS, "ТЕСТОВЫЙ ВХОД ОК"));
        eq("server.addSuccessStored", true,
                PatternBank.matches(banks.patterns().success(), "Тестовый вход ок"));
        eq("server.add2fa", true, banks.addPattern(PatternSlot.TWO_FACTOR, "код из бота"));
        eq("server.add2faDup", false, banks.addPattern(PatternSlot.TWO_FACTOR, "код из бота"));
        eq("server.addCaptcha", true, banks.addPattern(PatternSlot.CAPTCHA, "нажми на картинку"));
        eq("server.addGlobalSlot", false, banks.addPattern(PatternSlot.NICK_ONLINE, "уже онлайн"));
        eq("server.addBlank", false, banks.addPattern(PatternSlot.FAIL, "   "));
        eq("server.bankIsolation", false, PatternBank.matches(other.patterns().success(), "тестовый вход ок"));
        eq("server.twoFactorIsolation", false, PatternBank.matches(other.twoFactorPatterns(), "код из бота"));
        banks.twoFactorDetection(false);
        eq("server.toggleOwn", false, banks.twoFactorDetection());
        eq("server.toggleIsolation", true, other.twoFactorDetection());

        // ---- Bulk removal by mark and by result ----
        ProbeServer removals = new ProbeServer("s");
        removals.replaceTargets(List.of(
                new ProbeTarget("a", List.of("1")).withSkip("x", 1L, ProbeTarget.KIND_2FA),
                new ProbeTarget("b", List.of("1")).withResult(ProbeTarget.RESULT_EXHAUSTED),
                new ProbeTarget("c", List.of("1"))));
        eq("remove.2fa", 1, removals.removeWithSkipKind(ProbeTarget.KIND_2FA));
        eq("remove.2faLeft", 2, removals.targets().size());
        eq("remove.exhausted", 1, removals.removeExhausted());
        eq("remove.exhaustedLeft", 1, removals.targets().size());
        eq("remove.keepsOne", 1, removals.targets().size());
        eq("remove.none", 0, removals.removeExhausted());

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