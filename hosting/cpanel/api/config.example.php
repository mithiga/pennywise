<?php
/**
 * Copy to config.php on the server (never commit real passwords).
 * Table names are prefixed pennyke_ so they can live beside OpenCart tables
 * in the same MySQL database.
 */
return [
    'db_host' => 'localhost',
    'db_name' => 'CPANELUSER_dbname',
    'db_user' => 'CPANELUSER_dbuser',
    'db_pass' => 'replace-me',
    'table_prefix' => 'pennyke_',
    'sync_token' => 'replace-with-household-pairing-token',
    'two_factor_email' => 'you@example.com',
    'two_factor_phone' => '',
    'mail_from' => 'admin@detective.co.ke',
    'smtp_host' => 'mail.detective.co.ke',
    'smtp_port' => 465,
    'smtp_user' => '',
    'smtp_pass' => '',
    'sms_username' => '',
    'sms_api_key' => '',
    'sms_url' => '',
    'public_path' => '/pennyKE',
];
