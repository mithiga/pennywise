# PennyWise personal sync server

Self-hosted two-phone ledger sync. Both Android apps parse SMS locally and
exchange structured transactions, accounts, rules, and settings through this API.

This is **not** the public pennywise-web site. Run it on a VPS, NAS, or PC you control.

## Run locally

```bash
./gradlew :sync-server:run
```

On first boot a household token is printed and saved to `sync-server/data/PAIRING.txt`.

Put that token and `http://<host>:8080` into Settings → Device sync on both phones.

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
- `POST /v1/sync` with `Authorization: Bearer <token>`
