# MTProto Upload Setup

## Overview
The Photogram Backup app now supports MTProto settings for uploading large files (up to 2GB). While the app itself uses the standard Bot API, you can use the provided Python script with your MTProto credentials for large file uploads.

## Setup Instructions

### 1. Get Telegram API Credentials
1. Visit [my.telegram.org](https://my.telegram.org)
2. Log in with your phone number
3. Go to "API development tools"
4. Create an app (if not already created)
5. Copy your **API ID** and **API Hash**

### 2. Configure in Settings
1. Open Photogram Backup app
2. Go to Settings
3. Scroll to "MTProto Settings (For Large Files)"
4. Enter your **API ID** and **API Hash**
5. Save settings

### 3. Using the Python Script for Large Files
For files larger than 50MB, use the Python script:

```bash
python scripts/mtproto_uploader.py --api_id YOUR_API_ID --api_hash YOUR_API_HASH --bot_token YOUR_BOT_TOKEN --chat_id YOUR_CHAT_ID --file "path/to/large/file.zip"
```

The script will automatically break large files into chunks and upload them to Telegram.

## Note
- The Android app continues using Bot API (50MB limit)
- MTProto credentials are saved in app settings for future reference
- Use the Python script manually for files > 50MB
