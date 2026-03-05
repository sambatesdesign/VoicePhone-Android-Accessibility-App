# VoicePhone — Android Accessibility App
## Spec for Claude Code

---

## Overview

A full-screen Android accessibility app for blind/visually impaired users. 
Replaces the standard Android launcher and dialler entirely. 
Controlled entirely by touch-to-activate + voice commands.
Target user: elderly blind person, comfortable with voice assistants.

---

## Core UX Principle

> Touch anywhere → Lili speaks → User says what they want → It happens

No lock screen. No icons. No menus. No reading required. Ever.

---

## Technical Architecture

### App Role
- **Default launcher** — app IS the home screen, boots directly into it
- **Default dialler** — owns all call UI via `ConnectionService`
- **Foreground service** — always alive, never killed by Android
- **BroadcastReceiver** — wakes on incoming SMS regardless of app state

### Key Android APIs
- `SpeechRecognizer` — on-device STT (works offline, no internet needed)
- `TextToSpeech` — all audio feedback
- `ConnectionService` / `InCallService` — intercept and own call UI
- `TelecomManager` — register as default phone app
- `SmsManager` + `ContentResolver` — send/read SMS
- `ContactsContract` — read contacts by name
- `PowerManager.WakeLock` + `FLAG_KEEP_SCREEN_ON` — screen always on
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prevent service being killed
- `BroadcastReceiver` for `SMS_RECEIVED` and `PHONE_STATE`

### Language / Build
- **Kotlin**
- **Android SDK min API 26** (Android 8.0) — covers ~99% of phones in use
- **Target API 34** (Android 14)
- Standard Gradle build, no exotic dependencies
- No backend required — fully self-contained on device

---

## Screens / States

The app has essentially ONE screen with multiple states. 
Not traditional navigation — state machine driven by voice.

### State 1: IDLE
- Black screen, large white text: **"Touch to speak"**
- Subtle pulsing animation to show it's alive
- Screen never turns off (`FLAG_KEEP_SCREEN_ON`)

### State 2: LISTENING
- Triggered by any touch anywhere on screen
- TTS says: *"Hello, how can I help?"* (first use) or just a short beep
- `SpeechRecognizer` starts, listening indicator shown (simple animated circle)
- Timeout after 8 seconds → returns to IDLE with TTS: *"I didn't hear anything"*

### State 3: PROCESSING
- Brief state while intent is parsed
- TTS says something appropriate (see intent list below)

### State 4: IN CALL
- Full screen call UI
- Large text showing who is being called / who is calling
- TTS announces call status: *"Calling Sam…"*, *"Call connected"*, *"Call ended"*
- Touch anywhere → TTS reads options: *"Say hang up to end the call"*
- No buttons needed but optionally show large HANG UP button as fallback

### State 5: INCOMING CALL
- App comes to foreground automatically
- TTS announces repeatedly: *"Incoming call from Sam. Say answer or ignore."*
- Touch anywhere → repeats the announcement
- Voice: "answer" → accepts call, "ignore" → rejects

### State 6: INCOMING SMS NOTIFICATION  
- TTS announces: *"You have a message from Sam. Say read it to hear it."*
- Voice: "read it" → reads message aloud
- Voice: "ignore" / no response → returns to IDLE

---

## Voice Intent Handlers

All intent matching should be fuzzy / forgiving — user may phrase things differently.

### Calling
| Utterance examples | Action |
|---|---|
| "Call Sam" / "Phone Sam" / "Ring Sam" | Look up Sam in contacts, dial |
| "Call 07700 900123" | Dial number directly |
| "Call the doctor" / "Call my GP" | Match against contact name |
| "Answer" / "Answer the call" | Accept incoming call |
| "Ignore" / "Reject" / "Not now" | Reject incoming call |
| "Hang up" / "End call" / "Goodbye" | End active call |

### SMS
| Utterance examples | Action |
|---|---|
| "Read my messages" / "Any messages?" | Read unread SMS aloud, oldest first |
| "Read it" | Read the pending/last message |
| "Send a message to Sam" | Prompt for message content, then send |
| "Reply" | Reply to last received SMS |

### Information
| Utterance examples | Action |
|---|---|
| "What time is it?" | TTS reads current time |
| "What day is it?" / "What's the date?" | TTS reads date |
| "Who called me?" / "Any missed calls?" | Read missed call log |

### Help
| Utterance examples | Action |
|---|---|
| "Help" / "What can you do?" | TTS reads list of commands |

---

## Intent Parsing

Keep this simple — no LLM needed, no internet needed.

Use a **keyword matching approach**:
1. Normalise utterance to lowercase, strip punctuation
2. Check for trigger keywords in order of priority:
   - Contains "call" / "phone" / "ring" → CALL intent, extract name/number
   - Contains "answer" → ANSWER intent
   - Contains "hang up" / "end" / "goodbye" → HANGUP intent  
   - Contains "message" / "text" / "sms" → SMS intent
   - Contains "read" → READ intent
   - Contains "time" → TIME intent
   - Contains "date" / "day" → DATE intent
   - Contains "help" → HELP intent
3. For CALL intent: extract everything after "call"/"phone"/"ring" as the contact name, fuzzy match against contacts

Contact name matching:
- Exact match first
- Then `String.contains()` / starts-with
- If multiple matches → TTS: *"I found Sam Smith and Sam Jones. Which one?"*
- If no match → TTS: *"I couldn't find anyone called [name] in your contacts"*

---

## Permissions Required

```
CALL_PHONE
READ_CONTACTS  
READ_CALL_LOG
READ_SMS
SEND_SMS
RECEIVE_SMS
RECORD_AUDIO
FOREGROUND_SERVICE
RECEIVE_BOOT_COMPLETED (restart service after reboot)
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
SYSTEM_ALERT_WINDOW (overlay for incoming calls)
```

All requested at first launch with TTS explanation of why each is needed.

---

## Setup / First Launch Flow

1. App opens → TTS: *"Welcome to VoicePhone. I need a few permissions to work properly."*
2. Request each permission with TTS explanation before each dialog
3. Prompt to set as default launcher → opens Android settings, TTS guides user
4. Prompt to set as default phone app → same
5. Request battery optimisation exemption
6. TTS: *"You're all set. Touch the screen at any time and tell me what you'd like to do."*

Setup is a one-time flow. After reboot, goes straight to IDLE state.

---

## Contacts

- Read from Android contacts (`ContactsContract`) — no separate contacts management needed
- Family sets up contacts on the phone normally before giving it to user
- App never needs to let user manage contacts (can be done by family)
- On first load, cache contact list in memory for fast lookup

---

## Audio Behaviour

- **TTS always plays through earpiece during calls, speaker otherwise**
- STT does NOT start while TTS is speaking (avoid feedback loop)
- TTS speech rate: slightly slower than default (0.85x) for elderly users
- TTS pitch: default
- All system sounds muted except ringtone and call audio
- Ringtone for incoming calls: standard, loud

---

## What This App Does NOT Do

- No internet browsing
- No apps
- No camera (accessible via Android settings if family sets up)
- No email
- No WhatsApp / messaging apps
- No settings accessible from main UI (family configures phone normally)
- No lock screen PIN (family decision, but recommend none for this user)

---

## Project Structure (suggested)

```
app/
  src/main/
    java/com/voicephone/
      MainActivity.kt          — single activity, state machine
      VoiceService.kt          — foreground service, always running
      SpeechHandler.kt         — STT + intent parsing
      CallManager.kt           — ConnectionService implementation  
      SmsManager.kt            — read/send SMS
      ContactsHelper.kt        — contact lookup + fuzzy match
      TtsManager.kt            — TextToSpeech wrapper
      IncomingCallReceiver.kt  — BroadcastReceiver for calls
      SmsReceiver.kt           — BroadcastReceiver for SMS
    res/
      layout/activity_main.xml — minimal, mostly drawn in code
      values/strings.xml       — all TTS strings centralised here
  AndroidManifest.xml
```

---

## MVP Scope (Phase 1)

Get this working first:

1. App as launcher, screen always on, touch to speak
2. "Call [name]" — look up contact, dial via native telephony  
3. Incoming call announcement + answer/reject by voice
4. "Hang up" to end calls
5. "What time is it" / "What's the date"
6. Foreground service survives reboot

**Not in MVP:**
- SMS (phase 2)
- Missed call log reading (phase 2)
- Multi-match contact disambiguation (phase 2 — just call first match for now)

---

## Phase 2 (after MVP works)

- Full SMS read/send/reply
- Missed call log
- Contact disambiguation ("which Sam?")
- Low battery TTS warning
- Remote welfare check via SMS (like Liliphone's monitoring feature)
- Possibly: swap native telephony for SIP/Loopup trunk for proper CLI

---

## Notes for Claude Code

- Keep the codebase simple and readable — this may need to be maintained/extended
- Avoid third-party dependencies where Android SDK covers it
- All user-facing strings should go in `strings.xml` — makes it easy to adjust TTS phrasing
- The state machine in MainActivity should be explicit and easy to follow
- Test on Android emulator with API 26 minimum
- The `ConnectionService` implementation is the most complex part — worth tackling first to validate feasibility

---

## Phase 3 — LLM Integration (Claude API)

### Overview

The intent parsing layer in `SpeechHandler.kt` is deliberately simple keyword matching for MVP. It is designed to be replaced or augmented with Claude API calls in a later phase without touching any other part of the codebase. Everything else — call stack, TTS, SMS, foreground service, ConnectionService — stays identical.

### Architecture Change

```
Phase 1 (MVP):
Utterance → SpeechHandler keyword matcher → Intent → Action

Phase 3 (LLM):
Utterance → SpeechHandler → Claude API → Intent + Response → Action
```

### Graceful Degradation

Network availability should gate LLM usage — the app must remain fully functional offline:

```kotlin
fun handleUtterance(text: String) {
    if (isNetworkAvailable()) {
        claudeHandler.process(text)   // rich, natural language
    } else {
        keywordMatcher.process(text)  // reliable offline fallback
    }
}
```

Never make the app dependent on the network for core calling functionality.

### What LLM Integration Unlocks

- **Natural phrasing** — "I need to speak to my doctor about my prescription" rather than requiring exact trigger words
- **Ambiguity resolution** — "Call my son" when multiple sons exist → Claude asks naturally which one
- **General questions** — weather, news summaries, "what was on Radio 4 this morning"
- **Better SMS composition** — user dictates naturally, Claude cleans up grammar/punctuation before sending
- **Context awareness** — "Call Sam back, he rang earlier" → Claude checks call log and resolves who Sam is
- **Companionship / conversation** — genuinely valuable for an isolated elderly user living alone
- **Medication or appointment reminders** — "remind me to take my tablets at 8pm"

### Implementation Notes

- Use **Claude claude-sonnet-4-20250514** via the Anthropic API (`api.anthropic.com/v1/messages`)
- Pass a **system prompt** that establishes context: user is elderly and blind, available actions are call/SMS/time/date/help, respond concisely in plain spoken English suitable for TTS
- Pass the **contact list** in the system prompt or as context so Claude can resolve names to numbers directly
- Pass **recent call log** as context for "call back" type requests
- Return structured intent JSON from Claude (function calling / tool use) so the app can act on it, not just speak a response
- TTS speaks Claude's natural language response while the action executes in parallel

### Suggested System Prompt Skeleton

```
You are a voice assistant for an elderly blind user named [name].
You help them make phone calls, send and read text messages, and answer simple questions.
Always respond in short, clear spoken English — your response will be read aloud.
Never use markdown, lists, or special characters in your response.

The user's contacts are:
[inject contact list here]

Recent missed calls:
[inject call log here]

When the user wants to perform an action, respond with:
1. A short spoken confirmation (e.g. "Calling Sam now")
2. A structured JSON intent block so the app can act on it

Available intents: CALL, ANSWER, HANGUP, READ_SMS, SEND_SMS, TIME, DATE, MISSED_CALLS, CONVERSE
```

### Longer Term Product Vision

This architecture — voice-first, Claude-powered, backed by telco SIP infrastructure for proper CLI — has genuine commercial potential as an accessibility product. There is currently no equivalent on the market. The Liliphone comparison is instructive: that product charges a premium for a simple Android app with basic voice control. A Claude-integrated version would be substantially more capable.

Keep this in mind during MVP build: structure `SpeechHandler.kt` as a clean interface so the LLM swap is additive, not a rewrite.
