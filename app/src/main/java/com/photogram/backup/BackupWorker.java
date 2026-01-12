package com.photogram.backup;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.android.gms.tasks.Tasks;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BackupWorker extends Worker {
    private static final Object SYNC_LOCK = new Object();
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;
    private static final String DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";
    private static final String PREFS_NAME = "BackupPrefs";
    private static final String TAG = "BackupWorker";
    
    // Configurable constants
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long UPLOAD_DELAY_MS = 1000;
    private static final int FIREBASE_TIMEOUT_SECONDS = 10;

    private final SharedPreferences prefs;
    private final DatabaseHelper dbHelper;
    private final NotificationManager nm;
    private final Context ctx;

    private boolean isLimited = false;
    private int dailyLimit = 0;
    private int currentUsage = 0;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.ctx = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.dbHelper = new DatabaseHelper(context);
        this.nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (SYNC_LOCK) {
            try {
                return performSync();
            } catch (Exception e) {
                dbHelper.addLog("ERROR", "Unexpected error in doWork: " + e.getMessage());
                return Result.failure();
            } finally {
                // Ensure notification is cancelled even if work fails
                if (nm != null) {
                    nm.cancel(NOTIF_ID);
                }
            }
        }
    }

    private Result performSync() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            dbHelper.addLog("ERROR", "Sync Failed: User not logged in");
            return Result.failure();
        }

        boolean isManual = getInputData().getBoolean("is_manual", false);
        dbHelper.addLog("INFO", "Sync Started (Manual: " + isManual + ", Attempt: " + getRunAttemptCount() + ")");

        // Check WiFi requirement for non-manual syncs
        if (!isManual && prefs.getBoolean("only_wifi", false) && !isWifiConnected()) {
            dbHelper.addLog("INFO", "Sync Deferred: Waiting for Wi-Fi");
            return Result.retry();
        }

        // Fetch cloud state and verify access
        if (!fetchCloudState(uid)) {
            dbHelper.addLog("ERROR", "Access Denied: Account not approved in Firebase");
            return Result.failure();
        }

        // Check if daily limit is already reached for limited accounts
        if (isLimited && currentUsage >= dailyLimit) {
            dbHelper.addLog("INFO", "Sync Skipped: Daily limit reached (" + currentUsage + "/" + dailyLimit + ")");
            return Result.success(); // Not a failure, just limit reached
        }

        // Set up foreground notification
        createChannel();
        try {
            setForegroundAsync(createForegroundInfo("Starting sync..."));
        } catch (Exception e) {
            dbHelper.addLog("WARN", "Failed to set foreground: " + e.getMessage());
        }

        // Get Telegram credentials
        String token = prefs.getString("custom_bot_token", "");
        if (token.isEmpty()) {
            token = BuildConfig.BOT_TOKEN;
        }
        
        String chatId = prefs.getString("chat_id", "");
        if (chatId.isEmpty()) {
            dbHelper.addLog("ERROR", "Sync Failed: Chat ID not configured");
            return Result.failure();
        }

        TelegramHelper helper = new TelegramHelper(token, chatId);
        
        try {
            // Load topic registry
            Map<String, String> reg = helper.getTopicRegistry();
            
            // Import history on first run
            if (dbHelper.getTotalBackupCount() == 0 && reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Importing history from cloud...");
                updateForegroundNotification("Importing history...");
                String historyJson = helper.downloadHistoryFile(reg.get("CLOUD_HISTORY_ID"));
                if (historyJson != null && !historyJson.isEmpty()) {
                    dbHelper.importHistoryFromJson(historyJson);
                }
            }

            // Perform delta sync
            long since = isManual ? 0 : (prefs.getLong("last_sync_timestamp", 0) / 1000);
            int count = performDeltaSync(since, helper, reg, uid);
            
            // Update cloud history if needed
            if (count > 0 || !reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("DEBUG", "Updating cloud history registry...");
                updateForegroundNotification("Updating cloud history...");
                String exportedJson = dbHelper.exportHistoryToJson();
                if (exportedJson != null && !exportedJson.isEmpty()) {
                    String fid = helper.uploadHistoryFile(exportedJson);
                    if (fid != null && !fid.isEmpty()) {
                        reg.put("CLOUD_HISTORY_ID", fid);
                        helper.saveTopicRegistry(reg);
                    }
                }
            }

            // Update last sync timestamp
            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            
            String resultMsg = "Sync Finished: " + count + " photos uploaded";
            if (isLimited) {
                resultMsg += " (" + currentUsage + "/" + dailyLimit + " daily limit)";
            }
            dbHelper.addLog("INFO", resultMsg);
            
            return Result.success(new Data.Builder()
                .putInt("uploaded_count", count)
                .putInt("usage_count", currentUsage)
                .build());
                
        } catch (SocketTimeoutException | UnknownHostException e) {
            dbHelper.addLog("ERROR", "Network error: " + e.getMessage());
            return handleRetry("Network error");
        } catch (IOException e) {
            dbHelper.addLog("ERROR", "IO error: " + e.getMessage());
            return handleRetry("IO error");
        } catch (InterruptedException e) {
            dbHelper.addLog("WARN", "Sync interrupted");
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Sync Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Result.failure();
        }
    }

    private Result handleRetry(String reason) {
        if (getRunAttemptCount() < MAX_RETRY_ATTEMPTS) {
            dbHelper.addLog("INFO", "Retrying sync due to " + reason + " (attempt " + (getRunAttemptCount() + 1) + "/" + MAX_RETRY_ATTEMPTS + ")");
            return Result.retry();
        } else {
            dbHelper.addLog("ERROR", "Max retry attempts reached, giving up");
            return Result.failure();
        }
    }

    private boolean fetchCloudState(String uid) {
        try {
            DataSnapshot snap = Tasks.await(
                FirebaseDatabase.getInstance(DB_URL)
                    .getReference("users")
                    .child(uid)
                    .get(),
                FIREBASE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );
            
            if (!snap.exists()) {
                dbHelper.addLog("ERROR", "User record not found in Firebase");
                return false;
            }

            String status = snap.child("status").getValue(String.class);
            if (status == null) {
                dbHelper.addLog("ERROR", "User status not set in Firebase");
                return false;
            }

            isLimited = "limited".equals(status);
            
            if (isLimited) {
                Integer limitValue = snap.child("daily_limit").getValue(Integer.class);
                Integer usageValue = snap.child("usage_count").getValue(Integer.class);
                
                dailyLimit = (limitValue != null) ? limitValue : 0;
                currentUsage = (usageValue != null) ? usageValue : 0;
                
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                String lastSyncDate = snap.child("last_sync_date").getValue(String.class);
                
                // Reset usage if it's a new day
                if (!today.equals(lastSyncDate)) {
                    currentUsage = 0;
                    FirebaseDatabase.getInstance(DB_URL)
                        .getReference("users")
                        .child(uid)
                        .child("usage_count")
                        .setValue(0);
                    FirebaseDatabase.getInstance(DB_URL)
                        .getReference("users")
                        .child(uid)
                        .child("last_sync_date")
                        .setValue(today);
                }
                
                dbHelper.addLog("INFO", "Account limited: " + currentUsage + "/" + dailyLimit + " daily uploads");
            }
            
            return "approved".equals(status) || "limited".equals(status);
            
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Failed to fetch cloud state: " + e.getMessage());
            return false;
        }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> reg, String uid) throws Exception {
        int count = 0;
        HashSet<String> processedThisScan = new HashSet<>();
        ContentResolver resolver = ctx.getContentResolver();
        
        dbHelper.addLog("DEBUG", "Scanning MediaStore since: " + since + " (" + new Date(since * 1000) + ")");
        
        String[] projection = {
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
        };
        
        String selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?";
        String[] selectionArgs = {String.valueOf(since)};
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " ASC";
        
        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {
                
            if (cursor == null) {
                dbHelper.addLog("ERROR", "MediaStore query returned null cursor");
                return 0;
            }
            
            int total = cursor.getCount();
            if (total == 0) {
                dbHelper.addLog("INFO", "No new photos found since last sync");
                return 0;
            }
            
            dbHelper.addLog("DEBUG", "Found " + total + " potential new photos");
            int idx = 0;
            int matchedFolders = 0;
            
            while (cursor.moveToNext()) {
                if (isStopped()) {
                    dbHelper.addLog("WARN", "Sync stopped by system");
                    break;
                }
                
                // Check daily limit for limited accounts
                if (isLimited && currentUsage >= dailyLimit) {
                    dbHelper.addLog("INFO", "Daily limit reached: " + currentUsage + "/" + dailyLimit);
                    break;
                }

                String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
                long mod = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED));
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE));
                
                // Skip duplicates in this scan
                if (processedThisScan.contains(path)) {
                    idx++;
                    continue;
                }
                processedThisScan.add(path);
                
                File f = new File(path);
                
                // Validate file exists and has size
                if (!f.exists() || size <= 0) {
                    dbHelper.addLog("DEBUG", "Skipping invalid/deleted file: " + path);
                    idx++;
                    continue;
                }
                
                File parentDir = f.getParentFile();
                if (parentDir == null) {
                    idx++;
                    continue;
                }
                
                String folderPath = parentDir.getAbsolutePath();
                
                // Check if folder is selected for backup
                if (prefs.getBoolean(folderPath, false)) {
                    matchedFolders++;
                    
                    // Check if already uploaded
                    if (!dbHelper.isFileUploaded(path, mod)) {
                        int progressPercent = total > 0 ? (int)((idx / (float)total) * 100) : 0;
                        
                        updateProgress(f.getName(), progressPercent);
                        updateForegroundNotification("Uploading " + f.getName() + " (" + (idx + 1) + "/" + total + ")");
                        
                        String tid = getTid(parentDir, helper, reg);
                        
                        if (tid != null && !tid.isEmpty()) {
                            String error = helper.uploadPhoto(f, tid);
                            if (error == null) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                
                                // Update usage for limited accounts
                                if (isLimited) {
                                    currentUsage++;
                                    FirebaseDatabase.getInstance(DB_URL)
                                        .getReference("users")
                                        .child(uid)
                                        .child("usage_count")
                                        .setValue(currentUsage);
                                }
                                
                                dbHelper.addLog("DEBUG", "Uploaded: " + f.getName() + " (" + formatFileSize(size) + ")");
                                
                                // Delay between uploads to avoid rate limiting
                                Thread.sleep(UPLOAD_DELAY_MS);
                            } else {
                                dbHelper.addLog("ERROR", "Failed to upload: " + f.getName() + " - " + error);
                            }
                        } else {
                            dbHelper.addLog("ERROR", "Failed to get topic ID for folder: " + parentDir.getName());
                        }
                    }
                }
                
                idx++;
            }
            
            dbHelper.addLog("DEBUG", "Scan result: " + matchedFolders + " photos in selected folders, " + count + " new uploaded.");
        }
        
        return count;
    }

    private String getTid(File directory, TelegramHelper helper, Map<String, String> registry) throws Exception {
        String folderName = directory.getName();
        
        // Return existing topic ID if available
        if (registry.containsKey(folderName)) {
            return registry.get(folderName);
        }
        
        // Create new topic
        dbHelper.addLog("DEBUG", "Creating new topic for folder: " + folderName);
        String topicId = helper.createTopic(folderName);
        
        if (topicId != null && !topicId.isEmpty()) {
            registry.put(folderName, topicId);
            helper.saveTopicRegistry(registry);
            return topicId;
        }
        
        return null;
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return false;
                }
                
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null && 
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                // Fallback for older Android versions
                android.net.NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null && 
                       networkInfo.isConnected() && 
                       networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Error checking WiFi: " + e.getMessage());
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Photo Backup Sync",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background photo backup synchronization");
            channel.setShowBadge(false);
            
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private ForegroundInfo createForegroundInfo(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Photogram Sync")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        
        return new ForegroundInfo(NOTIF_ID, builder.build());
    }

    private void updateForegroundNotification(String message) {
        try {
            setForegroundAsync(createForegroundInfo(message));
        } catch (Exception e) {
            // Notification update failed, but don't crash the sync
        }
    }

    private void updateProgress(String fileName, int percent) {
        try {
            setProgressAsync(new Data.Builder()
                .putString("current_file", fileName)
                .putInt("progress_percent", percent)
                .build());
        } catch (Exception e) {
            // Progress update failed, but don't crash the sync
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}