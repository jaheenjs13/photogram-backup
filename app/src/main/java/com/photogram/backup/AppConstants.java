package com.photogram.backup;

/**
 * Centralized constants for the Photogram Backup app.
 * All configuration values should be defined here.
 */
public final class AppConstants {
    
    // Firebase Configuration
    public static final String FIREBASE_DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";
    
    // Telegram Configuration
    public static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    public static final String TELEGRAM_FILE_BASE = "https://api.telegram.org/file/bot";
    
    // SharedPreferences
    public static final String PREFS_NAME = "BackupPrefs";
    public static final String PREF_BOT_TOKEN = "custom_bot_token";
    public static final String PREF_CHAT_ID = "chat_id";
    public static final String PREF_API_ID = "api_id";
    public static final String PREF_API_HASH = "api_hash";
    public static final String PREF_SYNC_INTERVAL = "sync_interval";
    public static final String PREF_ONLY_WIFI = "only_wifi";
    public static final String PREF_LAST_SYNC = "last_sync_timestamp";
    
    // Worker Configuration
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long UPLOAD_DELAY_MS = 1000;
    public static final int FIREBASE_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_SYNC_INTERVAL_MINUTES = 60;
    
    // Database Configuration
    public static final int MAX_LOGS = 100;
    public static final int HISTORY_DISPLAY_LIMIT = 100;
    
    // UI Configuration
    public static final int ANIMATION_DURATION_MS = 300;
    
    // Private constructor to prevent instantiation
    private AppConstants() {
        throw new UnsupportedOperationException("Cannot instantiate constants class");
    }
}
