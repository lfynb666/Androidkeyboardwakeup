package com.codex.wakeup;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Launcher icon state. The monitor is the only writer; API status is the source of truth. */
final class IconState {
    enum State { GREY, GREEN, RED }

    private static final String GREY_ALIAS = "com.codex.wakeup.LauncherGrey";
    private static final String GREEN_ALIAS = "com.codex.wakeup.LauncherGreen";
    private static final String RED_ALIAS = "com.codex.wakeup.LauncherRed";

    private IconState() {
    }

    static void set(Context context, State state) {
        PackageManager manager = context.getPackageManager();
        String enabledAlias = aliasFor(state);
        String[] aliases = {GREY_ALIAS, GREEN_ALIAS, RED_ALIAS};
        for (String alias : aliases) {
            manager.setComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), alias),
                    alias.equals(enabledAlias)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    private static String aliasFor(State state) {
        switch (state) {
            case GREEN:
                return GREEN_ALIAS;
            case RED:
                return RED_ALIAS;
            default:
                return GREY_ALIAS;
        }
    }
}
