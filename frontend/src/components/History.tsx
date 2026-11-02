import { useEffect, useState } from "react";
import { api, type Page, type Txn } from "../api";
import { formatMinor } from "../money";

const SIZE = 10;

export function History({ version }: { version: number }) {
  const [filters, setFilters] = useState({ direction: "", status: "", category: "" });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Txn> | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const q = new URLSearchParams({ page: String(page), size: String(SIZE) });
    for (const [k, v] of Object.entries(filters)) if (v) q.set(k, v);
    api<Page<Txn>>(`/wallet/transactions?${q}`).then(setData).catch((e) => setError(e.message));
  }, [filters, page, version]);

  const set = (k: keyof typeof filters) => (e: React.ChangeEvent<HTMLSelectElement>) => { setPage(0); setFilters({ ...filters, [k]: e.target.value }); };
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1;

  return (
    <section className="card">
      <h2>Transaction history</h2>
      <div className="filters">
        <select aria-label="direction" value={filters.direction} onChange={set("direction")}>
          <option value="">All</option><option value="SENT">Sent</option><option value="RECEIVED">Received</option><option value="TOPUP">Top-ups</option>
        </select>
        <select aria-label="status" value={filters.status} onChange={set("status")}>
          <option value="">Any status</option><option>COMPLETED</option><option>HELD</option><option>DECLINED</option><option>REJECTED</option>
        </select>
        <select aria-label="category" value={filters.category} onChange={set("category")}>
          <option value="">Any category</option>{["food", "rent", "bills", "shopping", "travel", "other", "top-up"].map((c) => <option key={c}>{c}</option>)}
        </select>
      </div>
      {error && <p className="error">{error}</p>}
      <table>
        <thead><tr><th>Date</th><th>Type</th><th>Counterparty</th><th>Category</th><th className="num">Amount</th><th>Status</th></tr></thead>
        <tbody>
          {data?.items.map((t) => (
            <tr key={t.id} title={t.fraudReasons ?? undefined}>
              <td>{new Date(t.createdAt).toLocaleString()}</td>
              <td>{t.direction}</td>
              <td>{t.direction === "TOPUP" ? "Funding" : `Acct ${t.counterpartyAccount}`}</td>
              <td>{t.category ?? "-"}</td>
              <td className={"num " + (t.direction === "SENT" ? "neg" : "pos")}>{t.direction === "SENT" ? "-" : "+"}{formatMinor(t.amountMinor)}</td>
              <td><span className={"badge " + t.status.toLowerCase()}>{t.status}</span></td>
            </tr>
          ))}
          {data && data.items.length === 0 && <tr><td colSpan={6} className="muted">No transactions match.</td></tr>}
        </tbody>
      </table>
      <div className="pager">
        <button className="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {pages} ({data?.total ?? 0} total)</span>
        <button className="ghost" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </section>
  );
}
