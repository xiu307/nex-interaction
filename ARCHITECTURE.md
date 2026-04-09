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
agroacore/src/main/java/
└── ai/conv/internal/
    ├── rtc/          # 发布选项、进房封装、引擎 Config/扩展、IRtcEngineEventHandler 桥接
    ├── rtm/          # RtmConfig、登录状态机、链路 Listener 桥接
    ├── audio/        # CustomAudioInputManager、MicrophoneAudioCaptureManager
    ├── video/        # ExternalVideoCaptureManager
    └── convoai/      # 厂商协议/解析（原 io.agora.convoai.convoaiApi）：RTM 载荷、转写、Agent 状态回调

app/src/main/java/
└── ai/nex/interaction/
    ├── ui/           # AgentChatActivity + ViewModel + dialogs + base classes
    ├── session/      # 会话身份、Connection/Agent 状态、用户统一 Token、Agent REST 编排、RTM 对端常量等
    ├── transcript/   # TranscriptListUpsert（转录列表 upsert 纯函数）
    ├── video/        # 自定义视频发布控制器、CameraX 示例输入
    ├── convoai/      # 业务桥接：事件 Sink、DefaultConversationConvoAiEventSink，对接 agroacore 协议层
    ├── biometric/    # SAL / 人脸 RTM 上行、ROBOT_FACE_SPEAKER_BIND 协调等
    ├── api/          # AgentStarter + TokenGenerator + OkHttp config
    ├── tools/        # Permission helpers、DebugStatusLogList
    ├── KeyCenter.kt     # app 侧配置桥接；对话配置转发自 agroacore 的 ConvoConfig，OSS STS 仍取 app BuildConfig
    └── AgentApp.kt
```

## Runtime Shape

```text
AgentChatActivity / AgentChatViewModel /
RTC / RTM / ConversationalAIAPI / TokenGenerator / AgentStarter
```

`agroacore` 承载 RTC / RTM / ConversationalAIAPI 运行时内核；其中 `ai.conv.internal.convoai` 负责解析 RTM 并回调 Agent/转写，业务监听桥接仍在 `ai.nex.interaction.convoai`。

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
ConvoConfig
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
- `agroacore/src/main/java/ai/conv/internal/convoai/` 可与上游示例对齐整体拷贝；本仓库内若需改协议解析，优先在此包演进并保留变更说明
