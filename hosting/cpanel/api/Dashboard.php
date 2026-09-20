<?php
declare(strict_types=1);

require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/Store.php';

final class PennyKeDashboard
{
    private const DEFAULT_CATEGORIES = [
        'Food & Dining', 'Groceries', 'Transport', 'Shopping', 'Bills & Utilities',
        'Entertainment', 'Health', 'Others', 'Income', 'Transfer',
    ];

    public function __construct(private PennyKeStore $store)
    {
    }

    public function summary(): array
    {
        $transactions = array_values(array_filter(
            $this->allTransactions(),
            fn(array $tx) => empty($tx['excludedFromAnalytics'])
        ));
        $month = (new DateTimeImmutable('now'))->format('Y-m');
        $inMonth = array_values(array_filter(
            $transactions,
            fn(array $tx) => str_starts_with((string) $tx['dateTime'], $month)
        ));
        $income = $this->totalsByCurrency(array_filter(
            $inMonth,
            fn(array $tx) => $tx['transactionType'] === 'INCOME'
        ));
        $expense = $this->totalsByCurrency(array_filter(
            $inMonth,
            fn(array $tx) => $tx['transactionType'] === 'EXPENSE' || $tx['transactionType'] === 'CREDIT'
        ));
        usort($transactions, fn($a, $b) => strcmp($b['dateTime'], $a['dateTime']));
        return [
            'income' => (object) $income,
            'expense' => (object) $expense,
            'accounts' => $this->accounts(),
            'recent' => array_slice($transactions, 0, 8),
        ];
    }

    public function listTransactions(?string $query, ?string $type, ?string $account, ?string $from, ?string $to): array
    {
        $fromDt = $this->parseBound($from, true);
        $toDt = $this->parseBound($to, false);
        $q = strtolower(trim((string) $query));
        $items = [];
        foreach ($this->allTransactions() as $tx) {
            if ($q !== '') {
                $hay = strtolower($tx['merchantName'] . ' ' . $tx['category'] . ' ' . ($tx['description'] ?? '') . ' ' . ($tx['bankName'] ?? ''));
                if (!str_contains($hay, $q)) {
                    continue;
                }
            }
            if ($type !== null && $type !== '' && strcasecmp($tx['transactionType'], $type) !== 0) {
                continue;
            }
            if ($account !== null && $account !== '' && $this->accountKey($tx['bankName'], $tx['accountNumber']) !== $account) {
                continue;
            }
            if ($fromDt !== null && $tx['dateTime'] < $fromDt) {
                continue;
            }
            if ($toDt !== null && $tx['dateTime'] > $toDt) {
                continue;
            }
            $items[] = $tx;
        }
        usort($items, fn($a, $b) => strcmp($b['dateTime'], $a['dateTime']));
        return $items;
    }

    public function getTransaction(string $hash): array
    {
        $entity = $this->store->latestByKey('transactions', $hash);
        $tx = $entity ? $this->toTransaction($entity) : null;
        if ($tx === null) {
            json_error(404, 'transaction not found');
        }
        return $tx;
    }

    public function updateTransaction(string $hash, array $patch): array
    {
        $existing = $this->store->latestByKey('transactions', $hash);
        if ($existing === null) {
            json_error(404, 'transaction not found');
        }
        $now = date('Y-m-d\\TH:i:s');
        $merged = $existing['payload'];
        foreach (['amount', 'currency', 'merchantName', 'category', 'transactionType', 'dateTime'] as $field) {
            if (array_key_exists($field, $patch) && $patch[$field] !== null) {
                $merged[$field] = $patch[$field];
            }
        }
        foreach (['description', 'bankName', 'accountNumber', 'fromAccount', 'toAccount'] as $field) {
            if (array_key_exists($field, $patch)) {
                $merged[$field] = $patch[$field];
            }
        }
        if (array_key_exists('excludedFromAnalytics', $patch) && $patch['excludedFromAnalytics'] !== null) {
            $merged['excludedFromAnalytics'] = (bool) $patch['excludedFromAnalytics'];
        }
        $merged['updatedAt'] = $now;
        $merged['id'] = 0;
        $entity = [
            'type' => 'transactions',
            'key' => $hash,
            'payload' => $merged,
            'updated_at' => $now,
        ];
        $revision = $this->store->applyLocal([
            'device_id' => 'pennyke-web',
            'upserts' => [$entity],
        ]);
        $tx = $this->toTransaction($entity);
        if ($tx === null) {
            json_error(500, 'invalid payload');
        }
        return ['revision' => $revision, 'hash' => $hash, 'transaction' => $tx];
    }

    public function createTransaction(array $body): array
    {
        $amount = trim((string) ($body['amount'] ?? ''));
        $merchant = trim((string) ($body['merchantName'] ?? ''));
        $dateTime = trim((string) ($body['dateTime'] ?? ''));
        if ($amount === '' || $merchant === '' || $dateTime === '') {
            json_error(400, 'amount, merchantName, and dateTime are required');
        }
        if (!is_numeric($amount) || (float) $amount <= 0) {
            json_error(400, 'amount must be positive');
        }
        $hash = md5("MANUAL_{$amount}_{$merchant}_{$dateTime}");
        if ($this->store->latestByKey('transactions', $hash) !== null) {
            json_error(409, 'transaction already exists');
        }
        $now = date('Y-m-d\\TH:i:s');
        $payload = [
            'id' => 0,
            'amount' => $amount,
            'merchantName' => $merchant,
            'category' => trim((string) ($body['category'] ?? '')) ?: 'Others',
            'transactionType' => trim((string) ($body['transactionType'] ?? '')) ?: 'EXPENSE',
            'dateTime' => $dateTime,
            'description' => $body['description'] ?? null,
            'smsBody' => null,
            'bankName' => trim((string) ($body['bankName'] ?? '')) ?: 'Manual Entry',
            'smsSender' => null,
            'accountNumber' => $body['accountNumber'] ?? null,
            'transactionHash' => $hash,
            'isRecurring' => false,
            'isDeleted' => false,
            'excludedFromAnalytics' => (bool) ($body['excludedFromAnalytics'] ?? false),
            'createdAt' => $now,
            'updatedAt' => $now,
            'currency' => trim((string) ($body['currency'] ?? '')) ?: 'KES',
            'fromAccount' => $body['fromAccount'] ?? null,
            'toAccount' => $body['toAccount'] ?? null,
        ];
        $entity = [
            'type' => 'transactions',
            'key' => $hash,
            'payload' => $payload,
            'updated_at' => $now,
        ];
        $revision = $this->store->applyLocal([
            'device_id' => 'pennyke-web',
            'upserts' => [$entity],
        ]);
        $tx = $this->toTransaction($entity);
        return ['revision' => $revision, 'hash' => $hash, 'transaction' => $tx];
    }

    public function deleteTransaction(string $hash): int
    {
        if ($this->store->latestByKey('transactions', $hash) === null) {
            json_error(404, 'transaction not found');
        }
        return $this->store->applyLocal([
            'device_id' => 'pennyke-web',
            'deletes' => [['type' => 'transactions', 'key' => $hash]],
        ]);
    }

    public function accounts(): array
    {
        $txs = $this->allTransactions();
        $counts = [];
        foreach ($txs as $tx) {
            $key = $this->accountKey($tx['bankName'], $tx['accountNumber']);
            $counts[$key] = ($counts[$key] ?? 0) + 1;
        }
        $byAccount = [];
        $latestBalances = [];
        foreach ($this->store->latestLive('account_balances') as $entity) {
            $bank = payload_str($entity['payload'], 'bankName');
            $last4 = payload_str($entity['payload'], 'accountLast4');
            if ($bank === null || $last4 === null) {
                continue;
            }
            $key = $this->accountKey($bank, $last4);
            $timestamp = payload_str($entity['payload'], 'timestamp') ?? '';
            if (!isset($latestBalances[$key]) || $timestamp > $latestBalances[$key][0]) {
                $latestBalances[$key] = [$timestamp, [
                    'bankName' => $bank,
                    'accountLast4' => $last4,
                    'balance' => payload_str($entity['payload'], 'balance'),
                    'currency' => payload_str($entity['payload'], 'currency') ?? 'KES',
                    'alias' => payload_str($entity['payload'], 'alias'),
                    'isCreditCard' => payload_bool($entity['payload'], 'isCreditCard'),
                    'transactionCount' => $counts[$key] ?? 0,
                ]];
            }
        }
        foreach ($latestBalances as $key => [$ts, $account]) {
            $byAccount[$key] = $account;
        }
        foreach ($this->store->latestLive('cards') as $entity) {
            $bank = payload_str($entity['payload'], 'bankName');
            $last4 = payload_str($entity['payload'], 'cardLast4');
            if ($bank === null || $last4 === null) {
                continue;
            }
            $key = $this->accountKey($bank, $last4);
            if (!isset($byAccount[$key])) {
                $cardType = payload_str($entity['payload'], 'cardType') ?? '';
                $byAccount[$key] = [
                    'bankName' => $bank,
                    'accountLast4' => $last4,
                    'balance' => payload_str($entity['payload'], 'currentBalance') ?? payload_str($entity['payload'], 'balance'),
                    'currency' => payload_str($entity['payload'], 'currency') ?? 'KES',
                    'alias' => payload_str($entity['payload'], 'nickname'),
                    'isCreditCard' => str_contains(strtoupper($cardType), 'CREDIT'),
                    'transactionCount' => $counts[$key] ?? 0,
                ];
            }
        }
        foreach ($txs as $tx) {
            $last4 = $tx['accountNumber'];
            if ($last4 === null || $last4 === '') {
                continue;
            }
            $bank = $tx['bankName'] ?? 'Unknown';
            $key = $this->accountKey($bank, $last4);
            if (!isset($byAccount[$key])) {
                $byAccount[$key] = [
                    'bankName' => $bank,
                    'accountLast4' => $last4,
                    'balance' => null,
                    'currency' => $tx['currency'],
                    'alias' => null,
                    'isCreditCard' => false,
                    'transactionCount' => $counts[$key] ?? 0,
                ];
            }
        }
        $accounts = array_values($byAccount);
        usort($accounts, fn($a, $b) => [strtolower($a['bankName']), $a['accountLast4']] <=> [strtolower($b['bankName']), $b['accountLast4']]);
        return $accounts;
    }

    public function categories(): array
    {
        $names = self::DEFAULT_CATEGORIES;
        foreach ($this->store->latestLive('categories') as $entity) {
            $name = payload_str($entity['payload'], 'name');
            if ($name) {
                $names[] = $name;
            }
        }
        foreach ($this->allTransactions() as $tx) {
            $names[] = $tx['category'];
        }
        $names = array_values(array_unique(array_filter(array_map('trim', $names))));
        sort($names, SORT_NATURAL | SORT_FLAG_CASE);
        return $names;
    }

    private function allTransactions(): array
    {
        $items = [];
        foreach ($this->store->latestLive('transactions') as $entity) {
            $tx = $this->toTransaction($entity);
            if ($tx !== null) {
                $items[] = $tx;
            }
        }
        return $items;
    }

    private function totalsByCurrency(array $items): array
    {
        $sums = [];
        foreach ($items as $item) {
            $currency = $item['currency'];
            $amount = is_numeric($item['amount']) ? (float) $item['amount'] : 0.0;
            $sums[$currency] = ($sums[$currency] ?? 0.0) + $amount;
        }
        foreach ($sums as $currency => $value) {
            $sums[$currency] = number_format($value, 2, '.', '');
        }
        return $sums;
    }

    private function toTransaction(array $entity): ?array
    {
        $payload = $entity['payload'];
        $amount = payload_str($payload, 'amount');
        $merchant = payload_str($payload, 'merchantName');
        $dateTime = payload_str($payload, 'dateTime');
        if ($amount === null || $merchant === null || $dateTime === null) {
            return null;
        }
        return [
            'hash' => payload_str($payload, 'transactionHash') ?? $entity['key'],
            'amount' => $amount,
            'currency' => payload_str($payload, 'currency') ?? 'KES',
            'merchantName' => $merchant,
            'description' => payload_str($payload, 'description'),
            'category' => payload_str($payload, 'category') ?? 'Others',
            'transactionType' => payload_str($payload, 'transactionType') ?? 'EXPENSE',
            'dateTime' => $dateTime,
            'bankName' => payload_str($payload, 'bankName'),
            'accountNumber' => payload_str($payload, 'accountNumber'),
            'fromAccount' => payload_str($payload, 'fromAccount'),
            'toAccount' => payload_str($payload, 'toAccount'),
            'excludedFromAnalytics' => payload_bool($payload, 'excludedFromAnalytics'),
            'updatedAt' => payload_str($payload, 'updatedAt') ?? $entity['updated_at'],
        ];
    }

    private function accountKey(?string $bank, ?string $last4): string
    {
        return ($bank ?? '') . '||' . ($last4 ?? '');
    }

    private function parseBound(?string $raw, bool $startOfDay): ?string
    {
        if ($raw === null || trim($raw) === '') {
            return null;
        }
        $trimmed = trim($raw);
        if (strlen($trimmed) === 10) {
            return $startOfDay ? $trimmed . 'T00:00:00' : $trimmed . 'T23:59:59';
        }
        return $trimmed;
    }
}
