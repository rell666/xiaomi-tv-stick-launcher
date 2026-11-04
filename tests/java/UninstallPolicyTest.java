package home.cleanframe.tv;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * UninstallPolicy: which apps the long-press menu may offer to uninstall. Plain Java, no JUnit: compiled
 * and run by tests/fallback.test.sh. Prints a total; exits 1 on any failure.
 */
public final class UninstallPolicyTest {
    static final String SELF = "home.cleanframe.tv";
    static final Set<String> NO_HOMES = Collections.emptySet();

    static void eq(String got, String want, String what) {
        if (!want.equals(got)) throw new AssertionError(what + ": got \"" + got + "\", want \"" + want + "\"");
    }

    /** A user app as Android describes it. */
    static String user(String pkg) {
        return UninstallPolicy.refusal(pkg, SELF, true, false, false, NO_HOMES);
    }

    public static void main(String[] args) {
        eq(user("com.emby.embyatv"), "", "a user app");
        eq(user("org.jellyfin.androidtv"), "", "another");

        eq(UninstallPolicy.refusal("com.a.b", SELF, false, false, false, NO_HOMES), UninstallPolicy.NOT_INSTALLED, "not installed");
        eq(UninstallPolicy.refusal("com.a.b", SELF, true, true, false, NO_HOMES), UninstallPolicy.PROTECTED, "FLAG_SYSTEM");
        eq(UninstallPolicy.refusal("com.a.b", SELF, true, false, true, NO_HOMES), UninstallPolicy.PROTECTED, "FLAG_UPDATED_SYSTEM_APP");
        eq(UninstallPolicy.refusal("com.a.b", SELF, true, true, true, NO_HOMES), UninstallPolicy.PROTECTED, "both");

        // Installed as user apps on the Hugsun: FLAG_SYSTEM alone does not catch these.
        for (String pkg : Arrays.asList("com.google.android.gms", "com.google.android.gsf", "com.android.vending",
                "com.google.android.katniss", "com.google.android.tv.remote.service", "com.android.webview",
                "com.google.android.webview", "home.cleanframe.tv", "home.cleanframe.tv.staging")) {
            eq(user(pkg), UninstallPolicy.PROTECTED, pkg);
        }
        eq(UninstallPolicy.refusal("home.cleanframe.tv", "home.cleanframe.tv.staging", true, false, false, NO_HOMES), UninstallPolicy.PROTECTED, "the staging build refuses the release build");
        eq(UninstallPolicy.refusal("home.cleanframe.tv.staging", "home.cleanframe.tv.staging", true, false, false, NO_HOMES), UninstallPolicy.PROTECTED, "and itself");
        eq(UninstallPolicy.refusal("com.other.tv", "com.other.tv", true, false, false, NO_HOMES), UninstallPolicy.PROTECTED, "self, whatever its name");

        Set<String> homes = new HashSet<>(Arrays.asList("com.some.launcher"));
        eq(UninstallPolicy.refusal("com.some.launcher", SELF, true, false, false, homes), UninstallPolicy.PROTECTED, "provides HOME");
        eq(UninstallPolicy.refusal("com.other.app", SELF, true, false, false, homes), "", "another app, with a launcher about");

        for (String bad : new String[]{null, "", "nodots", ".a.b", "a..b", "com.a.b;x", "com.a.b/c", "com a.b", "1com.a", "com.1a", "package:com.a.b", "com.a.b\n"}) {
            eq(UninstallPolicy.refusal(bad, SELF, true, false, false, NO_HOMES), UninstallPolicy.NOT_INSTALLED, "not a package name: " + bad);
        }
        System.out.println("UninstallPolicyTest ok");
    }
}
