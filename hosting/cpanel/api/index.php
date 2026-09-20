<?php
declare(strict_types=1);

require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/Store.php';
require_once __DIR__ . '/Dashboard.php';

header('X-Content-Type-Options: nosniff');

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$uri = $_SERVER['REQUEST_URI'] ?? '/';
$path = parse_url($uri, PHP_URL_PATH) ?: '/';
$path = preg_replace('#^/pennyKE#i', '', $path) ?: '/';
$path = preg_replace('#^/api#', '', $path) ?: $path;
$path = '/' . ltrim($path, '/');

if ($method === 'OPTIONS') {
    header('Allow: GET, POST, PUT, DELETE, OPTIONS');
    http_response_code(204);
    exit;
}

try {
    $config = pennyke_config();
    $pdo = pdo_connect($config);
    $store = new PennyKeStore($pdo, $config);
    $dashboard = new PennyKeDashboard($store);
    $token = (string) $config['sync_token'];

    if ($path === '/v1/health' && $method === 'GET') {
        json_ok(['status' => 'ok']);
    }

    if ($path === '/v1/sync' && $method === 'POST') {
        require_token($token);
        json_ok($store->sync(request_json()));
    }

    if ($path === '/v1/dashboard/summary' && $method === 'GET') {
        require_token($token);
        json_ok($dashboard->summary());
    }

    if ($path === '/v1/dashboard/transactions' && $method === 'GET') {
        require_token($token);
        json_ok(['transactions' => $dashboard->listTransactions(
            $_GET['q'] ?? null,
            $_GET['type'] ?? null,
            $_GET['account'] ?? null,
            $_GET['from'] ?? null,
            $_GET['to'] ?? null
        )]);
    }

    if ($path === '/v1/dashboard/transactions' && $method === 'POST') {
        require_token($token);
        json_ok($dashboard->createTransaction(request_json()), 201);
    }

    if (preg_match('#^/v1/dashboard/transactions/([^/]+)$#', $path, $m)) {
        require_token($token);
        $hash = rawurldecode($m[1]);
        if ($method === 'GET') {
            json_ok($dashboard->getTransaction($hash));
        }
        if ($method === 'PUT') {
            json_ok($dashboard->updateTransaction($hash, request_json()));
        }
        if ($method === 'DELETE') {
            $revision = $dashboard->deleteTransaction($hash);
            json_ok(['revision' => (string) $revision, 'hash' => $hash]);
        }
    }

    if ($path === '/v1/dashboard/accounts' && $method === 'GET') {
        require_token($token);
        json_ok(['accounts' => $dashboard->accounts()]);
    }

    if ($path === '/v1/dashboard/categories' && $method === 'GET') {
        require_token($token);
        json_ok(['names' => $dashboard->categories()]);
    }

    json_error(404, 'not found');
} catch (Throwable $e) {
    json_error(500, $e->getMessage());
}
