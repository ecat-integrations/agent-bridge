package com.ecat.integration.agentbridge.subagent.impl;

import com.ecat.integration.agentbridge.subagent.AbstractSubAgent;
import com.ecat.integration.agentbridge.subagent.ArgDescriptor;
import com.ecat.integration.agentbridge.subagent.SafetyLevel;
import com.ecat.integration.agentbridge.subagent.ToolDescriptor;

import java.util.Arrays;
import java.util.List;

/**
 * 设备领域 SubAgent。
 *
 * <p>映射 core-api {@code DeviceController}（BASE_PATH=/core-api/devices）的 5 个工具：
 * list / get / get-attributes / get-attribute-schemas / set-attribute。
 *
 * <p>端点已核实（DeviceController.registerRoutes）：
 * <ul>
 *   <li>GET  /core-api/devices — list</li>
 *   <li>GET  /core-api/devices/{id} — get</li>
 *   <li>GET  /core-api/devices/{id}/attributes — get-attributes（复数）</li>
 *   <li>GET  /core-api/devices/{id}/attribute/schemas — get-attribute-schemas（单数 attribute）</li>
 *   <li>PUT  /core-api/devices/{id}/attributes/{attrId}/value — set-attribute（复数 attributes，异步）</li>
 * </ul>
 *
 * @author coffee
 */
public class DeviceSubAgent extends AbstractSubAgent {

    public DeviceSubAgent() {
        super("device", "com.ecat:integration-ecat-core-api");
    }

    @Override
    public List<ToolDescriptor> getTools() {
        return Arrays.asList(
                buildListTool(),
                buildGetTool(),
                buildGetAttributesTool(),
                buildGetAttributeSchemasTool(),
                buildSetAttributeTool());
    }

    /** device list — GET /core-api/devices */
    private ToolDescriptor buildListTool() {
        return new ToolDescriptor.Builder("list")
                .description("获取所有物理设备列表，含在线/离线状态和基本信息（id、name、coordinate）。"
                        + "可通过 coordinate 过滤特定集成类型的设备。")
                .usage("device list [--coordinate <coordinate>]")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("coordinate").flag("--coordinate")
                                .description("集成坐标过滤，如 com.ecat:integration-sailhero").build()))
                .examples(Arrays.asList("device list",
                        "device list --coordinate com.ecat:integration-sailhero"))
                .httpMethod("GET").httpPath("/core-api/devices")
                .safetyLevel(SafetyLevel.SAFE)
                .build();
    }

    /** device get — GET /core-api/devices/{id} */
    private ToolDescriptor buildGetTool() {
        return new ToolDescriptor.Builder("get")
                .description("获取单个物理设备的详细信息，含所有属性当前值。")
                .usage("device get --id <deviceId>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("设备唯一标识符，可通过 device list 获取").build()))
                .examples(Arrays.asList("device get --id sensor-01"))
                .httpMethod("GET").httpPath("/core-api/devices/{id}")
                .safetyLevel(SafetyLevel.SAFE)
                .build();
    }

    /** device get-attributes — GET /core-api/devices/{id}/attributes */
    private ToolDescriptor buildGetAttributesTool() {
        return new ToolDescriptor.Builder("get-attributes")
                .description("获取指定设备的所有属性当前值（属性名、值、单位、状态）。")
                .usage("device get-attributes --id <deviceId>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("设备唯一标识符").build()))
                .examples(Arrays.asList("device get-attributes --id sensor-01"))
                .httpMethod("GET").httpPath("/core-api/devices/{id}/attributes")
                .safetyLevel(SafetyLevel.SAFE)
                .build();
    }

    /** device get-attribute-schemas — GET /core-api/devices/{id}/attribute/schemas（单数 attribute） */
    private ToolDescriptor buildGetAttributeSchemasTool() {
        return new ToolDescriptor.Builder("get-attribute-schemas")
                .description("获取指定设备的所有属性 Schema（类型、可选值、单位约束）。"
                        + "set-attribute 前可先用此工具查看属性可接受的值与单位。")
                .usage("device get-attribute-schemas --id <deviceId>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("设备唯一标识符").build()))
                .examples(Arrays.asList("device get-attribute-schemas --id sensor-01"))
                .httpMethod("GET").httpPath("/core-api/devices/{id}/attribute/schemas")
                .safetyLevel(SafetyLevel.SAFE)
                .build();
    }

    /** device set-attribute — PUT /core-api/devices/{id}/attributes/{attrId}/value（异步） */
    private ToolDescriptor buildSetAttributeTool() {
        return new ToolDescriptor.Builder("set-attribute")
                .description("设置设备属性值（控制仪器或更新参数）。操作直接作用于真实设备，请确认参数正确。"
                        + "异步执行，返回后用 event query-async-result 凭 taskId 查询结果。")
                .usage("device set-attribute --id <deviceId> --attr-id <attrId> "
                        + "--value <value> --unit <unit>")
                .args(Arrays.asList(
                        new ArgDescriptor.Builder("id").flag("--id").pathParam().required()
                                .description("设备唯一标识符").build(),
                        new ArgDescriptor.Builder("attrId").flag("--attr-id").pathParam().required()
                                .description("要设置的属性ID，可通过 device get-attribute-schemas 获取").build(),
                        new ArgDescriptor.Builder("value").flag("--value").required()
                                .description("属性值。数值型传数字字符串(如 25.5)，枚举型传选项值").build(),
                        new ArgDescriptor.Builder("unit").flag("--unit").required()
                                .description("属性单位，如 ppb、mg/m3、°C。无单位时传空字符串").build()))
                .examples(Arrays.asList(
                        "device set-attribute --id sensor-01 --attr-id temperature --value 25.5 --unit \"°C\""))
                .httpMethod("PUT").httpPath("/core-api/devices/{id}/attributes/{attrId}/value")
                .safetyLevel(SafetyLevel.MODERATE).async()
                .build();
    }
}
