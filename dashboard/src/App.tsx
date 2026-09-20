import { useCallback, useEffect, useState } from "react";
import { api, clearToken, getToken } from "./api";
import { AppShell, type Page } from "./components/AppShell";
import { Accounts } from "./pages/Accounts";
import { Home } from "./pages/Home";
import { Login } from "./pages/Login";
import { Transactions } from "./pages/Transactions";

function pageFromHash(): Page {
  const hash = window.location.hash.replace(/^#\/?/, "");
  if (hash.startsWith("transactions")) return "transactions";
  if (hash.startsWith("accounts")) return "accounts";
  return "home";
}

export function App() {
  const [authed, setAuthed] = useState(() => Boolean(getToken()));
  const [page, setPage] = useState<Page>(pageFromHash);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const go = useCallback((next: Page) => {
    window.location.hash = `/${next}`;
    setPage(next);
  }, []);

  if (!authed) {
    return <Login onAuthed={() => setAuthed(true)} />;
  }

  return (
    <AppShell
      page={page}
      onNavigate={go}
      onSignOut={() => {
        void api.logout();
        clearToken();
        setAuthed(false);
      }}
    >
      {page === "home" && <Home onOpenTransactions={() => go("transactions")} />}
      {page === "transactions" && <Transactions />}
      {page === "accounts" && <Accounts />}
    </AppShell>
  );
}
