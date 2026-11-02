/** Money is handled as integer minor units (cents) end to end. Floats are never used for amounts. */

export function formatMinor(minor: number, currency = "USD"): string {
  const sign = minor < 0 ? "-" : "";
  const abs = Math.abs(minor);
  const whole = Math.floor(abs / 100).toLocaleString("en-US");
  const cents = String(abs % 100).padStart(2, "0");
  const symbol = currency === "USD" ? "$" : currency + " ";
  return `${sign}${symbol}${whole}.${cents}`;
}

/** "12", "12.5", "12.34" -> minor units; anything else (negative, 3 decimals, text, empty) -> null. */
export function parseToMinor(input: string): number | null {
  const m = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(input.trim());
  if (!m) return null;
  const minor = Number(m[1]) * 100 + Number((m[2] ?? "").padEnd(2, "0") || "0");
  return minor > 0 ? minor : null;
}
