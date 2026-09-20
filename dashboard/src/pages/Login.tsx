import { FormEvent, useEffect, useState } from "react";
import { api, setToken, type AuthChannel, type AuthStatus, type LoginChallenge } from "../api";

export function Login({ onAuthed }: { onAuthed: () => void }) {
  const [token, setLocal] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [challenge, setChallenge] = useState<LoginChallenge | null>(null);
  const [step, setStep] = useState<"token" | "channel" | "code">("token");

  useEffect(() => {
    let cancelled = false;
    api
      .authStatus()
      .then((next) => {
        if (!cancelled) setStatus(next);
      })
      .catch(() => {
        if (!cancelled) setError("Could not reach the portal.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function sendCode(channel: AuthChannel) {
    setBusy(true);
    setError("");
    try {
      const next = await api.login(token.trim(), channel);
      setChallenge(next);
      setCode("");
      setStep("code");
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not send code";
      setError(message === "unauthorized" ? "Wrong pairing token." : message);
    } finally {
      setBusy(false);
    }
  }

  async function submitToken(event: FormEvent) {
    event.preventDefault();
    const channels = status?.channels ?? [];
    if (channels.length === 1) {
      await sendCode(channels[0]);
      return;
    }
    if (channels.length > 1) {
      setError("");
      setStep("channel");
      return;
    }
    setError("Two-factor is not configured on this portal.");
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault();
    if (!challenge) return;
    setBusy(true);
    setError("");
    try {
      const result = await api.verify(challenge.challengeId, code.trim());
      setToken(result.sessionToken);
      onAuthed();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not verify code");
    } finally {
      setBusy(false);
    }
  }

  const channels = status?.channels ?? [];

  return (
    <div className="login">
      <form className="login-card" onSubmit={step === "code" ? submitCode : submitToken}>
        <h1>PennyKE</h1>
        {step === "code" && challenge ? (
          <>
            <p>
              Enter the 6-digit code sent to <strong>{challenge.destinationHint}</strong>.
            </p>
            <input
              autoFocus
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              maxLength={6}
              placeholder="123456"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            />
            <button className="btn" type="submit" disabled={busy || code.length !== 6}>
              {busy ? "Checking…" : "Verify"}
            </button>
            <button
              className="btn secondary"
              type="button"
              disabled={busy}
              onClick={() => void sendCode(challenge.channel === "sms" ? "sms" : "email")}
            >
              Resend code
            </button>
            <button
              className="btn secondary"
              type="button"
              disabled={busy}
              onClick={() => {
                setChallenge(null);
                setStep(channels.length > 1 ? "channel" : "token");
              }}
            >
              Back
            </button>
          </>
        ) : step === "channel" ? (
          <>
            <p>Choose where to send the one-time code.</p>
            <div className="login-actions">
              {channels.includes("email") ? (
                <button className="btn" type="button" disabled={busy} onClick={() => void sendCode("email")}>
                  Email me a code
                  {status?.emailHint ? <span className="muted"> · {status.emailHint}</span> : null}
                </button>
              ) : null}
              {channels.includes("sms") ? (
                <button className="btn secondary" type="button" disabled={busy} onClick={() => void sendCode("sms")}>
                  Text me a code
                  {status?.phoneHint ? <span className="muted"> · {status.phoneHint}</span> : null}
                </button>
              ) : null}
            </div>
            <button className="btn secondary" type="button" onClick={() => setStep("token")}>
              Back
            </button>
          </>
        ) : (
          <>
            <p>Enter the household pairing token. We will then send a one-time code by email or SMS.</p>
            <input
              autoFocus
              type="password"
              placeholder="Pairing token"
              value={token}
              onChange={(event) => setLocal(event.target.value)}
              autoComplete="current-password"
            />
            <button className="btn" type="submit" disabled={busy || !token.trim() || channels.length === 0}>
              {busy ? "Sending…" : channels.length > 1 ? "Continue" : "Send code"}
            </button>
          </>
        )}
        {error ? <div className="error">{error}</div> : null}
      </form>
    </div>
  );
}
