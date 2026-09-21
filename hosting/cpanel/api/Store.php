<?php
declare(strict_types=1);

require_once __DIR__ . '/helpers.php';

final class PennyKeStore
{
    public function __construct(private PDO $pdo, private array $config)
    {
        ensure_schema($this->pdo, $this->config);
    }

    public function sync(array $request): array
    {
        $deviceId = trim((string) ($request['device_id'] ?? ''));
        if ($deviceId === '') {
            json_error(400, 'device_id required');
        }
        $this->pdo->beginTransaction();
        try {
            $this->applyUpserts($deviceId, $request['upserts'] ?? []);
            $this->applyDeletes($deviceId, $request['deletes'] ?? []);
            if (isset($request['preference_patch']) && is_array($request['preference_patch'])) {
                $this->applyPreferencePatch($deviceId, $request['preference_patch']);
            }
            $revision = $this->currentRevision();
            $inbound = $this->changesSince((int) ($request['base_revision'] ?? 0), $deviceId);
            $this->pdo->commit();
            $inbound['revision'] = $revision;
            return $inbound;
        } catch (Throwable $e) {
            $this->pdo->rollBack();
            throw $e;
        }
    }

    public function applyLocal(array $request): int
    {
        $deviceId = trim((string) ($request['device_id'] ?? 'pennyke-web'));
        $this->pdo->beginTransaction();
        try {
            $this->applyUpserts($deviceId, $request['upserts'] ?? []);
            $this->applyDeletes($deviceId, $request['deletes'] ?? []);
            $revision = $this->currentRevision();
            $this->pdo->commit();
            return $revision;
        } catch (Throwable $e) {
            $this->pdo->rollBack();
            throw $e;
        }
    }

    public function latestLive(string $type): array
    {
        $table = table($this->config, 'changes');
        $sql = "SELECT c.stable_key, c.payload, c.updated_at
                FROM $table c
                INNER JOIN (
                    SELECT entity_type, stable_key, MAX(revision) AS max_rev
                    FROM $table
                    WHERE entity_type = ?
                    GROUP BY entity_type, stable_key
                ) latest
                  ON c.entity_type = latest.entity_type
                 AND c.stable_key = latest.stable_key
                 AND c.revision = latest.max_rev
                WHERE c.deleted = 0 AND c.payload IS NOT NULL";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([$type]);
        $items = [];
        foreach ($stmt as $row) {
            $payload = json_decode((string) $row['payload'], true);
            if (!is_array($payload)) {
                continue;
            }
            $items[] = [
                'type' => $type,
                'key' => $row['stable_key'],
                'payload' => $payload,
                'updated_at' => $row['updated_at'],
            ];
        }
        return $items;
    }

    public function latestByKey(string $type, string $key): ?array
    {
        $row = $this->latestRow($type, $key);
        if ($row === null || (int) $row['deleted'] === 1 || $row['payload'] === null) {
            return null;
        }
        $payload = json_decode((string) $row['payload'], true);
        if (!is_array($payload)) {
            return null;
        }
        return [
            'type' => $type,
            'key' => $key,
            'payload' => $payload,
            'updated_at' => $row['updated_at'],
        ];
    }

    private function applyUpserts(string $deviceId, array $upserts): void
    {
        foreach ($upserts as $entity) {
            if (!is_array($entity)) {
                continue;
            }
            $type = (string) ($entity['type'] ?? '');
            $key = (string) ($entity['key'] ?? '');
            $payload = $entity['payload'] ?? null;
            $updatedAt = $entity['updated_at'] ?? null;
            if ($type === '' || $key === '' || !is_array($payload)) {
                continue;
            }
            $encoded = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
            $latest = $this->latestRow($type, $key);
            if ($latest !== null && (int) $latest['deleted'] === 0 && $latest['payload'] === $encoded) {
                continue;
            }
            if ($latest !== null && (int) $latest['deleted'] === 0 && $this->isStale($updatedAt, $latest['updated_at'])) {
                continue;
            }
            $this->insertChange($type, $key, $encoded, false, $updatedAt, $deviceId);
        }
    }

    private function applyDeletes(string $deviceId, array $deletes): void
    {
        foreach ($deletes as $tombstone) {
            if (!is_array($tombstone)) {
                continue;
            }
            $type = (string) ($tombstone['type'] ?? '');
            $key = (string) ($tombstone['key'] ?? '');
            if ($type === '' || $key === '') {
                continue;
            }
            $latest = $this->latestRow($type, $key);
            if ($latest !== null && (int) $latest['deleted'] === 1) {
                continue;
            }
            $this->insertChange($type, $key, null, true, null, $deviceId);
        }
    }

    private function applyPreferencePatch(string $deviceId, array $patch): void
    {
        $encoded = json_encode($patch, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $latest = $this->latestRow('preferences', 'global');
        if ($latest !== null && (int) $latest['deleted'] === 0 && $latest['payload'] === $encoded) {
            return;
        }
        $this->insertChange('preferences', 'global', $encoded, false, null, $deviceId);
    }

    private function changesSince(int $baseRevision, string $deviceId): array
    {
        $table = table($this->config, 'changes');
        $sql = "SELECT c.entity_type, c.stable_key, c.payload, c.deleted, c.updated_at
                FROM $table c
                INNER JOIN (
                    SELECT entity_type, stable_key, MAX(revision) AS max_rev
                    FROM $table
                    WHERE revision > ?
                    GROUP BY entity_type, stable_key
                ) latest
                  ON c.entity_type = latest.entity_type
                 AND c.stable_key = latest.stable_key
                 AND c.revision = latest.max_rev
                WHERE c.origin_device <> ?
                ORDER BY c.revision ASC";
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute([$baseRevision, $deviceId]);
        $upserts = [];
        $deletes = [];
        $preferencePatch = null;
        foreach ($stmt as $row) {
            $type = $row['entity_type'];
            $key = $row['stable_key'];
            $deleted = (int) $row['deleted'] === 1;
            if ($type === 'preferences') {
                if (!$deleted && $row['payload'] !== null) {
                    $preferencePatch = json_decode((string) $row['payload'], true);
                }
                continue;
            }
            if ($deleted) {
                $deletes[] = ['type' => $type, 'key' => $key];
            } elseif ($row['payload'] !== null) {
                $payload = json_decode((string) $row['payload'], true);
                if (is_array($payload)) {
                    $upserts[] = [
                        'type' => $type,
                        'key' => $key,
                        'payload' => $payload,
                        'updated_at' => $row['updated_at'],
                    ];
                }
            }
        }
        return [
            'revision' => 0,
            'upserts' => $upserts,
            'deletes' => $deletes,
            'preference_patch' => $preferencePatch,
        ];
    }

    private function currentRevision(): int
    {
        $table = table($this->config, 'changes');
        $value = $this->pdo->query("SELECT COALESCE(MAX(revision), 0) FROM $table")->fetchColumn();
        return (int) $value;
    }

    private function latestRow(string $type, string $key): ?array
    {
        $table = table($this->config, 'changes');
        $stmt = $this->pdo->prepare(
            "SELECT payload, deleted, updated_at FROM $table
             WHERE entity_type = ? AND stable_key = ?
             ORDER BY revision DESC LIMIT 1"
        );
        $stmt->execute([$type, $key]);
        $row = $stmt->fetch();
        return $row === false ? null : $row;
    }

    private function insertChange(
        string $type,
        string $key,
        ?string $payload,
        bool $deleted,
        ?string $updatedAt,
        string $originDevice
    ): void {
        $table = table($this->config, 'changes');
        $stmt = $this->pdo->prepare(
            "INSERT INTO $table (entity_type, stable_key, payload, deleted, updated_at, origin_device)
             VALUES (?, ?, ?, ?, ?, ?)"
        );
        $stmt->execute([$type, $key, $payload, $deleted ? 1 : 0, $updatedAt, $originDevice]);
    }

    private function isStale(?string $incoming, ?string $stored): bool
    {
        if ($incoming === null || $incoming === '' || $stored === null || $stored === '') {
            return false;
        }
        try {
            return new DateTimeImmutable($incoming) < new DateTimeImmutable($stored);
        } catch (Throwable) {
            return false;
        }
    }
}
