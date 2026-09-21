#!/usr/bin/env python3
"""Upload the PennyKE cPanel bundle to detective.co.ke/pennyKE.

Reads a dotenv file (FTP/cPanel/MySQL). Never prints secret values.
"""
from __future__ import annotations

import argparse
import ftplib
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE_SRC = ROOT / "hosting" / "cpanel"
DASH_DIST = ROOT / "dashboard" / "dist"


def parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("'").strip('"')
        values[key] = value
    return values


def first(env: dict[str, str], *keys: str, default: str = "") -> str:
    lower = {k.lower(): v for k, v in env.items()}
    for key in keys:
        if key in env and env[key]:
            return env[key]
        if key.lower() in lower and lower[key.lower()]:
            return lower[key.lower()]
    return default


def write_config(dest: Path, env: dict[str, str], token: str) -> None:
    db_name = first(env, "DB_NAME", "MYSQL_DATABASE", "DATABASE_NAME", "DB_DATABASE")
    db_user = first(env, "DB_USER", "MYSQL_USER", "DATABASE_USER", "DB_USERNAME")
    db_pass = first(env, "DB_PASSWORD", "MYSQL_PASSWORD", "DATABASE_PASSWORD", "DB_PASS")
    db_host = first(env, "DB_HOST", "MYSQL_HOST", "DATABASE_HOST", default="localhost")
    def php_str(value: str) -> str:
        return json.dumps(value, ensure_ascii=False)
    email = first(env, "TWO_FACTOR_EMAIL", "2FA_EMAIL", "ADMIN_EMAIL")
    extra_email = Path("/tmp/pennyke-2fa-email")
    if not email and extra_email.is_file():
        email = extra_email.read_text(encoding="utf-8").strip()
    phone = first(env, "TWO_FACTOR_PHONE", "2FA_PHONE")
    mail_from = first(env, "MAIL_FROM", "SMTP_FROM", default="admin@detective.co.ke")
    smtp_host = first(env, "SMTP_HOST", default="mail.detective.co.ke")
    smtp_port = first(env, "SMTP_PORT", default="465")
    smtp_user = first(env, "SMTP_USER", "SMTP_USERNAME")
    smtp_pass = first(env, "SMTP_PASS", "SMTP_PASSWORD")
    sms_user = first(env, "AFRICASTALKING_USERNAME", "AT_USERNAME", "SMS_USERNAME")
    sms_key = first(env, "AFRICASTALKING_API_KEY", "AT_API_KEY", "SMS_API_KEY")
    sms_url = first(env, "SMS_URL", "TWO_FACTOR_SMS_URL")
    if not email and not phone:
        print("TWO_FACTOR_EMAIL (or TWO_FACTOR_PHONE) is required to lock the web portal.", file=sys.stderr)
        raise SystemExit(2)
    dest.write_text(
        "<?php\nreturn [\n"
        f"    'db_host' => {php_str(db_host)},\n"
        f"    'db_name' => {php_str(db_name)},\n"
        f"    'db_user' => {php_str(db_user)},\n"
        f"    'db_pass' => {php_str(db_pass)},\n"
        "    'table_prefix' => 'pennyke_',\n"
        f"    'sync_token' => {php_str(token)},\n"
        f"    'two_factor_email' => {php_str(email)},\n"
        f"    'two_factor_phone' => {php_str(phone)},\n"
        f"    'mail_from' => {php_str(mail_from)},\n"
        f"    'smtp_host' => {php_str(smtp_host)},\n"
        f"    'smtp_port' => {int(smtp_port or 465)},\n"
        f"    'smtp_user' => {php_str(smtp_user)},\n"
        f"    'smtp_pass' => {php_str(smtp_pass)},\n"
        f"    'sms_username' => {php_str(sms_user)},\n"
        f"    'sms_api_key' => {php_str(sms_key)},\n"
        f"    'sms_url' => {php_str(sms_url)},\n"
        "    'public_path' => '/pennyKE',\n"
        "];\n",
        encoding="utf-8",
    )
    dest.chmod(stat.S_IRUSR | stat.S_IWUSR)


def cpanel_opener(env: dict[str, str]):
    import http.cookiejar
    import ssl
    import urllib.parse
    import urllib.request

    user = first(env, "CPANEL_USER", "CPANEL_USERNAME")
    password = first(env, "CPANEL_PASSWORD", "CPANEL_PASS")
    if not user or not password:
        return None, ""
    ctx = ssl.create_default_context()
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar),
        urllib.request.HTTPSHandler(context=ctx),
    )
    payload = urllib.parse.urlencode({"user": user, "pass": password}).encode()
    req = urllib.request.Request("https://detective.co.ke:2083/login/?login_only=1", data=payload)
    with opener.open(req, timeout=20) as resp:
        body = json.loads(resp.read().decode("utf-8", "replace"))
    token = body.get("security_token") or ""
    if body.get("status") != 1 or not token:
        raise RuntimeError("cPanel login failed")
    return opener, token


def ensure_smtp_mailbox(env: dict[str, str]) -> dict[str, str]:
    """Create pennyke@<domain> if needed and return SMTP settings. Never prints secrets."""
    if first(env, "SMTP_USER", "SMTP_USERNAME") and first(env, "SMTP_PASS", "SMTP_PASSWORD"):
        return {}
    secret_file = Path("/tmp/pennyke-smtp.pass")
    password = secret_file.read_text(encoding="utf-8").strip() if secret_file.is_file() else secrets.token_urlsafe(18)
    try:
        opener, token = cpanel_opener(env)
    except Exception as exc:
        print(f"cPanel mail setup skipped: {type(exc).__name__}")
        return {}
    if opener is None:
        return {}
    import urllib.parse
    import urllib.request

    domain = "detective.co.ke"
    local = "pennyke"
    mailbox = f"{local}@{domain}"
    listed = opener.open(
        f"https://detective.co.ke:2083{token}/execute/Email/list_pops", timeout=20
    )
    accounts = json.loads(listed.read().decode("utf-8", "replace"))
    emails = [row.get("email") for row in (accounts.get("data") or []) if isinstance(row, dict)]
    if mailbox not in emails:
        qs = urllib.parse.urlencode(
            {"email": local, "password": password, "quota": 250, "domain": domain}
        )
        created = opener.open(
            f"https://detective.co.ke:2083{token}/execute/Email/add_pop?{qs}", timeout=20
        )
        result = json.loads(created.read().decode("utf-8", "replace"))
        if not result.get("status"):
            print("cPanel add_pop did not succeed; falling back to PHP mail()")
            return {}
        print("Created domain mailbox for OTP mail")
    else:
        qs = urllib.parse.urlencode(
            {"email": local, "password": password, "domain": domain}
        )
        changed = opener.open(
            f"https://detective.co.ke:2083{token}/execute/Email/passwd_pop?{qs}", timeout=20
        )
        result = json.loads(changed.read().decode("utf-8", "replace"))
        if not result.get("status"):
            print("Existing mailbox password was left unchanged")
            if not secret_file.is_file():
                return {}
        else:
            print("Updated domain mailbox password for OTP mail")
    secret_file.write_text(password + "\n", encoding="utf-8")
    secret_file.chmod(stat.S_IRUSR | stat.S_IWUSR)
    return {
        "MAIL_FROM": mailbox,
        "SMTP_HOST": first(env, "SMTP_HOST", default="mail.detective.co.ke") or "mail.detective.co.ke",
        "SMTP_PORT": first(env, "SMTP_PORT", default="465") or "465",
        "SMTP_USER": mailbox,
        "SMTP_PASS": password,
    }


def iter_files(root: Path):
    for path in root.rglob("*"):
        if path.is_file():
            yield path


def ftp_mkdirs(ftp: ftplib.FTP, remote_dir: str) -> None:
    parts = [p for p in remote_dir.split("/") if p]
    cursor = ""
    for part in parts:
        cursor = f"{cursor}/{part}"
        try:
            ftp.mkd(cursor)
        except ftplib.error_perm:
            pass


def upload(ftp: ftplib.FTP, local_root: Path, remote_root: str) -> int:
    count = 0
    ftp_mkdirs(ftp, remote_root)
    for path in iter_files(local_root):
        rel = path.relative_to(local_root).as_posix()
        remote = f"{remote_root.rstrip('/')}/{rel}"
        ftp_mkdirs(ftp, str(Path(remote).parent).replace("\\", "/"))
        with path.open("rb") as handle:
            ftp.storbinary(f"STOR {remote}", handle)
        count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", nargs="?", help="Path to Detective/.env")
    args = parser.parse_args()

    candidates = []
    if args.env_file:
        candidates.append(Path(args.env_file))
    extra = os.environ.get("DETECTIVE_ENV")
    if extra:
        candidates.append(Path(extra))
    candidates.extend(
        [
            Path("/tmp/Detective.env"),
            Path.home() / "Detective" / ".env",
            ROOT / "Detective" / ".env",
            ROOT / ".env.detective",
            Path("/Detective/.env"),
        ]
    )
    env_path = next((p for p in candidates if p.is_file()), None)
    if env_path is None:
        print("No .env file found. Looked at:", file=sys.stderr)
        for path in candidates:
            print(f"  {path}", file=sys.stderr)
        return 2

    env = parse_env(env_path)
    print(f"Loaded env keys from {env_path.name}: {', '.join(sorted(env))}")

    ftp_host = first(
        env,
        "FTP_HOST",
        "FTP_SERVER",
        "CPANEL_HOST",
        "HOST",
        default="detective.co.ke",
    )
    ftp_user = first(env, "FTP_USER", "FTP_USERNAME", "CPANEL_USER", "CPANEL_USERNAME", "USER")
    ftp_pass = first(env, "FTP_PASSWORD", "FTP_PASS", "CPANEL_PASSWORD", "CPANEL_PASS", "PASSWORD")
    ftp_port = int(first(env, "FTP_PORT", default="21") or "21")
    remote_dir = first(
        env,
        "FTP_DIR",
        "FTP_PATH",
        "REMOTE_DIR",
        default="public_html/pennyKE",
    )
    token = first(env, "SYNC_TOKEN", "PAIRING_TOKEN", "PENNYKE_TOKEN") or secrets.token_hex(24)

    if not ftp_user or not ftp_pass:
        print("FTP_USER / FTP_PASSWORD (or CPANEL_USER / CPANEL_PASSWORD) missing.", file=sys.stderr)
        return 2
    if not first(env, "DB_NAME", "MYSQL_DATABASE", "DATABASE_NAME", "DB_DATABASE"):
        print("DB_NAME missing — need the cPanel MySQL database name.", file=sys.stderr)
        return 2

    print("Building dashboard with base /pennyKE/")
    env_build = os.environ.copy()
    env_build["PENNYKE_BASE"] = "/pennyKE/"
    subprocess.check_call(["npm", "run", "build"], cwd=ROOT / "dashboard", env=env_build)

    staging = ROOT / "build" / "pennyKE"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    shutil.copytree(BUNDLE_SRC, staging, dirs_exist_ok=True)
    if DASH_DIST.is_dir():
        for item in DASH_DIST.iterdir():
            dest = staging / item.name
            if item.is_dir():
                if dest.exists():
                    shutil.rmtree(dest)
                shutil.copytree(item, dest)
            else:
                shutil.copy2(item, dest)
    else:
        print("dashboard/dist missing — run PENNYKE_BASE=/pennyKE/ npm --prefix dashboard run build", file=sys.stderr)
        return 2

    mail_settings = ensure_smtp_mailbox(env)
    env = {**env, **mail_settings}
    write_config(staging / "api" / "config.php", env, token)
    # Do not upload examples or the PHP unit runner.
    for extra_file in [
        staging / "api" / "config.example.php",
        staging / "api" / "test.php",
        staging / "config.example.php",
        staging / "README.md",
    ]:
        if extra_file.exists():
            extra_file.unlink()

    print(f"Connecting FTP {ftp_host}:{ftp_port} as {ftp_user}")
    ftp = ftplib.FTP()
    ftp.connect(ftp_host, ftp_port, timeout=30)
    ftp.login(ftp_user, ftp_pass)
    ftp.set_pasv(True)
    try:
        uploaded = upload(ftp, staging, remote_dir if remote_dir.startswith("/") else remote_dir)
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()

    print(f"Uploaded {uploaded} files to {remote_dir}")
    print("Site: https://detective.co.ke/pennyKE/")
    print(f"Pairing token length: {len(token)} (not printed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
