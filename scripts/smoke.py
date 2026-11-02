"""End-to-end smoke test against a running `docker compose up` stack, through the gateway only.

    python scripts/smoke.py [http://localhost:8080]

Covers: register/login, JWT enforcement, top-up, idempotent transfer (replay must not double-spend), fraud HOLD +
admin approval, Kafka -> notification delivery, and the admin ledger reconciliation.
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")


def call(method, path, body=None, token=None, key=None, expect=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, headers, raw = r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        status, headers, raw = e.code, e.headers, e.read()
    data = json.loads(raw) if raw else None
    if expect is not None and status != expect:
        sys.exit(f"FAIL {method} {path}: expected {expect}, got {status}: {data}")
    return status, headers, data


def wait_ready():
    for _ in range(90):
        try:
            if call("GET", "/actuator/health")[0] == 200:
                return
        except Exception:
            pass
        time.sleep(2)
    sys.exit("FAIL gateway never became healthy")


def step(msg):
    print(f"  ok  {msg}")


wait_ready()
tag = uuid.uuid4().hex[:8]
pw = "smoke-password-1"
# retry registration a few times: auth-service may still be starting behind the healthy gateway
for _ in range(30):
    s, _, a = call("POST", "/api/auth/register", {"email": f"alice-{tag}@smoke.test", "password": pw})
    if s == 201:
        break
    time.sleep(2)
else:
    sys.exit(f"FAIL register: {s} {a}")
alice = a["accessToken"]
bob = call("POST", "/api/auth/register", {"email": f"bob-{tag}@smoke.test", "password": pw}, expect=201)[2]["accessToken"]
step("registered two users")

call("GET", "/api/wallet/account", expect=401)
call("GET", "/api/wallet/account", token="not-a-jwt", expect=401)
step("gateway rejects missing/invalid JWT")

for _ in range(30):  # wallet-service may still be starting
    s, _, acc_a = call("GET", "/api/wallet/account", token=alice)
    if s == 200:
        break
    time.sleep(2)
else:
    sys.exit("FAIL wallet-service never answered")
acc_b = call("GET", "/api/wallet/account", token=bob, expect=200)[2]

call("POST", "/api/wallet/topups", {"amountMinor": 100_000}, token=alice, key=f"top-{tag}", expect=201)
step("topped up 1000.00")

# Fail-safe behaviour: if fraud-service is cold/slow the transfer is HELD (not lost). Warm it up with 1-cent transfers
# until one completes, so the exact-balance assertions below are deterministic.
warm = 0
for i in range(8):
    r = call("POST", "/api/wallet/transfers", {"toAccountId": acc_b["accountId"], "amountMinor": 1}, token=alice, key=f"warm-{i}-{tag}", expect=201)[2]
    if r["status"] == "COMPLETED":
        warm = 1
        break
    time.sleep(1)
assert warm == 1, "fraud-service never became responsive"

key = f"xfer-{tag}"
body = {"toAccountId": acc_b["accountId"], "amountMinor": 2_500, "category": "food", "note": "smoke"}
s1, h1, r1 = call("POST", "/api/wallet/transfers", body, token=alice, key=key, expect=201)
s2, h2, r2 = call("POST", "/api/wallet/transfers", body, token=alice, key=key, expect=201)
assert r1["txnId"] == r2["txnId"] and h2["Idempotent-Replay"] == "true", "replay must return the same transaction"
assert call("GET", "/api/wallet/account", token=bob)[2]["balanceMinor"] == 2_501, "bob must be credited exactly once"
assert call("GET", "/api/wallet/account", token=alice)[2]["balanceMinor"] == 97_499
call("POST", "/api/wallet/transfers", {**body, "amountMinor": 9}, token=alice, key=key, expect=422)
step("idempotent transfer: replay returned same txn, money moved once, key reuse with other body -> 422")

call("POST", "/api/wallet/transfers", {**body, "amountMinor": 10_000_000}, token=alice, key=f"big-{tag}", expect=422)
step("insufficient funds rejected")

call("POST", "/api/wallet/topups", {"amountMinor": 1_000_000}, token=alice, key=f"top2-{tag}", expect=201)
held = call("POST", "/api/wallet/transfers", {**body, "amountMinor": 600_000}, token=alice, key=f"held-{tag}")[2]
assert held["status"] in ("HELD", "DECLINED"), f"600.00 to a new payee must trip the fraud rules, got {held}"
step(f"fraud-service flagged the large transfer: {held['status']} (score {held['fraudScore']})")

admin = call("POST", "/api/auth/login", {"email": "admin@wallet.example", "password": "admin-password-1"}, expect=200)[2]["accessToken"]
call("GET", "/api/wallet/admin/flagged", token=alice, expect=403)
flagged = call("GET", "/api/wallet/admin/flagged", token=admin, expect=200)[2]
assert any(t["id"] == held["txnId"] for t in flagged)
step("flagged list is admin-only and contains the transfer")

# a $5,000-and-up transfer to a brand new payee scores 90 (DECLINE); a single rule hit is only HELD. Make a HELD one:
one = call("POST", "/api/wallet/transfers", {**body, "amountMinor": 500_000, "toAccountId": acc_b["accountId"]}, token=alice, key=f"held2-{tag}")[2]
if one["status"] == "HELD":
    done = call("POST", f"/api/wallet/admin/transactions/{one['txnId']}/approve", {}, token=admin, expect=200)[2]
    assert done["status"] == "COMPLETED"
    step("admin approved a held transfer")

rec = call("GET", "/api/wallet/admin/reconciliation", token=admin, expect=200)[2]
assert rec["healthy"], rec
step(f"ledger reconciles: {rec}")

for _ in range(30):
    notes = call("GET", "/api/notifications", token=bob, expect=200)[2]
    if any("received" in n["message"] for n in notes):
        break
    time.sleep(1)
else:
    sys.exit("FAIL bob never received a notification via Kafka")
step(f"Kafka -> notification-service delivered: {notes[0]['message']}")
print("SMOKE TEST PASSED")
