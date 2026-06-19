# integration-agent-bridge

ECAT Agent Bridge — MCP（Model Context Protocol）Server 集成模块。把 ECAT 平台的设备、逻辑设备、媒体、事件能力以 MCP 工具的形式暴露给外部 AI Agent，让 Agent 不必懂 ECAT 的 HTTP API 就能操作平台。

## 谁会用 / 接什么 Agent

任何 MCP 兼容客户端都可接入：

| Agent | 接入方式 |
|-------|---------|
| **Claude Code** | 项目根 `.mcp.json` 配置（见下） |
| **Cursor / Windsurf** | 各自 MCP server 配置，填同一 endpoint + Bearer 头 |
| **ChatGPT** 及其他 MCP 客户端 | Streamable HTTP endpoint + Bearer 头 |
| 自研 Agent | 直接按 JSON-RPC 2.0 over HTTP 调用（见裸 curl 示例） |

传输协议：MCP Streamable HTTP（JSON-RPC 2.0 over `POST /mcp`），会话关闭走 `DELETE /mcp`。

## Agent 能拿到什么（2 个元工具 + 4 个领域能力）

接入后 Agent 只看到 2 个 MCP 工具——**自发现、自执行**：

| 元工具 | 作用 |
|--------|------|
| `getTools` | 取工具的参数/示例。**description 即能力菜单**：`tools/list` 阶段就能读到「领域 Agents: agent: [tools]」清单（无需调用即可看到）；再调 `getTools("<agent>")` 取该领域每个工具的详细参数与示例。先 `getTools` 再 `useTools`。 |
| `useTools` | 执行工具。传一条 CLI 命令（如 `device list`）→ 自动路由、解析参数、调用 ECAT → 返回结果。`set-attribute` 是异步操作，返回 taskId，用 `event query-async-result --id <taskId>` 查结果。 |

Agent 无需预先记住工具清单：`tools/list` 时 `getTools` 的 description 已列出全部领域与各自工具，`getTools("<agent>")` 取参数，`useTools` 执行。

4 个领域能力（按需启用，未启用的领域不出现在索引里）：

| 领域 | agent 名 | 工具 | 能做什么 |
|------|----------|------|----------|
| 物理设备 | `device` | `list` / `get` / `get-attributes` / `get-attribute-schemas` / `set-attribute` | 查设备、读属性、改属性（`set-attribute` 异步，返回 taskId） |
| 逻辑设备 | `logic-device` | `list` / `get-attributes` / `set-attribute` | 聚合/派生设备的查询与属性读写 |
| 媒体 | `media` | `snapshot` / `stream` / `stop-stream` / `stream-info` / `record` / `record-info` / `get-download-url` | 摄像头拍照/直播/录像；`get-download-url` 把媒体 URI 转成**无需 token**、5 分钟有效的下载 URL |
| 事件 | `event` | `query-async-result` | 查异步操作结果（`set-attribute` 等返回的 taskId） |

> 媒体能力需额外启用 `integration-media-api`（独立端口 9931）；其余领域依赖 `integration-ecat-core-api`（通常默认启用）。

## 配置与接入

### 1. 启用集成

`.ecat-data/core/integrations.yml`：

```yaml
com.ecat:integration-agent-bridge:
  enabled: true
  groupId: com.ecat
  artifactId: integration-agent-bridge
  version: 2.0.0
```

### 2. 生成 Agent Token

通过 ConfigFlow 两步向导生成（先用管理员账号登录拿 AUTH_TOKEN）：

```bash
# 1. 登录拿管理 Token
AUTH_TOKEN=$(curl -s -X POST http://localhost:9999/core-api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin@123"}' | jq -r '.data.token')

# 2. 启动 ConfigFlow
FLOW_ID=$(curl -s -X POST http://localhost:9999/core-api/config-flows/start \
  -H "Content-Type: application/json" -H "Authorization: Bearer $AUTH_TOKEN" \
  -d '{"providerCoordinate":"com.ecat:integration-agent-bridge"}' | jq -r '.data.flowId')

# 3. token_setup：填 name / role（生成 Token）
curl -s -X POST http://localhost:9999/core-api/config-flows/step \
  -H "Content-Type: application/json" -H "Authorization: Bearer $AUTH_TOKEN" \
  -d "{\"flowId\":\"$FLOW_ID\",\"stepId\":\"token_setup\",\"userInput\":{\"name\":\"my-agent\",\"role\":\"admin\"}}"

# 4. final_confirm：确认 → 响应 data.rawToken 即生成的 Agent Token（妥善保存）
curl -s -X POST http://localhost:9999/core-api/config-flows/step \
  -H "Content-Type: application/json" -H "Authorization: Bearer $AUTH_TOKEN" \
  -d "{\"flowId\":\"$FLOW_ID\",\"stepId\":\"final_confirm\",\"userInput\":{\"confirmed\":true}}"
```

**Token 要点**：
- 格式 `ecat-agent-<32位hex>`，**永不过期**，core 重启后仍有效（落盘的是 SHA-256 hash，不可逆，不存明文）。
- 原始 Token 仅在生成时回显一次（`final_confirm` 响应的 `data.rawToken`），丢失需重新生成。
- 认证：`Authorization: Bearer <token>` 请求头；无 header 能力的客户端可用 `?token=<token>` 查询参数回退。

### 3. 接入你的 MCP 客户端

**Endpoint**：`http://<core-host>:9999/mcp`　**认证**：`Authorization: Bearer <token>`

通用配置（MCP Streamable HTTP，`url` + `headers` 形态）：

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

- **Claude Code**：把上面这段写进项目根的 `.mcp.json`。
- **Cursor / Windsurf / ChatGPT** 等：在各自 MCP server 配置处填同一 endpoint URL 与 `Authorization` 头（各客户端配置入口不同，填法一致）。
- **裸验证（自研 Agent / 排障）**：

```bash
curl -X POST http://localhost:9999/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ecat-agent-xxxx" \
  -d '{"jsonrpc":"2.0","method":"initialize","id":1,
       "params":{"protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"my-agent","version":"1.0"}}}'
```

## 用法（Agent 视角）

典型一次会话：

```
连上 → initialize → tools/list                    → [getTools, useTools]
                      （getTools 的 description 已列出全部领域 + 各自工具清单，无需调用即可看到）
     → getTools("media")                           → media 各工具的参数与示例
     → useTools("device list")                     → 设备列表
     → useTools("device set-attribute --device-id ... --attribute-id ... --value ...")
                                                   → 返回 taskId（异步）
     → useTools("event query-async-result --id <taskId>")  → 查执行结果
```

下载媒体文件（无需把 token 交给下载请求）：

```
useTools("media snapshot --device-id cam-01")          → 拿到 ecat-media:// URI
useTools("media get-download-url --uri <URI>")         → 返回无需 token、5 分钟有效的下载 URL
直接 GET 该 URL（浏览器 / curl 都行）                     → 拉到 JPEG / MP4
```

## 构建

```bash
mvnd clean install -pl ecat-integrations/agent-bridge -am -DskipTests   # 仅构建
mvnd clean install -pl ecat-integrations/agent-bridge                  # 构建 + 单元测试（109 例）
```

> 逐测试类覆盖明细见 `src/test/` 与 `docs/06`。

## 设计与文档

README 聚焦接入与使用；架构、交互流程、实现细节见 `docs/`：

| 文档 | 内容 |
|------|------|
| [docs/requirements.md](docs/requirements.md) | 需求与验收标准 |
| [docs/05-bcp-subagent-architecture-design.md](docs/05-bcp-subagent-architecture-design.md) | SubAgent / 工具路由架构设计（当前架构权威） |
| [docs/06-bcp-implementation-plan.md](docs/06-bcp-implementation-plan.md) | 实现计划与完成情况（当前架构权威） |
| [docs/03-bcp-bounded-context-packs-reference.md](docs/03-bcp-bounded-context-packs-reference.md) | BCP（Bounded Context Pack）背景 |
| [docs/04-bcp-interaction-flow-analysis.md](docs/04-bcp-interaction-flow-analysis.md) | BCP 交互流程分析 |
| [docs/design-async-operations.md](docs/design-async-operations.md) | 异步操作管理（`set-attribute` 返回 taskId 的基础） |
| [docs/design-agent-bridge-mcp-server.md](docs/design-agent-bridge-mcp-server.md) | MCP 传输层设计（McpServer / McpSession，仍适用） |

## 已知限制

1. **能力按集成启用动态可见**：`media` 领域需启用 `integration-media-api`；未启用的领域不出现在 `getTools` 索引里。
2. **领域 SubAgent 硬编码装配**：4 个领域在启动时硬编码，新增领域需改代码并 `mvn install`。
3. **Java 8 兼容**：自行实现 MCP 协议（官方 MCP Java SDK 需 Java 17），不依赖官方 SDK。

## License

Apache License 2.0
