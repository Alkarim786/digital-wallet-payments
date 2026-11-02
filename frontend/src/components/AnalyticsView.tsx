import { useEffect, useState } from "react";
import { api, type Analytics } from "../api";
import { formatMinor } from "../money";

/** Dependency-free SVG bar chart: sent (red) and received (green) per day. */
export function DailyChart({ days }: { days: Analytics["byDay"] }) {
  const W = 640, H = 200, pad = 28;
  const max = Math.max(1, ...days.map((d) => Math.max(d.sentMinor, d.receivedMinor)));
  const slot = (W - pad * 2) / Math.max(days.length, 1);
  const bar = Math.max(2, slot / 2 - 2);
  const h = (v: number) => ((H - pad * 2) * v) / max;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Sent and received per day" className="chart">
      <line x1={pad} y1={H - pad} x2={W - pad} y2={H - pad} stroke="currentColor" opacity=".3" />
      {days.map((d, i) => {
        const x = pad + i * slot;
        return (
          <g key={d.date}>
            <title>{`${d.date}: sent ${formatMinor(d.sentMinor)}, received ${formatMinor(d.receivedMinor)}`}</title>
            <rect data-testid="bar-sent" x={x} y={H - pad - h(d.sentMinor)} width={bar} height={h(d.sentMinor)} fill="var(--neg)" />
            <rect data-testid="bar-received" x={x + bar + 1} y={H - pad - h(d.receivedMinor)} width={bar} height={h(d.receivedMinor)} fill="var(--pos)" />
            {(i === 0 || i === days.length - 1 || i % 7 === 0) && <text x={x} y={H - 8} fontSize="9" fill="currentColor">{d.date.slice(5)}</text>}
          </g>
        );
      })}
    </svg>
  );
}

export function CategoryBars({ items }: { items: Analytics["byCategory"] }) {
  const max = Math.max(1, ...items.map((i) => i.sentMinor));
  if (items.length === 0) return <p className="muted">No spending in this period.</p>;
  return (
    <ul className="cats">
      {items.map((c) => (
        <li key={c.category}>
          <span>{c.category}</span>
          <span className="track"><span className="fill" style={{ width: `${(c.sentMinor / max) * 100}%` }} /></span>
          <span className="num">{formatMinor(c.sentMinor)}</span>
        </li>
      ))}
    </ul>
  );
}

export function AnalyticsView({ version }: { version: number }) {
  const [days, setDays] = useState(30);
  const [data, setData] = useState<Analytics | null>(null);
  useEffect(() => { api<Analytics>(`/wallet/analytics?days=${days}`).then(setData); }, [days, version]);
  return (
    <section className="card">
      <div className="row">
        <h2>Spending &amp; income</h2>
        <select aria-label="period" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option><option value={30}>Last 30 days</option><option value={90}>Last 90 days</option>
        </select>
      </div>
      {data && (<>
        <p>Sent <strong className="neg">{formatMinor(data.totalSentMinor)}</strong> · Received <strong className="pos">{formatMinor(data.totalReceivedMinor)}</strong></p>
        <DailyChart days={data.byDay} />
        <h3>Spend by category</h3>
        <CategoryBars items={data.byCategory} />
      </>)}
    </section>
  );
}
