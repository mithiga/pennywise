import { useEffect, useState } from "react";
import { api } from "../api";
import { Money, MoneyMapView } from "../components/Money";
import { TransactionTable } from "../components/TransactionTable";
import { accountLabel } from "../money";
import type { DashboardSummary } from "../types";

export function Home({ onOpenTransactions }: { onOpenTransactions: () => void }) {
  const [data, setData] = useState<DashboardSummary | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api
      .summary()
      .then(setData)
      .catch((err: Error) => setError(err.message));
  }, []);

  if (error) return <div className="error">{error}</div>;
  if (!data) return <div className="empty">Loading this month…</div>;

  return (
    <>
      <div className="page-head">
        <div>
          <h2>Home</h2>
          <p>This month, grouped by currency. KES and other currencies stay separate.</p>
        </div>
        <button className="btn secondary" type="button" onClick={onOpenTransactions}>
          All transactions
        </button>
      </div>

      <div className="stat-grid">
        <div className="stat income">
          <label>Income</label>
          <strong>
            <MoneyMapView map={data.income} />
          </strong>
        </div>
        <div className="stat expense">
          <label>Expense</label>
          <strong>
            <MoneyMapView map={data.expense} />
          </strong>
        </div>
      </div>

      <div className="account-strip">
        {data.accounts.length === 0 ? (
          <div className="card muted">No accounts yet.</div>
        ) : (
          data.accounts.map((account) => (
            <div className="card" key={`${account.bankName}||${account.accountLast4}`}>
              <label className="muted">{accountLabel(account.bankName, account.accountLast4)}</label>
              <strong style={{ display: "block", marginTop: 8 }}>
                {account.balance ? (
                  <Money amount={account.balance} currency={account.currency} />
                ) : (
                  "—"
                )}
              </strong>
              <div className="muted" style={{ marginTop: 6 }}>
                {account.transactionCount} txn{account.transactionCount === 1 ? "" : "s"}
                {account.isCreditCard ? " · card" : ""}
              </div>
            </div>
          ))
        )}
      </div>

      <h3 style={{ margin: "0 0 12px", fontWeight: 600 }}>Recent</h3>
      <TransactionTable items={data.recent} onSelect={() => onOpenTransactions()} />
    </>
  );
}
