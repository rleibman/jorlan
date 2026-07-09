# Mini-Design: Speech-to-Text and Text-to-Speech

## Why

Several use cases (language-coach, music-coach, meeting-assistant, skill-development) require the ability
to receive spoken input and produce spoken output. Without STT/TTS, these use cases are limited to
text-only interaction — which works for coaching and planning but misses pronunciation practice,
recording review, and natural voice interaction.

The goal is to add STT and TTS capabilities in a layered way: browser-native first (zero cost, zero
server work), then server-side skills for richer scenarios (file transcription, Telegram voice, language
practice with recording upload).

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│ Layer 1: Browser (Web Speech API)                                  │
│   ChatPage mic button → SpeechRecognition → sends text to server   │
│   Agent text response ← SpeechSynthesis ← agent output            │
│   Zero server changes. Free. Chrome/Edge/Safari.                   │
└───────────────────────────────┬────────────────────────────────────┘
                                │ (for richer scenarios)
┌───────────────────────────────▼────────────────────────────────────┐
│ Layer 2: Server-side STT Skill                                     │
│   stt.transcribe_file(workspacePath, language?) → String           │
│   stt.transcribe_url(url, language?) → String                      │
│   Backends: whisper.cpp (local/free) or OpenAI Whisper API         │
└───────────────────────────────┬────────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────────┐
│ Layer 3: Server-side TTS Skill                                     │
│   tts.synthesize(text, voice?, speed?, format?) → workspacePath    │
│   tts.list_voices(language?) → List[Voice]                         │
│   Backends: Piper TTS (local/free) or OpenAI TTS API               │
│   Served via existing StaticRoutes audio endpoints                 │
└───────────────────────────────┬────────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────────┐
│ Layer 4: Telegram Voice Extension                                  │
│   Incoming voice messages → auto-transcribed at connector boundary │
│   Agent TTS responses → sent as Telegram voice messages            │
│   InboundMessage.content stays as String (transcription hidden)    │
└────────────────────────────────────────────────────────────────────┘
```

---

## Layer 1: Browser-side (Web Speech API)

### What it covers
- Voice input in the web chat UI (mic button → speech recognized → sent as text message)
- Voice output in the web chat UI (agent responses optionally read aloud)

### What changes
Only `web/src/main/scala/jorlan/web/pages/ChatPage.scala` needs to change. No server changes.

### Implementation details

The Web Speech API is available globally in the browser. Scala.js accesses it via raw JS interop:

```scala
// In ChatPage.scala — no ScalablyTyped binding needed

import scala.scalajs.js
import org.scalajs.dom

// STT — start recognition
def startListening(onResult: String => Callback): Callback = Callback {
  val SpeechRecognition = js.Dynamic.global.SpeechRecognition
    .asInstanceOf[js.UndefOr[js.Dynamic]]
    .orElse(js.Dynamic.global.webkitSpeechRecognition.asInstanceOf[js.UndefOr[js.Dynamic]])
  SpeechRecognition.foreach { ctor =>
    val recognition = js.Dynamic.newInstance(ctor)()
    recognition.lang = "en-US"
    recognition.interimResults = false
    recognition.onresult = (event: js.Dynamic) => {
      val transcript = event.results(0)(0).transcript.asInstanceOf[String]
      onResult(transcript).runNow()
    }
    recognition.start()
  }
}

// TTS — speak text
def speak(text: String, lang: String = "en-US"): Callback = Callback {
  val utterance = js.Dynamic.newInstance(js.Dynamic.global.SpeechSynthesisUtterance)(text)
  utterance.lang = lang
  dom.window.asInstanceOf[js.Dynamic].speechSynthesis.speak(utterance)
}
```

### UI changes to ChatPage

Add to the `State`:
```scala
micActive:     Boolean   // is mic listening?
ttsEnabled:    Boolean   // should agent responses be read aloud?
```

Add to the render:
- **Mic button** (MUI `IconButton` with `Mic`/`MicOff` icon) beside the text input — toggles `micActive`,
  starts/stops `SpeechRecognition`, on result calls `sendMessage`
- **TTS toggle** (MUI `IconButton` with `VolumeUp`/`VolumeOff` icon) in the chat toolbar — toggles
  `ttsEnabled`; when on, each new agent message triggers `speak(content)`

### Browser compatibility
- Chrome, Edge: full support (SpeechRecognition + SpeechSynthesis)
- Safari (desktop/iOS): SpeechSynthesis supported; SpeechRecognition partial (webkit prefix)
- Firefox: SpeechSynthesis supported; SpeechRecognition not supported (show "not available" gracefully)

### Considerations
- HTTPS is required for microphone access (already the case in production deployments)
- Requires user permission grant for microphone — browser prompts automatically on first use
- No server changes, no API costs, no model downloads

---

## Layer 2: Server-side STT Skill

### When you need this instead of Layer 1

- Language coach: user uploads a recording of themselves speaking for evaluation
- Music coach: user uploads a practice recording (for transcription component)
- Telegram: user sends a voice message
- Batch transcription of meeting recordings
- Any scenario where audio comes as a file rather than live browser microphone

### New SBT subproject: `stt/`

Structure mirrors existing external skills (`weather/`, `search/`, etc.):

```
stt/
  jvm/src/main/scala/jorlan/stt/
    SttSkill.scala
    SttProvider.scala          (trait)
    WhisperLocalProvider.scala (whisper.cpp via shell)
    OpenAiWhisperProvider.scala (OpenAI Whisper API via http)
    SttConfig.scala
  shared/src/main/scala/jorlan/stt/
    SttConfig.scala
```

### Tools exposed by `SttSkill`

```
stt.transcribe_file
  Input:  { workspacePath: String, language?: String }
  Output: { transcript: String, confidence?: Float, duration_seconds?: Float }
  Notes:  workspacePath is relative to the agent's workspace

stt.transcribe_url
  Input:  { url: String, language?: String }
  Output: { transcript: String, confidence?: Float, duration_seconds?: Float }
  Notes:  Downloads audio from URL before transcribing; URL must be accessible from server

stt.supported_languages
  Input:  {}
  Output: { languages: List[{ code: String, name: String }] }
```

### STT provider options

#### Option A: whisper.cpp (local, recommended for self-hosting)

No API costs, runs on-server. Requires:
1. `whisper.cpp` installed on the server (or in Docker image)
2. A Whisper model downloaded (e.g. `ggml-base.en.bin` for English-only, `ggml-medium.bin` for multilingual)
3. Models stored at a configurable path (e.g. `/opt/jorlan/models/whisper/`)

Implementation uses `shell.run` internally or ZIO Process:
```
whisper-cli --model /opt/jorlan/models/whisper/ggml-base.en.bin \
            --file /tmp/audio.ogg \
            --output-txt \
            --language en
```

Models and accuracy:
| Model | Size | Languages | WER (English) | VRAM |
|---|---|---|---|---|
| `tiny.en` | 39MB | English only | ~10% | 1GB |
| `base.en` | 74MB | English only | ~7% | 1GB |
| `medium` | 1.5GB | Multilingual | ~4% | 5GB |
| `large-v3` | 2.9GB | Multilingual | ~2.7% | 10GB |

For language-coach use (multi-language), use `medium` or `large-v3`.

#### Option B: OpenAI Whisper API

Simple REST call, no local infrastructure:
```
POST https://api.openai.com/v1/audio/transcriptions
Content-Type: multipart/form-data
Authorization: Bearer <OPENAI_API_KEY>

file=<audio file>
model=whisper-1
language=en (optional)
```
Cost: $0.006/minute. A 1-minute recording costs less than 1 cent.

#### Configuration

`application.conf`:
```hocon
jorlan.stt {
  provider = "whisper-local"  # or "openai"
  whisper-local {
    binary = "/usr/local/bin/whisper-cli"
    model-path = "/opt/jorlan/models/whisper/ggml-base.en.bin"
    temp-dir = "/tmp/jorlan-stt"
  }
  openai {
    api-key = ${?OPENAI_API_KEY}
    model = "whisper-1"
  }
}
```

### Audio format support

Whisper accepts: flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm.
Telegram voice messages use OGG Opus — compatible out of the box.

---

## Layer 3: Server-side TTS Skill

### New SBT subproject: `tts/`

```
tts/
  jvm/src/main/scala/jorlan/tts/
    TtsSkill.scala
    TtsProvider.scala          (trait)
    PiperTtsProvider.scala     (Piper local TTS via shell)
    OpenAiTtsProvider.scala    (OpenAI TTS API)
    TtsConfig.scala
  shared/src/main/scala/jorlan/tts/
    TtsConfig.scala
```

### Tools exposed by `TtsSkill`

```
tts.synthesize
  Input:  { text: String, voice?: String, speed?: Float (0.25-4.0), format?: "mp3"|"wav"|"ogg" }
  Output: { workspacePath: String, durationSeconds: Float }
  Notes:  Returns workspace path; file served via StaticRoutes audio endpoint

tts.list_voices
  Input:  { language?: String }
  Output: { voices: List[{ id: String, name: String, language: String, gender?: String }] }
```

### Audio serving

Generated audio files are written to the workspace (existing `WorkspaceSkill` storage) and served by
the existing `StaticRoutes` audio endpoint which already handles mp3, wav, ogg, m4a, aac content types.

The agent can share the URL with the user (e.g. in Telegram as a voice message, or in the web UI as
an `<audio>` element rendered in the chat).

### TTS provider options

#### Option A: Piper TTS (local, recommended)

- Open source (Mozilla/Rhasspy project), Apache 2.0 license
- Runs on CPU; no GPU required for inference
- ONNX-based models — very fast (< 1 second for short sentences)
- Excellent voice quality comparable to commercial TTS
- Many voices across 30+ languages
- Models: ~30-80MB each; download from [HuggingFace piper-tts](https://huggingface.co/rhasspy/piper-voices)

Installation:
```bash
pip install piper-tts
# or download the binary release from GitHub
```

Invocation:
```bash
echo "Hello, this is Jorlan." | \
  piper --model /opt/jorlan/models/tts/en_US-ryan-medium.onnx \
        --output_file /tmp/output.wav
```

Recommended voice models:
| Voice | Language | Quality | Size |
|---|---|---|---|
| `en_US-ryan-medium` | English (US) | ★★★★ | 63MB |
| `en_US-kathleen-low` | English (US) | ★★★ | 30MB |
| `es_ES-davefx-medium` | Spanish | ★★★★ | 63MB |
| `fr_FR-upmc-medium` | French | ★★★★ | 63MB |
| `de_DE-thorsten-medium` | German | ★★★★ | 63MB |
| `it_IT-riccardo-x_low` | Italian | ★★★ | 30MB |

#### Option B: OpenAI TTS API

Simple REST call:
```
POST https://api.openai.com/v1/audio/speech
Content-Type: application/json
Authorization: Bearer <OPENAI_API_KEY>

{
  "model": "tts-1",
  "input": "Hello, this is Jorlan.",
  "voice": "onyx",
  "response_format": "mp3"
}
```
Cost: $15/1M characters ($0.000015/char). Very natural-sounding voices.
Available voices: alloy, echo, fable, onyx, nova, shimmer.

#### Configuration

`application.conf`:
```hocon
jorlan.tts {
  provider = "piper"  # or "openai"
  piper {
    binary = "/usr/local/bin/piper"
    models-dir = "/opt/jorlan/models/tts"
    default-voice = "en_US-ryan-medium"
    output-dir = "/tmp/jorlan-tts"
  }
  openai {
    api-key = ${?OPENAI_API_KEY}
    model = "tts-1"
    default-voice = "onyx"
  }
}
```

---

## Layer 4: Telegram Voice Extension

### What changes in TelegramConnectorSkill

#### Incoming voice messages

Telegram sends `voice` and `audio` message objects instead of `text`. Currently the connector only
handles text. Extension:

1. Detect `voice`/`audio` message type in the Telegram polling loop
2. Call Telegram `getFile` API to get the file path
3. Download the file to a temp location
4. Call `SttSkill.transcribe_file(path)` to get the transcript
5. Create `InboundMessage(content = transcript, ...)` — same shape as a text message
6. Agent receives text; no change to agent layer

The STT transcription happens **at the connector boundary** — `InboundMessage.content` stays as `String`.
This is the cleanest approach: the agent always sees text.

Optional: prefix the content with `[Voice message, ~30s]: ` so the agent knows it came from voice.

#### Outgoing voice messages

Add a new tool to `TelegramConnectorSkill`:
```
telegram.send_voice
  Input:  { chatRef: String, workspacePath: String, caption?: String }
  Output: { messageId: String }
  Notes:  Sends the audio file at workspacePath as a Telegram voice message
```

The language-coach agent can then:
1. Generate pronunciation example: `tts.synthesize("Le chien mange", voice="fr_FR-upmc-medium")`
2. Send it: `telegram.send_voice(chatRef=..., workspacePath=...)`

#### TelegramConnectorSkill dependency on SttSkill

The connector needs access to the STT skill for auto-transcription. Options:
- Inject `SttProvider` as a ZIO dependency into `TelegramConnectorSkill`
- Or keep them decoupled: connector stores raw voice file in workspace, agent then calls `stt.transcribe_file`

**Recommendation:** inject `SttProvider` into the connector for seamless voice-message handling.
The agent should not need to know that a voice message arrived; it just sees text.

---

## Phase Sequencing

### Phase A: Browser-only (quick win, 1–2 days)

**What:** Add mic button and TTS toggle to `ChatPage.scala` using the Web Speech API.

**Changes:**
- `web/src/main/scala/jorlan/web/pages/ChatPage.scala` — add `micActive`, `ttsEnabled` state; add mic button; call `speak()` on agent messages when TTS enabled
- No server changes, no new dependencies, no API costs

**Value:** Immediate voice interaction in the web UI for all use cases.

### Phase B: Server-side STT skill (1–3 days)

**What:** New `stt` SBT subproject with `WhisperLocalProvider` (or `OpenAiWhisperProvider`).

**Changes:**
- New `stt/` subproject
- Add to `build.sbt`
- Register in `SkillRegistry` / `EnvironmentBuilder`
- `application.conf` — add `jorlan.stt` block
- If using whisper.cpp: add to Docker image or server setup docs

**Value:** Language coach can evaluate uploaded recordings; files from any source can be transcribed.

### Phase C: Server-side TTS skill (1–3 days)

**What:** New `tts` SBT subproject with `PiperTtsProvider` (or `OpenAiTtsProvider`).

**Changes:**
- New `tts/` subproject
- Add to `build.sbt`
- Register in `SkillRegistry` / `EnvironmentBuilder`
- `application.conf` — add `jorlan.tts` block
- If using Piper: add to Docker image or server setup docs; download voice models

**Value:** Language coach can speak words/sentences back to user; agents can send voice replies.

### Phase D: Telegram voice integration (1–2 days)

**What:** Extend `TelegramConnectorSkill` to handle incoming voice messages and send voice replies.

**Changes:**
- `telegram/jvm/src/main/scala/jorlan/telegram/TelegramConnectorSkill.scala` — detect voice message type, transcribe, dispatch as text `InboundMessage`; add `telegram.send_voice` tool
- Inject `SttProvider` into `TelegramConnectorSkill` (ZIO layer)
- New test: `TelegramVoiceMessageSpec`

**Value:** Users can speak to Jorlan agents via Telegram voice messages; agents can reply with spoken audio.

---

## Required Infrastructure

| Item | Type | Provider | Est. cost |
|---|---|---|---|
| whisper.cpp binary | Server binary | Open source | Free |
| Whisper model files | Model weights | OpenAI (open weights) | Free |
| Piper TTS binary | Server binary | Mozilla/rhasspy (Apache 2.0) | Free |
| Piper voice model files | Model weights | rhasspy/piper-voices | Free |
| Storage for audio temp files | Server disk | Existing workspace | ~100MB/day max |
| **OR** OpenAI Whisper API | Cloud API | OpenAI | ~$0.006/min |
| **OR** OpenAI TTS API | Cloud API | OpenAI | ~$15/1M chars |

**Recommendation:** Use local Piper + whisper.cpp for self-hosted deployments (zero recurring cost);
offer OpenAI API as a configuration alternative for users who prefer cloud quality over setup effort.

---

## What Remains Out of Scope

| Requirement | Why |
|---|---|
| Real-time streaming transcription (live meeting) | Requires audio stream from meeting platform (Zoom/Meet bot), not just file upload |
| Audio analysis / pitch detection (music-coach) | Requires a specialized ML model (Librosa, aubio, etc.); separate from STT |
| Speaker diarization (who said what) | Not supported by basic Whisper; requires additional models (pyannote.audio) |
| Emotion/sentiment from voice | Specialized ML; out of scope |
| Custom voice cloning | Would require ElevenLabs or equivalent; significant cost |

---

## Summary

| Layer | Effort | Cost | Covers |
|---|---|---|---|
| A: Browser Web Speech API | 1–2 days | Free | Web UI voice input/output |
| B: STT skill (whisper.cpp or OpenAI) | 1–3 days | Free / $0.006/min | File transcription, uploads |
| C: TTS skill (Piper or OpenAI) | 1–3 days | Free / $15/1M chars | Voice output, audio replies |
| D: Telegram voice extension | 1–2 days | n/a | Voice messages in Telegram |

**Total effort estimate: 4–10 days** (parallelizable; A and B+C can be done simultaneously).

Layers B and C depend on the same infrastructure setup (binary install + model download) and should
be implemented together as a single phase.
