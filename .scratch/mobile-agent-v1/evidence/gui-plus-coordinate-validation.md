# gui-plus 图像与坐标适配验证

2026-09-25。模型由用户明确指定为 gui-plus；日期版额度403和旧协议下失败保留，不以本修复覆盖历史。

## 实现

精确 gui-plus 使用上传图像像素坐标；Android 本地 resize 为28倍数，像素范围3136–1003520，prompt声明实际上传宽高。1080×2400截图处理为672×1484，x/y独立映射回观察屏幕坐标；负值、非有限数、达到或超过图像边界拒绝。日期版继续0–1000。所有角色使用相同准备入口，原截图/观察身份与源尺寸、上传尺寸、变换ID存入请求证据。任务恢复拒绝与已保存模型不一致的配置，未发送的图片适配失败不预留API费用。四角色、规划与现场核对路径保留。

依据：[GUI-Plus使用说明](https://help.aliyun.com/zh/model-studio/gui-automation)及[API图像参数](https://help.aliyun.com/zh/model-studio/gui-plus-interface-interaction-model)。不依据单次误点猜测比例。

## 离线检查

定向 JVM：VlmCoordinateProtocolTest 5、MobileAgentVlmRolesTest 13、VlmApiClientTest 9，27项通过。

集成：`:app:testDebugUnitTest :app:assembleDebug`，135项通过，无失败/错误/跳过，APK构建成功。Android Bitmap实际缩放路径尚需真机请求证据；不能用纯坐标测试代替该检查。

源码 `717b174d67c93be8251b0abd370d3ad04c00c6dc`；APK SHA-256 `5ca1cf036394dcfb2a092584ab4a8c49b66f30a3a8108315c3a2c5223f89c36b`。Sol范围审查未发现当前调用路径支持的阻塞缺陷，指出Android PNG实际处理和供应商/真机点击效果需补证。

真机 input04 和 visual02 两任务的请求均200，trace保留原1080×2400→上传672×1484、transform及原观察身份，补上Android实际PNG处理/传输证据。input04真实文字已写入，但实际由Jev改选set_text，后因早采样旧树而暂停、fresh复核完成；visual02只点到预设按钮，蓝框仍ready。两项不能证明独立VLM正确坐标命中或任务25放行。具体结果与准备差异见 [对照记录](on-demand-planning-validation.md)。

任务25仍未通过，最终物理拔线验收未完成。Jev40次用完后用户明确提高至70，已用与费用不重置，费用限制仍累计¥10/任务¥1/阶段¥2。

## 独立VLM真机补证

[visual03-vlm-only](planning25-on-visual03-vlm-only.json)关闭Jev选择/树核验/影子，只由VLM产生与执行坐标，规划仍ON。第三个coordinate_tap [540,1010]真实命中蓝框，原观察与新上传尺寸关联完整；前两次误点预设按钮的原[486,990]→屏幕[781,1601]完整保留。7 VLM/0 Jev，¥0.0290955。动作后正常状态文本更新再次导致早采样门禁暂停，fresh复核后完成。故图片处理、映射和真实坐标副作用已补证，自动稳定核验仍待单独修复；不据此宣布任务25或拔线验收通过。
