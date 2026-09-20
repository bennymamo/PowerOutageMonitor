"""Read-only ADB evidence capture. No installs, app data reads, or settings changes.
Use: python probe-phone-connectivity.py SERIAL --samples 20 --output PATH
HTTP 204 probes test the phone path; they cannot prove the inverter's internet path.
"""
import argparse
import concurrent.futures
import datetime
import json
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("--samples", type=int, default=20)
parser.add_argument("--interval", type=float, default=15)
parser.add_argument("--output", required=True)
args = parser.parse_args()

def adb(command, data=None):
    try:
        p = subprocess.run(["adb", "-s", args.serial, "shell", *command], input=data,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
        return p.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired as error:
        return (error.stdout or b"").decode("utf-8", "replace")
    except OSError:
        return ""

def http_probe():
    response = adb(["toybox", "nc", "-w", "4", "-W", "4", "-q", "2", "connectivitycheck.gstatic.com", "80"],
                   b"GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n")
    match = re.search(r"HTTP/\d[.\d]* (\d{3})", response)
    return int(match[1]) if match else None

with open(args.output, "a", encoding="utf-8") as output:
    for index in range(args.samples):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            battery_future = pool.submit(adb, ["dumpsys", "battery"])
            network_future = pool.submit(adb, ["dumpsys", "connectivity"])
            http_future = pool.submit(http_probe)
            battery = battery_future.result()
            network = network_future.result()
            default = re.search(r"Active default network: (\d+)", network)
            line = next((line for line in network.splitlines()
                         if default and "NetworkAgentInfo{network{" + default[1] + "}" in line), "")
            row = {"host_time": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
                   "phone_reachable": bool(battery),
                   "ac_powered": "AC powered: true" in battery if battery else None,
                   "usb_powered": "USB powered: true" in battery if battery else None,
                   "default_transport": "wifi" if "WIFI" in line else "cellular" if "MOBILE" in line else "unknown",
                   "android_validated": "IS_VALIDATED" in line if line else None,
                   "phone_http_status": http_future.result()}
        output.write(json.dumps(row) + "\n")
        output.flush()
        print(json.dumps(row), flush=True)
        if index + 1 < args.samples:
            time.sleep(max(1, args.interval))
