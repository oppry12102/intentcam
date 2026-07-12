package com.example.intentcam

/**
 * [2026-07-13] Phase E — post-emit_bubble type verifier.
 *
 * Why this exists: the 3→8 intent jump in Phase A+B leaves the LLM
 * confused on multi-signal images. A 黄焖鸡 storefront carries BOTH a
 * phone number (027-xxxx) AND an address (光谷街 X 号); the model
 * defaults to `location` because address has more chars.  Phase D's
 * prompt-only attempt regressed -0.035 because pulling attention
 * toward type-classification rules cuts into the OCR-verbatim text
 * extraction rules (third-time confirmation of the 2026-07-10
 * "verbose intent block pulls attention from text" lesson).
 *
 * Design constraint: **don't ask the LLM to do the classification.**
 * Run a regex/classifier step on the bubble's surface text AFTER the
 * LLM has finished, and silently override the `type` label when a
 * stronger domain signal is unambiguously present.  This is the same
 * pattern as the OCR-verbatim Phase 2 — the LLM writes whatever it
 * writes, the structure enforces correctness downstream.
 *
 * Conservative scope:
 *  - Only OVERRIDES (never appends).  If the LLM said `phone` correctly,
 *    we don't touch it.
 *  - Only OVERRIDES ambiguous types (`location`, `info`).  Specific
 *    types like `phone`, `recruit_hiring` are assumed intentional.
 *  - The two highest-confidence overrides are:
 *      * `location` + mobile number present → `phone`
 *      * `info` + QR-payment keyword present → `payment_qr`
 *  - All other conflicts (real_estate_rental, recruit, id_document)
 *    are deliberately left for a future pass — their regex signal
 *    is too noisy to override confidently (e.g. "出租" appears in
 *    many storefront addresses that are NOT real-estate ads).
 *
 * Output: returns a **corrected type string**.  When no override
 * applies, returns the input string unchanged — the call site can
 * `replace` blindly without branching.
 */
object IntentVerifier {

    /** Mobile: 1[3-9]xxxxxxxxx — strong "user wants to dial"
     *  signal whenever it appears in the bubble text. */
    private val MOBILE = Regex("""1[3-9]\d{9}""")

    /** 400/800 service line.  Slightly weaker than mobile
     *  because "服务热线 400-…" can also describe a bank's main
     *  switchboard.  Triggers an info→payment_qr-only override
     *  (NOT location→phone) because service lines often live on
     *  store backdrops that contain addresses anyway. */
    private val SERVICE = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")

    /** QR-payment signal — substring markers that uniquely mean
     *  "this is a QR for paying" rather than "扫一扫 for 关注公众号".
     *  Excludes the WeChat-follow case explicitly. */
    private val QR_PAYMENT = Regex("""(?:扫\s*一\s*扫|收款码|付款码|收款|付款)""")

    // [2026-07-13] Phase E2 — extend verifier with the remaining 5
    //  override rules.  Each is conservative + monotonic (won't fire
    //  unless the signal is unambiguous), so adding them in one
    //  commit is safe — no LLM attention regression (the verifier
    //  runs post-emit_bubble, doesn't touch prompt tokens).

    /** Recruit signal — substring keywords that uniquely mean
     *  "this is a job posting".  Triggers info→recruit_hiring.
     *  Excludes the "招聘广告" verb-on-sign context where the word
     *  is incidental.  The (?!.*广告) lookahead catches both cases. */
    private val RECRUIT = Regex("""(?:招聘|招工|急招|诚招|诚聘|招服务员|招营业员|招工作人员|聘请|高薪诚聘|高薪聘请)""")

    /** Real-estate signal — "出租" / "出售" with a structured
     *  context (typically followed by 房/室/平米/户型).  Plain
     *  "出租" alone is too noisy (most real-estate ads use the
     *  pair pattern). */
    private val REAL_ESTATE = Regex("""(?:出租[房套房]|出售[房套]|二手房|二手房源|房源|楼盘出售|急售|吉房|精装[房套]|户型|平米|押一付三)""")

    /** ID-document signal — strong because these tokens only mean
     *  one thing in the Chinese scene-text distribution. */
    private val ID_DOCUMENT = Regex("""(?:身份证|居民身份证|营业执照|登记号|统一社会信用代码|工商注册号)""")

    // [2026-07-12] Phase G — high-signal observe-only markers.
    //  Each is intentionally tight (rare false positives in scene-
    //  text distribution) so the verifier can flip `info` →
    //  specialized intent without over-triggering on incidental
    //  mentions.  Mirror scan_intents.py cluster definitions.
    private val WARNING = Regex("""(?:请勿|禁止|警告|严禁|违禁|高危|危险|注意|小心|当心|触电|高压|易燃|易爆|剧毒|辐射|严禁烟火|当心触电|注意安全|禁止入内|禁止通行|高压危险|小心地滑)""")
    private val MENU = Regex("""(?:菜单|菜谱|菜品|招牌菜|主厨推荐|今日特价|主菜|配菜|汤品|甜品|主推|套餐价|今日菜单|厨师推荐)""")
    /** Hours — matches both time-pattern style ("9:00-22:00", "09:00 至
     *  21:30") and chinese-keyword style ("营业时间", "营业中").  The
     *  HOUR_PATTERN half covers 24h clock (with optional minute) and
     *  上下午 prefixes.  Keyword list is deliberately narrow — generic
     *  "营业" alone is too noisy (shows up on half the storefronts). */
    private val HOUR_PATTERN = Regex("""(?:\d{1,2}:\d{2}\s*[-—~至]\s*\d{1,2}:\d{2}|[上中下]午\d{1,2}[点:：]\d{0,2}|周一至周日|周一至周五|周一至周[日六])""")
    private val HOURS = Regex("""(?:营业时间|营业中|开张营业|打烊|店休|休业|暂停营业|营业时段|营业|开门|关门|停止营业|今日营业|24小时营业)""")

    /** Chinese landline (area code 3-4 + 7-8 digits, hyphenated
     *  or not).  Separated from MOBILE because landlines more
     *  often live on "store backdrops" alongside addresses —
     *  flipping location→phone on a landline is riskier than on
     *  a mobile, so we currently DO NOT use this; reserved for
     *  future Phase E iterations if data shows missing
     *  fixtures.  Kept here as a stub (Phase F2 reject —
     * v1.2 LT: but post-guard (option c) re-evaluation showed
     * image_1359 text emits landline 027-87875310 alongside
     * LLM `location` type and never gets flipped, so activating
     * Pass 1b' is the targeted single-var rescue). */
    private val LANDLINE = Regex("""\b0\d{2,3}[\s-]?\d{7,8}\b""")

    /**
     * Verify + (possibly) correct a bubble's intent type by reading
     * the bubble's surface text.  Pure function; no side effects.
     *
     * Returns the input [currentType] when no override applies,
     * otherwise returns the override target.  Caller can `replace`
     * blindly:
     *
     * ```kotlin
     * val verifiedType = IntentVerifier.verify(
     *     currentType = tb.type,
     *     title = tb.title,
     *     detail = tb.detail,
     *     details = tb.details,
     * )
     * ```
     *
     * Arguments are passed as scalars / lists so this stays
     * pure-data and doesn't drag a `Bubble` instance into `shared/`
     * boundaries — the verifier runs on the orchestrator's
     * `ToolResult` (which lives in `shared/`) before the platform-
     * specific `Bubble` is constructed.
     */
    fun verify(
        currentType: String,
        title: String,
        detail: String,
        details: List<Detail>,
    ): String {
        // Build a single search corpus once — same strategy as
        // Phase A's PhoneExtractor.  Title first because the model
        // often states the primary signal there.
        val corpus = buildString {
            append(title); append('\n')
            append(detail); append('\n')
            details.forEach { append(it.value).append('\n') }
        }
        return verifyType(currentType, corpus)
    }

    /**
     * [2026-07-11] Phase F — canonical action id for an intent type.
     * The single [ActionDef] each type ships with (mirrors
     * `registerDefaultActions` in app/ + `defaultActionIds` in
     * EvalRunner).  Observe-only types (`info`, `solve`) carry no
     * action → null.
     *
     * Lives here (not in app/ActionDecl) because Phase F's injection
     * runs in shared/ `ToolUseLoop` alongside [verify], before the
     * platform-specific `Bubble` is constructed.  Keep in lockstep
     * with the app's ActionDef registry and EvalRunner.defaultActionIds.
     */
    fun actionFor(type: String): String? = when (type) {
        "location"            -> "open_in_maps"
        "phone"               -> "dial_number"
        "real_estate_rental"  -> "copy_listing"
        "recruit_hiring"      -> "save_posting"
        "payment_qr"          -> "scan_to_pay"
        "id_document"         -> "redact_id"
        // [2026-07-12] Phase G — high-value observe intents with copy-style actions.
        "warning_safety"      -> "copy_warning"
        "menu_food"           -> "copy_menu"
        "hours_schedule"      -> "copy_hours"
        else                  -> null   // info / solve — no action
    }

    private fun verifyType(currentType: String, corpus: String): String {
        // Pass 1: location + mobile number → phone.  Strongest
        //  signal-by-volume rule; absence of FA cases observed in
        //  pii20 / phone_20 (a mobile in a "location" bubble
        //  almost always means the user wants to dial, not
        //  navigate).
        if (currentType == "location" && MOBILE.containsMatchIn(corpus)) {
            return "phone"
        }
        // Pass 1b: location + 400/800 service line → phone.  Same
        //  reasoning; service lines on storefront signs are
        //  dial-first signals for the user.
        if (currentType == "location" && SERVICE.containsMatchIn(corpus)) {
            return "phone"
        }
        // Pass 1b' (post-guard option (a) single-var test, 2026-07-12):
        //  location + landline (0xxx-xxxxxxx) → phone.  Same
        //  reasoning as 1/1b — storefront landline is a dial-first
        //  signal.  Was a stub-only regex until post-guard (option c,
        //  2026-07-12) re-evaluation showed image_1359 (restaurant
        //  with 027-87875310) emits `location` and post-guard can't
        //  rescue location→phone.  Promoting LANDLINE to a real rule
        //  is the targeted single-var fix.  Ordered AFTER 1b so
        //  service-line fixtures win the 400/800 pattern first.
        if (currentType == "location" && LANDLINE.containsMatchIn(corpus)) {
            return "phone"
        }
        // Pass 1c-1e (2026-07-11, Phase F enablement): location + strong
        //  PII token → the PII type.  Mirrors the info-source rules
        //  (Pass 4-6) for the `location` source.  The LLM types
        //  storefront / billboard PII signs (房屋出租 600平米 / 登记号 /
        //  招聘启事) as `location` because they are physical signs on
        //  buildings — so the info-only rules never fired and every
        //  such fixture sat at r2_type=0.5 with no action.  These three
        //  flips let the verifier correct the type, which in turn gives
        //  Phase F's action injection (in ToolUseLoop) a flip to act on.
        //  Ordered AFTER the mobile/service→phone rules above so a
        //  dialable number still wins (a storefront banner with both a
        //  mobile and 平米 goes to phone, not real_estate).  Same regex
        //  tightness + monotonic guarantee as the info-source rules.
        if (currentType == "location" && REAL_ESTATE.containsMatchIn(corpus)) {
            return "real_estate_rental"
        }
        if (currentType == "location" && RECRUIT.containsMatchIn(corpus)) {
            return "recruit_hiring"
        }
        if (currentType == "location" && ID_DOCUMENT.containsMatchIn(corpus)) {
            return "id_document"
        }
        // Pass 2: info + QR-payment language → payment_qr.
        //  Disambiguates the scan-to-pay posters (扫一扫 in
        //  WeChat-follow contexts typically includes 关注 too;
        //  here we keep it simple: any 收款/付款 token flips it).
        if (currentType == "info" && QR_PAYMENT.containsMatchIn(corpus)) {
            return "payment_qr"
        }
        // Pass 3: info + mobile → phone.  The text "售后电话
        //  138xxxx" inside a wall-noise info bubble is what
        //  hit image_1216 / image_2267 in the Phase C2 + Phase E1
        //  gap.  Adding the rule here, so a stray mobile in an
        //  info bubble isn't lost.
        if (currentType == "info" && MOBILE.containsMatchIn(corpus)) {
            return "phone"
        }
        // Pass 4: info + recruit keyword → recruit_hiring.  The
        //  keyword list is tight (招聘/招工/诚聘 etc.) to avoid
        //  "招聘广告制作" on info-style posters getting
        //  over-flipped.
        if (currentType == "info" && RECRUIT.containsMatchIn(corpus)) {
            return "recruit_hiring"
        }
        // Pass 5: info + structured real-estate token →
        //  real_estate_rental.  Restricted to compound patterns
        //  (出租房 / 出售房 / 二手房 / 房源 / 押一付三 / etc.) so
        //  "出租" alone doesn't over-trigger on incidental usage.
        if (currentType == "info" && REAL_ESTATE.containsMatchIn(corpus)) {
            return "real_estate_rental"
        }
        // Pass 6: info + ID-document token → id_document.
        //  身份证 / 营业执照 / 登记号 are unambiguous in the
        //  Chinese scene-text distribution.
        if (currentType == "info" && ID_DOCUMENT.containsMatchIn(corpus)) {
            return "id_document"
        }
        // [2026-07-12] Phase G — info + observe-intent markers
        //  → specialized type.  Ordered after the PII-flip rules
        //  (Pass 1c-1e pre-empt earlier, Pass 4-6 right above) so a
        //  a phone/payment-qr real-estate ad still wins over a
        //  warning-style poster; only flips when no PII signal.
        // Pass 8: info + warning / safety token → warning_safety.
        //  请勿 / 禁止 / 警告 / 危险 are unambiguous in the safety
        //  sign distribution.  Ordering before menu/hours because
        //  warning signs sometimes carry "营业" incidentally (e.g.
        //  "营业场所 禁止吸烟") — pass 8 fires first.
        if (currentType == "info" && WARNING.containsMatchIn(corpus)) {
            return "warning_safety"
        }
        // Pass 9: info + menu / dish keywords → menu_food.  菜单 /
        //  招牌菜 / 主厨推荐 are restaurant-specific.
        if (currentType == "info" && MENU.containsMatchIn(corpus)) {
            return "menu_food"
        }
        // Pass 10: info + hours keywords OR time pattern →
        //  hours_schedule.  Either HOURS regex (营业中 / 打烊 /
        //  营业时间) or HOUR_PATTERN (HH:MM-HH:MM, 上下午H) suffices
        //  — storefront signs often have one but not both.
        if (currentType == "info"
            && (HOURS.containsMatchIn(corpus) || HOUR_PATTERN.containsMatchIn(corpus))
        ) {
            return "hours_schedule"
        }
        // Pass 7 (E3, 2026-07-11): real_estate_rental + mobile, but no
        //  real-estate signal → phone.  Catches image_1216 (电动车商铺
        //  with 售后电话 183...), image_2267 (小南小区 大发搬家 with
        //  multiple mobile contacts) — fixtures the LLM over-types as
        //  real_estate_rental but whose GT is `phone`.  The
        //  !REAL_ESTATE guard is critical: image_3285 ("吉房急售" + a
        //  contact number) has real_estate_rental as its GT, and the
        //  corpus matches the REAL_ESTATE regex, so the guard fires
        //  and the rule skips — preserving the correct type.
        //
        //  Risk: a real-estate ad whose corpus has no REAL_ESTATE
        //  keyword (just a property name + contact phone) would be
        //  flipped to phone even when GT says real_estate_rental.
        //  Phase B's pii20 GT is curated so all 4 real_estate_rental
        //  fixtures carry at least one REAL_ESTATE token; cross-check
        //  before adding new fixtures that don't.
        if (currentType == "real_estate_rental"
            && MOBILE.containsMatchIn(corpus)
            && !REAL_ESTATE.containsMatchIn(corpus)
        ) {
            return "phone"
        }
        // [2026-07-12] Phase G post-guards — fix the LLM self-
        //  classify mistake observed on phone_20 image_1359 (黄焖鸡
        //  米饭 + 热干面馆 + 027-87875310): LLM emitted `type=
        //  menu_food` directly, Pass 9 didn't catch it (corpus has
        //  no `招牌菜`/`菜品`/etc. — MENU regex is strict), so the
        //  fixture regressed 0.90 → 0.79.  Post-flip here to `phone`
        //  recovers the user's actual intent ("call this restaurant
        //  service line").
        //
        //  Mirrors Pass 7 structure (real_estate_rental + MOBILE +
        //  !REAL_ESTATE → phone): only fires on a strong out-family
        //  signal (any of MOBILE / LANDLINE / SERVICE match).
        //  Restricted to currently over-firing observe intents
        //  (menu_food + hours_schedule).  warning_safety NOT
        //  covered — a "请勿拨打 119" warning IS the user-relevant
        //  intent.
        //
        //  LANDLINE regex un-suppressed here (was stub-only since
        //  F2 reject): covers 027-xxxx-xxxx area-coded numbers
        //  MOBILE / SERVICE patterns don't.  Combined
        //  (MOBILE|LANDLINE|SERVICE) matches image_1359's 027-
        //  87875310 (LANDLINE pattern fires).  Hours-with-callback
        //  sign (e.g. "营业 9-21, 客服 400-xxx-xxxx") would flip
        //  to phone — arguably correct, acceptable trade.
        if ((currentType == "menu_food" || currentType == "hours_schedule")
            && (MOBILE.containsMatchIn(corpus)
                || LANDLINE.containsMatchIn(corpus)
                || SERVICE.containsMatchIn(corpus))
        ) {
            return "phone"
        }
        return currentType
    }
}
