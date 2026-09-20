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
