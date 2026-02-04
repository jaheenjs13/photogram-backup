package com.photogram.backup;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DashboardActivity extends Activity {
    private DatabaseHelper dbHelper;
    private TextView tvTotalPhotos, tvTodayUploads, tvAccountStatus;
    private TextView tvDailyLimit, tvUsageCount, tvLastSync, tvNextSync;
    private TextView tvUserEmail, tvStorageInfo;
    private android.content.SharedPreferences prefs;
    private DatabaseReference userRef;
    private ValueEventListener firebaseListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        dbHelper = new DatabaseHelper(this);
        prefs = getSharedPreferences("BackupPrefs", MODE_PRIVATE);

        // Initialize views
        tvTotalPhotos = findViewById(R.id.tvDashTotalPhotos);
        tvTodayUploads = findViewById(R.id.tvDashTodayUploads);
        tvAccountStatus = findViewById(R.id.tvDashAccountStatus);
        tvDailyLimit = findViewById(R.id.tvDashDailyLimit);
        tvUsageCount = findViewById(R.id.tvDashUsageCount);
        tvLastSync = findViewById(R.id.tvDashLastSync);
        tvNextSync = findViewById(R.id.tvDashNextSync);
        tvUserEmail = findViewById(R.id.tvDashUserEmail);
        tvStorageInfo = findViewById(R.id.tvDashStorageInfo);
        
        Button btnClose = findViewById(R.id.btnCloseDashboard);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        loadDashboardData();
        fetchFirebaseData();
    }

    private void loadDashboardData() {
        // Total photos backed up
        int totalCount = dbHelper.getTotalBackupCount();
        tvTotalPhotos.setText(String.valueOf(totalCount));

        // Today's uploads (last 24 hours)
        int todayCount = getTodayUploadCount();
        tvTodayUploads.setText(String.valueOf(todayCount));

        // Last sync time
        long lastSync = prefs.getLong("last_sync_timestamp", 0);
        if (lastSync > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.US);
            tvLastSync.setText(sdf.format(new Date(lastSync)));
        } else {
            tvLastSync.setText("Never synced");
        }

        // Next sync time
        int syncInterval = prefs.getInt("sync_interval", 60);
        if (lastSync > 0) {
            long nextSync = lastSync + (syncInterval * 60 * 1000);
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd 'at' hh:mm a", Locale.US);
            tvNextSync.setText(sdf.format(new Date(nextSync)));
        } else {
            tvNextSync.setText("Not scheduled");
        }

        // User email
        String email = FirebaseAuth.getInstance().getCurrentUser() != null 
            ? FirebaseAuth.getInstance().getCurrentUser().getEmail() 
            : "Unknown";
        tvUserEmail.setText(email);

        // Storage info (estimated)
        long estimatedSize = totalCount * 2; // Rough estimate: 2MB per photo
        tvStorageInfo.setText(formatFileSize(estimatedSize * 1024 * 1024));
    }

    private int getTodayUploadCount() {
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
        android.database.Cursor cursor = null;
        try {
            cursor = dbHelper.getReadableDatabase().query(
                "history",
                new String[]{"COUNT(*)"},
                "upload_date > ?",
                new String[]{String.valueOf(oneDayAgo)},
                null, null, null
            );
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    private void fetchFirebaseData() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        userRef = FirebaseDatabase
            .getInstance(AppConstants.FIREBASE_DB_URL)
            .getReference("users")
            .child(uid);

        firebaseListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String status = snapshot.child("status").getValue(String.class);
                Integer dailyLimit = snapshot.child("daily_limit").getValue(Integer.class);
                Integer usageCount = snapshot.child("usage_count").getValue(Integer.class);

                if ("limited".equals(status)) {
                    tvAccountStatus.setText("Limited Account");
                    tvAccountStatus.setTextColor(getResources().getColor(R.color.status_warning, null));
                    
                    if (dailyLimit != null) {
                        tvDailyLimit.setText(String.valueOf(dailyLimit));
                    }
                    
                    if (usageCount != null) {
                        tvUsageCount.setText(String.valueOf(usageCount));
                        
                        // Color code usage
                        if (dailyLimit != null) {
                            float percent = (float) usageCount / dailyLimit;
                            if (percent >= 0.9f) {
                                tvUsageCount.setTextColor(getResources().getColor(R.color.status_error, null));
                            } else if (percent >= 0.7f) {
                                tvUsageCount.setTextColor(getResources().getColor(R.color.status_warning, null));
                            } else {
                                tvUsageCount.setTextColor(getResources().getColor(R.color.status_success, null));
                            }
                        }
                    }
                } else {
                    tvAccountStatus.setText("Unlimited Account");
                    tvAccountStatus.setTextColor(getResources().getColor(R.color.status_success, null));
                    tvDailyLimit.setText("∞");
                    tvUsageCount.setText("--");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvAccountStatus.setText("Error loading");
            }
        };
        userRef.addValueEventListener(firebaseListener);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove Firebase listener to prevent memory leaks
        if (userRef != null && firebaseListener != null) {
            userRef.removeEventListener(firebaseListener);
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }
}
