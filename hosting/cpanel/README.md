# cPanel host bundle for detective.co.ke/pennyKE

Shared cPanel cannot run the Kotlin sync-server. This folder is a PHP + MySQL
port of the same household protocol:

- `POST /pennyKE/v1/sync` — phones (pairing token)
- `/pennyKE/v1/dashboard/*` — desktop SPA (pairing token + email/SMS OTP, then a session)
- tables `pennyke_changes` / `pennyke_meta` / `pennyke_auth_*` (safe beside OpenCart)

Dashboard login is two-step. The pairing token alone cannot read or edit the ledger in the browser. Phones keep using `Authorization: Bearer <pairing token>` on `/v1/sync`.

Set `TWO_FACTOR_EMAIL` (and optionally `TWO_FACTOR_PHONE` plus Africa's Talking `AFRICASTALKING_USERNAME` / `AFRICASTALKING_API_KEY`, or `SMS_URL`) in the deploy `.env`. Never commit those values.

Deploy:

```bash
PENNYKE_BASE=/pennyKE/ npm --prefix dashboard run build
./scripts/deploy-detective.sh /path/to/Detective/.env
```

Phones: Settings → Device sync URL `https://detective.co.ke/pennyKE`
and the pairing token from the `.env` `SYNC_TOKEN` (or the generated one
printed by the deploy script).
