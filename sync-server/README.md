# PennyWise personal sync server

Self-hosted two-phone ledger sync. Both Android apps parse SMS locally and
exchange structured transactions, accounts, rules, and settings through this API.

This is **not** the public pennywise-web site. Run it on a VPS, NAS, or PC you control.

## Run locally

```bash
./gradlew :sync-server:run
```

If Node.js is on `PATH`, that also runs `npm install && npm run build` in
`dashboard/` and serves the desktop SPA at `http://<host>:8080/`. Without Node,
build it yourself first (`cd dashboard && npm install && npm run build`); the
server keeps using the last `dashboard/dist` if present.

On first boot a household token is printed and saved to `sync-server/data/PAIRING.txt`.

Put that token and `http://<host>:8080` into Settings → Device sync on both phones.
Open the same URL in a desktop browser. Sign in with the pairing token, then the
email or SMS one-time code (`TWO_FACTOR_EMAIL` / `TWO_FACTOR_PHONE`). Phones keep
using the pairing token on `/v1/sync` only.

## Desktop dashboard

Vite + React app in [`dashboard/`](../dashboard). Core ledger only: Home,
Transactions, Accounts. Writes go through `/v1/dashboard/*` into the same
revision log the phones poll (`device_id = pennyke-web`).

```bash
cd dashboard
npm install
npm run dev      # Vite on :5173, proxies /v1 to :8080
npm run build    # writes dashboard/dist for Ktor
```

Ktor still serves `/PennyKE.apk` and `/v1/sync` alongside the SPA.

## cPanel (detective.co.ke/pennyKE)

Shared hosting cannot run the Kotlin server. Use the PHP + MySQL bundle in
[`hosting/cpanel`](../hosting/cpanel) and:

```bash
./scripts/deploy-detective.sh /path/to/Detective/.env
```

Phones then use `https://detective.co.ke/pennyKE` as the sync URL. Tables are
prefixed `pennyke_` so they can share the existing cPanel MySQL database.


## Docker

```bash
./gradlew :sync-server:fatJar
cd sync-server
docker compose up --build
```

HTTPS termination is yours (Caddy, nginx, or a reverse-proxy on the VPS). The app
accepts HTTP so a LAN NAS works without extra certs.

## API

- `GET /v1/health`
- `POST /v1/sync` with `Authorization: Bearer <pairing token>`
- Dashboard login (public):
  - `GET /v1/dashboard/auth`
  - `POST /v1/dashboard/login`
  - `POST /v1/dashboard/login/verify`
  - `POST /v1/dashboard/logout`
- Dashboard (session cookie or `Authorization: Bearer <session token>` — not the pairing token):
  - `GET /v1/dashboard/summary`
  - `GET /v1/dashboard/transactions`
  - `GET /v1/dashboard/transactions/{hash}`
  - `PUT /v1/dashboard/transactions/{hash}`
  - `POST /v1/dashboard/transactions`
  - `DELETE /v1/dashboard/transactions/{hash}`
  - `GET /v1/dashboard/accounts`
  - `GET /v1/dashboard/categories`
