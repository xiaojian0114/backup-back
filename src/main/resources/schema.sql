CREATE TABLE IF NOT EXISTS backup_task (
                                           id INTEGER PRIMARY KEY AUTOINCREMENT,
                                           source_path TEXT NOT NULL,
                                           schedule TEXT,
                                           backup_mode TEXT NOT NULL,
                                           is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
                                           backup_count INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS backup_log (
                                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                                          task_id INTEGER NOT NULL,
                                          filename TEXT NOT NULL,
                                          disk_id INTEGER NOT NULL,
                                          target_path TEXT NOT NULL,
                                          checksum TEXT NOT NULL,
                                          status TEXT NOT NULL,
                                          transfer_offset INTEGER NOT NULL,
                                          backup_time TEXT NOT NULL,
                                          index_path TEXT
);

CREATE TABLE IF NOT EXISTS hard_disk (
                                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                                         disk_id TEXT NOT NULL UNIQUE,
                                         serial_number TEXT NOT NULL UNIQUE,
                                         total_capacity INTEGER NOT NULL,
                                         available_capacity INTEGER NOT NULL,
                                         status TEXT NOT NULL,
                                         mount_point TEXT NOT NULL,
                                         migration_status TEXT,
                                         migration_target INTEGER
);

CREATE TABLE IF NOT EXISTS config (
                                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                                      config_key TEXT NOT NULL UNIQUE,
                                      config_value TEXT NOT NULL,
                                      description TEXT
);