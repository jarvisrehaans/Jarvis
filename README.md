# 🤖 JARVIS AI - Autonomous Android Intelligence Assistant

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.4-brightgreen.svg)](https://gradle.org)
[![Version](https://img.shields.io/badge/Release-v4.0.0-orange.svg)](version.json)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)](#)

JARVIS AI is a next-generation autonomous Android AI assistant and system automation suite powered by Google Gemini Live multi-modal streaming, Firebase Realtime Database cloud telemetry, and an advanced Accessibility & Floating Overlay architecture.

---

## 🏛️ Architecture Overview

The project is structured into three primary components:
1. **Core Android Native Application (`/app`)**: Kotlin-based Android application orchestrating voice, vision, accessibility actions, and device-level operations.
2. **Real-time Admin Web Console (`/admin-panel`)**: Lightweight, high-performance web dashboard for real-time mobile screen mirroring, command dispatching, and system monitoring.
3. **OTA Release Distribution Engine**: GitHub Raw & in-app automatic update delivery system with real-time version handshakes (`version.json`).

```
Jarvis-Ai/
├── app/                                # Android Native Client Source
│   ├── src/main/
│   │   ├── java/com/jarvis/assistant/
│   │   │   ├── ai/                     # Gemini Live audio engine & streaming client
│   │   │   ├── firebase/               # Firebase Firestore & Realtime sync layer
│   │   │   ├── model/                  # Data structures & chat models
│   │   │   ├── security/               # Encrypted preferences & integrity verification
│   │   │   ├── service/                # Foreground, accessibility & floating orb services
│   │   │   ├── ui/                     # Presentation layer (Auth, Main, Settings, Legal)
│   │   │   ├── update/                 # OTA in-app update manager
│   │   │   ├── util/                   # Device automation, calling, SMS & web engines
│   │   │   ├── vision/                 # Real-time camera & screen streaming engines
│   │   │   ├── wake/                   # Hotword & wake word detection
│   │   │   └── youtube/                # YouTube playback & API integration
│   │   ├── assets/                     # 3D Three.js orb renderer & local web views
│   │   └── res/                        # Native vector drawables, layouts & styles
│   └── build.gradle                    # App module dependencies and build config
├── admin-panel/                        # Cloud Admin Web Control Center
│   ├── css/style.css                   # Glassmorphism dark UI theme
│   ├── js/app.js                       # Real-time stream consumer & command controller
│   ├── js/firebase-config.js           # Firebase configuration
│   └── index.html                      # Control console interface
├── Jarvis-AI-Release.apk               # Official signed release binary (v4.0.0)
├── version.json                        # OTA version manifest
├── build.gradle                        # Root build script
├── settings.gradle                     # Gradle project settings
└── .gitignore                          # Standardized clean repository ignore rules
```

---

## ⚡ Core Capabilities

- **🎙️ Real-time Bi-directional Gemini Live**: Direct WebSocket audio streaming with ultra-low latency conversational responses.
- **👁️ Computer Vision & Live Screen Mirroring**: 5-FPS continuous real-time mobile screen streaming to the Admin Panel via Firebase base64 chunks.
- **🦾 Deep OS Accessibility Automation**: Autonomous touch, swipe, click, app launching, WhatsApp message dispatch, and contact caller automation.
- **🔮 3D Reactive Holographic Orb**: Interactive Three.js WebGL particle orb with sound-wave reactive audio animations.
- **🛡️ Secure Key & Credential Management**: Hardware-backed AES-256 encrypted SharedPreferences storage.
- **🔄 Seamless In-App OTA Updates**: Automatic update checks against `version.json` with background download and package installer handshakes.

---

## 🚀 Setup & Build Guide

### Prerequisites
- **JDK**: Java 17+
- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Android SDK**: API 26 (Minimum) to API 34 (Target)
- **Gradle**: 8.4+

### Building APK
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

---

## 🔒 Security & Privacy

- All sensitive API keys are encrypted at rest using Android Keystore.
- Cloud telemetry is restricted to authenticated Firebase projects.
- In compliance with Google Play Developer policies regarding Accessibility APIs.

---

## 📄 License & Ownership
Copyright © 2026 Jarvis AI. All rights reserved.
