package com.photogram.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
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

public class BackupWorker extends Worker {
    private static final Object SYNC_LOCK = new Object();
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 1;
    private static final String DB_URL = "https://photogram-dd154-default-rtdb.asia-southeast1.firebasedatabase.app/";

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
        this.prefs = context.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE);
        this.dbHelper = new DatabaseHelper(context);
        this.nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (SYNC_LOCK) {
            String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            dbHelper.addLog("ERROR", "Sync Failed: User not logged in");
            return Result.failure();
        }

        boolean isManual = getInputData().getBoolean("is_manual", false);
        dbHelper.addLog("INFO", "Sync Started (Manual: " + isManual + ")");

        if (!isManual && prefs.getBoolean("only_wifi", false) && !isWifiConnected()) {
            dbHelper.addLog("INFO", "Sync Deferred: Waiting for Wi-Fi");
            return Result.retry();
        }

        if (!fetchCloudState(uid)) {
            dbHelper.addLog("ERROR", "Access Denied: Account not approved in Firebase");
            return Result.failure();
        }

        createChannel();
        setForegroundAsync(new ForegroundInfo(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Photogram Sync").setOngoing(true).build()));
        
        String token = prefs.getString("custom_bot_token", "");
        if (token.isEmpty()) token = BuildConfig.BOT_TOKEN;
        
        TelegramHelper helper = new TelegramHelper(token, prefs.getString("chat_id", ""));
        try {
            Map<String, String> reg = helper.getTopicRegistry();
            if (dbHelper.getTotalBackupCount() == 0 && reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("INFO", "Importing history from cloud...");
                dbHelper.importHistoryFromJson(helper.downloadHistoryFile(reg.get("CLOUD_HISTORY_ID")));
            }

            long since = isManual ? 0 : (prefs.getLong("last_sync_timestamp", 0) / 1000);
            int count = performDeltaSync(since, helper, reg, uid);
            dbHelper.addLog("INFO", "Sync Finished: " + count + " photos uploaded");

            if (count > 0 || !reg.containsKey("CLOUD_HISTORY_ID")) {
                dbHelper.addLog("DEBUG", "Updating cloud history registry...");
                String fid = helper.uploadHistoryFile(dbHelper.exportHistoryToJson());
                if (fid != null) { reg.put("CLOUD_HISTORY_ID", fid); helper.saveTopicRegistry(reg); }
            }

            prefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply();
            return Result.success();
        } catch (Exception e) {
            dbHelper.addLog("ERROR", "Sync Failed: " + e.getMessage());
            if (e instanceof IOException || e instanceof SocketTimeoutException || e instanceof UnknownHostException) {
                // Retry for network issues, but limit attempts to avoid battery drain
                if (getRunAttemptCount() < 3) {
                    return Result.retry();
                }
            }
            return Result.failure();
        }
        }
    }

    private boolean fetchCloudState(String uid) {
        try {
            DataSnapshot snap = Tasks.await(FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).get());
            String status = snap.child("status").getValue(String.class);
            isLimited = "limited".equals(status);
            if (isLimited) {
                dailyLimit = snap.child("daily_limit").getValue(Integer.class);
                currentUsage = snap.child("usage_count").getValue(Integer.class);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                if (!today.equals(snap.child("last_sync_date").getValue(String.class))) {
                    currentUsage = 0;
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(0);
                    FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("last_sync_date").setValue(today);
                }
            }
            return "approved".equals(status) || "limited".equals(status);
        } catch (Exception e) { return false; }
    }

    private int performDeltaSync(long since, TelegramHelper helper, Map<String, String> reg, String uid) throws Exception {
        int count = 0;
        HashSet<String> processedThisScan = new HashSet<>();
        ContentResolver resolver = ctx.getContentResolver();
        dbHelper.addLog("DEBUG", "Scanning MediaStore since: " + since);
        int matchedFolders = 0;
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED}, MediaStore.Images.Media.DATE_MODIFIED + " > ?", new String[]{String.valueOf(since)}, MediaStore.Images.Media.DATE_MODIFIED + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int total = cursor.getCount(), idx = 0;
                dbHelper.addLog("DEBUG", "Found " + total + " potential new photos");
                do {
                    if (isStopped()) break;
                    if (isLimited && currentUsage >= dailyLimit) break;

                    String path = cursor.getString(0);
                    if (processedThisScan.contains(path)) continue;
                    processedThisScan.add(path);
                    
                    long mod = cursor.getLong(1);
                    File f = new File(path);
                    String folderPath = f.getParentFile() != null ? f.getParentFile().getAbsolutePath() : "";
                    
                    if (!folderPath.isEmpty() && prefs.getBoolean(folderPath, false)) {
                        matchedFolders++;
                        if (!dbHelper.isFileUploaded(path, mod)) {
                            setProgressAsync(new Data.Builder().putString("current_file", f.getName()).putInt("progress_percent", (int)((idx/(float)total)*100)).build());
                            String tid = getTid(f.getParentFile(), helper, reg);
                            if (!tid.isEmpty() && helper.uploadPhoto(f, tid)) {
                                dbHelper.markAsUploaded(path, mod);
                                count++;
                                    if (isLimited) {
                                        currentUsage++;
                                        FirebaseDatabase.getInstance(DB_URL).getReference("users").child(uid).child("usage_count").setValue(currentUsage);
                                    }
                                    dbHelper.addLog("DEBUG", "Uploaded: " + f.getName());
                                    Thread.sleep(1000); // 1 second fast sync
                                } else {
                                    dbHelper.addLog("ERROR", "Failed to upload: " + f.getName());
                                }
                        }
                    }
                    idx++;
                } while (cursor.moveToNext());
            }
        }
        dbHelper.addLog("DEBUG", "Scan result: " + matchedFolders + " photos in selected folders, " + count + " new.");
        return count;
    }

    private String getTid(File d, TelegramHelper h, Map<String, String> r) throws Exception {
        if (r.containsKey(d.getName())) return r.get(d.getName());
        String id = h.createTopic(d.getName());
        r.put(d.getName(), id); h.saveTopicRegistry(r);
        return id;
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        return false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW));
    }
}