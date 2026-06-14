package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.SafetyLevel;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;

import java.util.Arrays;
import java.util.List;

/**
 * 媒体领域 SubAgent。
 *
 * <p>映射 media-api {@code MediaController}（plugin 模式挂 core-api 9999）的工具：
 * snapshot / stream / stop-stream。
 *
 * <p>端点已核实（MediaApiIntegration plugin 模式 basePath）：
 * <ul>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/snapshot — 拍照</li>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/stream/start — 启动直播流</li>
 *   <li>POST /plugins/com.ecat/integration-media-api/media/stream/stop — 停止直播流</li>
 * </ul>
 *
 * <p>plugin 模式下挂在 core-api 9999（默认 baseUrl + internal token），非 standalone 9931。
 *
 * @author coffee
 */
public class MediaSubAgent extends AbstractSubAgent {

    private static final String MEDIA_BASE = "/plugins/com.ecat/integration-media-api/media";

    public MediaSubAgent() {
        super("media", "com.ecat:integration-media-api");
    }

    @Override
    public List<ToolDescriptor> getTools() {
        return Arrays.asList(buildSnapshotTool(), buildStreamTool(), buildStopStreamTool());
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
                .safetyLevel(SafetyLevel.MODERATE)
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
                .safetyLevel(SafetyLevel.MODERATE)
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
                .safetyLevel(SafetyLevel.SAFE)
                .build();
    }
}
