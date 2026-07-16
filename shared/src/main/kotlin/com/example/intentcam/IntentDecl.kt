package com.example.intentcam

/**
 * One intent's declaration.  The LLM sees `id` in [Bubble.type] /
 * `emit_bubble.type`; the eval scorer checks [family] for equivalence;
 * the UI reads [label].  Adding a new intent = register one more
 * IntentDecl — orchestrator code stays unchanged.
 *
 * The `id` value used to be hardcoded across [Tools], [ToolUseLoop],
 * [LlmClient], and [EvalRunner] as `"info" | "location" | "solve"`.
 * With this file those literals become [IntentRegistry.FALLBACK_ID] (a
 * named constant) and `IntentRegistry.allIds()` (the dynamic list).
 * Direct consumers still pass strings around (kotlin default-param
 * limits us to constants) but every default points at the same
 * source of truth.
 */
data class IntentDecl(
    val id: String,                  // "info" / "location" / "solve" / "shopping" / ...
    val label: String,               // UI label: "信息" / "定位" / ...
    val llmHint: String,             // Chinese single-line description baked into system prompt
    val family: IntentFamily,        // eval equivalence group
    /**
     * Soft canonical action hint — the chip id the LLM should default
     * to in `emit_bubble.action_ids` when the bubble's type matches this
     * intent. Surfaced in the system prompt as `__INTENT_HINTS_BLOCK__`
     * by [LlmClient.toolUseSystemPrompt] when an [IntentRegistry] is
     * supplied. **`null` = no hint** (the generic intents `info` /
     * `location` / `solve` deliberately stay unhinted — they don't
     * own a canonical chip surface).
     *
     * "Soft" means the LLM may omit / override the hint when the
     * image contradicts it (e.g. a phone scene with no readable number
     * → omit `dial_number`). The scorer downstream still scores
     * whatever the LLM emits. See
     * `docs/adr/2026-07-14-v3-inversion.md` §72-75 (planned follow-up
     * shipped 2026-07-16) for the inversion's "soft system-prompt
     * hint" prescription that motivates this field.
     */
    val canonicalAction: String? = null,
)

/**
 * Equivalence group used by the eval scorer (graded partial-credit,
 *  2026-07-15 redesign — see [com.example.intentcam.eval.ScorerV2]):
 *  same family 0.7, cross-family 0.3, empty·unknown 0.0, exact 1.0.
 *
 * `OBSERVE` = the user just wants the picture read (info, location).
 * `ACT_ON`  = the user wants something done (solve, shopping, ...).
 *
 * Add a new intent by registering an [IntentDecl] — declare its family
 * explicitly.  No defaults; an intent with no family is a bug.
 */
enum class IntentFamily { OBSERVE, ACT_ON }

/**
 * Mutable bag of [IntentDecl]s.  Build once at app start, then treat
 * as read-only.  Mirrors the shape of [ToolRegistry] so the two have
 * parallel structure.
 */
class IntentRegistry {

    private val byId = linkedMapOf<String, IntentDecl>()

    fun register(decl: IntentDecl): IntentDecl {
        require(decl.id !in byId) { "duplicate intent id: ${decl.id}" }
        byId[decl.id] = decl
        return decl
    }

    fun get(id: String): IntentDecl? = byId[id]

    fun list(): List<IntentDecl> = byId.values.toList()

    fun allIds(): List<String> = byId.keys.toList()

    /** Ids whose [IntentDecl.family] equals [family].  Cheap — keeps
     *  the eval scorer's "same-family equivalence" check data-driven. */
    fun idsInFamily(family: IntentFamily): Set<String> =
        byId.values.filter { it.family == family }.map { it.id }.toSet()

    companion object {
        /**
         * Used as the default for [Bubble.type] / [ToolResult.type] /
         * fallback after a parse failure.  The companion const means
         * default-param values (`val type: String = IntentRegistry.FALLBACK_ID`)
         * can reference it without an instance.
         *
         * MUST equal the `id` of the first [IntentDecl] registered by
         * [registerDefaultIntents] — if you change one, change the other.
         */
        const val FALLBACK_ID = "info"
    }
}

/**
 * Register IntentCam's three default intents.  Adding a new intent =
 * add one more [IntentDecl] here.  No orchestrator / eval / prompt
 * change is needed for an in-OBSERVE or in-ACT_ON addition.
 */
fun registerDefaultIntents(reg: IntentRegistry) {
    reg.register(IntentDecl(
        id = IntentRegistry.FALLBACK_ID,
        label = "信息",
        llmHint = "描述信息（默认）：物体 / 文字 / 数字 / 概念",
        family = IntentFamily.OBSERVE,
    ))
    reg.register(IntentDecl(
        id = "location",
        label = "定位",
        llmHint = "定位：路标 / 地名 / 找这家店",
        family = IntentFamily.OBSERVE,
    ))
    reg.register(IntentDecl(
        id = "solve",
        label = "解答",
        llmHint = "解决问题：翻译 / 公式 / 解题",
        family = IntentFamily.ACT_ON,
    ))
    // Extended intents and their action mappings follow the framework's
    // two-register lockstep policy.  See
    // `docs/adr/2026-07-10-intent-action-framework.md`.
    reg.register(IntentDecl(
        id = "phone",
        label = "电话",
        llmHint = "拨号：手机号 / 座机 / 400电话 / 服务热线",
        family = IntentFamily.ACT_ON,
        canonicalAction = "dial_number",
    ))
    reg.register(IntentDecl(
        id = "real_estate_rental",
        label = "租房",
        llmHint = "房地产：出租 / 出售 / 二手房 / 楼盘 / 户型 / 平米 / 急售 / 吉房 / 中介 / 物业",
        family = IntentFamily.ACT_ON,
        canonicalAction = "share",
    ))
    reg.register(IntentDecl(
        id = "recruit_hiring",
        label = "招聘",
        llmHint = "招聘：招工 / 求职 / 兼职 / 高薪",
        family = IntentFamily.ACT_ON,
        canonicalAction = "share",
    ))
    reg.register(IntentDecl(
        id = "payment_qr",
        label = "支付",
        llmHint = "支付：扫一扫 / 收款码 / 付款码 / 转账",
        family = IntentFamily.ACT_ON,
        canonicalAction = "scan_to_pay",
    ))
    reg.register(IntentDecl(
        id = "id_document",
        label = "证件",
        llmHint = "证件：身份证 / 营业执照 / 车牌",
        family = IntentFamily.ACT_ON,
        canonicalAction = "redact_id",
    ))
    // `love_dating` is intentionally omitted: a dating-app-ad is just
    // an info bubble (read it for context).  No PII action ships —
    // the existing `info` family already serves the model's
    // `type=info` classification for these fixtures.

    reg.register(IntentDecl(
        id = "warning_safety",
        label = "警示",
        llmHint = "警示：请勿 / 禁止 / 警告 / 危险 / 注意（高风险/合规标识）",
        family = IntentFamily.OBSERVE,
        canonicalAction = "share",
    ))
    reg.register(IntentDecl(
        id = "menu_food",
        label = "菜单",
        llmHint = "菜单：菜品 / 套餐 / 招牌菜 / 主厨推荐 / 价格表",
        family = IntentFamily.OBSERVE,
        canonicalAction = "share",
    ))
    reg.register(IntentDecl(
        id = "hours_schedule",
        label = "营业",
        llmHint = "营业时间：营业中 / HH:MM-HH:MM / 营业时段 / 周一至周日",
        family = IntentFamily.OBSERVE,
        canonicalAction = "share",
    ))
    reg.register(IntentDecl(
        id = "route_to",
        label = "导航",
        llmHint = "导航：箭头 / 方位词 / 步行 N 米 / 步行 N 分钟 / 前方/出口/入口 标记",
        family = IntentFamily.OBSERVE,
        canonicalAction = "open_in_maps",
    ))
    reg.register(IntentDecl(
        id = "service_institution",
        label = "机构",
        llmHint = "公共机构：医院 / 学校 / 政府机关 / 银行 / 邮局 / 法院 / 派出所 / 大使馆",
        family = IntentFamily.OBSERVE,
        canonicalAction = "open_in_maps",
    ))
    reg.register(IntentDecl(
        id = "shopping_promo",
        label = "促销",
        llmHint = "促销：特价 / 打折 / 满减 / 秒杀 / 亏本 / 清仓 / 甩卖 / 红包 / 限时 / 抢购",
        family = IntentFamily.OBSERVE,
        canonicalAction = "share",
    ))
}

/** Compact "id（label） / id（label） / ..." form used by tool descriptions. */
fun IntentRegistry.renderTypeList(): String =
    list().joinToString(" / ") { "${it.id}（${it.label}）" }
