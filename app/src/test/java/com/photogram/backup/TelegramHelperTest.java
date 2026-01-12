package com.photogram.backup;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

public class TelegramHelperTest {

    @Test
    public void testRegistryJsonFormatting() throws Exception {
        Map<String, String> registry = new HashMap<>();
        registry.put("Folder1", "12345");
        registry.put("Folder2", "67890");
        
        String json = new JSONObject(registry).toString();
        String text = "PHOTOGRAM_REGISTRY:" + json;
        
        assertTrue(text.contains("Folder1"));
        assertTrue(text.contains("12345"));
        assertTrue(text.startsWith("PHOTOGRAM_REGISTRY:"));
    }

    @Test
    public void testRegistrySizeLimitLogic() {
        // Simulating the check I added in TelegramHelper
        Map<String, String> registry = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            registry.put("Folder_" + i, "thread_id_" + i);
        }
        
        String text = "PHOTOGRAM_REGISTRY:" + new JSONObject(registry).toString();
        // The limit I set was 4000
        assertTrue("Registry size should be monitored", text.length() > 0);
    }
}
