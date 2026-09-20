import type { ReactNode } from "react";

export type Page = "home" | "transactions" | "accounts";

const NAV: { id: Page; label: string }[] = [
  { id: "home", label: "Home" },
  { id: "transactions", label: "Transactions" },
  { id: "accounts", label: "Accounts" },
];

export function AppShell({
  page,
  onNavigate,
  onSignOut,
  children,
}: {
  page: Page;
  onNavigate: (page: Page) => void;
  onSignOut: () => void;
  children: ReactNode;
}) {

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <h1>PennyKE</h1>
          <span>Household ledger</span>
        </div>
        <nav className="nav">
          {NAV.map((item) => (
            <button
              key={item.id}
              className={page === item.id ? "active" : undefined}
              onClick={() => onNavigate(item.id)}
              type="button"
            >
              {item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div>
            <div className="muted">Access</div>
            <div>Signed in</div>
          </div>
          <button type="button" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </aside>
      <main className="main">{children}</main>
    </div>
  );
}
