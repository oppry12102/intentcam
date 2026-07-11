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

    /** Chinese landline (area code 3-4 + 7-8 digits, hyphenated
     *  or not).  Separated from MOBILE because landlines more
     *  often live on "store backdrops" alongside addresses —
     *  flipping location→phone on a landline is riskier than on
     *  a mobile, so we currently DO NOT use this; reserved for
     *  future Phase E iterations if data shows missing
     *  fixtures.  Kept here as a stub. */
    @Suppress("unused")
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
        return currentType
    }
}
