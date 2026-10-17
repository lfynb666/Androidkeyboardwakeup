package com.codex.wakeup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!SecureStore.hasToken(this)) {
            startActivity(new android.content.Intent(this, SetupActivity.class));
            finish();
            return;
        }
        IconState.State current = IconState.read(this);
        if (current == IconState.State.GREY) {
            refreshStatus();
        } else {
            performAction(current);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refreshStatus() {
        runApi(new ApiWork() {
            @Override
            public void run(String token) throws Exception {
                ApiClient.Status status = ApiClient.status(token);
                IconState.set(MainActivity.this,
                        status.online ? IconState.State.GREEN : IconState.State.RED);
            }
        }, true);
    }

    private void performAction(final IconState.State current) {
        IconState.set(this, IconState.State.GREY);
        final String action = current == IconState.State.GREEN ? "sleep" : "wake";
        runApi(new ApiWork() {
            @Override
            public void run(String token) throws Exception {
                ApiClient.action(token, action);
                Thread.sleep(1200L);
            }
        }, false);
    }

    private void runApi(final ApiWork work, final boolean reportErrors) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = SecureStore.readToken(MainActivity.this);
                    if (token == null || token.length() == 0) {
                        throw new IllegalStateException("设备尚未配对");
                    }
                    work.run(token);
                } catch (final Exception e) {
                    IconState.set(MainActivity.this, IconState.State.GREY);
                    if (reportErrors) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this,
                                        "获取 Windows 状态失败：" + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } finally {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                }
            }
        });
    }

    private interface ApiWork {
        void run(String token) throws Exception;
    }
}
