# Bluetooth HID keyboard PoC

This project turns an Android 9 phone into a Bluetooth HID keyboard and system
sleep button.
It intentionally does not use USB Gadget, /dev/hidg0, UDC, or any USB
reconfiguration.

The app registers a keyboard report plus a Consumer Control/System Sleep report
through BluetoothHidDevice, keeps the registration in a foreground service, and
listens on 127.0.0.1:27841. The Termux scripts send fixed `SPACE` or `SLEEP`
commands to that local socket. The ECS/SSH path therefore remains:

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

## ECS command path

Install `ecs/windows` on the ECS as `/usr/local/bin/windows` and make it
executable (`chmod 755`). Install `termux/windows-wakeup-bt.sh` on the phone
at the path above and make it executable (`chmod 700`). The ECS root account
must be able to SSH non-interactively to `u0_a137@100.64.0.3` on port `8022`,
and the phone host key must already be in `/root/.ssh/known_hosts`.

The supported invocation is:

~~~sh
windows wakeup
~~~

`windows wakeup` sends exactly one Space press and release through the local
Bluetooth HID service. `windows sleep` sends the standard HID System Sleep
usage (Consumer Control usage 0x82); Windows' power manager applies the
configured Sleep Button Action. It does not use SetSuspendState, hibernation,
or a session-local AHK hotkey, so it remains independent of a locked console
session and a separate active RDP session. The old phone
script name can be retained as a safe alias to the Bluetooth helper by
installing `termux/windows-wakeup-legacy.sh` as `~/windows-wakeup.sh`.

Because the HID report descriptor now includes report IDs and a Consumer
Control collection, Windows must forget the old Bluetooth Keyboard pairing and
pair the updated app again after installing a build containing this change.

Before enabling the remote sleep action, verify on Windows:

~~~bat
powercfg /a
powercfg /q SCHEME_CURRENT SUB_BUTTONS
~~~

The available state must include `Standby (S0 Low Power Idle)`, and the active
scheme's Sleep Button Action must be `Sleep` (value `1`), not `Hibernate`
(value `2`).

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
