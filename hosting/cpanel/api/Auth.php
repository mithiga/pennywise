<?php
declare(strict_types=1);

require_once __DIR__ . '/helpers.php';

final class PennyKeAuth
{
    private const OTP_TTL = 600;
    private const SESSION_TTL = 43200;
    private const MAX_ATTEMPTS = 5;
    private const LOGIN_WINDOW = 900;
    private const LOGIN_LIMIT = 5;
    private const VERIFY_LIMIT = 10;

    /** @var callable|null */
    private $sender;

    public function __construct(private PDO $pdo, private array $config, ?callable $sender = null)
    {
        ensure_schema($this->pdo, $this->config);
        $this->sender = $sender;
        $this->purgeExpired();
    }

    public function status(): array
    {
        $channels = $this->channels();
        return [
            'channels' => $channels,
            'emailHint' => in_array('email', $channels, true) ? mask_email($this->email()) : null,
            'phoneHint' => in_array('sms', $channels, true) ? mask_phone($this->phone()) : null,
        ];
    }

    public function startLogin(string $pairingToken, ?string $channel, string $ip): array
    {
        $this->throttle('login:' . $ip, self::LOGIN_LIMIT, self::LOGIN_WINDOW);
        $expected = (string) ($this->config['sync_token'] ?? '');
        if ($expected === '' || $pairingToken === '' || !hash_equals($expected, $pairingToken)) {
            json_error(401, 'unauthorized');
        }
        $channels = $this->channels();
        if ($channels === []) {
            json_error(503, '2FA is not configured');
        }
        $chosen = $channel !== null && $channel !== '' ? strtolower(trim($channel)) : null;
        if ($chosen === null) {
            if (count($channels) !== 1) {
                json_error(400, 'choose email or sms');
            }
            $chosen = $channels[0];
        }
        if (!in_array($chosen, $channels, true)) {
            json_error(400, 'channel unavailable');
        }
        $destination = $chosen === 'sms' ? $this->phone() : $this->email();
        $code = str_pad((string) random_int(0, 999999), 6, '0', STR_PAD_LEFT);
        $id = bin2hex(random_bytes(16));
        $expires = time() + self::OTP_TTL;
        $table = table($this->config, 'auth_challenges');
        $stmt = $this->pdo->prepare(
            "INSERT INTO $table (id, code_hash, channel, expires_at, attempts, ip) VALUES (?, ?, ?, ?, 0, ?)"
        );
        $stmt->execute([$id, $this->codeHash($id, $code), $chosen, $expires, $ip]);
        try {
            $this->deliver($chosen, $destination, $code);
        } catch (Throwable $e) {
            $this->pdo->prepare("DELETE FROM $table WHERE id = ?")->execute([$id]);
            json_error(503, 'could not send sign-in code');
        }
        return [
            'challengeId' => $id,
            'channel' => $chosen,
            'destinationHint' => $chosen === 'sms' ? mask_phone($destination) : mask_email($destination),
            'expiresIn' => self::OTP_TTL,
        ];
    }

    public function verify(string $challengeId, string $code, string $ip): array
    {
        $this->throttle('verify:' . $ip, self::VERIFY_LIMIT, self::LOGIN_WINDOW);
        $challengeId = trim($challengeId);
        $code = trim($code);
        if ($challengeId === '' || !preg_match('/^[0-9]{6}$/', $code)) {
            json_error(400, 'invalid code');
        }
        $table = table($this->config, 'auth_challenges');
        $stmt = $this->pdo->prepare("SELECT * FROM $table WHERE id = ?");
        $stmt->execute([$challengeId]);
        $row = $stmt->fetch();
        if (!$row || (int) $row['expires_at'] < time()) {
            json_error(401, 'code expired');
        }
        if ((int) $row['attempts'] >= self::MAX_ATTEMPTS) {
            json_error(401, 'too many attempts');
        }
        $this->pdo->prepare("UPDATE $table SET attempts = attempts + 1 WHERE id = ?")->execute([$challengeId]);
        if (!hash_equals((string) $row['code_hash'], $this->codeHash($challengeId, $code))) {
            json_error(401, 'invalid code');
        }
        $this->pdo->prepare("DELETE FROM $table WHERE id = ?")->execute([$challengeId]);
        $session = bin2hex(random_bytes(32));
        $sessions = table($this->config, 'auth_sessions');
        $now = time();
        $expires = $now + self::SESSION_TTL;
        $ins = $this->pdo->prepare(
            "INSERT INTO $sessions (token_hash, expires_at, ip, created_at) VALUES (?, ?, ?, ?)"
        );
        $ins->execute([hash('sha256', $session), $expires, $ip, $now]);
        $this->setSessionCookie($session, $expires);
        return [
            'sessionToken' => $session,
            'expiresAt' => gmdate('c', $expires),
            'expiresIn' => self::SESSION_TTL,
        ];
    }

    public function logout(?string $sessionToken): void
    {
        if ($sessionToken) {
            $table = table($this->config, 'auth_sessions');
            $this->pdo->prepare("DELETE FROM $table WHERE token_hash = ?")
                ->execute([hash('sha256', $sessionToken)]);
        }
        $this->clearSessionCookie();
    }

    public function sessionValid(?string $sessionToken): bool
    {
        if ($sessionToken === null || $sessionToken === '') {
            return false;
        }
        $expected = (string) ($this->config['sync_token'] ?? '');
        if ($expected !== '' && hash_equals($expected, $sessionToken)) {
            return false;
        }
        $table = table($this->config, 'auth_sessions');
        $stmt = $this->pdo->prepare("SELECT expires_at FROM $table WHERE token_hash = ?");
        $stmt->execute([hash('sha256', $sessionToken)]);
        $row = $stmt->fetch();
        if (!$row) {
            return false;
        }
        if ((int) $row['expires_at'] < time()) {
            $this->pdo->prepare("DELETE FROM $table WHERE token_hash = ?")
                ->execute([hash('sha256', $sessionToken)]);
            return false;
        }
        return true;
    }

    /** @return list<string> */
    private function channels(): array
    {
        $out = [];
        if ($this->email() !== '') {
            $out[] = 'email';
        }
        if ($this->phone() !== '' && $this->smsConfigured()) {
            $out[] = 'sms';
        }
        return $out;
    }

    private function email(): string
    {
        return trim((string) ($this->config['two_factor_email'] ?? ''));
    }

    private function phone(): string
    {
        return trim((string) ($this->config['two_factor_phone'] ?? ''));
    }

    private function smsConfigured(): bool
    {
        $user = trim((string) ($this->config['sms_username'] ?? ''));
        $key = trim((string) ($this->config['sms_api_key'] ?? ''));
        $url = trim((string) ($this->config['sms_url'] ?? ''));
        return ($user !== '' && $key !== '') || $url !== '';
    }

    private function deliver(string $channel, string $destination, string $code): void
    {
        if ($this->sender !== null) {
            ($this->sender)($channel, $destination, $code);
            return;
        }
        $message = "Your PennyKE sign-in code is {$code}. It expires in 10 minutes. If you did not try to sign in, ignore this.";
        if ($channel === 'email') {
            $from = trim((string) ($this->config['mail_from'] ?? 'noreply@detective.co.ke'));
            $headers = "From: PennyKE <{$from}>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8";
            $ok = @mail($destination, 'PennyKE sign-in code', $message, $headers, '-f ' . $from);
            if ($ok === false) {
                throw new RuntimeException('mail failed');
            }
            return;
        }
        $this->sendSms($destination, $message);
    }

    private function sendSms(string $phone, string $message): void
    {
        $url = trim((string) ($this->config['sms_url'] ?? ''));
        if ($url !== '') {
            $payload = json_encode(['to' => $phone, 'message' => $message], JSON_UNESCAPED_SLASHES);
            $ctx = stream_context_create([
                'http' => [
                    'method' => 'POST',
                    'header' => "Content-Type: application/json\r\n",
                    'content' => $payload ?: '{}',
                    'timeout' => 15,
                ],
            ]);
            $raw = @file_get_contents($url, false, $ctx);
            if ($raw === false) {
                throw new RuntimeException('sms webhook failed');
            }
            return;
        }
        $user = trim((string) ($this->config['sms_username'] ?? ''));
        $key = trim((string) ($this->config['sms_api_key'] ?? ''));
        if ($user === '' || $key === '') {
            throw new RuntimeException('sms not configured');
        }
        $post = http_build_query([
            'username' => $user,
            'to' => $phone,
            'message' => $message,
        ]);
        $ctx = stream_context_create([
            'http' => [
                'method' => 'POST',
                'header' => "apiKey: {$key}\r\nAccept: application/json\r\nContent-Type: application/x-www-form-urlencoded\r\n",
                'content' => $post,
                'timeout' => 15,
            ],
        ]);
        $raw = @file_get_contents('https://api.africastalking.com/version1/messaging', false, $ctx);
        if ($raw === false) {
            throw new RuntimeException('sms send failed');
        }
    }

    private function codeHash(string $challengeId, string $code): string
    {
        $pepper = (string) ($this->config['sync_token'] ?? 'pennyke');
        return hash_hmac('sha256', $challengeId . ':' . $code, $pepper);
    }

    private function throttle(string $key, int $limit, int $window): void
    {
        $table = table($this->config, 'auth_throttle');
        $now = time();
        $stmt = $this->pdo->prepare("SELECT window_start, count FROM $table WHERE k = ?");
        $stmt->execute([$key]);
        $row = $stmt->fetch();
        if (!$row || (int) $row['window_start'] + $window < $now) {
            $up = $this->pdo->prepare("REPLACE INTO $table (k, window_start, count) VALUES (?, ?, 1)");
            $up->execute([$key, $now]);
            return;
        }
        $count = (int) $row['count'] + 1;
        $this->pdo->prepare("UPDATE $table SET count = ? WHERE k = ?")->execute([$count, $key]);
        if ($count > $limit) {
            json_error(429, 'too many attempts');
        }
    }

    private function purgeExpired(): void
    {
        $now = time();
        $challenges = table($this->config, 'auth_challenges');
        $sessions = table($this->config, 'auth_sessions');
        $this->pdo->exec("DELETE FROM $challenges WHERE expires_at < $now");
        $this->pdo->exec("DELETE FROM $sessions WHERE expires_at < $now");
    }

    private function setSessionCookie(string $token, int $expires): void
    {
        if (PHP_SAPI === 'cli') {
            return;
        }
        $path = (string) ($this->config['public_path'] ?? '/pennyKE');
        if ($path === '') {
            $path = '/';
        }
        setcookie('pennyke_session', $token, [
            'expires' => $expires,
            'path' => $path,
            'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
            'httponly' => true,
            'samesite' => 'Lax',
        ]);
    }

    private function clearSessionCookie(): void
    {
        if (PHP_SAPI === 'cli') {
            return;
        }
        $path = (string) ($this->config['public_path'] ?? '/pennyKE');
        if ($path === '') {
            $path = '/';
        }
        setcookie('pennyke_session', '', [
            'expires' => time() - 3600,
            'path' => $path,
            'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
            'httponly' => true,
            'samesite' => 'Lax',
        ]);
    }
}
