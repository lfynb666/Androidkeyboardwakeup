package com.codex.bthid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HidService extends Service {
    private static final String TAG = "StreetJackHid";
    public static final String ACTION_STATUS = "com.codex.bthid.STATUS";
    public static final String ACTION_CONNECT = "com.codex.bthid.CONNECT";
    public static final String ACTION_SPACE = "com.codex.bthid.SPACE";
    public static final String EXTRA_ADDRESS = "address";
    public static final int LOCAL_PORT = 27841;

    private static final String PREFS = "hid";
    private static final String PREF_TARGET = "target_address";
    private static final String CHANNEL_ID = "bluetooth_hid";
    private static final int NOTIFICATION_ID = 27841;

    // Standard 8-byte boot keyboard report descriptor, with no LED output report.
    private static final byte[] KEYBOARD_DESCRIPTOR = new byte[] {
            0x05, 0x01,
            0x09, 0x06,
            (byte) 0xA1, 0x01,
            0x05, 0x07,
            0x19, (byte) 0xE0,
            0x29, (byte) 0xE7,
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            (byte) 0x95, 0x08,
            (byte) 0x81, 0x02,
            (byte) 0x95, 0x01,
            0x75, 0x08,
            (byte) 0x81, 0x01,
            (byte) 0x95, 0x06,
            0x75, 0x08,
            0x15, 0x00,
            0x25, 0x65,
            0x05, 0x07,
            0x19, 0x00,
            0x29, 0x65,
            (byte) 0x81, 0x00,
            (byte) 0xC0
    };

    private static final byte[] SPACE_DOWN = new byte[] {
            0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] ALL_KEYS_UP = new byte[8];

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice targetDevice;
    private Executor callbackExecutor;
    private ExecutorService serverExecutor;
    private ServerSocket serverSocket;
    private volatile boolean stopping;
    private boolean appRegistered;

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) {
                        return;
                    }
                    hidDevice = (BluetoothHidDevice) proxy;
                    Log.i(TAG, "HID device profile connected");
                    registerHidApplication();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null;
                        appRegistered = false;
                        publishStatus("HID service disconnected");
                    }
                }
            };

    private final BluetoothHidDevice.Callback hidCallback =
            new BluetoothHidDevice.Callback() {
                @Override
                public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                    appRegistered = registered;
                    Log.i(TAG, "HID application status: " + registered
                            + (pluggedDevice == null ? "" : " device=" + pluggedDevice.getAddress()));
                    if (pluggedDevice != null) {
                        setTargetDevice(pluggedDevice);
                    }
                    publishStatus(registered ? "HID application registered" : "HID application stopped");
                    if (registered && targetDevice != null
                            && hidDevice != null
                            && hidDevice.getConnectionState(targetDevice)
                            != BluetoothProfile.STATE_CONNECTED) {
                        hidDevice.connect(targetDevice);
                    }
                }

                @Override
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    Log.i(TAG, "HID connection state=" + stateName(state)
                            + " device=" + (device == null ? "null" : device.getAddress()));
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        setTargetDevice(device);
                    }
                    publishStatus("HID connection: " + stateName(state));
                }

                @Override
                public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
                    if (hidDevice == null) {
                        return;
                    }
                    byte[] report = new byte[Math.min(Math.max(bufferSize, 0), 8)];
                    hidDevice.replyReport(device, type, id, report);
                }

                @Override
                public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
                    if (hidDevice != null) {
                        hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                    }
                }

                @Override
                public void onVirtualCableUnplug(BluetoothDevice device) {
                    if (targetDevice != null && targetDevice.equals(device)) {
                        targetDevice = null;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .remove(PREF_TARGET).apply();
                    }
                    publishStatus("HID virtual cable unplugged");
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting Bluetooth HID"));
        callbackExecutor = Executors.newSingleThreadExecutor();
        serverExecutor = Executors.newSingleThreadExecutor();
        stopping = false;

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            publishStatus("Bluetooth adapter unavailable");
            return;
        }
        loadTargetDevice();
        startLocalCommandServer();
        adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE);
        publishStatus("Waiting for HID service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                connectToAddress(intent.getStringExtra(EXTRA_ADDRESS));
            } else if (ACTION_SPACE.equals(action)) {
                sendSpace();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (hidDevice != null && appRegistered) {
            hidDevice.unregisterApp();
        }
        if (adapter != null && hidDevice != null) {
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        }
        if (callbackExecutor instanceof ExecutorService) {
            ((ExecutorService) callbackExecutor).shutdownNow();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerHidApplication() {
        if (hidDevice == null || appRegistered) {
            return;
        }
        BluetoothHidDeviceAppSdpSettings sdp =
                new BluetoothHidDeviceAppSdpSettings(
                        "Street-Jack Wake",
                        "Bluetooth keyboard for S0 wake testing",
                        "Codex",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        KEYBOARD_DESCRIPTOR);
        boolean accepted = hidDevice.registerApp(
                sdp, null, null, callbackExecutor, hidCallback);
        publishStatus(accepted ? "Register request sent" : "Register request rejected");
    }

    private void loadTargetDevice() {
        String address = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_TARGET, null);
        if (adapter == null) {
            return;
        }
        if (address != null) {
            try {
                targetDevice = adapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid stored target address", e);
            }
        }
        if (targetDevice == null) {
            // On first run, prefer the paired computer named by the PoC rather than
            // accidentally attempting to connect to a headset or speaker.
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name != null && "STREET-JACK".equalsIgnoreCase(name.trim())) {
                    setTargetDevice(device);
                    Log.i(TAG, "Auto-selected paired HID host: " + device.getAddress());
                    break;
                }
            }
        }
    }

    private void connectToAddress(String address) {
        if (address == null || adapter == null || hidDevice == null || !appRegistered) {
            publishStatus("Cannot connect: HID app is not ready");
            return;
        }
        try {
            setTargetDevice(adapter.getRemoteDevice(address));
            boolean accepted = hidDevice.connect(targetDevice);
            Log.i(TAG, "HID connect request for " + address + ": " + accepted);
            publishStatus(accepted ? "Connect request sent" : "Connect request rejected");
        } catch (IllegalArgumentException e) {
            publishStatus("Invalid Bluetooth address");
        }
    }

    private void setTargetDevice(BluetoothDevice device) {
        targetDevice = device;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_TARGET, device.getAddress()).apply();
    }

    private boolean sendSpace() {
        if (hidDevice == null || targetDevice == null || !appRegistered) {
            publishStatus("Space failed: HID not ready");
            return false;
        }
        if (hidDevice.getConnectionState(targetDevice)
                != BluetoothProfile.STATE_CONNECTED) {
            publishStatus("Space failed: host not connected");
            return false;
        }
        boolean down = hidDevice.sendReport(targetDevice, 0, SPACE_DOWN);
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean up = hidDevice.sendReport(targetDevice, 0, ALL_KEYS_UP);
        publishStatus(down && up ? "Space sent" : "Space send failed");
        return down && up;
    }

    private void startLocalCommandServer() {
        serverExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(
                            LOCAL_PORT, 1, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "Local command server listening on 127.0.0.1:" + LOCAL_PORT);
                    while (!stopping) {
                        try (Socket socket = serverSocket.accept();
                             BufferedReader reader = new BufferedReader(
                                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                             BufferedWriter writer = new BufferedWriter(
                                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                            String command = reader.readLine();
                            boolean ok = "SPACE".equals(command) && sendSpace();
                            writer.write(ok ? "OK\n" : "ERR\n");
                            writer.flush();
                        }
                    }
                } catch (IOException e) {
                    if (!stopping) {
                        Log.e(TAG, "Local command server stopped", e);
                        publishStatus("Local command server failed");
                    }
                }
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void publishStatus(String status) {
        Log.i(TAG, status);
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra("status", status);
        sendBroadcast(intent);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "DISCONNECTING";
            default:
                return "DISCONNECTED";
        }
    }
}
