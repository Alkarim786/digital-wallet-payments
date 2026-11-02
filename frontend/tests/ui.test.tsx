import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CategoryBars, DailyChart } from "../src/components/AnalyticsView";
import { ApiError, api, newIdempotencyKey, post, saveSession, setUnauthorizedHandler } from "../src/api";
import { formatMinor, parseToMinor } from "../src/money";

afterEach(cleanup);

describe("money", () => {
  it("formats minor units without floating point surprises", () => {
    expect(formatMinor(0)).toBe("$0.00");
    expect(formatMinor(5)).toBe("$0.05");
    expect(formatMinor(123456)).toBe("$1,234.56");
    expect(formatMinor(-250)).toBe("-$2.50");
    expect(formatMinor(1000, "EUR")).toBe("EUR 10.00");
  });

  it("parses user input strictly into integer cents", () => {
    expect(parseToMinor("12")).toBe(1200);
    expect(parseToMinor("12.5")).toBe(1250);
    expect(parseToMinor("0.07")).toBe(7);
    expect(parseToMinor(" 19.99 ")).toBe(1999);
    // classic float trap: 0.1 + 0.2 style inputs must stay exact
    expect(parseToMinor("1.15")).toBe(115);
    for (const bad of ["", "abc", "-5", "0", "0.00", "1.234", "1,5", "1e3", "12.", ".5"]) {
      expect(parseToMinor(bad), bad).toBeNull();
    }
  });
});

describe("charts", () => {
  const days = [
    { date: "2026-11-01", sentMinor: 1000, receivedMinor: 0 },
    { date: "2026-11-02", sentMinor: 0, receivedMinor: 500 },
    { date: "2026-11-03", sentMinor: 0, receivedMinor: 0 },
  ];

  it("draws a sent and received bar per day with proportional heights", () => {
    render(<DailyChart days={days} />);
    const sent = screen.getAllByTestId("bar-sent");
    const received = screen.getAllByTestId("bar-received");
    expect(sent).toHaveLength(3);
    expect(received).toHaveLength(3);
    const h = (el: HTMLElement) => Number(el.getAttribute("height"));
    expect(h(sent[0])).toBeGreaterThan(h(received[1]));
    expect(h(received[1])).toBeCloseTo(h(sent[0]) / 2, 5);
    expect(h(sent[2])).toBe(0);
  });

  it("lists categories and handles the empty case", () => {
    const { rerender } = render(<CategoryBars items={[{ category: "food", sentMinor: 2500 }]} />);
    expect(screen.getByText("food")).toBeTruthy();
    expect(screen.getByText("$25.00")).toBeTruthy();
    rerender(<CategoryBars items={[]} />);
    expect(screen.getByText(/No spending/)).toBeTruthy();
  });
});

describe("api client", () => {
  const store: Record<string, string> = {};
  beforeEach(() => {
    for (const k of Object.keys(store)) delete store[k];
    vi.stubGlobal("localStorage", { getItem: (k: string) => store[k] ?? null, setItem: (k: string, v: string) => (store[k] = v), removeItem: (k: string) => delete store[k] });
    setUnauthorizedHandler(() => {});
  });

  it("sends the bearer token and the Idempotency-Key header", async () => {
    saveSession({ accessToken: "tok", userId: 1, email: "a@b.c", role: "USER" });
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);
    await post("/wallet/transfers", { amountMinor: 5 }, "key-123");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/wallet/transfers");
    const h = new Headers(init.headers);
    expect(h.get("Authorization")).toBe("Bearer tok");
    expect(h.get("Idempotency-Key")).toBe("key-123");
    expect(h.get("Content-Type")).toBe("application/json");
  });

  it("surfaces API errors with code and body, and triggers sign-out on 401", async () => {
    saveSession({ accessToken: "expired", userId: 1, email: "a@b.c", role: "USER" });
    const onUnauth = vi.fn();
    setUnauthorizedHandler(onUnauth);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: "UNAUTHENTICATED", message: "expired" }), { status: 401 })));
    await expect(api("/wallet/account")).rejects.toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
    expect(onUnauth).toHaveBeenCalledOnce();
  });

  it("keeps the DECLINED result body on a 422 so the UI can show reasons", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ status: "DECLINED", reasons: ["BLOCKED_COUNTRY"] }), { status: 422 })));
    const err = (await post("/wallet/transfers", {}, "k").catch((e) => e)) as ApiError;
    expect(err.status).toBe(422);
    expect((err.body as any).reasons).toEqual(["BLOCKED_COUNTRY"]);
  });

  it("generates a distinct idempotency key per attempt", () => {
    vi.stubGlobal("crypto", { randomUUID: (() => { let n = 0; return () => `uuid-${n++}`; })() });
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});
