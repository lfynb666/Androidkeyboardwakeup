# Bluetooth HID keyboard PoC

This project turns an Android 9 phone into a classic Bluetooth HID keyboard.
It intentionally does not use USB Gadget, /dev/hidg0, UDC, or any USB
reconfiguration.

The app registers a standard 8-byte boot keyboard descriptor through
BluetoothHidDevice, keeps the registration in a foreground service, and
listens on 127.0.0.1:27841. The Termux script sends SPACE to that local
socket. The ECS/SSH path therefore remains:

~~~text
ECS -> SSH/Tailscale -> Termux -> localhost socket -> Bluetooth HID -> Windows
~~~

## Build

Push this directory to a GitHub repository. The workflow in
.github/workflows/android.yml builds app-debug.apk and publishes it as an
Actions artifact. The checked-in Gradle Wrapper means the runner does not need
the Gradle installation from the development computer. The workflow does not
access the phone or the Windows host.

The first CI run can be started with a normal push or with the `workflow_dispatch`
button in the Actions page. Download `bt-hid-poc-debug-apk` from the completed
run and install that APK on the disconnected phone. This is intentionally a
build-and-deliver pipeline: installing the APK and testing the Bluetooth link
remain explicit local steps because the phone is not connected to the runner.

For a new repository, the source can be pushed with:

~~~text
git init
git add .
git commit -m "Add Bluetooth HID wake PoC"
git branch -M main
git remote add origin https://github.com/<account>/<repository>.git
git push -u origin main
~~~

The project targets Android API 28, matching the Android 9 phone. The CI
compiler uses a newer compile SDK only for build tooling.

## First-time setup

1. Keep the phone electrically disconnected from the computer.
2. Install the APK from the GitHub Actions artifact.
3. Launch the app once. Keep its foreground notification enabled and exempt
   the app from the phone's battery/background restrictions.
4. With the app open, pair the phone from Windows Bluetooth settings. The app
   must be registered before Windows can discover it as a HID device.
5. In the app, select the bonded Windows host.
6. While Windows is awake, press the "发送 Space（亮屏测试）" button.
7. Only after the bright-state test succeeds, run
   termux/windows-wakeup-bt.sh over SSH.

Install the Termux helper as:

~~~text
/data/data/com.termux/files/home/windows-wakeup-bt.sh
~~~

Make it executable with chmod 700. It returns a non-zero status if the
Android HID service is not registered, the host is not connected, or the
report send fails.

## Acceptance sequence

1. Confirm Windows sees the phone as a Bluetooth HID keyboard.
2. Confirm the app reports HID connection: CONNECTED.
3. Run the Termux helper while Windows is awake and verify the Space key.
4. Enter S0 and invoke the helper from ECS.
5. Verify the display wakes and the new Event ID 507 contains
   Reason: Input Keyboard.

WH_KEYBOARD_LL is a bright-state observation tool only. It cannot be the
mechanism that wakes a sleeping system.

## Security and scope

The local command socket binds only to loopback. Any local process on the
phone that can access loopback can request the test key, so this is a
controlled PoC rather than a hardened remote-control product. The app does
not attempt automatic Bluetooth pairing and does not touch USB state.
