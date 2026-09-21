import { useEffect, useState } from "react";
import { api } from "../api";
import { Money } from "../components/Money";
import { accountLabel } from "../money";
import type { DashboardAccount } from "../types";

export function Accounts() {
  const [accounts, setAccounts] = useState<DashboardAccount[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api
      .accounts()
      .then((body) => setAccounts(body.accounts))
      .catch((err: Error) => setError(err.message));
  }, []);

  if (error) return <div className="error">{error}</div>;
  if (!accounts) return <div className="empty">Loading accounts…</div>;

  return (
    <>
      <div className="page-head">
        <div>
          <h2>Accounts</h2>
          <p>Latest balances from the household sync log, plus transaction counts.</p>
        </div>
      </div>
      {accounts.length === 0 ? (
        <div className="empty">No accounts in the ledger yet.</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Account</th>
              <th>Type</th>
              <th className="amount">Balance</th>
              <th className="amount">Transactions</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => (
              <tr key={`${account.bankName}||${account.accountLast4}`}>
                <td>
                  <div>{account.alias || accountLabel(account.bankName, account.accountLast4)}</div>
                  {account.alias ? (
                    <div className="muted">{accountLabel(account.bankName, account.accountLast4)}</div>
                  ) : null}
                </td>
                <td>{account.isCreditCard ? "Card" : "Bank"}</td>
                <td className="amount">
                  {account.balance ? (
                    <Money amount={account.balance} currency={account.currency} />
                  ) : (
                    "—"
                  )}
                </td>
                <td className="amount">{account.transactionCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
