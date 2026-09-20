import { FormEvent, useEffect, useState } from "react";
import { api } from "../api";
import type { DashboardTransaction, TransactionWrite } from "../types";

const TYPES = ["EXPENSE", "INCOME", "CREDIT", "TRANSFER", "INVESTMENT"];

function toInputDateTime(iso: string): string {
  if (!iso) return "";
  return iso.slice(0, 16);
}

function fromInputDateTime(value: string): string {
  if (!value) return "";
  return value.length === 16 ? `${value}:00` : value;
}

function emptyDraft(): TransactionWrite {
  const now = new Date();
  const pad = (n: number) => n.toString().padStart(2, "0");
  const local = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:00`;
  return {
    amount: "",
    currency: "KES",
    merchantName: "",
    description: "",
    category: "Others",
    transactionType: "EXPENSE",
    dateTime: local,
    bankName: "Manual Entry",
    accountNumber: "",
    fromAccount: "",
    toAccount: "",
    excludedFromAnalytics: false,
  };
}

function fromTransaction(tx: DashboardTransaction): TransactionWrite {
  return {
    amount: tx.amount,
    currency: tx.currency,
    merchantName: tx.merchantName,
    description: tx.description ?? "",
    category: tx.category,
    transactionType: tx.transactionType,
    dateTime: tx.dateTime,
    bankName: tx.bankName ?? "",
    accountNumber: tx.accountNumber ?? "",
    fromAccount: tx.fromAccount ?? "",
    toAccount: tx.toAccount ?? "",
    excludedFromAnalytics: tx.excludedFromAnalytics,
  };
}

export function TransactionInspector({
  mode,
  transaction,
  categories,
  onClose,
  onSaved,
  onDeleted,
}: {
  mode: "edit" | "create";
  transaction: DashboardTransaction | null;
  categories: string[];
  onClose: () => void;
  onSaved: (hash: string) => void;
  onDeleted?: () => void;
}) {
  const [draft, setDraft] = useState<TransactionWrite>(emptyDraft);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setError("");
    if (mode === "edit" && transaction) setDraft(fromTransaction(transaction));
    else setDraft(emptyDraft());
  }, [mode, transaction]);

  function patch<K extends keyof TransactionWrite>(key: K, value: TransactionWrite[K]) {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const body: TransactionWrite = {
      ...draft,
      dateTime: fromInputDateTime(toInputDateTime(draft.dateTime ?? "")),
      description: draft.description || null,
      bankName: draft.bankName || null,
      accountNumber: draft.accountNumber || null,
      fromAccount: draft.fromAccount || null,
      toAccount: draft.toAccount || null,
    };
    try {
      if (mode === "create") {
        const created = await api.createTransaction(body);
        onSaved(created.hash);
      } else if (transaction) {
        const updated = await api.updateTransaction(transaction.hash, body);
        onSaved(updated.hash);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!transaction || !onDeleted) return;
    if (!window.confirm(`Delete ${transaction.merchantName}? Phones will pick up the tombstone on the next sync.`)) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api.deleteTransaction(transaction.hash);
      onDeleted();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Delete failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="inspector" onSubmit={submit}>
      <h3>{mode === "create" ? "Add transaction" : "Edit transaction"}</h3>
      <div className="field">
        <label>Merchant</label>
        <input
          required
          value={draft.merchantName ?? ""}
          onChange={(event) => patch("merchantName", event.target.value)}
        />
      </div>
      <div className="field-row">
        <div className="field">
          <label>Amount</label>
          <input
            required
            inputMode="decimal"
            value={draft.amount ?? ""}
            onChange={(event) => patch("amount", event.target.value)}
          />
        </div>
        <div className="field">
          <label>Currency</label>
          <input
            required
            value={draft.currency ?? "KES"}
            onChange={(event) => patch("currency", event.target.value.toUpperCase())}
          />
        </div>
      </div>
      <div className="field-row">
        <div className="field">
          <label>Type</label>
          <select
            value={draft.transactionType ?? "EXPENSE"}
            onChange={(event) => patch("transactionType", event.target.value)}
          >
            {TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Category</label>
          <input
            list="category-options"
            value={draft.category ?? ""}
            onChange={(event) => patch("category", event.target.value)}
          />
          <datalist id="category-options">
            {categories.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
        </div>
      </div>
      <div className="field">
        <label>Date & time</label>
        <input
          required
          type="datetime-local"
          value={toInputDateTime(draft.dateTime ?? "")}
          onChange={(event) => patch("dateTime", fromInputDateTime(event.target.value))}
        />
      </div>
      <div className="field">
        <label>Description</label>
        <textarea
          rows={2}
          value={draft.description ?? ""}
          onChange={(event) => patch("description", event.target.value)}
        />
      </div>
      <div className="field-row">
        <div className="field">
          <label>Bank</label>
          <input
            value={draft.bankName ?? ""}
            onChange={(event) => patch("bankName", event.target.value)}
          />
        </div>
        <div className="field">
          <label>Last 4</label>
          <input
            value={draft.accountNumber ?? ""}
            onChange={(event) => patch("accountNumber", event.target.value)}
          />
        </div>
      </div>
      {draft.transactionType === "TRANSFER" ? (
        <div className="field-row">
          <div className="field">
            <label>From</label>
            <input
              value={draft.fromAccount ?? ""}
              onChange={(event) => patch("fromAccount", event.target.value)}
            />
          </div>
          <div className="field">
            <label>To</label>
            <input
              value={draft.toAccount ?? ""}
              onChange={(event) => patch("toAccount", event.target.value)}
            />
          </div>
        </div>
      ) : null}
      <div className="check">
        <input
          id="excluded"
          type="checkbox"
          checked={Boolean(draft.excludedFromAnalytics)}
          onChange={(event) => patch("excludedFromAnalytics", event.target.checked)}
        />
        <label htmlFor="excluded">Exclude from analytics</label>
      </div>
      {error ? <div className="error">{error}</div> : null}
      <div className="inspector-actions">
        <button className="btn" type="submit" disabled={busy}>
          {busy ? "Saving…" : "Save"}
        </button>
        <button className="btn secondary" type="button" onClick={onClose}>
          Close
        </button>
        {mode === "edit" ? (
          <button className="btn danger" type="button" disabled={busy} onClick={remove}>
            Delete
          </button>
        ) : null}
      </div>
    </form>
  );
}
