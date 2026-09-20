# PennyKE desktop dashboard

Desktop ledger UI for the household [sync-server](../sync-server). It is **not**
the public [`pennywise-web`](../pennywise-web) parse-report site.

Home, Transactions, and Accounts only. Analytics, budgets, rules, AI, and
unrecognized SMS stay on the phones. Parsed original SMS is readable on a
transaction. Portal sign-in is the pairing token plus an email or SMS code.

## Develop

Start the sync server, then:

```bash
npm install
npm run dev
```

Vite listens on `:5173` and proxies `/v1` to `http://127.0.0.1:8080`. Sign in
with the pairing token from `sync-server/data/PAIRING.txt`, then the one-time
code. Set `TWO_FACTOR_EMAIL` (and optionally `TWO_FACTOR_PHONE`) on the server.

## Production

```bash
npm run build
```

Ktor serves `dashboard/dist` at `/` while keeping `/PennyKE.apk` and `/v1/*`.
`./gradlew :sync-server:run` builds this SPA automatically when `npm` is on PATH.
