import type {
  DashboardAccount,
  DashboardSummary,
  DashboardTransaction,
  TransactionWrite,
} from "./types";

const TOKEN_KEY = "pennyke.session";
const LEGACY_TOKEN_KEY = "pennyke.token";

export function getToken(): string {
  sessionStorage.removeItem(LEGACY_TOKEN_KEY);
  return sessionStorage.getItem(TOKEN_KEY) ?? "";
}

export function setToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token.trim());
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function apiUrl(path: string): string {
  const base = import.meta.env.BASE_URL.replace(/\/$/, "");
  return `${base}${path}`;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(apiUrl(path), { ...init, headers, credentials: "same-origin" });
  if (response.status === 401) {
    clearToken();
    throw new Error("unauthorized");
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = (await response.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      /* ignore */
    }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export type AuthChannel = "email" | "sms";

export interface AuthStatus {
  channels: AuthChannel[];
  emailHint: string | null;
  phoneHint: string | null;
}

export interface LoginChallenge {
  challengeId: string;
  channel: AuthChannel | string;
  destinationHint: string;
  expiresIn: number;
}

export const api = {
  authStatus: () => request<AuthStatus>("/v1/dashboard/auth"),
  login: (token: string, channel: AuthChannel) =>
    request<LoginChallenge>("/v1/dashboard/login", {
      method: "POST",
      body: JSON.stringify({ token, channel }),
    }),
  verify: (challengeId: string, code: string) =>
    request<{ sessionToken: string; expiresAt: string; expiresIn: number }>("/v1/dashboard/login/verify", {
      method: "POST",
      body: JSON.stringify({ challengeId, code }),
    }),
  logout: async () => {
    try {
      await request<{ ok: boolean }>("/v1/dashboard/logout", { method: "POST" });
    } catch {
      /* still clear local session */
    }
    clearToken();
  },
  summary: () => request<DashboardSummary>("/v1/dashboard/summary"),
  transactions: (params: Record<string, string | undefined> = {}) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value) query.set(key, value);
    });
    const suffix = query.toString() ? `?${query}` : "";
    return request<{ transactions: DashboardTransaction[] }>(`/v1/dashboard/transactions${suffix}`);
  },
  transaction: (hash: string) => request<DashboardTransaction>(`/v1/dashboard/transactions/${hash}`),
  updateTransaction: (hash: string, body: TransactionWrite) =>
    request<{ revision: number; hash: string; transaction: DashboardTransaction }>(
      `/v1/dashboard/transactions/${hash}`,
      { method: "PUT", body: JSON.stringify(body) }
    ),
  createTransaction: (body: TransactionWrite) =>
    request<{ revision: number; hash: string; transaction: DashboardTransaction }>(
      "/v1/dashboard/transactions",
      { method: "POST", body: JSON.stringify(body) }
    ),
  deleteTransaction: (hash: string) =>
    request<{ revision: string; hash: string }>(`/v1/dashboard/transactions/${hash}`, {
      method: "DELETE",
    }),
  accounts: () => request<{ accounts: DashboardAccount[] }>("/v1/dashboard/accounts"),
  categories: () => request<{ names: string[] }>("/v1/dashboard/categories"),
};
