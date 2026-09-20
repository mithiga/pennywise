import { FormEvent, useState } from "react";
import { api, setToken, clearToken } from "../api";

export function Login({ onAuthed }: { onAuthed: () => void }) {
  const [token, setLocal] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setToken(token);
    try {
      await api.summary();
      onAuthed();
    } catch {
      clearToken();
      setError("Could not sign in. Use the household pairing token from PAIRING.txt.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="login-card" onSubmit={submit}>
        <h1>PennyKE</h1>
        <p>Enter the household pairing token to open the desktop ledger.</p>
        <input
          autoFocus
          type="password"
          placeholder="Pairing token"
          value={token}
          onChange={(event) => setLocal(event.target.value)}
          autoComplete="current-password"
        />
        <button className="btn" type="submit" disabled={busy || !token.trim()}>
          {busy ? "Signing in…" : "Continue"}
        </button>
        {error ? <div className="error">{error}</div> : null}
      </form>
    </div>
  );
}
