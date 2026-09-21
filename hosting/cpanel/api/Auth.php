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
            $this->sendEmail($destination, $message);
            return;
        }
        $this->sendSms($destination, $message);
    }

    private function sendEmail(string $to, string $message): void
    {
        $from = trim((string) ($this->config['mail_from'] ?? 'admin@detective.co.ke'));
        if ($from === '') {
            $from = 'admin@detective.co.ke';
        }
        $subject = 'PennyKE sign-in code';
        if ($this->smtpConfigured()) {
            $this->sendSmtp($from, $to, $subject, $message);
            return;
        }
        $headers = "From: PennyKE <{$from}>\r\nReply-To: {$from}\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8";
        if (@mail($to, $subject, $message, $headers) === true) {
            return;
        }
        if (@mail($to, $subject, $message, $headers, '-f ' . $from) === true) {
            return;
        }
        throw new RuntimeException('mail failed');
    }

    private function smtpConfigured(): bool
    {
        return trim((string) ($this->config['smtp_host'] ?? '')) !== ''
            && trim((string) ($this->config['smtp_user'] ?? '')) !== ''
            && trim((string) ($this->config['smtp_pass'] ?? '')) !== '';
    }

    private function sendSmtp(string $from, string $to, string $subject, string $message): void
    {
        $host = trim((string) $this->config['smtp_host']);
        $port = (int) ($this->config['smtp_port'] ?? 25);
        if ($port <= 0) {
            $port = 25;
        }
        $user = trim((string) $this->config['smtp_user']);
        $pass = (string) $this->config['smtp_pass'];
        $remote = ($port === 465 ? 'ssl://' : 'tcp://') . $host . ':' . $port;
        $local = in_array($host, ['127.0.0.1', 'localhost', '::1'], true);
        $ctx = stream_context_create([
            'ssl' => [
                'verify_peer' => !$local,
                'verify_peer_name' => !$local,
                'allow_self_signed' => $local,
            ],
        ]);
        $fp = @stream_socket_client($remote, $errno, $errstr, 20, STREAM_CLIENT_CONNECT, $ctx);
        if ($fp === false) {
            throw new RuntimeException('smtp connect failed');
        }
        stream_set_timeout($fp, 20);
        try {
            $this->smtpExpect($fp, '220');
            $this->smtpCmd($fp, 'EHLO pennyke.local');
            $this->smtpExpect($fp, '250');
            if ($port === 587) {
                $this->smtpCmd($fp, 'STARTTLS');
                $this->smtpExpect($fp, '220');
                if (!stream_socket_enable_crypto($fp, true, STREAM_CRYPTO_METHOD_TLS_CLIENT)) {
                    throw new RuntimeException('smtp tls failed');
                }
                $this->smtpCmd($fp, 'EHLO pennyke.local');
                $this->smtpExpect($fp, '250');
            }
            if ($user !== '' && $pass !== '') {
                $this->smtpCmd($fp, 'AUTH LOGIN');
                $this->smtpExpect($fp, '334');
                $this->smtpCmd($fp, base64_encode($user));
                $this->smtpExpect($fp, '334');
                $this->smtpCmd($fp, base64_encode($pass));
                $this->smtpExpect($fp, '235');
            }
            $this->smtpCmd($fp, 'MAIL FROM:<' . $from . '>');
            $this->smtpExpect($fp, '250');
            $this->smtpCmd($fp, 'RCPT TO:<' . $to . '>');
            $this->smtpExpect($fp, '250');
            $this->smtpCmd($fp, 'DATA');
            $this->smtpExpect($fp, '354');
            $safeBody = preg_replace('/^\./m', '..', $message) ?? $message;
            fwrite($fp, 'From: PennyKE <' . $from . ">\r\n");
            fwrite($fp, 'To: <' . $to . ">\r\n");
            fwrite($fp, 'Subject: ' . $subject . "\r\n");
            fwrite($fp, "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
            fwrite($fp, $safeBody . "\r\n.\r\n");
            $this->smtpExpect($fp, '250');
            $this->smtpCmd($fp, 'QUIT');
        } finally {
            fclose($fp);
        }
    }

    private function smtpCmd($fp, string $line): void
    {
        fwrite($fp, $line . "\r\n");
    }

    private function smtpExpect($fp, string $prefix): void
    {
        $line = fgets($fp, 1024);
        if ($line === false || !str_starts_with($line, $prefix)) {
            throw new RuntimeException('smtp handshake failed');
        }
        while ($line !== false && isset($line[3]) && $line[3] === '-') {
            $line = fgets($fp, 1024);
        }
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
