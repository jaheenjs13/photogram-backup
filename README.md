# ☁️ Photogram Backup

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/Min%20SDK-21-blue?style=for-the-badge" alt="Min SDK"/>
  <img src="https://img.shields.io/badge/Target%20SDK-34-blue?style=for-the-badge" alt="Target SDK"/>
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License"/>
</p>

<p align="center">
  <strong>Transform Telegram into your unlimited, organized cloud storage for photos.</strong>
</p>

---

## 🌟 What is Photogram?

Photogram is a modern Android app that automatically backs up your photos to Telegram. Each folder on your phone becomes a separate **Topic** in your private Telegram Group, keeping everything perfectly organized.

> 💡 **Unlimited Storage** — Telegram offers free, unlimited cloud storage. Photogram leverages this to give you worry-free photo backups.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🚀 **Smart Delta Sync** | Uses MediaStore to detect only new photos — fast and battery-friendly |
| 🔄 **Background Engine** | Powered by WorkManager for reliable syncing even after reboots |
| 📁 **Folder Organization** | Each phone folder = one Telegram Topic |
| 🔒 **Firebase Auth** | Secure user authentication with approval system |
| 📊 **Dashboard** | Real-time stats on uploads, usage limits, and sync status |
| 🎨 **Modern UI** | Glassmorphism design with smooth animations |
| ☁️ **Cloud Memory** | History stored in Telegram — reinstall-proof |
| 📱 **Android 14 Ready** | Full support for latest Android permissions |

---

## 📱 Screenshots

<p align="center">
  <em>Login → Main → Dashboard → Settings</em>
</p>

---

## 🚀 Quick Start

### 1. Create Telegram Group
```
1. Create a new Group in Telegram
2. Enable Topics in Group Settings
3. Add your bot to the group as Admin
4. Get the Group Chat ID (e.g., -100123456789)
```

### 2. Install & Configure
```
1. Download APK from Releases
2. Open Settings in the app
3. Enter Bot Token and Chat ID
4. Select folders to backup
5. Tap "Sync Now"
```

---

## 🏗️ Building from Source

### Prerequisites
- Android Studio Hedgehog+
- JDK 17
- Android SDK 34

### Build Steps
```bash
git clone https://github.com/jaheenjs13/photogram-backup.git
cd photogram-backup
./gradlew assembleDebug
```

### Environment Variables
| Variable | Description |
|----------|-------------|
| `TELEGRAM_BOT_TOKEN` | Your Telegram Bot Token |

---

## 🛠️ Tech Stack

| Component | Technology |
|-----------|------------|
| **Language** | Java |
| **Network** | OkHttp 4 |
| **Database** | SQLite |
| **Background** | WorkManager |
| **Auth** | Firebase Auth |
| **Cloud DB** | Firebase Realtime Database |
| **UI** | XML + Material Design |

---

## 📊 Architecture

```
app/
├── LoginActivity      # Firebase authentication
├── MainActivity       # Folder selection & sync trigger
├── DashboardActivity  # Stats & usage monitoring
├── SettingsActivity   # Bot configuration
├── BackupWorker       # Background sync engine
├── TelegramHelper     # Telegram API integration
├── DatabaseHelper     # Local SQLite operations
└── AppConstants       # Centralized configuration
```

---

## 🔐 Security

- **Firebase Auth** — Email/password authentication
- **Admin Approval** — Users require approval before syncing
- **Usage Limits** — Configurable daily upload limits
- **No Hardcoded Secrets** — Config injected via build system

---

## 📄 License

This project is licensed under the MIT License.

---

<p align="center">
  Made with ❤️ for seamless photo backups
</p>
