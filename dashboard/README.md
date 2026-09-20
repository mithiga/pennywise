# PennyKE desktop dashboard

Desktop ledger UI for the household [sync-server](../sync-server). It is **not**
the public [`pennywise-web`](../pennywise-web) parse-report site.

Home, Transactions, and Accounts only. Analytics, budgets, rules, AI, and SMS
stay on the phones.

## Develop

Start the sync server, then:

```bash
npm install
npm run dev
```

Vite listens on `:5173` and proxies `/v1` to `http://127.0.0.1:8080`. Paste the
pairing token from `sync-server/data/PAIRING.txt` on first visit.

## Production

```bash
npm run build
```

Ktor serves `dashboard/dist` at `/` while keeping `/PennyKE.apk` and `/v1/*`.
`./gradlew :sync-server:run` builds this SPA automatically when `npm` is on PATH.
