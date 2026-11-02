import { useCallback, useEffect, useState } from "react";
import { api, post, type Reconciliation, type Txn } from "../api";
import { formatMinor } from "../money";

export function Admin({ onChange }: { onChange: () => void }) {
  const [flagged, setFlagged] = useState<Txn[]>([]);
  const [rec, setRec] = useState<Reconciliation | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(() => {
    api<Txn[]>("/wallet/admin/flagged").then(setFlagged).catch((e) => setError(e.message));
    api<Reconciliation>("/wallet/admin/reconciliation").then(setRec).catch(() => {});
  }, []);
  useEffect(load, [load]);

  async function decide(id: string, action: "approve" | "reject") {
    setError("");
    try { await post(`/wallet/admin/transactions/${id}/${action}`, {}); onChange(); load(); }
    catch (e: any) { setError(e.message); }
  }

  return (
    <>
      <section className="card">
        <h2>Ledger health</h2>
        {rec && (
          <p className={rec.healthy ? "ok" : "error"}>
            {rec.healthy ? "Healthy" : "PROBLEM DETECTED"}: sum of all balances {rec.sumOfAllBalances}, accounts drifting from their entries {rec.accountsWithDrift}, unbalanced transactions {rec.unbalancedTransactions}.
          </p>
        )}
      </section>
      <section className="card">
        <h2>Flagged transactions</h2>
        {error && <p className="error" role="alert">{error}</p>}
        <table>
          <thead><tr><th>Date</th><th>To</th><th className="num">Amount</th><th>Status</th><th>Score</th><th>Why</th><th /></tr></thead>
          <tbody>
            {flagged.map((t) => (
              <tr key={t.id}>
                <td>{new Date(t.createdAt).toLocaleString()}</td>
                <td>Acct {t.counterpartyAccount}</td>
                <td className="num">{formatMinor(t.amountMinor)}</td>
                <td><span className={"badge " + t.status.toLowerCase()}>{t.status}</span></td>
                <td>{t.fraudScore}</td>
                <td className="small">{t.fraudReasons}</td>
                <td>{t.status === "HELD" && (<>
                  <button onClick={() => decide(t.id, "approve")}>Approve</button>{" "}
                  <button className="danger" onClick={() => decide(t.id, "reject")}>Reject</button>
                </>)}</td>
              </tr>
            ))}
            {flagged.length === 0 && <tr><td colSpan={7} className="muted">Nothing flagged.</td></tr>}
          </tbody>
        </table>
      </section>
    </>
  );
}
