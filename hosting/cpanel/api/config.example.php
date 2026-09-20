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
];
