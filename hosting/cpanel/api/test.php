#!/usr/bin/env php
<?php
declare(strict_types=1);

require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/Store.php';
require_once __DIR__ . '/Dashboard.php';
require_once __DIR__ . '/Auth.php';

function assert_true(bool $cond, string $msg): void
{
    if (!$cond) {
        fwrite(STDERR, "FAIL: $msg\n");
        exit(1);
    }
}

$sqlite = sys_get_temp_dir() . '/pennyke-test-' . uniqid() . '.sqlite';
$config = [
    'sqlite_path' => $sqlite,
    'table_prefix' => 'pennyke_',
    'sync_token' => 'secret-token',
];
$pdo = pdo_connect($config);
$store = new PennyKeStore($pdo, $config);
$dashboard = new PennyKeDashboard($store);

$day = (new DateTimeImmutable('now'))->format('Y-m') . '-05T10:00:00';
$store->sync([
    'device_id' => 'phone-a',
    'base_revision' => 0,
    'upserts' => [
        [
            'type' => 'transactions',
            'key' => 'hash-kes',
            'updated_at' => $day,
            'payload' => [
                'transactionHash' => 'hash-kes',
                'amount' => '500.00',
                'currency' => 'KES',
                'merchantName' => 'Naivas',
                'category' => 'Food',
                'transactionType' => 'EXPENSE',
                'dateTime' => $day,
                'bankName' => 'M-PESA',
                'accountNumber' => '1234',
                'smsBody' => 'Confirmed. KES 500.00 paid to Naivas.',
                'smsSender' => 'MPESA',
            ],
        ],
        [
            'type' => 'transactions',
            'key' => 'hash-inr',
            'updated_at' => $day,
            'payload' => [
                'transactionHash' => 'hash-inr',
                'amount' => '100.00',
                'currency' => 'INR',
                'merchantName' => 'Swiggy',
                'category' => 'Food',
                'transactionType' => 'EXPENSE',
                'dateTime' => $day,
                'bankName' => 'HDFC',
                'accountNumber' => '4321',
            ],
        ],
    ],
]);

$summary = $dashboard->summary();
$expenseMap = $summary['expense'];
$expense = is_array($expenseMap) ? $expenseMap : get_object_vars($expenseMap);
assert_true(($expense['KES'] ?? null) === '500.00', 'KES expense');
assert_true(($expense['INR'] ?? null) === '100.00', 'INR expense');
assert_true(!isset($expense['total']), 'no mixed total');
$got = $dashboard->getTransaction('hash-kes');
assert_true(($got['smsBody'] ?? null) === 'Confirmed. KES 500.00 paid to Naivas.', 'sms body');

$updated = $dashboard->updateTransaction('hash-kes', [
    'category' => 'Transport',
    'merchantName' => 'Shell',
]);
assert_true($updated['transaction']['merchantName'] === 'Shell', 'edit merchant');

$pull = $store->sync(['device_id' => 'phone-b', 'base_revision' => 0]);
$found = null;
foreach ($pull['upserts'] as $row) {
    if ($row['key'] === 'hash-kes') {
        $found = $row['payload'];
    }
}
assert_true(($found['merchantName'] ?? null) === 'Shell', 'phone-b sees edit');

$created = $dashboard->createTransaction([
    'amount' => '75.50',
    'merchantName' => 'Java House',
    'category' => 'Food & Dining',
    'transactionType' => 'EXPENSE',
    'dateTime' => '2026-09-20T08:00:00',
    'currency' => 'KES',
]);
assert_true($created['hash'] !== '', 'manual hash');
$dashboard->deleteTransaction($created['hash']);
assert_true($store->latestByKey('transactions', $created['hash']) === null, 'tombstone');

$sent = [];
$config['two_factor_email'] = 'otp@example.test';
$config['two_factor_phone'] = '+254711111111';
$config['sms_url'] = 'http://127.0.0.1:9/unused';
$auth = new PennyKeAuth($pdo, $config, function (string $channel, string $destination, string $code) use (&$sent): void {
    $sent[] = ['channel' => $channel, 'destination' => $destination, 'code' => $code];
});
$status = $auth->status();
assert_true($status['channels'] === ['email', 'sms'], '2FA channels');
assert_true(is_string($status['emailHint']) && str_contains($status['emailHint'], '*'), 'email hint masked');
$login = $auth->startLogin('secret-token', 'email', '127.0.0.1');
assert_true(($login['channel'] ?? '') === 'email', 'email challenge');
assert_true(count($sent) === 1 && preg_match('/^[0-9]{6}$/', $sent[0]['code']) === 1, 'otp sent');
assert_true($auth->sessionValid('secret-token') === false, 'pairing token is not a dashboard session');
$verified = $auth->verify($login['challengeId'], $sent[0]['code'], '127.0.0.1');
assert_true(($verified['sessionToken'] ?? '') !== '', 'session issued');
assert_true($auth->sessionValid($verified['sessionToken']) === true, 'session valid');
$smsLogin = $auth->startLogin('secret-token', 'sms', '127.0.0.1');
assert_true($sent[1]['channel'] === 'sms', 'sms otp sent');
assert_true($smsLogin['channel'] === 'sms', 'sms challenge');

@unlink($sqlite);
fwrite(STDOUT, "PHP API tests passed\n");
