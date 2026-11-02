export type Role = "USER" | "ADMIN";

export interface Session { accessToken: string; userId: number; email: string; role: Role }
export interface Account { accountId: number; balanceMinor: number; currency: string }
export interface TransferResult { txnId: string; status: string; amountMinor: number; fromAccount: number; toAccount: number; fraudScore: number; reasons: string[]; balanceMinor: number }
export interface Txn {
  id: string; type: string; status: string; direction: "SENT" | "RECEIVED" | "TOPUP"; counterpartyAccount: number;
  amountMinor: number; currency: string; category: string | null; note: string | null; fraudScore: number; fraudReasons: string | null; createdAt: string;
}
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Analytics {
  byDay: { date: string; sentMinor: number; receivedMinor: number }[];
  byCategory: { category: string; sentMinor: number }[];
  totalSentMinor: number; totalReceivedMinor: number;
}
export interface Notification { id: number; kind: string; message: string; createdAt: string; read: boolean }
export interface Reconciliation { sumOfAllBalances: number; accountsWithDrift: number; unbalancedTransactions: number; healthy: boolean }

const KEY = "wallet.session";

export function loadSession(): Session | null {
  try { return JSON.parse(localStorage.getItem(KEY) ?? "null"); } catch { return null; }
}
export function saveSession(s: Session | null) {
  try { s ? localStorage.setItem(KEY, JSON.stringify(s)) : localStorage.removeItem(KEY); } catch { /* storage unavailable */ }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body?: unknown) { super(message); }
}

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

/** A fresh key identifies ONE logical attempt. Reuse it when retrying that attempt; make a new one for the next. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export async function api<T>(path: string, init: RequestInit & { idempotencyKey?: string } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const s = loadSession();
  if (s) headers.set("Authorization", `Bearer ${s.accessToken}`);
  if (init.body) headers.set("Content-Type", "application/json");
  if (init.idempotencyKey) headers.set("Idempotency-Key", init.idempotencyKey);
  const res = await fetch(`/api${path}`, { ...init, headers });
  let body: any = null;
  try { body = await res.json(); } catch { /* empty body */ }
  if (res.status === 401 && s && !path.startsWith("/auth/login")) onUnauthorized();
  if (!res.ok) {
    // 422 DECLINED carries a normal TransferResult body; surface it to the caller.
    throw new ApiError(res.status, body?.error ?? body?.status ?? "ERROR", body?.message ?? res.statusText, body);
  }
  return body as T;
}

export const post = <T,>(path: string, data: unknown, idempotencyKey?: string) =>
  api<T>(path, { method: "POST", body: JSON.stringify(data), idempotencyKey });
