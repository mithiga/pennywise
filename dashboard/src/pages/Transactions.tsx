import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { TransactionInspector } from "../components/TransactionInspector";
import { TransactionTable } from "../components/TransactionTable";
import { accountLabel } from "../money";
import type { DashboardAccount, DashboardTransaction } from "../types";

export function Transactions() {
  const [items, setItems] = useState<DashboardTransaction[]>([]);
  const [accounts, setAccounts] = useState<DashboardAccount[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [selected, setSelected] = useState<DashboardTransaction | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [account, setAccount] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      const [list, accountBody, categoryBody] = await Promise.all([
        api.transactions({ q, type, account, from, to }),
        api.accounts(),
        api.categories(),
      ]);
      setItems(list.transactions);
      setAccounts(accountBody.accounts);
      setCategories(categoryBody.names);
      setSelected((current) => {
        if (!current) return null;
        return list.transactions.find((tx) => tx.hash === current.hash) ?? current;
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load");
    }
  }, [q, type, account, from, to]);

  useEffect(() => {
    const handle = window.setTimeout(() => {
      void load();
    }, 150);
    return () => window.clearTimeout(handle);
  }, [load]);

  const accountOptions = useMemo(
    () =>
      accounts.map((item) => ({
        value: `${item.bankName}||${item.accountLast4}`,
        label: accountLabel(item.bankName, item.accountLast4),
      })),
    [accounts]
  );

  return (
    <>
      <div className="page-head">
        <div>
          <h2>Transactions</h2>
          <p>Search the household ledger. Edits write into the same sync log the phones poll.</p>
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => {
            setCreating(true);
            setSelected(null);
          }}
        >
          Add
        </button>
      </div>

      <div className="toolbar">
        <input
          placeholder="Search merchant, category, bank…"
          value={q}
          onChange={(event) => setQ(event.target.value)}
        />
        <select value={type} onChange={(event) => setType(event.target.value)}>
          <option value="">All types</option>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
          <option value="CREDIT">Credit</option>
          <option value="TRANSFER">Transfer</option>
          <option value="INVESTMENT">Investment</option>
        </select>
        <select value={account} onChange={(event) => setAccount(event.target.value)}>
          <option value="">All accounts</option>
          {accountOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
        <input type="date" value={to} onChange={(event) => setTo(event.target.value)} />
      </div>

      {error ? <div className="error">{error}</div> : null}

      <div className="split">
        <TransactionTable
          items={items}
          selectedHash={creating ? null : selected?.hash}
          onSelect={(tx) => {
            setCreating(false);
            setSelected(tx);
          }}
        />
        {creating || selected ? (
          <TransactionInspector
            mode={creating ? "create" : "edit"}
            transaction={creating ? null : selected}
            categories={categories}
            onClose={() => {
              setCreating(false);
              setSelected(null);
            }}
            onSaved={async (hash) => {
              setCreating(false);
              await load();
              const fresh = await api.transaction(hash).catch(() => null);
              setSelected(fresh);
            }}
            onDeleted={async () => {
              setSelected(null);
              await load();
            }}
          />
        ) : (
          <div className="inspector">
            <h3>Inspector</h3>
            <p className="muted">Select a row to edit, or add a manual transaction.</p>
          </div>
        )}
      </div>
    </>
  );
}
