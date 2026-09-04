package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;

import java.util.Arrays;
import java.util.List;

/**
 * 逻辑设备领域 SubAgent。
 *
 * <p>映射 logicdevice-api 插件 {@code LogicDeviceApiController}（basePath 见 {@link #BASE_PATH}）
 * 的 3 个工具：list / get-attributes / set-attribute。
 *
 * <p>端点已核实（插件模式，LogicDeviceApiController.buildRoutes + basePath 注入）：
 * <ul>
 *   <li>GET  /plugins/com.ecat/integration-logicdevice-api/logic-devices — list</li>
 *   <li>GET  /plugins/com.ecat/integration-logicdevice-api/logic-devices/{id}/attributes — get-attributes</li>
 *   <li>PUT  /plugins/com.ecat/integration-logicdevice-api/logic-devices/{id}/attributes/{attrId}/value — set-attribute（异步）</li>
 * </ul>
 *
 * @author coffee
 */
public class LogicDeviceSubAgent extends AbstractSubAgent {

    /**
     * 逻辑设备 API 插件模式的 basePath。真相源 = logicdevice-api
     * LogicdeviceApiIntegration:81 注入的插件 basePath（"/plugins/" + 坐标 + "/logic-devices"）。
     * standalone 形态（/core-api/logic-devices）本 workspace 不部署，严格模式不写双路径兼容兜底。
     */
    private static final String BASE_PATH = "/plugins/com.ecat/integration-logicdevice-api/logic-devices";

    public LogicDeviceSubAgent() {
        super("logic-device", "com.ecat:integration-ecat-core-api");
    }

    @Override
    public List<ToolDescriptor> getTools() {
        return Arrays.asList(buildListTool(), buildGetAttributesTool(), buildSetAttributeTool());
    }

    /** logic-device list — GET BASE_PATH */
    private ToolDescriptor buildListTool() {
        return new ToolDescriptor.Builder("list")
                .description("获取所有逻辑设备列表。逻辑设备是对物理设备的业务抽象（如站房设备、校准设备等），"
                        + "可绑定多个物理设备属性做聚合/映射。返回 id、name、deviceClass 等。")
                .usage("logic-device list")
                .examples(Arrays.asList("logic-device list"))
                .httpMethod("GET").httpPath(BASE_PATH)
                .build();
    }

    /** logic-device get-attributes — GET BASE_PATH/{id}/attributes */
    private ToolDescriptor buildGetAttributesTool() {
        return new ToolDescriptor.Builder("get-attributes")
                .description("获取指定逻辑设备的所有属性当前值（属性名、值、单位、状态）。")
                .usage("logic-device get-attributes --id <logicDeviceId>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("逻辑设备唯一标识符，可通过 logic-device list 获取").build()))
                .examples(Arrays.asList("logic-device get-attributes --id station-01"))
                .httpMethod("GET").httpPath(BASE_PATH + "/{id}/attributes")
                .build();
    }

    /** logic-device set-attribute — PUT BASE_PATH/{id}/attributes/{attrId}/value（异步） */
    private ToolDescriptor buildSetAttributeTool() {
        return new ToolDescriptor.Builder("set-attribute")
                .description("设置逻辑设备属性值（控制业务行为，如阈值/开关/校准参数）。操作异步执行，"
                        + "返回后用 event query-async-result 凭 taskId 查询结果。")
                .usage("logic-device set-attribute --id <logicDeviceId> --attr-id <attrId> "
                        + "--value <value> --unit <unit>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("逻辑设备唯一标识符").build(),
                        new ArgDescriptor.Builder("attrId").flag("--attr-id").pathParam().required()
                                .description("要设置的属性ID").build(),
                        new ArgDescriptor.Builder("value").flag("--value").required()
                                .description("属性值").build(),
                        new ArgDescriptor.Builder("unit").flag("--unit").required()
                                .description("属性单位，无单位传空字符串").build()))
                .examples(Arrays.asList(
                        "logic-device set-attribute --id station-01 --attr-id threshold --value 50 --unit \"ppb\""))
                .httpMethod("PUT").httpPath(BASE_PATH + "/{id}/attributes/{attrId}/value")
                .async()
                .build();
    }
}
