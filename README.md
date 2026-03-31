# Conversational AI Quickstart — Android Kotlin

## 功能概述

### 解决的问题

本示例项目展示了如何在 Android 应用中集成声网 Conversational AI（对话式 AI）功能，实现与 AI 语音助手的实时对话交互。主要解决以下问题：

- **实时语音交互**：通过声网 RTC SDK 实现与 AI 代理的实时音频通信
- **消息传递**：通过声网 RTM SDK 实现与 AI 代理的消息交互和状态同步
- **实时转录**：支持实时显示用户和 AI 代理的对话转录内容，包括转录状态（进行中、完成、中断等）
- **状态管理**：统一管理连接状态、Agent 启动状态、静音状态、转录状态等 UI 状态
- **自动流程**：自动完成频道加入、RTM 登录、Agent 启动等流程
- **统一界面**：所有功能（日志、状态、转录、控制按钮）集成在同一个页面

### 适用场景

- 智能客服系统：构建基于 AI 的实时语音客服应用
- 语音助手应用：开发类似 Siri、小爱同学的语音助手功能
- 实时语音转录：实时显示用户和 AI 代理的对话转录内容
- 语音交互游戏：开发需要语音交互的游戏应用
- 教育培训：构建语音交互式教学应用

### 前置条件

- Android SDK API Level 26（Android 8.0）或更高
- 声网开发者账号 [Console](https://console.shengwang.cn/)
- 已在声网控制台开通 **实时消息 RTM** 功能（必需）
- 已创建声网项目并获取 App ID 和 App Certificate

## 快速开始

### 依赖安装

1. **克隆项目**：
```bash
git clone https://github.com/AgoraIO-Community/conversational-ai-quickstart-native.git
cd conversational-ai-quickstart-native/android-kotlin
```

2. **配置 Android 项目**：
    - 使用 Android Studio 打开项目
    - 配置声网 Key：

      复制 `env.example.properties` 文件为 `env.properties`：
         ```bash
         cp env.example.properties env.properties
         ```

   编辑 `env.properties` 文件，填入你的实际配置值：
      ```properties
      APP_ID=your_agora_app_id
      APP_CERTIFICATE=your_agora_app_certificate
      LLM_API_KEY=sk-your_dashscope_api_key
      TTS_BYTEDANCE_APP_ID=your_bytedance_app_id
      TTS_BYTEDANCE_TOKEN=your_bytedance_access_token
      ```

   **配置项说明**：
    - `APP_ID`：你的声网 App ID（必需）
    - `APP_CERTIFICATE`：你的 App Certificate（必需，用于 Token 生成和 REST API 认证）
    - `LLM_API_KEY`：LLM API Key（必需，如阿里云百炼 DashScope）
    - `LLM_URL`：LLM API 地址（可选，默认 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`）
    - `LLM_MODEL`：LLM 模型名称（可选，默认 `qwen-plus`）
    - `TTS_BYTEDANCE_APP_ID`：火山引擎 TTS App ID（必需）
    - `TTS_BYTEDANCE_TOKEN`：火山引擎 TTS Access Token（必需）
    - 火山引擎其余 TTS 默认参数当前直接写在代码里：`cluster=volcano_tts`、`voice_type=BV700_streaming`、`speed_ratio=1.0`、`volume_ratio=1.0`、`pitch_ratio=1.0`
    - 凤鸣 ASR 为声网内置供应商，当前示例不需要单独配置额外凭证

   **获取方式**：
    - 体验声网对话式 AI 引擎前，你需要先在声网控制台创建项目并开通对话式 AI 引擎服务，获取 App ID 和 App Certificate。[开通服务](https://doc.shengwang.cn/doc/convoai/restful/get-started/enable-service)
    - LLM API Key：在 [阿里云百炼](https://help.aliyun.com/zh/model-studio/get-api-key) 获取
    - TTS 凭证：在 [火山引擎豆包语音文档](https://www.volcengine.com/docs/6561/105873) 对应控制台获取

   **注意**：
    - `env.properties` 文件包含敏感信息，不会被提交到版本控制系统。请确保不要将你的实际凭证提交到代码仓库。
    - 每次启动时会自动生成随机的 channelName，格式为 `channel_kotlin_<6-digit-random>`，无需手动配置。
    - ⚠️ **重要**：`TokenGenerator.kt` 中的 Token 生成功能仅用于演示和开发测试，**生产环境必须使用自己的服务端生成 Token**。代码中已添加详细警告说明。
    - 当前默认 pipeline 为 `fengming + qwen + bytedance`：LLM 只要求 `LLM_API_KEY`，TTS 只要求 `TTS_BYTEDANCE_APP_ID` 和 `TTS_BYTEDANCE_TOKEN`。
   - 等待 Gradle 同步完成

3. **配置 Agent 启动方式**：
   
   默认配置，无需额外设置。Android 应用直接调用声网 RESTful API 启动 Agent，方便开发者快速体验功能。
   
   **使用前提**：
   - 确保已正确配置 `env.properties` 文件中的声网凭证和 Fengming/Qwen/Volcengine 配置。
   
   **适用场景**：
   - 快速体验和功能验证
   - 无需启动额外服务器，开箱即用
   
   ⚠️ **重要说明**：
   - 此方式**仅用于快速体验和开发测试**，**不推荐用于生产环境**
   - 直接在前端调用声网 RESTful API 会暴露 LLM/STT/TTS API Key，存在安全风险
   
   ⚠️ **生产环境要求**：
   - **必须将敏感信息放在后端**：`appCertificate`、LLM/STT/TTS API Key 等敏感信息必须存储在服务端，绝对不能暴露在客户端代码中
   - **客户端通过后端获取 Token**：客户端请求自己的业务后台接口，由服务端使用 `appCertificate` 生成 Token 并返回给客户端
   - **客户端通过后端启动 Agent**：客户端请求自己的业务后台接口，由服务端调用声网 RESTful API 启动 Agent（包含 STT/LLM/TTS 配置）
   - **参考实现**：可参考 `../server-python/agora_http_server.py` 了解如何在服务端实现 Token 生成和 Agent 启动接口

## 测试验证

### 快速体验流程

1. **Agent Chat 页面**（`AgentChatActivity`）：
   - 运行应用，进入 Agent Chat 页面
   - 页面布局从上到下依次为：
     - **日志区域**：显示 Agent 启动相关的状态消息
     - **转录列表区域**：
       - **聊天气泡**：Agent 消息左对齐（"AI" 头像），用户消息右对齐（"Me" 头像）
       - **Agent 状态指示器**：底部显示圆点 + 状态文字，颜色随状态变化
     - **控制按钮**：初始显示"Start Agent"按钮
   
2. **启动 Agent**：
   - 点击"Start Agent"按钮
   - 按钮变为禁用态"Connecting..."，应用自动：
     - 生成随机 channelName（格式：`channel_kotlin_<6-digit-random>`）
     - 加入 RTC 频道并登录 RTM
     - 连接成功后自动启动 AI Agent
   - 如果启动失败，按钮变为"Retry"
   - Agent 启动成功后：
     - "Start Agent"按钮隐藏
     - 显示麦克风按钮（圆形）和"Stop Agent"按钮
     - 可以开始与 AI Agent 对话

3. **对话交互**：
   - 实时显示聊天气泡形式的转录内容
   - 麦克风按钮支持静音/取消静音
   - 点击"Stop Agent"按钮结束对话并断开连接

### 功能验证清单

- ✅ RTC 频道加入成功
- ✅ RTM 登录成功
- ✅ Agent 启动成功（按钮状态变化，显示麦克风和停止按钮）
- ✅ 音频传输正常（能够听到 AI 回复）
- ✅ 转录功能正常（聊天气泡形式显示对话内容）
- ✅ Agent 状态指示正常
- ✅ 静音/取消静音功能正常
- ✅ 停止功能正常（断开连接，按钮恢复为 Start Agent）
- ✅ 错误处理正常（失败时可重试）

## 关键文件

- `AgentChatActivity.kt`：主界面，包含日志显示、Agent 状态指示器、聊天气泡转录列表和控制按钮
- `AgentChatViewModel.kt`：业务逻辑层，包含 RTC 引擎、RTM 客户端的管理和 Agent 启动逻辑
- `AgentStarter.kt`：Agent 启动 API 封装，使用 `agora token=<token>` 认证模式，内联 Fengming/Qwen/Volcengine pipeline 配置
- `TokenGenerator.kt`：Token 生成工具（仅用于开发测试，生产环境需使用服务端生成）
- `io/agora/convoai/convoaiApi/`：声网 ConversationalAIAPI 封装，禁止修改

## License

See [LICENSE](./LICENSE).
