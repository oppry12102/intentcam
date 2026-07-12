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
)

/**
 * Equivalence group used by the eval scorer.  Two intents in the same
 * family score 1.0 against each other (interchangeable), cross-family
 * score 0.5, empty/unknown score 0.
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
    // [2026-07-13] `phone`: text-rich image whose primary actionable
    //  content is a phone number (cell / landline / 400-line).  ACT_ON
    //  because the user's goal is to dial, not merely observe — pairs
    //  with the `dial_number` ActionDef declared in app/ActionDecl.kt
    //  (consent-gated).  Detection regex lives in `scan_intents.py` +
    //  any future Cloud-OCR product hint (e.g. "phone_intent_hint"
    //  detector).
    reg.register(IntentDecl(
        id = "phone",
        label = "电话",
        llmHint = "拨号：手机号 / 座机 / 400电话 / 服务热线",
        family = IntentFamily.ACT_ON,
    ))
    // [2026-07-13] Phase B: PII-sensitive intents (consolidated).
    //  All four below ship with their corresponding ActionDef under
    //  `requiresConfirmation=true` + `userPrefKey=...` gating, so the
    //  chip + consent flow from `dial_number` is reused verbatim.
    //  Lives here (not in a separate `registerPiiIntents`) to keep
    //  the single-registry invariant ("one bag of intents, one bag
    //  of actions") enforced at runtime.

    reg.register(IntentDecl(
        id = "real_estate_rental",
        label = "租房",
        llmHint = "租房：出租 / 二手房 / 房源 / 中介",
        family = IntentFamily.ACT_ON,
    ))
    reg.register(IntentDecl(
        id = "recruit_hiring",
        label = "招聘",
        llmHint = "招聘：招工 / 求职 / 兼职 / 高薪",
        family = IntentFamily.ACT_ON,
    ))
    reg.register(IntentDecl(
        id = "payment_qr",
        label = "支付",
        llmHint = "支付：扫一扫 / 收款码 / 付款码 / 转账",
        family = IntentFamily.ACT_ON,
    ))
    reg.register(IntentDecl(
        id = "id_document",
        label = "证件",
        llmHint = "证件：身份证 / 营业执照 / 车牌",
        family = IntentFamily.ACT_ON,
    ))
    // `love_dating` is intentionally omitted: a dating-app-ad is just
    // an info bubble (read it for context).  No PII action ships —
    // the existing `info` family already serves the model's
    // `type=info` classification for these fixtures.

    // [2026-07-12] Phase G — high-value single-purpose observe intents.
    //  All three are OBSERVE (read-and-keep) rather than ACT_ON: the
    //  user wants to UNDERSTAND / preserve the content (warning text,
    //  menu items, business hours), not trigger an outbound side-effect.
    //  Actions are copy-to-clipboard-style (verbatim text retention,
    //  share-sheet handed off to the user) so the consumption UX is
    //  uniform across the three.
    //
    //  Source data: `scan_intents.py` top-20 candidates
    //  (profiling/INTENT_TOP20_CANDIDATES.md):
    //    - warning_safety   (#6, 509 hits / 6.3%)
    //    - menu_food        (#9, 308 hits / 3.8%)
    //    - hours_schedule   (#12, 140 hits / 1.7%)
    reg.register(IntentDecl(
        id = "warning_safety",
        label = "警示",
        llmHint = "警示：请勿 / 禁止 / 警告 / 危险 / 注意（高风险/合规标识）",
        family = IntentFamily.OBSERVE,
    ))
    reg.register(IntentDecl(
        id = "menu_food",
        label = "菜单",
        llmHint = "菜单：菜品 / 套餐 / 招牌菜 / 主厨推荐 / 价格表",
        family = IntentFamily.OBSERVE,
    ))
    reg.register(IntentDecl(
        id = "hours_schedule",
        label = "营业",
        llmHint = "营业时间：营业中 / HH:MM-HH:MM / 营业时段 / 周一至周日",
        family = IntentFamily.OBSERVE,
    ))
}

/**
 * Render the dynamic intent block that gets spliced into the tool-use
 * system prompt and into the `emit_bubble` tool description.
 *
 * Two callers:
 *   - [LlmClient.toolUseSystemPrompt] (the system prompt's `type ∈ {...}`
 *     enumeration line)
 *   - `emit_bubble` tool description (a brief "info / location / solve"
 *     inline label, via [renderTypeList])
 *
 * Kept as a single line so the default-3-intent case is byte-identical
 * to the pre-2026-07-10 prompt.  A first attempt rendered a 4-line
 * block with one Chinese description per intent; that pulled ~80
 * tokens of attention away from the verbatim-OCR rules below, and
 * fixture-level regressions (rctw_default_15 -0.15 etc.) cut
 * composite by 0.012.  The compact form is what we ship.
 *
 * Per-intent labels (`info` / `location` / `solve` 中文标签) still
 * reach the model via the `emit_bubble` tool description, which
 * [renderTypeList] renders the same way.
 */
fun IntentRegistry.renderIntentBlock(): String =
    "type ∈ {${allIds().joinToString(", ")}}。"

/** Compact "id（label） / id（label） / ..." form used by tool descriptions. */
fun IntentRegistry.renderTypeList(): String =
    list().joinToString(" / ") { "${it.id}（${it.label}）" }
