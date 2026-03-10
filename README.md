# VoicePhone — Android Accessibility App

A voice-first Android phone app designed for blind and visually impaired users. VoicePhone replaces the standard home screen and dialler with a single black screen — touch anywhere to speak, and the app handles everything by voice.

---

## What it does

- **Make calls** — say "Call Mum" and it finds the contact and dials
- **Answer calls** — phone rings + announces who's calling, say "yes" or "answer" to pick up
- **Hang up** — tap the screen during a call to hang up immediately
- **Reject calls** — say "no" or "ignore" to reject an incoming call
- **Send messages** — say "text John" or "message John", speak the message, confirm with "yes"
- **Read messages** — say "read my messages" to hear unread texts
- **Time & date** — say "what time is it" or "what's the date"
- **Missed calls** — say "who called me"
- **Open contacts** — say "open contacts" for a carer to manage the address book
- **Open settings** — say "open settings" for a carer to adjust phone settings
- **Always ready** — runs as a foreground service, restarts on reboot

---

## Architecture

```
app/src/main/java/com/voicephone/
├── MainActivity.kt           # Pure view layer — state machine renderer
├── VoiceService.kt           # Foreground service engine, owns all state
├── SpeechHandler.kt          # STT + keyword intent parser (Phase 3 LLM seam)
├── TtsManager.kt             # TextToSpeech wrapper (0.85× rate for clarity)
├── ContactsHelper.kt         # ContactsContract lookup + fuzzy name matching
├── CallManager.kt            # ConnectionService implementation
├── InCallHandler.kt          # InCallService — routes call events to VoiceService
├── SmsHelper.kt              # Unread SMS reading + sending
├── IncomingCallReceiver.kt   # Wakes service on incoming call (fallback)
├── SmsReceiver.kt            # Wakes service on incoming SMS
├── BootReceiver.kt           # Restarts service after device reboot
└── PermissionSetupActivity.kt # First-launch TTS-guided permission flow
```

**State machine:**
```
IDLE → (touch) → LISTENING → (speech recognised) → PROCESSING
     → DIALLING / IN_CALL / INCOMING_CALL / IDLE
     → COMPOSING_SMS → CONFIRMING_SMS → IDLE
```

---

## Requirements

- Android API 25+ (Android 7.1 Nougat)
- Target API 34
- Kotlin 1.9 / AGP 8.2
- Google Play Services (required for speech recognition)

---

## Build

1. Clone the repo and open in **Android Studio Hedgehog** or later
2. Connect a physical device (API 25+) — speech recognition requires Google Play Services
3. Run `app` configuration

On first launch, the app walks through all required permissions via TTS. You will also be prompted to set VoicePhone as the **default home screen** and **default phone app** — both are required for full functionality.

### Optional: Cloud TTS (Deepgram)

The app defaults to Android's on-device TTS. To enable the higher-quality Deepgram neural voice:

1. Create a free account at [deepgram.com](https://deepgram.com) and get an API key
2. Add the key to `local.properties` (this file is gitignored and never committed):
   ```
   DEEPGRAM_API_KEY=your_key_here
   ```
3. Rebuild and deploy the app
4. On the device, say **"use cloud voice"** to switch to Deepgram, or **"use local voice"** to switch back

The voice used is `aura-2-thalia-en` — change `DEEPGRAM_VOICE` in `TtsManager.kt` to use a different voice. The app falls back to Android TTS automatically if the network is unavailable or the API call fails.

---

## Permissions required

| Permission | Purpose |
|---|---|
| `CALL_PHONE` | Place outgoing calls |
| `READ_PHONE_STATE` | Default SIM selection for SMS sending |
| `ANSWER_PHONE_CALLS` | Answer/reject incoming calls |
| `READ_CONTACTS` | Look up contacts by name |
| `READ_CALL_LOG` | Report missed calls |
| `READ_SMS` / `SEND_SMS` / `RECEIVE_SMS` | Read and send text messages |
| `RECORD_AUDIO` | Microphone for speech recognition |
| `FOREGROUND_SERVICE` | Keep service alive in background |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot |

---

## Phase roadmap

| Phase | Status | Description |
|---|---|---|
| **1 — MVP** | ✅ Done | Voice calls, SMS read, time/date, incoming call handling |
| **2 — SMS compose + confirmation** | ✅ Done | Speak message body, confirm before send, re-record option |
| **3 — LLM integration** | Planned | Replace `SpeechHandler.parseIntent()` with Claude API for natural language |
| **4 — Family setup app** | Planned | Companion app for carers to manage contacts + preferences |

**Phase 3 seam:** `SpeechHandler.parseIntent()` is the single isolated swap point for Claude API. The offline keyword matcher is the fallback when no network is available — the rest of the call/TTS/SMS stack is unchanged.

---

## Project spec

See [`voicephone-app-spec.md`](voicephone-app-spec.md) for the full product specification.
