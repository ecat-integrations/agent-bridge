# integration-agent-bridge

ECAT Agent Bridge — MCP Server 集成模块，让外部 AI Agent 通过 MCP 协议（Model Context Protocol）与 ECAT 平台交互。

## 概述

Agent Bridge 作为 ECAT 与 AI Agent 之间的桥梁，将 ECAT 平台的设备管理、数据采集、告警处理等能力以 MCP 工具（Tools）、资源（Resources）、提示词（Prompts）的形式暴露给外部 Agent。

**支持的 Agent 框架**：Claude Code、ChatGPT、Cursor、Windsurf 等所有支持 MCP 协议的客户端。

**传输协议**：MCP Streamable HTTP（JSON-RPC 2.0 over HTTP POST + SSE）。

## 功能特性

### MCP 协议支持

| 功能 | MCP 方法 | 说明 |
|------|---------|------|
| 握手初始化 | `initialize` | 协议版本协商、能力声明 |
| 工具发现 | `tools/list` | 列出当前可用工具 |
| 工具调用 | `tools/call` | 执行指定工具 |
| 资源列表 | `resources/list` | 列出只读资源 |
| 资源读取 | `resources/read` | 读取指定资源内容 |
| 提示词列表 | `prompts/list` | 列出可用提示词模板 |
| 提示词获取 | `prompts/get` | 获取填充后的提示词 |
| SSE 事件推送 | GET `/mcp` | 实时事件通知流 |
| 会话管理 | DELETE `/mcp` | 关闭 MCP 会话 |

### 6 个内置工具

始终可见，无需加载能力组：

| 工具名 | 说明 | 安全级别 |
|--------|------|---------|
| `ecat_search_capabilities` | 按关键词搜索能力组 | SAFE |
| `ecat_load_capabilities` | 加载能力组工具到当前会话 | SAFE |
| `ecat_subscribe_events` | 订阅设备状态变更等实时事件 | SAFE |
| `query_async_result` | 查询异步操作执行状态和结果 | SAFE |
| `confirm_operation` | 确认高风险操作（需操作确认码） | HIGH_RISK |
| `query_audit_log` | 查询 Agent 操作审计日志 | SAFE |

### 4 个内置资源

| URI | 说明 |
|-----|------|
| `ecat://system/info` | 系统版本、运行时间、集成/设备数量 |
| `ecat://devices/overview` | 设备数量及状态统计 |
| `ecat://capabilities/loaded` | 当前已加载的 MCP 能力组 |
| `ecat://audit/recent` | 最近 20 条 Agent 操作审计记录 |

### 3 个提示词模板

| 名称 | 说明 | 参数 |
|------|------|------|
| `ecat_system_overview` | ECAT 系统概述 | `language` |
| `ecat_device_guide` | 设备操作指南 | `device_type`, `operation` |
| `ecat_safety_guide` | 安全操作规范 | 无 |

## 快速开始

### 1. 启用集成

在 `.ecat-data/core/integrations.yml` 中添加：

```yaml
com.ecat:integration-agent-bridge:
  enabled: true
  groupId: com.ecat
  artifactId: integration-agent-bridge
  version: 2.0.0
```

### 2. 生成 Agent Token

通过 ConfigFlow 向导生成：

```bash
# 1. 登录获取管理 Token
AUTH_TOKEN=$(curl -s -X POST http://localhost:9999/core-api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin@123"}' | jq -r '.data.token')

# 2. 启动 ConfigFlow
FLOW_ID=$(curl -s -X POST http://localhost:9999/core-api/config-flows/start \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{"providerCoordinate":"com.ecat:integration-agent-bridge"}' | jq -r '.data.flowId')

# 3. 提交 Token 配置（生成 Token）
curl -s -X POST http://localhost:9999/core-api/config-flows/step \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d "{\"flowId\":\"$FLOW_ID\",\"stepId\":\"token_setup\",\"userInput\":{\"name\":\"my-agent\",\"role\":\"admin\"}}"

# 4. 确认（从上一步响应的 schema.fields 中提取生成的 Token，然后确认）
curl -s -X POST http://localhost:9999/core-api/config-flows/step \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -d "{\"flowId\":\"$FLOW_ID\",\"stepId\":\"final_confirm\",\"userInput\":{\"confirmed\":true}}"
```

### 3. MCP 客户端连接

**Endpoint**: `http://localhost:9999/mcp`

**认证方式**: Bearer Token（Header 或 Query Parameter）

```bash
# MCP Initialize
curl -X POST http://localhost:9999/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ecat-agent-xxxx" \
  -d '{
    "jsonrpc": "2.0",
    "method": "initialize",
    "id": 1,
    "params": {
      "protocolVersion": "2025-03-26",
      "capabilities": {},
      "clientInfo": {"name": "my-agent", "version": "1.0"}
    }
  }'
```

### 4. Claude Code 配置示例

在项目的 `.mcp.json` 中添加：

```json
{
  "mcpServers": {
    "ecat": {
      "url": "http://localhost:9999/mcp",
      "headers": {
        "Authorization": "Bearer ecat-agent-xxxx"
      }
    }
  }
}
```

## 架构

### 模块依赖

```
integration-agent-bridge
  ├── integration-httpserver (必需) — HTTP 服务器、路由注册
  └── integration-ecat-core-api (必需) — API 路由元数据、内部 Token
```

### 代码结构

```
com.ecat.integration.agentbridge/
├── AgentBridgeIntegration.java    # 主集成类，生命周期管理 + MCP 请求分发
├── mcp/                           # MCP 协议层
│   ├── McpServer.java             # Streamable HTTP 传输（POST/GET/DELETE /mcp）
│   ├── McpSession.java            # 会话状态（agentId、已加载能力组、订阅事件）
│   ├── McpRequestHandler.java     # 请求分发接口
│   ├── McpAuthenticator.java      # 认证接口
│   ├── JsonRpcRequest.java        # JSON-RPC 2.0 请求模型
│   ├── JsonRpcResponse.java       # JSON-RPC 2.0 响应模型
│   ├── JsonRpcNotification.java   # JSON-RPC 通知模型
│   └── McpException.java         # MCP 异常（含错误码）
├── auth/                          # 认证与安全
│   ├── AgentAuthManager.java      # Token 生成/验证（SHA-256 哈希存储）
│   ├── AgentToken.java            # Token 数据（token、identity、过期时间）
│   ├── AgentIdentity.java         # Agent 身份（ID、名称、角色、权限）
│   ├── ConfirmationManager.java   # 高风险操作确认管理（300s 超时）
│   ├── AuthException.java         # 认证异常
│   └── AuditLoggerBridge.java     # 审计日志接口（解耦 auth 与 audit）
├── capability/                    # 能力发现与加载
│   ├── CapabilityManager.java     # 扫描 RouteDescriptor 构建工具索引
│   ├── CapabilityGroupSummary.java # 能力组摘要 DTO
│   └── LoadResult.java            # 加载结果 DTO
├── tool/                          # 工具定义与执行
│   ├── McpTool.java               # MCP 工具定义（name、schema、annotations）
│   ├── ToolGenerator.java         # RouteDescriptor → McpTool 转换
│   ├── ToolExecutor.java          # HTTP 自调用执行 API 路由工具
│   └── ToolResult.java            # 执行结果（content、isError、asyncId）
├── event/                         # 事件管理
│   ├── EventManager.java          # Bus 事件订阅 → MCP 通知转发
│   └── UnifiedEvent.java          # 统一事件模型
├── resource/                      # MCP Resources
│   └── ResourceManager.java       # 4 个只读资源
├── audit/                         # 审计日志
│   ├── AuditLogger.java           # 日志写入/查询（按日期分割文件）
│   └── AuditRecord.java           # 审计记录 DTO
├── prompt/                        # MCP Prompts
│   ├── PromptManager.java         # 提示词管理（3 个内置模板）
│   └── PromptTemplate.java        # 模板引擎（{argName} 占位符替换）
└── config/                        # ConfigFlow 配置向导
    └── AgentBridgeConfigFlow.java # 2 步向导：token_setup → final_confirm
```

### 生命周期

```
onLoad()   → 获取 httpserver 依赖、创建 authManager、设置 ConfigFlow 回调
             ↓
           registerConfigFlow() — IntegrationManager 调用 getConfigFlow()
             ↓
onInit()   → 无操作
             ↓
onStart()  → 创建子组件、注册 /mcp 路由、订阅 Bus 事件
             ↓
INTEGRATIONS_ALL_LOADED → buildIndex() + obtainInternalToken()
             ↓
onPause()  → 停止 MCP 服务和事件订阅
             ↓
onRelease()→ 释放所有资源
```

### 渐进式能力发现（Progressive Disclosure）

Agent 初次连接只看到 6 个核心工具。通过 `ecat_search_capabilities` 搜索能力组，
再用 `ecat_load_capabilities` 按需加载。加载后收到 `notifications/tools/list_changed` 通知，
后续 `tools/list` 返回扩展后的工具列表。

```
Agent 连接 → tools/list → 6 个核心工具
          → ecat_search_capabilities("device") → [device-monitoring, device-config, ...]
          → ecat_load_capabilities("device-monitoring") → 加载 12 个工具
          → notifications/tools/list_changed → 通知 Agent 工具列表变更
          → tools/list → 6 + 12 = 18 个工具
```

### Token 安全

- Token 格式：`ecat-agent-<32位随机hex>`
- 存储方式：SHA-256 哈希，原始 Token 仅在生成时返回一次
- 有效期：24 小时
- 认证方式：`Authorization: Bearer <token>` Header 或 `?token=<token>` Query Parameter（SSE 场景）
- 内部 Token：Agent Bridge 通过 `AuthManager.createInternalSession()` 获取 ecat-core-api 的内部 session Token，用于 HTTP 自调用

## 构建

```bash
# 从项目根目录构建
mvnd clean install -pl ecat-integrations/integration-agent-bridge -am -DskipTests

# 运行单元测试
mvnd clean install -pl ecat-integrations/integration-agent-bridge
```

### 单元测试

| 测试类 | 测试数 | 覆盖范围 |
|--------|-------|---------|
| `PromptTemplateTest` | 17 | 模板解析、占位符替换、边界情况 |
| `PromptManagerTest` | 12 | 提示词列表、获取、不存在处理 |
| `AgentBridgeConfigFlowTest` | 18 | ConfigFlow 2 步向导全流程 |
| **合计** | **47** | |

## 文档索引

| 文档 | 说明 |
|------|------|
| [requirements.md](docs/requirements.md) | 需求文档 — 愿景、功能需求、验收标准 |
| [design-overview.md](docs/design-overview.md) | 架构设计总览 — 技术选型、整体架构、分步实施计划 |
| [design-httpserver-route-descriptor.md](docs/design-httpserver-route-descriptor.md) | 设计一 — HTTP 基础设施增强（RouteDescriptor、池化改造） |
| [design-async-operations.md](docs/design-async-operations.md) | 设计二 — 异步操作管理（AsyncExecutionManager） |
| [design-agent-bridge-mcp-server.md](docs/design-agent-bridge-mcp-server.md) | 设计三 — Agent Bridge MCP Server 详细设计 |

## 已知限制

1. **能力索引依赖 RouteDescriptor**：只有通过新 `registerUrl()` 重载（带 RouteDescriptor）注册的路由才会被能力发现。现有 ecat-core-api 路由使用旧 API 注册，暂不可被发现。
2. **Token 重启失效**：Agent Token 仅存储在内存中，ECAT 重启后需重新生成。
3. **Java 8 兼容**：自行实现 MCP 协议，不使用官方 MCP Java SDK（需要 Java 17）。
4. **SSE 无过滤**：事件推送暂未实现按 session 订阅类型过滤，所有事件广播到所有连接。

## License

Apache License 2.0
