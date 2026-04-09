# Conversational AI Quickstart Android — AI Assistant Guide

## How to Use This Project

This is a complete, runnable Android demo for real-time voice conversation with an AI agent.

- If you don't have an existing project, use this project directly. Modify it based on user requirements.
- If you already have a project, refer to the key parts of this project (connection flow, UI structure, ConversationalAIAPI integration) and adapt them into the existing codebase.

## How to Switch AI Providers

The STT/LLM/TTS vendor configuration lives in two places that must be changed together:

1. `agroacore` 内的 `ConvoConfig`（源码内置，`KeyCenter.kt` 仅做桥接；`OSS_STS_TOKEN_URL` 继续留在 `app`）— API keys and user-configurable IDs
2. `AgentStarter.kt` → `buildJsonPayload()` — the JSON builder that specifies vendor names and maps KeyCenter values into the request body

To switch a provider:
- Change the `"vendor"` value in `buildJsonPayload()` (e.g., `"microsoft"` → `"fengming"` for STT)
- Update the `"params"` sub-object to match the new vendor's required fields
- Add/update the corresponding values in `ConvoConfig`，再由 `KeyCenter` 映射进请求体

Supported vendors for STT/TTS/LLM change over time. Refer to the [创建对话式智能体 API 文档](https://doc.shengwang.cn/doc/convoai/restful/convoai/operations/start-agent) for the up-to-date list of supported vendors and their required parameters.

LLM: Any OpenAI-compatible API — change `ConvoConfig` 中的 `LLM_URL` and `LLM_MODEL`.

## Project Overview

Conversational AI Quickstart — Android real-time voice conversation client.

The client directly calls ShengWang RESTful API to start/stop Agent, with STT (Speech-to-Text), LLM (Large Language Model), and TTS (Text-to-Speech) configuration in the request body, authenticated via HTTP token (`Authorization: agora token=<token>`). This auth mode requires APP_CERTIFICATE to be enabled.

Current quickstart scope is limited to voice session startup, default audio self-capture startup, transcript display, state rendering, mute, audio input stop/resume, and stop. It does not expose text or image message sending UI.

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin |
| UI Framework | View + XML Layout + ViewBinding |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 36 |
| Build Tool | Gradle (Kotlin DSL) |
| State Management | ViewModel + StateFlow |
| Networking | OkHttp 5.0.0-alpha.14 |
| RTC SDK | ShengWang RTC SDK (`io.agora.rtc:full-sdk:4.5.1`) |
| RTM SDK | ShengWang RTM SDK (`io.agora:agora-rtm-lite:2.2.6`) |
| Coroutines | Kotlin Coroutines 1.9.0 |
| ConversationalAIAPI | Built-in module, do not modify |

For runtime structure, see `ARCHITECTURE.md`. For entry files, see `README.md`.

## Core Modules

### AgentChatViewModel

- Manages RTC Engine and RTM Client lifecycle
- Subscribes to RTM messages via ConversationalAIAPI, parses Agent state and transcripts
- Exposes four StateFlows:
  - `uiState: StateFlow<ConversationUiState>` — connection state (Idle/Connecting/Connected/Error) + mute + audio input enabled state
  - `agentState: StateFlow<AgentState>` — Agent state (IDLE/SILENT/LISTENING/THINKING/SPEAKING)
  - `transcriptList: StateFlow<List<Transcript>>` — transcript list (deduplicated/updated by turnId + type)
  - `debugLogList: StateFlow<List<String>>` — debug logs (max 20 entries)
- Auto flow: joinRTC + loginRTM → both ready → generateToken → startAgent → auto-start default microphone audio capture
- `userId` / `agentUid` are randomly generated in the companion object, and `channelName` format is `channel_kotlin_<6-digit-random>`

### AgentStarter

- `startAgentAsync()`: POST `/join/`, request body carries full Pipeline config
  - STT: Fengming ASR
  - LLM: 阿里云百炼千问（DashScope OpenAI-compatible endpoint）
  - TTS: 火山引擎（token + app_id + cluster + voice_type）
  - Advanced features: `enable_rtm: true`, `data_channel: "rtm"`, `enable_string_uid: false`, `idle_timeout: 120`
  - Remote UIDs: `remote_rtc_uids: ["<currentUserUid>"]`
- `stopAgentAsync()`: POST `/agents/{agentId}/leave`
- Authentication: `Authorization: agora token=<authToken>` (requires APP_CERTIFICATE enabled)

### TokenGenerator (Demo Only)

- Generates RTC/RTM tokens via demo service at `https://service.apprtc.cn/toolbox/v2/token/generate`
- Sends `appId`, `appCertificate`, `channelName`, `uid`, `types` (1=RTC, 2=RTM) in POST body
- Returns a unified token usable for both RTC and RTM
- **Requires APP_CERTIFICATE**: the demo token service needs `appCertificate` to generate valid tokens
- ⚠️ Demo only — production must use your own backend for token generation

### ConversationalAIAPI

- Wraps RTM message subscription/parsing
- The quickstart currently reacts to:
  - `onAgentStateChanged`
  - `onTranscriptUpdated`
  - `onAgentError` (logged through ViewModel state/logs)
  - `onDebugLog`
- Audio settings: `loadAudioSettings(AUDIO_SCENARIO_AI_CLIENT)` (must be called before joinChannel)

## Configuration

### Configuration Flow

```
ConvoConfig → KeyCenter → AgentStarter / TokenGenerator
```

### Configuration Fields (ConvoConfig)

| Field | Description | Required | Default |
|-------|-------------|----------|---------|
| `APP_ID` | ShengWang App ID | ✅ | — |
| `APP_CERTIFICATE` | ShengWang App Certificate (must be enabled) | ✅ | — |
| `LLM_API_KEY` | DashScope API Key | ✅ | — |
| `LLM_URL` | Qwen OpenAI-compatible endpoint URL | | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| `LLM_MODEL` | Qwen model name | | `qwen-plus` |
| `TTS_BYTEDANCE_APP_ID` | Volcengine TTS App ID | ✅ | — |
| `TTS_BYTEDANCE_TOKEN` | Volcengine TTS access token | ✅ | — |

### APP_CERTIFICATE Must Be Enabled

This project uses HTTP token auth (`Authorization: agora token=<token>`) for REST API calls, and the demo `TokenGenerator` sends `appCertificate` to the token service. Both require the App Certificate to be enabled. If `APP_CERTIFICATE` is empty or the certificate is not enabled in the ShengWang console, token generation and REST API calls will fail.

Make sure to:
1. Enable the primary certificate for your App ID in the [ShengWang Console](https://console.shengwang.cn/)
2. Fill in the certificate value in `agroacore/src/main/java/ai/conv/internal/config/ConvoConfig.kt`

If any are missing, the build fails with a message listing the missing properties.

## API Endpoints

Client directly calls ShengWang REST API (Demo mode):

| Endpoint | Method | Auth Header | Description |
|----------|--------|-------------|-------------|
| `api.agora.io/cn/api/conversational-ai-agent/v2/projects/{appId}/join/` | POST | `Authorization: agora token=<authToken>` | Start Agent |
| `api.agora.io/cn/api/conversational-ai-agent/v2/projects/{appId}/agents/{agentId}/leave` | POST | `Authorization: agora token=<authToken>` | Stop Agent |

Token generated via Demo service (must be replaced with your own backend in production):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `service.apprtc.cn/toolbox/v2/token/generate` | POST | Generate RTC/RTM Token (requires appId + appCertificate) |

### Start Agent Request Body Structure

```json
{
  "name": "<channelName>",
  "properties": {
    "channel": "<channelName>",
    "token": "<agentToken>",
    "agent_rtc_uid": "<agentRtcUid>",
    "remote_rtc_uids": ["<currentUserUid>"],
    "enable_string_uid": false,
    "idle_timeout": 120,
    "advanced_features": { "enable_rtm": true },
    "asr": {
      "vendor": "fengming",
      "language": "zh-CN"
    },
    "llm": {
      "vendor": "aliyun",
      "url": "<LLM_URL>",
      "api_key": "<LLM_API_KEY>",
      "system_messages": [{ "role": "system", "content": "你是一名有帮助的 AI 助手。" }],
      "greeting_message": "你好！我是你的 AI 助手，有什么可以帮你？",
      "failure_message": "抱歉，我暂时处理不了你的请求，请稍后再试。",
      "params": { "model": "<LLM_MODEL>" }
    },
    "tts": {
      "vendor": "bytedance",
      "params": {
        "token": "<TTS_BYTEDANCE_TOKEN>",
        "app_id": "<TTS_BYTEDANCE_APP_ID>",
        "cluster": "volcano_tts",
        "voice_type": "BV700_streaming",
        "speed_ratio": 1.0,
        "volume_ratio": 1.0,
        "pitch_ratio": 1.0
      }
    },
    "parameters": { "data_channel": "rtm", "enable_error_message": true }
  }
}
```

### Token Generation Request Body

```json
{
  "appId": "<APP_ID>",
  "appCertificate": "<APP_CERTIFICATE>",
  "channelName": "<channelName>",
  "uid": "<uid>",
  "types": [1, 2],
  "expire": 86400,
  "src": "Android",
  "ts": "<timestamp>"
}
```

## Data Flow

```
User Action → ViewModel → ShengWang SDK (RTC/RTM)
                  ↓
            StateFlow ← ConversationalAIAPI event callbacks
                  ↓
            Activity observes → UI update
```

## Event Flow

1. User taps Start Agent → check microphone permission
2. Generate userToken (unified for RTC+RTM, channelName is empty string, uid=userId)
3. Parallel: join RTC channel + login RTM (both use the same userToken)
4. Both ready → subscribeMessage(channelName) → generate agentToken + authToken (uid=agentUid, channelName=current channel)
5. Call `AgentStarter.startAgentAsync(channelName, agentRtcUid, agentToken, authToken, remoteRtcUid)` to start Agent, where `remoteRtcUid` is the current user RTC UID
6. Agent starts successfully → default microphone audio capture starts automatically
7. ConversationalAIAPI receives Agent events via RTM → update StateFlow → UI responds
8. User taps Stop → unsubscribeMessage → `AgentStarter.stopAgentAsync(agentId, authToken)` → leave RTC → clean up state

## How to Change Request Parameters

The agent start request body is built in `AgentStarter.kt` → `buildJsonPayload()` as a nested `JSONObject`. Key sections:

| Section | What it controls | Where in the JSON |
|---------|-----------------|-------------------|
| `asr` | Speech-to-text vendor, language, credentials | `properties.asr` |
| `llm` | LLM endpoint, model, system prompt, greeting/failure messages | `properties.llm` |
| `tts` | Text-to-speech vendor, voice, speed | `properties.tts` |
| `parameters` | Data channel (`rtm`), error message toggle | `properties.parameters` |
| `advanced_features` | RTM enable flag | `properties.advanced_features` |
| Top-level | Channel name, agent UID, idle timeout, token | `properties.*` |

To modify request parameters: edit `buildJsonPayload()` in `AgentStarter.kt`. Static values (API keys, model names) should stay in `ConvoConfig` / `KeyCenter`；structural changes (adding fields, changing nesting) go directly in the JSON builder.

## Key Constraints

1. **APP_CERTIFICATE required**: This project uses HTTP token auth for REST API and token generation. APP_CERTIFICATE must be enabled in the ShengWang console and configured in `ConvoConfig`。
2. **Demo Mode**: Config currently hardcoded in `ConvoConfig`，client directly calls REST API
3. **Production**: Sensitive info (appCertificate, LLM/STT/TTS keys) must be on backend; client only fetches Token and starts Agent through backend
4. **Token Generation**: `TokenGenerator.kt` is Demo-only; production must use your own server
5. **Resource Cleanup**: RTC/RTM resources fully released in `hangup()` and `onCleared()`; ConversationalAIAPI released via `destroy()`
6. **Permissions**: Requires `RECORD_AUDIO` and `INTERNET` permissions
7. **Vendor ConversationalAI 解析层**: 首阶段已抽到 `agroacore/src/main/java/ai/conv/internal/convoai/`（由原 `io.agora.convoai.convoaiApi` 迁入）。与声网示例对齐时可整体替换；业务侧继续只通过 `ai.nex.interaction.convoai` 桥接接入。详见 `agroacore/.../convoai/README.md`
8. **Audio Settings**: `loadAudioSettings()` must be called before `joinChannel()`; Avatar mode uses `AUDIO_SCENARIO_DEFAULT`

## File Naming

- Kotlin files: `PascalCase.kt`
- Resource files: `snake_case.xml`
- Layout files: `activity_*.xml` / `item_*.xml` / `dialog_*.xml`

## Documentation Navigation

| Document | Description |
|----------|-------------|
| AGENTS.md | AI Agent development guidelines and project constraints |
| ARCHITECTURE.md | Technical architecture details (data flows, threading, lifecycle) |
| README.md | Quick start and usage guide (Chinese) |
| docs/WORKFLOW_TEMPLATES.md | Workflow entry, routing, and acceptance templates for feat/fix/refactor/chore/docs tasks |
| docs/PROJECT_STATE_TEMPLATE.md | Template for the local `PROJECT_STATE.md` memory file maintained during workflow execution |
| docs/REVIEW_TEMPLATES.md | Review criteria and phase/status output conventions for code and docs tasks |
| docs/PR_CHECKLIST.md | PR review checklist covering app code, workflow assets, docs, and skills |
| .agents/skills/ | Local workflow skills used to manage planning, execution, review, and state handoff |
| PROJECT_STATE.md | Local task state file created and updated by `ac-memory` during active workflow sessions |
