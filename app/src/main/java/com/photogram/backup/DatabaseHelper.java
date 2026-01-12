package com.photogram.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseHelper";
    private static final String DATABASE_NAME = "photogram_v5.db";
    private static final int DATABASE_VERSION = 1;
    
    // Table names
    private static final String TABLE_HISTORY = "history";
    private static final String TABLE_FOLDERS = "folders";
    private static final String TABLE_LOGS = "logs";
    
    // History table columns
    private static final String COL_HISTORY_ID = "id";
    private static final String COL_HISTORY_FILE_PATH = "file_path";
    private static final String COL_HISTORY_LAST_MODIFIED = "last_modified";
    private static final String COL_HISTORY_UPLOAD_DATE = "upload_date";
    
    // Folders table columns
    private static final String COL_FOLDERS_PATH = "path";
    private static final String COL_FOLDERS_NAME = "name";
    
    // Logs table columns
    private static final String COL_LOGS_ID = "id";
    private static final String COL_LOGS_TIMESTAMP = "timestamp";
    private static final String COL_LOGS_TYPE = "type";
    private static final String COL_LOGS_MESSAGE = "message";
    
    // Constants
    private static final int MAX_LOGS = 100;

    public DatabaseHelper(@NonNull Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create history table with unique constraint
        db.execSQL("CREATE TABLE " + TABLE_HISTORY + " (" +
            COL_HISTORY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_HISTORY_FILE_PATH + " TEXT NOT NULL, " +
            COL_HISTORY_LAST_MODIFIED + " LONG NOT NULL, " +
            COL_HISTORY_UPLOAD_DATE + " LONG NOT NULL, " +
            "UNIQUE(" + COL_HISTORY_FILE_PATH + ", " + COL_HISTORY_LAST_MODIFIED + ")" +
            ")");
        
        db.execSQL("CREATE INDEX idx_path ON " + TABLE_HISTORY + 
            " (" + COL_HISTORY_FILE_PATH + ")");
        
        db.execSQL("CREATE INDEX idx_upload_date ON " + TABLE_HISTORY + 
            " (" + COL_HISTORY_UPLOAD_DATE + ")");
        
        // Create folders table
        db.execSQL("CREATE TABLE " + TABLE_FOLDERS + " (" +
            COL_FOLDERS_PATH + " TEXT PRIMARY KEY, " +
            COL_FOLDERS_NAME + " TEXT NOT NULL" +
            ")");
        
        // Create logs table with index on timestamp
        db.execSQL("CREATE TABLE " + TABLE_LOGS + " (" +
            COL_LOGS_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_LOGS_TIMESTAMP + " LONG NOT NULL, " +
            COL_LOGS_TYPE + " TEXT NOT NULL, " +
            COL_LOGS_MESSAGE + " TEXT NOT NULL" +
            ")");
        
        db.execSQL("CREATE INDEX idx_log_timestamp ON " + TABLE_LOGS + 
            " (" + COL_LOGS_TIMESTAMP + " DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Handle future database upgrades
        if (oldVersion < newVersion) {
            Log.i(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
            // Add migration logic here when needed
        }
    }

    /**
     * Export upload history to JSON format
     * @return JSON string of history or empty string on error
     */
    @NonNull
    public String exportHistoryToJson() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        
        try {
            db = this.getReadableDatabase();
            cursor = db.query(
                TABLE_HISTORY,
                new String[]{COL_HISTORY_FILE_PATH, COL_HISTORY_LAST_MODIFIED, COL_HISTORY_UPLOAD_DATE},
                null, null, null, null,
                COL_HISTORY_UPLOAD_DATE + " DESC"
            );
            
            JSONArray array = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject obj = new JSONObject();
                    obj.put("p", cursor.getString(0)); // path
                    obj.put("m", cursor.getLong(1));   // last_modified
                    obj.put("u", cursor.getLong(2));   // upload_date
                    array.put(obj);
                } while (cursor.moveToNext());
            }
            
            return array.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error during export: " + e.getMessage(), e);
            return "[]";
        } catch (Exception e) {
            Log.e(TAG, "Error exporting history: " + e.getMessage(), e);
            return "[]";
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Import upload history from JSON format
     * @param json JSON string containing history data
     * @return number of records imported
     */
    public int importHistoryFromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty() || json.equals("[]")) {
            Log.w(TAG, "No history to import");
            return 0;
        }
        
        SQLiteDatabase db = null;
        int importCount = 0;
        
        try {
            db = this.getWritableDatabase();
            JSONArray array = new JSONArray(json);
            
            db.beginTransaction();
            
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                
                ContentValues values = new ContentValues();
                values.put(COL_HISTORY_FILE_PATH, obj.getString("p"));
                values.put(COL_HISTORY_LAST_MODIFIED, obj.getLong("m"));
                
                // Use upload_date from JSON if available, otherwise use current time
                if (obj.has("u")) {
                    values.put(COL_HISTORY_UPLOAD_DATE, obj.getLong("u"));
                } else {
                    values.put(COL_HISTORY_UPLOAD_DATE, System.currentTimeMillis());
                }
                
                long result = db.insertWithOnConflict(
                    TABLE_HISTORY, 
                    null, 
                    values, 
                    SQLiteDatabase.CONFLICT_IGNORE
                );
                
                if (result != -1) {
                    importCount++;
                }
            }
            
            db.setTransactionSuccessful();
            Log.i(TAG, "Imported " + importCount + " history records");
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error during import: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Error importing history: " + e.getMessage(), e);
        } finally {
            if (db != null && db.inTransaction()) {
                db.endTransaction();
            }
        }
        
        return importCount;
    }

    /**
     * Add a log entry
     * @param type Log type (ERROR, WARN, INFO, DEBUG)
     * @param message Log message
     */
    public void addLog(@NonNull String type, @NonNull String message) {
        SQLiteDatabase db = null;
        
        try {
            db = this.getWritableDatabase();
            
            ContentValues values = new ContentValues();
            values.put(COL_LOGS_TIMESTAMP, System.currentTimeMillis());
            values.put(COL_LOGS_TYPE, type.toUpperCase());
            values.put(COL_LOGS_MESSAGE, message);
            
            long result = db.insert(TABLE_LOGS, null, values);
            
            if (result == -1) {
                Log.w(TAG, "Failed to insert log entry");
            }
            
            // Clean up old logs, keeping only the most recent MAX_LOGS entries
            db.execSQL("DELETE FROM " + TABLE_LOGS + 
                " WHERE " + COL_LOGS_ID + " NOT IN (" +
                "SELECT " + COL_LOGS_ID + " FROM " + TABLE_LOGS + 
                " ORDER BY " + COL_LOGS_TIMESTAMP + " DESC LIMIT " + MAX_LOGS +
                ")");
                
        } catch (Exception e) {
            Log.e(TAG, "Error adding log: " + e.getMessage(), e);
        }
    }

    /**
     * Get recent logs as formatted strings
     * @return List of formatted log strings
     */
    @NonNull
    public ArrayList<String> getRecentLogs() {
        ArrayList<String> list = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = getReadableDatabase().query(
                TABLE_LOGS,
                new String[]{COL_LOGS_TYPE, COL_LOGS_MESSAGE},
                null, null, null, null,
                COL_LOGS_TIMESTAMP + " DESC"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(0);
                    String message = cursor.getString(1);
                    list.add("[" + type + "] " + message);
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting recent logs: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return list;
    }

    /**
     * Get recent logs with detailed information for LogActivity
     * @param limit Maximum number of logs to return
     * @return List of LogEntry objects
     */
    @NonNull
    public List<LogActivity.LogEntry> getRecentLogsWithDetails(int limit) {
        List<LogActivity.LogEntry> list = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = getReadableDatabase().query(
                TABLE_LOGS,
                new String[]{COL_LOGS_TYPE, COL_LOGS_MESSAGE, COL_LOGS_TIMESTAMP},
                null, null, null, null,
                COL_LOGS_TIMESTAMP + " DESC",
                String.valueOf(limit)
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(0);
                    String message = cursor.getString(1);
                    long timestamp = cursor.getLong(2);
                    
                    list.add(new LogActivity.LogEntry(type, message, timestamp));
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting logs with details: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return list;
    }

    /**
     * Clear all logs from the database
     */
    public void clearLogs() {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            int deleted = db.delete(TABLE_LOGS, null, null);
            Log.i(TAG, "Cleared " + deleted + " log entries");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing logs: " + e.getMessage(), e);
        }
    }

    /**
     * Get total count of uploaded files
     * @return Total number of uploaded files
     */
    public int getTotalBackupCount() {
        Cursor cursor = null;
        
        try {
            cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_HISTORY, 
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting backup count: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return 0;
    }

    /**
     * Get statistics about the backup history
     * @return BackupStats object with detailed statistics
     */
    @NonNull
    public BackupStats getBackupStats() {
        BackupStats stats = new BackupStats();
        Cursor cursor = null;
        
        try {
            // Get total count
            cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), MIN(" + COL_HISTORY_UPLOAD_DATE + "), MAX(" + COL_HISTORY_UPLOAD_DATE + ") " +
                "FROM " + TABLE_HISTORY,
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                stats.totalFiles = cursor.getInt(0);
                stats.firstUploadDate = cursor.getLong(1);
                stats.lastUploadDate = cursor.getLong(2);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting backup stats: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return stats;
    }

    /**
     * Save selected folders to database
     * @param folderList List of folders to save
     */
    public void saveFolders(@NonNull ArrayList<File> folderList) {
        SQLiteDatabase db = null;
        
        try {
            db = this.getWritableDatabase();
            db.beginTransaction();
            
            for (File folder : folderList) {
                if (folder == null) continue;
                
                ContentValues values = new ContentValues();
                values.put(COL_FOLDERS_PATH, folder.getAbsolutePath());
                values.put(COL_FOLDERS_NAME, folder.getName());
                
                db.insertWithOnConflict(
                    TABLE_FOLDERS, 
                    null, 
                    values, 
                    SQLiteDatabase.CONFLICT_REPLACE
                );
            }
            
            db.setTransactionSuccessful();
            Log.i(TAG, "Saved " + folderList.size() + " folders");
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving folders: " + e.getMessage(), e);
        } finally {
            if (db != null && db.inTransaction()) {
                db.endTransaction();
            }
        }
    }

    /**
     * Get saved folders from database
     * @return List of saved folders
     */
    @NonNull
    public ArrayList<File> getSavedFolders() {
        ArrayList<File> list = new ArrayList<>();
        Cursor cursor = null;
        
        try {
            cursor = getReadableDatabase().query(
                TABLE_FOLDERS,
                new String[]{COL_FOLDERS_PATH},
                null, null, null, null,
                COL_FOLDERS_NAME + " ASC"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String path = cursor.getString(0);
                    File folder = new File(path);
                    
                    // Only add if folder still exists
                    if (folder.exists() && folder.isDirectory()) {
                        list.add(folder);
                    }
                } while (cursor.moveToNext());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting saved folders: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return list;
    }

    /**
     * Remove a folder from saved folders
     * @param folderPath Path of folder to remove
     * @return true if removed successfully
     */
    public boolean removeFolder(@NonNull String folderPath) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            int deleted = db.delete(
                TABLE_FOLDERS,
                COL_FOLDERS_PATH + " = ?",
                new String[]{folderPath}
            );
            return deleted > 0;
        } catch (Exception e) {
            Log.e(TAG, "Error removing folder: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if a file has already been uploaded
     * @param path File path
     * @param modified Last modified timestamp
     * @return true if file was already uploaded
     */
    public boolean isFileUploaded(@NonNull String path, long modified) {
        Cursor cursor = null;
        
        try {
            cursor = getReadableDatabase().query(
                TABLE_HISTORY,
                new String[]{COL_HISTORY_ID},
                COL_HISTORY_FILE_PATH + " = ? AND " + COL_HISTORY_LAST_MODIFIED + " = ?",
                new String[]{path, String.valueOf(modified)},
                null, null, null, "1"
            );
            
            return cursor != null && cursor.getCount() > 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking if file uploaded: " + e.getMessage(), e);
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Mark a file as uploaded
     * @param path File path
     * @param modified Last modified timestamp
     * @return true if marked successfully
     */
    public boolean markAsUploaded(@NonNull String path, long modified) {
        try {
            ContentValues values = new ContentValues();
            values.put(COL_HISTORY_FILE_PATH, path);
            values.put(COL_HISTORY_LAST_MODIFIED, modified);
            values.put(COL_HISTORY_UPLOAD_DATE, System.currentTimeMillis());
            
            long result = getWritableDatabase().insertWithOnConflict(
                TABLE_HISTORY,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            );
            
            return result != -1;
            
        } catch (Exception e) {
            Log.e(TAG, "Error marking file as uploaded: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Clear all upload history
     * @return number of records deleted
     */
    public int clearHistory() {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            int deleted = db.delete(TABLE_HISTORY, null, null);
            Log.i(TAG, "Cleared " + deleted + " history records");
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing history: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Inner class to hold backup statistics
     */
    public static class BackupStats {
        public int totalFiles = 0;
        public long firstUploadDate = 0;
        public long lastUploadDate = 0;
        
        public boolean hasData() {
            return totalFiles > 0;
        }
    }
}