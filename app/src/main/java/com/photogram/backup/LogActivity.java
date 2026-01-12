package com.photogram.backup;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class LogActivity extends Activity {
    private static final int LOG_LIMIT = 100;
    
    private DatabaseHelper db;
    private ListView logListView;
    private ArrayAdapter<LogEntry> adapter;
    private List<LogEntry> logEntries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        
        // Enable up button if using ActionBar
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("Sync Logs");
        }
        
        db = new DatabaseHelper(this);
        logListView = findViewById(R.id.logListView);
        Button btnClose = findViewById(R.id.btnCloseLogs);
        
        if (logListView == null) {
            Toast.makeText(this, "Error: Log view not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        loadLogs();
        
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Refresh")
            .setIcon(android.R.drawable.ic_menu_rotate)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 2, 0, "Clear Logs")
            .setIcon(android.R.drawable.ic_menu_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case 1: // Refresh
                loadLogs();
                Toast.makeText(this, "Logs refreshed", Toast.LENGTH_SHORT).show();
                return true;
            case 2: // Clear
                clearLogs();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void loadLogs() {
        try {
            // Get logs from database
            logEntries = db.getRecentLogsWithDetails(LOG_LIMIT);
            
            if (logEntries == null) {
                logEntries = new ArrayList<>();
            }
            
            if (logEntries.isEmpty()) {
                logEntries.add(new LogEntry("INFO", 
                    "No logs found yet. Start a sync to see events.", 
                    System.currentTimeMillis()));
            }

            // Create custom adapter
            adapter = new LogAdapter(this, logEntries);
            logListView.setAdapter(adapter);
            
            // Scroll to bottom to show most recent logs
            logListView.post(() -> logListView.setSelection(adapter.getCount() - 1));
            
        } catch (Exception e) {
            Toast.makeText(this, "Error loading logs: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }

    private void clearLogs() {
        try {
            db.clearLogs();
            loadLogs();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error clearing logs: " + e.getMessage(), 
                Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh logs when returning to this activity
        if (adapter != null) {
            loadLogs();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            db.close();
        }
    }

    /**
     * Custom adapter for displaying log entries with color coding
     */
    private static class LogAdapter extends ArrayAdapter<LogEntry> {
        
        public LogAdapter(@NonNull Activity context, @NonNull List<LogEntry> logs) {
            super(context, android.R.layout.simple_list_item_1, logs);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = view.findViewById(android.R.id.text1);
            
            LogEntry entry = getItem(position);
            if (entry != null && textView != null) {
                // Format the log entry
                textView.setText(entry.getFormattedMessage());
                
                // Set text size
                textView.setTextSize(12f);
                
                // Color code by log level
                int textColor = getColorForLevel(entry.level);
                textView.setTextColor(textColor);
                
                // Add padding for better readability
                textView.setPadding(16, 12, 16, 12);
            }
            
            return view;
        }

        private int getColorForLevel(String level) {
            switch (level.toUpperCase()) {
                case "ERROR":
                    return Color.rgb(255, 100, 100); // Light red
                case "WARN":
                case "WARNING":
                    return Color.rgb(255, 200, 100); // Orange/yellow
                case "INFO":
                    return Color.rgb(150, 200, 255); // Light blue
                case "DEBUG":
                    return Color.rgb(200, 200, 200); // Light gray
                case "SUCCESS":
                    return Color.rgb(100, 255, 150); // Light green
                default:
                    return Color.rgb(220, 220, 220); // Default light gray
            }
        }
    }

    /**
     * Internal class to represent a log entry with timestamp
     */
    public static class LogEntry {
        public final String level;
        public final String message;
        public final long timestamp;

        public LogEntry(String level, String message, long timestamp) {
            this.level = level != null ? level : "INFO";
            this.message = message != null ? message : "";
            this.timestamp = timestamp;
        }

        public String getFormattedMessage() {
            return String.format("[%s] %s: %s", 
                formatTimestamp(timestamp), 
                level, 
                message);
        }

        private String formatTimestamp(long millis) {
            long now = System.currentTimeMillis();
            long diff = now - millis;
            
            if (diff < 60000) { // Less than 1 minute
                return "just now";
            } else if (diff < 3600000) { // Less than 1 hour
                return (diff / 60000) + "m ago";
            } else if (diff < 86400000) { // Less than 24 hours
                return (diff / 3600000) + "h ago";
            } else {
                // Show date if older than 24 hours
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "MMM dd HH:mm", java.util.Locale.US);
                return sdf.format(new java.util.Date(millis));
            }
        }

        @Override
        public String toString() {
            return getFormattedMessage();
        }
    }
}