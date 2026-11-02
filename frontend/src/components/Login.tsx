import { useState } from "react";
import { api, post, saveSession, type Session } from "../api";

interface TokenResponse { accessToken: string; userId: number; email: string; role: "USER" | "ADMIN" }

export function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError("");
    try {
      saveSession(null);
      const t = await post<TokenResponse>(`/auth/${mode}`, { email, password });
      onLogin({ accessToken: t.accessToken, userId: t.userId, email: t.email, role: t.role });
    } catch (err: any) {
      setError(err.message || "Something went wrong");
    } finally { setBusy(false); }
  }

  return (
    <div className="login">
      <h1>Wallet</h1>
      <form className="card" onSubmit={submit}>
        <h2>{mode === "login" ? "Sign in" : "Create account"}</h2>
        <label htmlFor="email">Email</label>
        <input id="email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <label htmlFor="password">Password</label>
        <input id="password" type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <p className="error" role="alert">{error}</p>}
        <button disabled={busy}>{mode === "login" ? "Sign in" : "Register"}</button>
        <p className="muted">
          {mode === "login" ? "New here? " : "Already registered? "}
          <a href="#" onClick={(e) => { e.preventDefault(); setMode(mode === "login" ? "register" : "login"); }}>
            {mode === "login" ? "Create an account" : "Sign in"}
          </a>
        </p>
        <p className="muted small">Demo admin: admin@wallet.example / admin-password-1</p>
      </form>
    </div>
  );
}

export { api };
