package home.cleanframe.tv;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which apps the long-press menu's Uninstall may offer (2026-11-04), in plain Java so
 * tests/fallback.test.sh runs it. MainActivity reads what Android says about the package now and
 * does what the answer says: nothing here touches a package, and the app never removes one itself.
 * Uninstall only opens Android's own confirm dialog (ACTION_DELETE).
 *
 * Refused: a package name that is not one, anything not installed (or not visible to this app, which
 * sees launchable apps only), a system app or an update of one, this app, its other build, anything that
 * provides HOME (this is the stick's only launcher) and REFUSED, which FLAG_SYSTEM alone does not cover:
 * on the Hugsun, Play and Google Play services are installed as user apps.
 */
final class UninstallPolicy {
    private UninstallPolicy() {
    }

    static final String NOT_INSTALLED = "This app is not installed.";
    static final String PROTECTED = "The TV needs this app, so it cannot be uninstalled.";
    static final String FAILED = "The TV could not open its uninstall page.";

    /** This app's two builds, whichever one is asking, and what the TV cannot work without. */
    static final Set<String> REFUSED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home.cleanframe.tv", "home.cleanframe.tv.staging",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.katniss", "com.google.android.tv.remote.service",
            "com.android.webview", "com.google.android.webview")));

    static boolean validName(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    /**
     * "" if pkg may be uninstalled, else the reason for the page. self: this app's package. installed,
     * system, updatedSystem: read from Android now (FLAG_SYSTEM, FLAG_UPDATED_SYSTEM_APP). homes: every
     * package providing HOME.
     */
    static String refusal(String pkg, String self, boolean installed, boolean system, boolean updatedSystem, Set<String> homes) {
        if (!validName(pkg) || !installed) return NOT_INSTALLED;
        if (system || updatedSystem || pkg.equals(self) || REFUSED.contains(pkg) || homes.contains(pkg)) return PROTECTED;
        return "";
    }
}
