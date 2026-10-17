#!/data/data/com.termux/files/usr/bin/bash
set -eu

/data/data/com.termux/files/usr/bin/python3 - <<'PY'
import socket
import sys

address = ("127.0.0.1", 27841)
with socket.create_connection(address, timeout=3.0) as sock:
    sock.sendall(b"SPACE\n")
    response = sock.recv(32).decode("ascii", "replace").strip()

if response != "OK":
    print("[BT_HID] remote HID service rejected Space: " + response, file=sys.stderr)
    sys.exit(1)

print("[BT_HID] Space sent")
PY
