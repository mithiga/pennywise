import { formatMoney, formatMoneyMap } from "../money";

export function Money({
  amount,
  currency,
  signed,
  type,
}: {
  amount: string;
  currency: string;
  signed?: boolean;
  type?: string;
}) {
  const outflow = type === "EXPENSE" || type === "CREDIT";
  const inflow = type === "INCOME";
  const prefix = signed ? (outflow ? "−" : inflow ? "+" : "") : "";
  return (
    <span className={`amount ${outflow ? "neg" : ""} ${inflow ? "pos" : ""}`}>
      {prefix}
      {formatMoney(amount, currency)}
    </span>
  );
}

export function MoneyMapView({ map }: { map: Record<string, string> }) {
  return <>{formatMoneyMap(map)}</>;
}
