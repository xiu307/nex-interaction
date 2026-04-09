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
│   ├── session/       # 会话身份、Connection/Agent 状态、用户统一 Token（ConversationUserTokenLoader）、Agent REST 编排、RTM 对端常量等
│   ├── transcript/    # TranscriptListUpsert（转录列表 upsert 纯函数）
│   ├── rtc/             # 发布选项、进房封装（ConversationRtcJoinHelper）、引擎 Config/扩展、IRtcEngineEventHandler 桥接
│   ├── video/           # ExternalVideoCaptureManager、自定义视频发布（ConversationExternalVideoPublishController）
│   ├── rtm/             # RtmConfig、登录状态机、链路 Listener 桥接（ConversationRtmEventListener）
│   ├── convoai/         # ConversationalAIAPI 事件 Sink、默认适配（DefaultConversationConvoAiEventSink）、桥接
│   ├── biometric/     # SAL / 人脸 RTM 上行、ROBOT_FACE_SPEAKER_BIND 协调（RobotFaceSpeakerBindCoordinator）等
│   ├── api/           # AgentStarter + TokenGenerator + OkHttp config
│   ├── tools/         # Permission helpers、DebugStatusLogList（调试日志条数上限与追加）
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
  → generate one channel-scoped token（作 Agent RTC token 与 REST `agora token`）
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
