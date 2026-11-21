#!/usr/bin/env python3
"""Fetch the local BLE pair_key for a DJI Power station from a DJI account.

Standalone, no Home Assistant, no rooted phone, no BLE sniffing. It reproduces
the DJI Home app's account-center request signing to log in, then reads the
station's local `pair_key` from the account's device list. The pair_key + the
station's BLE MAC are all a local-only client needs; after this you never touch
DJI's cloud again.

Logic ported from the zuyan9/ha-dji-power-ble integration's cloud.py.

USAGE
    # Full login (fetches a captcha image you open and read):
    python3 dji_get_pair_key.py --email you@example.com

    # If you already have a member token (e.g. from the emulator extractor):
    python3 dji_get_pair_key.py --token US_xxxxxxxx...

Requires:  pip install requests
The captcha is a plain image from DJI's own domain (no Google, no cert pinning),
so it saves to dji_captcha.png — open that, type what you see, case-sensitive.
Your password is read via a hidden prompt and never stored or printed.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import sys
import time
import uuid

import requests

ACCOUNT_BASE = "https://account.dji.com/apis/apprest/v1"
HOME_API_HOSTS = (
    "https://home-api.djigate.com",
    "https://home-api-vg.djigate.com",
    "https://home-api-hz.djigate.com",
)
DEVICES_PATH = "/app/api/v1/users/devices/list"

# Bootstrap HMAC key for Sign-Mc (recovered from the DJI Home app). DJI may
# rotate it; if signing starts failing, that is the first thing to re-check.
SIGN_MC_KEY = "43421d0a-c0bf-4467-9542-3159cc6000cb"
USER_AGENT = "DJIHome/1.5.16 (Android)"

CODE_OK = 0
TWO_FACTOR_CODES = frozenset({553, 556})     # email / 2-step code required
RATE_LIMIT_CODES = frozenset({508, 554})     # verification-code throttled

CLIENT_NAME = "android-1.5.16"
DEVICE_ID = f"dji-home-{uuid.uuid4().hex[:16]}"


def signed_headers() -> dict[str, str]:
    """Build the DJI *-Mc common headers, signed with base64(HMAC-SHA1)."""
    ts = str(int(time.time()))
    invoke_id = f"DeviceId-Mc{ts}{uuid.uuid4().hex[:6]}"
    material = (
        "AppId-Mc" "cr-app"
        "ClientName-Mc" + CLIENT_NAME +
        "DeviceId-Mc" + DEVICE_ID +
        "InvokeId-Mc" + invoke_id +
        "Timestamp-Mc" + ts
    )
    sign = base64.b64encode(
        hmac.new(SIGN_MC_KEY.encode(), material.encode(), hashlib.sha1).digest()
    ).decode()
    return {
        "ClientName-Mc": CLIENT_NAME,
        "DeviceId-Mc": DEVICE_ID,
        "AppId-Mc": "cr-app",
        "Timestamp-Mc": ts,
        "InvokeId-Mc": invoke_id,
        "Sign-Mc": sign,
        "X-Risk-Version": "1.0",
        "X-DJI-SDK-Version": "1.0.0",
        "User-Agent": USER_AGENT,
        "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
    }


def die(msg: str) -> None:
    print(f"\nERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def get_captcha(session: requests.Session) -> str:
    """Fetch DJI's image captcha, save it to disk, return its srandom token."""
    srandom = uuid.uuid4().hex
    headers = signed_headers()
    headers.pop("Content-Type", None)
    r = session.get(f"{ACCOUNT_BASE}/vcode?srandom={srandom}",
                    headers=headers, timeout=20)
    r.raise_for_status()
    with open("dji_captcha.png", "wb") as fh:
        fh.write(r.content)
    return srandom


def validate_captcha(session: requests.Session, srandom: str, code: str) -> str:
    body = {
        "captchaType": "imageCaptcha",
        "captchaModule": "AppLogin",
        "verificationCode": code,
        "srandom": srandom,
    }
    r = session.post(f"{ACCOUNT_BASE}/validate_captcha",
                     headers=signed_headers(), data=body, timeout=20)
    r.raise_for_status()
    resp = r.json()
    if resp.get("code") != CODE_OK:
        die(f"captcha rejected (code {resp.get('code')}): {resp.get('message')}")
    ticket = (resp.get("data") or {}).get("captchaTicket")
    if not ticket:
        die("no captchaTicket in captcha response")
    return ticket


def login(session: requests.Session, email: str, password: str,
          ticket: str, email_code: str | None = None) -> str:
    body = {"userName": email, "password": password, "captchaTicket": ticket}
    if email_code:
        body["emailCode"] = email_code
        body["verificationCode"] = email_code
    r = session.post(f"{ACCOUNT_BASE}/user_login",
                     headers=signed_headers(), data=body, timeout=20)
    r.raise_for_status()
    resp = r.json()
    code = resp.get("code")
    if code in TWO_FACTOR_CODES:
        print("\nThis account needs an email / 2-step verification code.")
        ec = input("Enter the code DJI just emailed you: ").strip()
        return login(session, email, password, ticket, email_code=ec)
    if code in RATE_LIMIT_CODES:
        die("DJI is rate-limiting verification codes; wait a while and retry.")
    if code != CODE_OK:
        die(f"login failed (code {code}): {resp.get('message')}")
    token = (resp.get("data") or {}).get("token")
    if not token:
        die("login succeeded but no member token was returned")
    return token


def list_devices(session: requests.Session, token: str) -> list[dict]:
    """GET the account's device list; return entries that carry a pair_key."""
    last_err = None
    for host in HOME_API_HOSTS:
        try:
            r = session.get(f"{host}{DEVICES_PATH}",
                            headers={"x-member-token": token,
                                     "User-Agent": USER_AGENT,
                                     "Accept": "application/json"},
                            timeout=20)
            r.raise_for_status()
            payload = r.json()
        except (requests.RequestException, ValueError) as err:
            last_err = err
            continue
        out = []
        data = payload.get("data") or {}
        for key in ("dy_devices", "cr_devices"):
            for dev in data.get(key) or []:
                base = dev.get("base_info", {})
                pair = dev.get("pair_info", {})
                if pair.get("pair_key"):
                    out.append({
                        "name": base.get("name") or "DJI Power",
                        "sn": base.get("sn") or "",
                        "pair_uuid": pair.get("pair_uuid") or "",
                        "pair_key": pair["pair_key"],
                    })
        if out:
            return out
    if last_err:
        die(f"device list request failed: {last_err}")
    return []


def main() -> None:
    ap = argparse.ArgumentParser(description="Fetch DJI Power local pair_key")
    ap.add_argument("--email", help="DJI account email (full-login mode)")
    ap.add_argument("--token", help="existing US_ member token (skip login)")
    args = ap.parse_args()

    session = requests.Session()

    if args.token:
        token = args.token
    elif args.email:
        print("Fetching captcha -> dji_captcha.png")
        srandom = get_captcha(session)
        print("Open dji_captcha.png and read the characters (case-sensitive).")
        code = input("Captcha text: ").strip()
        ticket = validate_captcha(session, srandom, code)
        password = getpass.getpass("DJI password (hidden): ")
        token = login(session, args.email, password, ticket)
        print("Login OK; member token obtained.")
    else:
        die("pass --email for full login, or --token US_... if you have one")

    devices = list_devices(session, token)
    if not devices:
        die("no devices with a pair_key on this account "
            "(is the station bound to this DJI account?)")

    print("\n=== DJI Power stations on this account ===")
    for d in devices:
        print(f"  name      : {d['name']}")
        print(f"  serial    : {d['sn']}")
        print(f"  pair_uuid : {d['pair_uuid']}")
        print(f"  PAIR KEY  : {d['pair_key']}")
        print()
    print("Use the 32-hex PAIR KEY above with your station's BLE MAC in your "
          "local client. DJI's cloud is no longer needed.")


if __name__ == "__main__":
    main()
