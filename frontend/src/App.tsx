import { useEffect, useState } from "react";
import { loadSession, saveSession, setUnauthorizedHandler, type Session } from "./api";
import { Login } from "./components/Login";
import { Overview } from "./components/Overview";
import { History } from "./components/History";
import { AnalyticsView } from "./components/AnalyticsView";
import { Notifications } from "./components/Notifications";
import { Admin } from "./components/Admin";

type Tab = "overview" | "history" | "analytics" | "notifications" | "admin";

export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [tab, setTab] = useState<Tab>("overview");
  // Bumped after any money movement so every tab refetches instead of showing stale numbers.
  const [version, setVersion] = useState(0);

  useEffect(() => {
    setUnauthorizedHandler(() => { saveSession(null); setSession(null); });
  }, []);

  const logout = () => { saveSession(null); setSession(null); };
  if (!session) return <Login onLogin={(s) => { saveSession(s); setSession(s); }} />;

  const tabs: [Tab, string][] = [["overview", "Overview"], ["history", "History"], ["analytics", "Analytics"], ["notifications", "Notifications"]];
  if (session.role === "ADMIN") tabs.push(["admin", "Admin"]);

  return (
    <div className="shell">
      <header>
        <strong>Wallet</strong>
        <nav>
          {tabs.map(([id, label]) => (
            <button key={id} className={tab === id ? "tab active" : "tab"} onClick={() => setTab(id)}>{label}</button>
          ))}
        </nav>
        <span className="who">{session.email} <button className="ghost" onClick={logout}>Sign out</button></span>
      </header>
      <main>
        {tab === "overview" && <Overview version={version} onChange={() => setVersion((v) => v + 1)} />}
        {tab === "history" && <History version={version} />}
        {tab === "analytics" && <AnalyticsView version={version} />}
        {tab === "notifications" && <Notifications version={version} />}
        {tab === "admin" && session.role === "ADMIN" && <Admin onChange={() => setVersion((v) => v + 1)} />}
      </main>
    </div>
  );
}
