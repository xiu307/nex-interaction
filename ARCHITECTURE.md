# Architecture — Conversational AI Quickstart Android Kotlin

## Architecture Overview

This quickstart is a single-screen voice conversation demo built with Android Views + XML.

Current scope:

- Start Agent
- RTC join + RTM login
- audio self-capture auto-start after agent connection
- Real-time transcript rendering
- Agent status rendering
- Mute / unmute
- Stop Agent and cleanup

Out of scope for this quickstart:

- Text or image message sending UI
- Multi-screen business flow
- Backend-owned token / agent startup flow

## Page Layout

The Activity page is intentionally single-page and is organized into these regions:

- title and subtitle
- log panel
- transcript panel
- bottom agent status bar
- start / retry / mute / audio input stop-resume / stop controls

## Project Structure

```text
app/src/main/java/
├── cn/shengwang/convoai/quickstart/
│   ├── ui/            # AgentChatActivity + ViewModel + dialogs + base classes
│   ├── api/           # AgentStarter + TokenGenerator + OkHttp config
│   ├── tools/         # Permission helpers
│   ├── KeyCenter.kt
│   └── AgentApp.kt
└── io/agora/convoai/convoaiApi/
    └── ...            # Read-only RTM parsing / transcript component
```

## Runtime Shape

```text
AgentChatActivity / AgentChatViewModel /
RTC / RTM / ConversationalAIAPI / TokenGenerator / AgentStarter
```

`convoaiApi/` is a read-only module that parses RTM payloads and emits agent / transcript callbacks.

## Connection Flow (User taps Start Agent)

```text
Tap Start Agent
  → check microphone permission
  → generate userToken
  → join RTC + login RTM
  → subscribe RTM channel
  → generate agentToken + authToken
  → POST /join/ with inline ASR / LLM / TTS config
  → auto-start default microphone audio capture
  → save agentId
  → uiState = Connected
```

Kotlin-specific conventions:

- `userId` and `agentUid` are random 6-digit integers and do not conflict
- `channelName` format is `channel_kotlin_<6-digit-random>`
- REST auth header is `Authorization: agora token=<authToken>`

## Transcript Data Flow

```text
RTM message
  → ConversationalAIAPI
  → TranscriptController
  → AgentChatViewModel.addTranscript(...)
  → transcriptList update
  → AgentChatActivity refreshes transcript bubbles
```

The current UI renders:

- agent transcript on the left with `AI`
- user transcript on the right with `Me`

## UI State Rendering

```text
uiState        → Start / Connecting / Retry / Mute / Audio Input / Stop buttons
agentState     → bottom status bar color + text
transcriptList → transcript panel content
debugLogList   → log panel content
```

## Token Flow

The quickstart generates three token roles through the demo token service:

| Token | Purpose | Usage |
|-------|---------|-------|
| `userToken` | User RTC join + RTM login | `joinRtcChannel()` / `loginRtm()` |
| `agentToken` | Agent RTC join credential | Request body `properties.token` |
| `authToken` | REST API authentication | `Authorization: agora token=<authToken>` |

Notes:

- `userToken` uses `channelName=""` in the current demo flow
- `agentToken` and `authToken` are generated after RTC / RTM are both ready
- Production should replace the demo token service with a backend

## Agent Lifecycle

```text
IDLE
  → LISTENING
  → THINKING
  → SPEAKING
  → LISTENING
```

Additional behavior:

- `SILENT` can appear after interruption
- tapping `Stop Agent` unsubscribes RTM, stops the Agent, leaves RTC, and resets UI state back toward idle

## Config Contract

```text
env.properties
  → BuildConfig
  → KeyCenter
  → AgentStarter / TokenGenerator / ViewModel
```

Required fields:

- `APP_ID`
- `APP_CERTIFICATE`
- `LLM_API_KEY`
- `TTS_BYTEDANCE_APP_ID`
- `TTS_BYTEDANCE_TOKEN`

Optional fields:

- `LLM_URL`
- `LLM_MODEL`

Current default inline pipeline:

- ASR: `fengming`
- LLM: `aliyun` + `LLM_URL` + `LLM_MODEL`
- TTS: `bytedance`

## Constraints

- This is a demo; token generation and agent startup are client-side for convenience
- Production should move token generation and REST startup to a backend
- `convoaiApi/` should be copied as-is and not modified in place
