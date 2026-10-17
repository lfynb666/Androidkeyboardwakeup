package com.codex.bthid;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;

public final class MainActivity extends Activity {
    private TextView status;
    private LinearLayout deviceList;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HidService.ACTION_STATUS.equals(intent.getAction())) {
                status.setText(intent.getStringExtra("status"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        registerReceiver(statusReceiver, new IntentFilter(HidService.ACTION_STATUS));
        startService(new Intent(this, HidService.class));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Bluetooth HID keyboard PoC");
        title.setTextSize(20);
        root.addView(title, params());

        status = new TextView(this);
        status.setText("Starting...");
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status, params());

        Button refresh = new Button(this);
        refresh.setText("刷新已配对主机");
        refresh.setOnClickListener(v -> refreshDevices());
        root.addView(refresh, params());

        Button space = new Button(this);
        space.setText("发送 Space（亮屏测试）");
        space.setOnClickListener(v ->
                startService(new Intent(this, HidService.class).setAction(HidService.ACTION_SPACE)));
        root.addView(space, params());

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(deviceList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        refreshDevices();
    }

    private void refreshDevices() {
        deviceList.removeAllViews();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("没有 Bluetooth adapter");
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有已配对设备。先保持应用运行，再在 Windows 中配对。");
            deviceList.addView(empty, params());
            return;
        }
        for (BluetoothDevice device : bonded) {
            Button connect = new Button(this);
            String name = device.getName();
            if (name == null || name.length() == 0) {
                name = "Unknown";
            }
            connect.setText("连接: " + name + "\n" + device.getAddress());
            connect.setOnClickListener(v -> {
                Intent intent = new Intent(this, HidService.class)
                        .setAction(HidService.ACTION_CONNECT)
                        .putExtra(HidService.EXTRA_ADDRESS, device.getAddress());
                startService(intent);
            });
            deviceList.addView(connect, params());
        }
    }

    private LinearLayout.LayoutParams params() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
