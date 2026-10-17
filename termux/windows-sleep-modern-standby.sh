#!/data/data/com.termux/files/usr/bin/bash
set -eu

/data/data/com.termux/files/usr/bin/python3 - <<'PY'
import socket

address = ("127.0.0.1", 27841)
with socket.create_connection(address, timeout=3.0) as sock:
    sock.sendall(b"SLEEP\n")
    response = sock.recv(32).decode("ascii", "replace").strip()

if response != "OK":
    raise SystemExit("[BT_HID] remote HID service rejected Modern Standby trigger: " + response)

print("[BT_HID] Modern Standby trigger sent")
PY
