package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.mcp.McpException;
import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;
import com.ecat.integration.agentbridge.tool.MediaUrlSigner;
import com.ecat.integration.agentbridge.tool.ToolExecutor;
import com.ecat.integration.agentbridge.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 媒体领域 SubAgent。
 *
 * <p>映射 media-api {@code MediaController}（plugin 模式挂 core-api 9999）的工具：
 * snapshot / stream / stop-stream / stream-info / record / record-info，
 * 外加一个进程内工具 get-download-url（不走 HTTP，见下）。
 *
 * <p>端点已核实（MediaApiIntegration plugin 模式 basePath）：
 * <ul>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/snapshot — 拍照</li>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/stream/start — 启动直播流</li>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/stream/stop — 停止直播流</li>
 *   <li>GET  /plugins/com.ecat/integration-media-api/media/stream/info — 查询直播流会话状态</li>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/recording/start — 开始录像（事件/普通两路径统一返回结构）</li>
 *   <li>GET  /plugins/com.ecat/integration-media-api/media/recording/info — 查询录像状态/产出文件</li>
 * </ul>
 *
 * <p>录像"获取视频文件"路径：{@code record} 返回 sessionId（事件录制以 {@code evt-} 开头，普通录制以 {@code rec-} 开头），
 * 录制定长自动完成；{@code record-info} 在 {@code status==COMPLETED} 时返回 {@code result.uri} + {@code result.fileSize}，
 * agent 可据此定位/下载录像。事件录制与普通录制的 info 响应为<strong>同一结构</strong>，
 * 调用者无需区分会话类型即可判定结果。
 *
 * <p>无 {@code record-stop} 工具：record 必填 {@code durationSeconds}，两类录制均定长自动完成；
 * 如需提前停止普通录制，由前端/HTTP 直接调用 {@code POST /media/recording/stop}（不纳入 agent 工具集）。
 *
 * <p>plugin 模式下挂在 core-api 9999（默认 baseUrl + internal token），非 standalone 9931。
 *
 * <p><b>二进制文件下载（Plan A）</b>：snapshot/record-info 只返回 ecat-media:// URI，agent 无法直接 GET 二进制。
 * 为此提供进程内工具 {@code get-download-url}：输入 URI，进程内强类型直调 {@link MediaUrlSigner}（复用
 * AuthManager.signMediaUrl 的 HMAC+TTL 签名），返回 agent 可直接 GET 的完整无 token 下载 URL（5 分钟有效）。
 * 这是本 SubAgent 的首个进程内工具（{@link ToolDescriptor} 不设 httpMethod/httpPath，
 * {@link #execute} override 不走 HTTP）。
 *
 * @author coffee
 */
public class MediaSubAgent extends AbstractSubAgent {

    private static final String MEDIA_BASE = "/plugins/com.ecat/integration-media-api/media";

    /** 工具名常量：进程内下载 URL 签名工具 */
    private static final String TOOL_GET_DOWNLOAD_URL = "get-download-url";

    /** 下载 URL 签名器（强类型依赖 AuthManager，构造时注入，不可为 null） */
    private final MediaUrlSigner mediaUrlSigner;

    /**
     * @param mediaUrlSigner 下载 URL 签名器，不可为 null（get-download-url 工具依赖它）
     */
    public MediaSubAgent(MediaUrlSigner mediaUrlSigner) {
        super("media", "com.ecat:integration-media-api");
        if (mediaUrlSigner == null) {
            throw new IllegalArgumentException("mediaUrlSigner must not be null");
        }
        this.mediaUrlSigner = mediaUrlSigner;
    }

    @Override
    public List<ToolDescriptor> getTools() {
        return Arrays.asList(
                buildSnapshotTool(), buildStreamTool(), buildStopStreamTool(), buildStreamInfoTool(),
                buildRecordTool(), buildRecordInfoTool(), buildGetDownloadUrlTool());
    }

    /**
     * 进程内工具分发：{@code get-download-url} 在进程内签名拼 URL（不走 HTTP）；
     * 其余工具委托默认 HTTP 执行。
     */
    @Override
    public ToolResult execute(ToolDescriptor tool, Map<String, Object> params,
                              ToolExecutor toolExecutor, String baseUrl,
                              String requestHostPort) throws McpException {
        if (TOOL_GET_DOWNLOAD_URL.equals(tool.getToolName())) {
            String uri = (String) params.get("uri");
            // sign 内部严格校验：uri 为空 / signMediaUrl 返回 null 均抛异常，不兜底
            return ToolResult.success(mediaUrlSigner.sign(uri, requestHostPort, baseUrl));
        }
        return super.execute(tool, params, toolExecutor, baseUrl, requestHostPort);
    }

    /** media snapshot — POST .../media/snapshot */
    private ToolDescriptor buildSnapshotTool() {
        return new ToolDescriptor.Builder("snapshot")
                .description("触发摄像头即时拍照，返回快照文件的媒体 URI。"
                        + "前置条件：目标设备必须是支持快照的摄像头类型且设备在线。")
                .usage("media snapshot --device-id <deviceId>")
                .args(Arrays.asList(
                        // arg name（经 CliParser 成为 JSON body key）必须 == media-api 契约
                        // cameraDeviceId（MediaController 从 body 读 cameraDeviceId，非 deviceId）。
                        // CLI flag 保持 --device-id（用户友好）。Bug #5 回归保护。
                        new ArgDescriptor.Builder("cameraDeviceId").flag("--device-id").required()
                                .description("摄像头设备唯一ID，可通过 device list 获取").build()))
                .examples(Arrays.asList("media snapshot --device-id cam-01"))
                .httpMethod("POST").httpPath(MEDIA_BASE + "/snapshot")
                .build();
    }

    /** media stream — POST .../media/stream/start */
    private ToolDescriptor buildStreamTool() {
        return new ToolDescriptor.Builder("stream")
                .description("启动摄像头直播流，返回 HLS 流地址。")
                .usage("media stream --device-id <deviceId>")
                .args(Arrays.asList(
                        // arg name（经 CliParser 成为 JSON body key）必须 == media-api 契约
                        // cameraDeviceId（MediaController 从 body 读 cameraDeviceId，非 deviceId）。
                        // CLI flag 保持 --device-id（用户友好）。Bug #5 回归保护。
                        new ArgDescriptor.Builder("cameraDeviceId").flag("--device-id").required()
                                .description("摄像头设备唯一ID").build()))
                .examples(Arrays.asList("media stream --device-id cam-01"))
                .httpMethod("POST").httpPath(MEDIA_BASE + "/stream/start")
                .build();
    }

    /** media stop-stream — POST .../media/stream/stop */
    private ToolDescriptor buildStopStreamTool() {
        return new ToolDescriptor.Builder("stop-stream")
                .description("停止摄像头直播流。")
                .usage("media stop-stream --device-id <deviceId>")
                .args(Arrays.asList(
                        // arg name（经 CliParser 成为 JSON body key）必须 == media-api 契约
                        // cameraDeviceId（MediaController 从 body 读 cameraDeviceId，非 deviceId）。
                        // CLI flag 保持 --device-id（用户友好）。Bug #5 回归保护。
                        new ArgDescriptor.Builder("cameraDeviceId").flag("--device-id").required()
                                .description("摄像头设备唯一ID").build()))
                .examples(Arrays.asList("media stop-stream --device-id cam-01"))
                .httpMethod("POST").httpPath(MEDIA_BASE + "/stream/stop")
                .build();
    }

    /** media stream-info — GET .../media/stream/info?cameraDeviceId=... */
    private ToolDescriptor buildStreamInfoTool() {
        return new ToolDescriptor.Builder("stream-info")
                .description("查询摄像头直播流会话状态（是否在线、HLS 地址、运行时长）。")
                .usage("media stream-info --device-id <deviceId>")
                .args(Arrays.asList(
                        // GET 工具：非 pathParam 参数由 ToolExecutor 自动拼为 query string。
                        // arg name cameraDeviceId 对齐 MediaController handleStreamInfo 的 query key。
                        new ArgDescriptor.Builder("cameraDeviceId").flag("--device-id").required()
                                .description("摄像头设备唯一ID").build()))
                .examples(Arrays.asList("media stream-info --device-id cam-01"))
                .httpMethod("GET").httpPath(MEDIA_BASE + "/stream/info")
                .build();
    }

    /** media record — POST .../media/recording/start */
    private ToolDescriptor buildRecordTool() {
        return new ToolDescriptor.Builder("record")
                .description("触发摄像头录像，返回录制会话 ID。录制定长自动完成，"
                        + "完成后用 record-info 获取视频文件 URI（可下载）。"
                        + "preEventSeconds > 0 时走事件录制（回捞事前缓存 + 事后实时），"
                        + "默认 0 为纯定时录制。")
                .usage("media record --device-id <deviceId> --duration <秒> [--pre-event <秒>] [--filename <名>]")
                .args(Arrays.asList(
                        // arg name（JSON body key）必须 == media-api 契约：
                        // cameraDeviceId / durationSeconds / preEventSeconds / filename
                        new ArgDescriptor.Builder("cameraDeviceId").flag("--device-id").required()
                                .description("摄像头设备唯一ID").build(),
                        // durationSeconds 必填：严格模式，源头防 agent 忘传导致非预期录制。
                        // 底层 FFmpegRecordingSession 亦强制 > 0。
                        new ArgDescriptor.Builder("durationSeconds").flag("--duration").required()
                                .type(ArgDescriptor.Type.INTEGER)
                                .description("录制时长（秒），必须 > 0").build(),
                        new ArgDescriptor.Builder("preEventSeconds").flag("--pre-event")
                                .type(ArgDescriptor.Type.INTEGER)
                                .description("事前缓存回溯时长（秒），可选，默认 0（纯定时录制）").build(),
                        new ArgDescriptor.Builder("filename").flag("--filename")
                                .description("录像文件名，可选，缺失时自动生成（推荐）").build()))
                .examples(Arrays.asList(
                        "media record --device-id cam-01 --duration 10",
                        "media record --device-id cam-01 --duration 10 --pre-event 5"))
                .httpMethod("POST").httpPath(MEDIA_BASE + "/recording/start")
                .build();
    }

    /** media record-info — GET .../media/recording/info?sessionId=... */
    private ToolDescriptor buildRecordInfoTool() {
        return new ToolDescriptor.Builder("record-info")
                .description("查询录像会话状态与产出文件。status==COMPLETED 时返回 result.uri + result.fileSize，"
                        + "agent 可据此下载录像。事件录制(evt-)与普通录制(rec-)返回同一结构。")
                .usage("media record-info --id <sessionId>")
                .args(Arrays.asList(
                        // GET 工具：sessionId 由 ToolExecutor 拼为 query string。
                        // CLI flag --id 友好；arg name sessionId 对齐 MediaController handleRecordingInfo 的 query key。
                        new ArgDescriptor.Builder("sessionId").flag("--id").required()
                                .description("录像会话 ID（record 返回的 sessionId）").build()))
                .examples(Arrays.asList("media record-info --id rec-a1b2c3d4"))
                .httpMethod("GET").httpPath(MEDIA_BASE + "/recording/info")
                .build();
    }

    /**
     * media get-download-url — 进程内工具（无 HTTP 映射）。
     *
     * <p>输入 snapshot/record-info 返回的 ecat-media:// URI，返回 agent 可直接 GET 的完整无 token 下载 URL
     * （HMAC 签名，5 分钟有效）。host:port 取 MCP 请求 Host（支持远程 agent），scheme 取 baseUrl。
     */
    private ToolDescriptor buildGetDownloadUrlTool() {
        return new ToolDescriptor.Builder(TOOL_GET_DOWNLOAD_URL)
                .description("把媒体 URI 转为可直接 GET 下载的完整 URL（无需 token，5 分钟有效）。"
                        + "用于下载 snapshot 的快照图片或 record-info 中 status==COMPLETED 的录像文件。"
                        + "URI 来源：snapshot 返回的快照 URI、record-info 返回的 result.uri。")
                .usage("media get-download-url --uri <ecat-media://...>")
                .args(Collections.singletonList(
                        new ArgDescriptor.Builder("uri").flag("--uri").required()
                                .description("ecat-media:// 媒体 URI（snapshot / record-info 返回）").build()))
                .examples(Arrays.asList(
                        "media get-download-url --uri ecat-media://com.ecat:integration-media-test-client/snapshots/xxx.jpg"))
                // 进程内工具：不设 httpMethod/httpPath，execute override 走 MediaUrlSigner 而非 HTTP
                .build();
    }
}
