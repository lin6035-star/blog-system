package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * Agent Run 详情·开发者原始视图（V4 第一刀，只读）。
 *
 * 组合而非继承：{@code run} 是与用户侧同源的**安全摘要**，{@code contextJson} 是**未清洗的原始
 * observations**（含文章正文片段等模型可见材料）。两者字段分开摆放，避免使用者忘记哪一份可信。
 */
@Data
public class AgentRunDevDetailVO {
    /** 与 {@code GET /api/ai/agent-runs/{id}} 同源的安全摘要。 */
    private AgentRunDetailVO run;
    /** 原始 contextJson（observations 序列化；可能有长度截断，展示时需标注来源）。 */
    private String contextJson;
}
