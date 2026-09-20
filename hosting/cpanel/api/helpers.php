<?php
declare(strict_types=1);

function pennyke_config(): array
{
    static $config = null;
    if ($config !== null) {
        return $config;
    }
    $path = __DIR__ . '/config.php';
    if (!is_file($path)) {
        json_error(500, 'config.php missing');
    }
    $loaded = require $path;
    if (!is_array($loaded)) {
        json_error(500, 'invalid config');
    }
    $config = $loaded + [
        'db_host' => 'localhost',
        'db_name' => '',
        'db_user' => '',
        'db_pass' => '',
        'db_charset' => 'utf8mb4',
        'table_prefix' => 'pennyke_',
        'sync_token' => '',
        'sqlite_path' => '',
        'two_factor_email' => '',
        'two_factor_phone' => '',
        'mail_from' => 'noreply@detective.co.ke',
        'sms_username' => '',
        'sms_api_key' => '',
        'sms_url' => '',
        'public_path' => '/pennyKE',
    ];
    return $config;
}

function json_error(int $status, string $message): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => $message], JSON_UNESCAPED_SLASHES);
    exit;
}

function json_ok(mixed $body, int $status = 200): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

function request_json(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') {
        return [];
    }
    $decoded = json_decode($raw, true);
    if (!is_array($decoded)) {
        json_error(400, 'invalid json');
    }
    return $decoded;
}

function bearer_token(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        $header = $headers['Authorization'] ?? $headers['authorization'] ?? '';
    }
    return trim(preg_replace('/^Bearer\s+/i', '', $header) ?? '');
}

function require_token(string $expected): void
{
    $got = bearer_token();
    if ($expected === '' || $got === '' || !hash_equals($expected, $got)) {
        json_error(401, 'unauthorized');
    }
}

function require_dashboard(PennyKeAuth $auth): void
{
    if (!$auth->sessionValid(session_token())) {
        json_error(401, 'unauthorized');
    }
}

function session_token(): string
{
    $bearer = bearer_token();
    if ($bearer !== '') {
        return $bearer;
    }
    return trim((string) ($_COOKIE['pennyke_session'] ?? ''));
}

function client_ip(): string
{
    $forwarded = trim((string) ($_SERVER['HTTP_X_FORWARDED_FOR'] ?? ''));
    if ($forwarded !== '') {
        $first = trim(explode(',', $forwarded)[0]);
        if (filter_var($first, FILTER_VALIDATE_IP)) {
            return $first;
        }
    }
    $ip = trim((string) ($_SERVER['REMOTE_ADDR'] ?? ''));
    return $ip !== '' ? $ip : '0.0.0.0';
}

function mask_email(string $email): string
{
    $email = trim($email);
    $at = strpos($email, '@');
    if ($at === false) {
        return '***';
    }
    $user = substr($email, 0, $at);
    $domain = substr($email, $at + 1);
    $userMask = substr($user, 0, 1) . str_repeat('*', max(1, strlen($user) - 1));
    $dot = strpos($domain, '.');
    if ($dot === false) {
        return $userMask . '@***';
    }
    $name = substr($domain, 0, $dot);
    $rest = substr($domain, $dot);
    $domainMask = substr($name, 0, 1) . '***' . $rest;
    return $userMask . '@' . $domainMask;
}

function mask_phone(string $phone): string
{
    $digits = preg_replace('/\D+/', '', $phone) ?? '';
    if (strlen($digits) < 4) {
        return '***';
    }
    return '+' . str_repeat('*', max(0, strlen($digits) - 4)) . substr($digits, -4);
}

function pdo_connect(array $config): PDO
{
    if (!empty($config['sqlite_path'])) {
        $dir = dirname($config['sqlite_path']);
        if (!is_dir($dir)) {
            mkdir($dir, 0750, true);
        }
        $pdo = new PDO('sqlite:' . $config['sqlite_path']);
        $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        return $pdo;
    }
    $dsn = sprintf(
        'mysql:host=%s;dbname=%s;charset=%s',
        $config['db_host'],
        $config['db_name'],
        $config['db_charset'] ?? 'utf8mb4'
    );
    $pdo = new PDO($dsn, $config['db_user'], $config['db_pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    return $pdo;
}

function table(array $config, string $name): string
{
    return $config['table_prefix'] . $name;
}

function ensure_schema(PDO $pdo, array $config): void
{
    $changes = table($config, 'changes');
    $meta = table($config, 'meta');
    $driver = $pdo->getAttribute(PDO::ATTR_DRIVER_NAME);
    if ($driver === 'sqlite') {
        $pdo->exec("CREATE TABLE IF NOT EXISTS $changes (
            revision INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_type TEXT NOT NULL,
            stable_key TEXT NOT NULL,
            payload TEXT,
            deleted INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT,
            origin_device TEXT NOT NULL
        )");
        $pdo->exec("CREATE INDEX IF NOT EXISTS idx_{$changes}_lookup ON $changes(entity_type, stable_key, revision)");
        $pdo->exec("CREATE TABLE IF NOT EXISTS $meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        create_auth_tables_sqlite($pdo, $config);
        return;
    }
    $pdo->exec("CREATE TABLE IF NOT EXISTS `$changes` (
        revision BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
        entity_type VARCHAR(64) NOT NULL,
        stable_key VARCHAR(255) NOT NULL,
        payload LONGTEXT NULL,
        deleted TINYINT NOT NULL DEFAULT 0,
        updated_at VARCHAR(64) NULL,
        origin_device VARCHAR(128) NOT NULL,
        INDEX idx_lookup (entity_type, stable_key, revision)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS `$meta` (
        k VARCHAR(64) NOT NULL PRIMARY KEY,
        v VARCHAR(255) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    create_auth_tables_mysql($pdo, $config);
}

function create_auth_tables_sqlite(PDO $pdo, array $config): void
{
    $challenges = table($config, 'auth_challenges');
    $sessions = table($config, 'auth_sessions');
    $throttle = table($config, 'auth_throttle');
    $pdo->exec("CREATE TABLE IF NOT EXISTS $challenges (
        id TEXT PRIMARY KEY,
        code_hash TEXT NOT NULL,
        channel TEXT NOT NULL,
        expires_at INTEGER NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0,
        ip TEXT NOT NULL DEFAULT ''
    )");
    $pdo->exec("CREATE TABLE IF NOT EXISTS $sessions (
        token_hash TEXT PRIMARY KEY,
        expires_at INTEGER NOT NULL,
        ip TEXT NOT NULL DEFAULT '',
        created_at INTEGER NOT NULL
    )");
    $pdo->exec("CREATE TABLE IF NOT EXISTS $throttle (
        k TEXT PRIMARY KEY,
        window_start INTEGER NOT NULL,
        count INTEGER NOT NULL
    )");
}

function create_auth_tables_mysql(PDO $pdo, array $config): void
{
    $challenges = table($config, 'auth_challenges');
    $sessions = table($config, 'auth_sessions');
    $throttle = table($config, 'auth_throttle');
    $pdo->exec("CREATE TABLE IF NOT EXISTS `$challenges` (
        id VARCHAR(64) NOT NULL PRIMARY KEY,
        code_hash VARCHAR(128) NOT NULL,
        channel VARCHAR(16) NOT NULL,
        expires_at INT NOT NULL,
        attempts INT NOT NULL DEFAULT 0,
        ip VARCHAR(64) NOT NULL DEFAULT ''
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS `$sessions` (
        token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
        expires_at INT NOT NULL,
        ip VARCHAR(64) NOT NULL DEFAULT '',
        created_at INT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS `$throttle` (
        k VARCHAR(128) NOT NULL PRIMARY KEY,
        window_start INT NOT NULL,
        count INT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
}

function payload_str(?array $payload, string $key): ?string
{
    if ($payload === null || !array_key_exists($key, $payload) || $payload[$key] === null) {
        return null;
    }
    $value = $payload[$key];
    if (is_bool($value)) {
        return $value ? 'true' : 'false';
    }
    $text = trim((string) $value);
    return $text === '' ? null : $text;
}

function payload_bool(?array $payload, string $key): bool
{
    if ($payload === null || !array_key_exists($key, $payload) || $payload[$key] === null) {
        return false;
    }
    $value = $payload[$key];
    if (is_bool($value)) {
        return $value;
    }
    return strcasecmp((string) $value, 'true') === 0 || $value === 1 || $value === '1';
}
