package com.codex.wakeup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Launcher router. The actual status monitor lives in MonitorService so the
 * launcher activity can remain invisible and finish immediately.
 */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        route();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        route();
    }

    private void route() {
        if (!SecureStore.hasToken(this)) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            Intent intent = new Intent(this, MonitorService.class)
                    .setAction(MonitorService.ACTION_TAP);
            MonitorService.start(this, intent);
        }
        finish();
    }
}
