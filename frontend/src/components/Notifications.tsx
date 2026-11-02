import { useEffect, useState } from "react";
import { api, post, type Notification } from "../api";

export function Notifications({ version }: { version: number }) {
  const [items, setItems] = useState<Notification[]>([]);
  const load = () => api<Notification[]>("/notifications?limit=50").then(setItems).catch(() => setItems([]));
  // Notifications arrive asynchronously through Kafka, so poll while the tab is open.
  useEffect(() => { load(); const t = setInterval(load, 3000); return () => clearInterval(t); }, [version]);
  return (
    <section className="card">
      <div className="row">
        <h2>Notifications</h2>
        <button className="ghost" onClick={() => post("/notifications/read-all", {}).then(load)}>Mark all read</button>
      </div>
      {items.length === 0 && <p className="muted">Nothing yet.</p>}
      <ul className="notes">
        {items.map((n) => (
          <li key={n.id} className={n.read ? "" : "unread"}>
            <span className={"badge " + n.kind.toLowerCase()}>{n.kind}</span> {n.message}
            <span className="muted small"> {new Date(n.createdAt).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
