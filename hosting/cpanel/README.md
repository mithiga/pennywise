# cPanel host bundle for detective.co.ke/pennyKE

Shared cPanel cannot run the Kotlin sync-server. This folder is a PHP + MySQL
port of the same household protocol:

- `POST /pennyKE/v1/sync` — phones
- `/pennyKE/v1/dashboard/*` — desktop SPA
- tables `pennyke_changes` / `pennyke_meta` (safe beside OpenCart)

Deploy:

```bash
PENNYKE_BASE=/pennyKE/ npm --prefix dashboard run build
./scripts/deploy-detective.sh /path/to/Detective/.env
```

Phones: Settings → Device sync URL `https://detective.co.ke/pennyKE`
and the pairing token from the `.env` `SYNC_TOKEN` (or the generated one
printed by the deploy script).
