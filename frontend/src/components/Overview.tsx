import { useEffect, useRef, useState } from "react";
import { ApiError, api, newIdempotencyKey, post, type Account, type TransferResult } from "../api";
import { formatMinor, parseToMinor } from "../money";

export function Overview({ version, onChange }: { version: number; onChange: () => void }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [category, setCategory] = useState("");
  const [note, setNote] = useState("");
  const [topup, setTopup] = useState("100.00");
  const [msg, setMsg] = useState<{ kind: "ok" | "warn" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  // One key per logical attempt: a double click or a retry after a network error re-sends the SAME key, so the
  // server returns the stored result instead of moving money twice. It is renewed once the attempt has an answer.
  const sendKey = useRef(newIdempotencyKey());
  const topupKey = useRef(newIdempotencyKey());

  useEffect(() => { api<Account>("/wallet/account").then(setAccount).catch((e) => setMsg({ kind: "error", text: e.message })); }, [version]);

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const minor = parseToMinor(amount);
    const dest = Number(to);
    if (minor === null) return setMsg({ kind: "error", text: "Enter an amount like 12.50" });
    if (!Number.isInteger(dest) || dest <= 0) return setMsg({ kind: "error", text: "Enter the recipient's account number" });
    setBusy(true); setMsg(null);
    try {
      const r = await post<TransferResult>("/wallet/transfers", { toAccountId: dest, amountMinor: minor, category: category || undefined, note: note || undefined }, sendKey.current);
      sendKey.current = newIdempotencyKey();
      setMsg(r.status === "HELD"
        ? { kind: "warn", text: `Transfer of ${formatMinor(minor)} is under review: ${r.reasons.join("; ") || "manual check"}. No money has moved yet.` }
        : { kind: "ok", text: `Sent ${formatMinor(minor)} to account ${dest}.` });
      setAmount(""); setNote("");
      onChange();
    } catch (err) {
      const e2 = err as ApiError;
      if (e2.status === 422 && (e2.body as any)?.status === "DECLINED") {
        sendKey.current = newIdempotencyKey();
        setMsg({ kind: "error", text: `Declined for security reasons: ${((e2.body as any).reasons ?? []).join("; ")}` });
      } else if (e2.status >= 400 && e2.status < 500) {
        sendKey.current = newIdempotencyKey(); // definitive answer: the next click is a new attempt
        setMsg({ kind: "error", text: e2.message });
      } else {
        setMsg({ kind: "error", text: "Network problem. Press Send again: the same request key is reused, so you cannot be charged twice." });
      }
    } finally { setBusy(false); }
  }

  async function addFunds() {
    const minor = parseToMinor(topup);
    if (minor === null) return setMsg({ kind: "error", text: "Enter a top-up amount like 100.00" });
    try {
      await post("/wallet/topups", { amountMinor: minor }, topupKey.current);
      topupKey.current = newIdempotencyKey();
      setMsg({ kind: "ok", text: `Added ${formatMinor(minor)} (simulated deposit).` });
      onChange();
    } catch (err: any) { setMsg({ kind: "error", text: err.message }); }
  }

  return (
    <>
      <section className="card balance">
        <div className="muted">Available balance</div>
        <div className="big" data-testid="balance">{account ? formatMinor(account.balanceMinor, account.currency) : "…"}</div>
        <div className="muted">Your account number: <strong data-testid="account-number">{account?.accountId ?? "…"}</strong> (share it to receive money)</div>
        <div className="row">
          <input aria-label="top-up amount" value={topup} onChange={(e) => setTopup(e.target.value)} style={{ maxWidth: 120 }} />
          <button className="ghost" onClick={addFunds}>Add funds (demo)</button>
        </div>
      </section>

      <form className="card" onSubmit={send}>
        <h2>Send money</h2>
        <div className="grid">
          <div><label htmlFor="to">Recipient account number</label><input id="to" inputMode="numeric" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          <div><label htmlFor="amount">Amount (USD)</label><input id="amount" inputMode="decimal" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} /></div>
          <div><label htmlFor="category">Category</label>
            <select id="category" value={category} onChange={(e) => setCategory(e.target.value)}>
              <option value="">(none)</option>{["food", "rent", "bills", "shopping", "travel", "other"].map((c) => <option key={c}>{c}</option>)}
            </select></div>
          <div><label htmlFor="note">Note</label><input id="note" maxLength={140} value={note} onChange={(e) => setNote(e.target.value)} /></div>
        </div>
        <button disabled={busy}>{busy ? "Sending…" : "Send"}</button>
        {msg && <p className={msg.kind} role="status">{msg.text}</p>}
      </form>
    </>
  );
}
