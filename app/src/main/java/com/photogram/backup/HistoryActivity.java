package com.photogram.backup;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity {
    private DatabaseHelper dbHelper;
    private ListView historyListView;
    private EditText searchBox;
    private BaseAdapter adapter;
    private List<HistoryItem> allItems = new ArrayList<>();
    private List<HistoryItem> filteredItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        dbHelper = new DatabaseHelper(this);
        historyListView = findViewById(R.id.historyListView);
        searchBox = findViewById(R.id.searchHistory);
        Button btnClose = findViewById(R.id.btnCloseHistory);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        loadHistory();
        setupAdapter();

        if (searchBox != null) {
            searchBox.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { filterHistory(s.toString()); }
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void loadHistory() {
        allItems.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.query("history", 
            new String[]{"file_path", "upload_date"},
            null, null, null, null,
            "upload_date DESC",
            "100")) {
            if (c != null && c.moveToFirst()) {
                do {
                    String path = c.getString(0);
                    long uploadDate = c.getLong(1);
                    allItems.add(new HistoryItem(path, uploadDate));
                } while (c.moveToNext());
            }
        }
        filteredItems.addAll(allItems);
    }

    private void filterHistory(String query) {
        filteredItems.clear();
        for (HistoryItem item : allItems) {
            if (item.fileName.toLowerCase().contains(query.toLowerCase())) {
                filteredItems.add(item);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void setupAdapter() {
        adapter = new BaseAdapter() {
            public int getCount() { return filteredItems.size(); }
            public Object getItem(int i) { return filteredItems.get(i); }
            public long getItemId(int i) { return i; }

            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.history_item, null);
                HistoryItem item = filteredItems.get(i);
                
                ((TextView)v.findViewById(R.id.historyFileName)).setText(item.fileName);
                ((TextView)v.findViewById(R.id.historyFilePath)).setText(item.folderPath);
                ((TextView)v.findViewById(R.id.historyUploadDate)).setText(item.getFormattedDate());
                
                return v;
            }
        };
        historyListView.setAdapter(adapter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    static class HistoryItem {
        String filePath;
        String fileName;
        String folderPath;
        long uploadDate;

        HistoryItem(String path, long date) {
            this.filePath = path;
            this.uploadDate = date;
            
            java.io.File f = new java.io.File(path);
            this.fileName = f.getName();
            this.folderPath = f.getParent();
        }

        String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US);
            return sdf.format(new Date(uploadDate));
        }
    }
}
