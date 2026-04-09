# `session/` 包说明

本目录存放**单次对话会话**相关的状态与编排类型（身份、连接态、Agent REST、用户 Token 加载等），与 UI 层解耦，便于单测与复用。

## 生产环境（必读）

本工程为 **Demo**：客户端可直接请求演示 Token、调用 REST 启停 Agent，密钥当前直接内嵌在 `agroacore` 的 `ConvoConfig` 中。

**上线时**须改为：**Token 与启停 Agent 均由你们自己的后端完成**，客户端只拿短期凭证、不持有 `appCertificate` / LLM·TTS 等敏感配置。详见**仓库根目录** [`AGENTS.md`](../../../../../../../../../AGENTS.md) 中 **Key Constraints**（含 Production）与 **Token Generation** 相关段落。

## 与 Token 相关的类型

- **ConversationUserTokenLoader**：用户侧 RTC+RTM 统一 Token（`channelName` 为空等演示约定）。
- **ConversationAgentRestCoordinator**：频道级 Token 与 `AgentStarter` 启停；当前实现将同一枚频道 Token 用于 Agent 进房与 REST `Authorization`（若服务端要求分离，只改协调类即可）。
