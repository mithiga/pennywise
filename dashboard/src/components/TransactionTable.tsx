import type { DashboardTransaction } from "../types";
import { accountLabel, formatDateTime } from "../money";
import { Money } from "./Money";

export function TransactionTable({
  items,
  selectedHash,
  onSelect,
}: {
  items: DashboardTransaction[];
  selectedHash?: string | null;
  onSelect: (tx: DashboardTransaction) => void;
}) {
  if (items.length === 0) {
    return <div className="empty">No transactions match these filters.</div>;
  }

  return (
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Merchant</th>
          <th>Account</th>
          <th>Category</th>
          <th>Type</th>
          <th className="amount">Amount</th>
        </tr>
      </thead>
      <tbody>
        {items.map((tx) => (
          <tr
            key={tx.hash}
            className={selectedHash === tx.hash ? "selected" : undefined}
            onClick={() => onSelect(tx)}
          >
            <td>{formatDateTime(tx.dateTime)}</td>
            <td>
              {tx.merchantName}
              {tx.excludedFromAnalytics ? (
                <span className="badge" style={{ marginLeft: 8 }}>
                  excluded
                </span>
              ) : null}
            </td>
            <td>{accountLabel(tx.bankName, tx.accountNumber)}</td>
            <td>{tx.category}</td>
            <td>
              <span className="badge">{tx.transactionType}</span>
            </td>
            <td className="amount">
              <Money amount={tx.amount} currency={tx.currency} signed type={tx.transactionType} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
