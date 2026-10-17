package com.codex.wakeup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SetupActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText password;
    private Button pair;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Windows Wake 配对");
        title.setTextSize(22);
        layout.addView(title, params());

        TextView description = new TextView(this);
        description.setText("首次输入控制端密码。之后令牌保存在本机 Android Keystore，不会重复要求密码。");
        layout.addView(description, params());

        password = new EditText(this);
        password.setHint("配对密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(password, params());

        pair = new Button(this);
        pair.setText("配对");
        pair.setOnClickListener(v -> pair());
        layout.addView(pair, params());
        setContentView(layout);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void pair() {
        final String value = password.getText().toString();
        if (value.length() == 0) {
            password.setError("请输入配对密码");
            return;
        }
        pair.setEnabled(false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = ApiClient.pair(value);
                    SecureStore.saveToken(SetupActivity.this, token);
                    IconState.set(SetupActivity.this, IconState.State.GREY);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SetupActivity.this, "配对成功，开始监测", Toast.LENGTH_SHORT).show();
                            MonitorService.start(SetupActivity.this,
                                    new Intent(SetupActivity.this, MonitorService.class)
                                            .setAction(MonitorService.ACTION_START));
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pair.setEnabled(true);
                            Toast.makeText(SetupActivity.this,
                                    "配对失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private ViewGroup.LayoutParams params() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
