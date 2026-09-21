const FALLBACK: Record<string, string> = {
  KES: "KES",
  INR: "INR",
  USD: "USD",
  EUR: "EUR",
  GBP: "GBP",
};

export function formatMoney(amount: string, currency: string): string {
  const value = Number(amount);
  if (Number.isNaN(value)) return `${currency} ${amount}`;
  try {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency,
      currencyDisplay: "narrowSymbol",
    }).format(value);
  } catch {
    return `${FALLBACK[currency] ?? currency} ${value.toFixed(2)}`;
  }
}

export function formatMoneyMap(map: Record<string, string>): string {
  const entries = Object.entries(map);
  if (entries.length === 0) return "—";
  return entries.map(([currency, amount]) => formatMoney(amount, currency)).join(" · ");
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso.replace("T", " ");
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

export function accountLabel(bank?: string | null, last4?: string | null): string {
  if (!bank && !last4) return "Unassigned";
  if (bank && last4) return `${bank} · ${last4}`;
  return bank || last4 || "Unassigned";
}
