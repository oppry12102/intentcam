package com.example.intentcam

/**
 * Reusable parsers for the common [ActionInputSpec] inputs.
 *
 * Single source of truth shared by:
 *   - `app/.../ActionDecl.kt` — used in `ActionDef.requiredInputs`
 *     (Android-coupled `body` lambda consumes them via
 *     `ActionOrchestrator.validateInputs`)
 *   - `shared/.../eval/EvalRunner.kt` — `markValidated` callback
 *     feeds `bubble.validatedInputs` for scoring
 *   - `shared/.../eval/ScorerV2.kt` — `r_inputs_complete` checks
 *     the regex constants directly (no lambda)
 *
 * Lives in `shared/` (not `app/`) so the eval pipeline can
 * import it without dragging Android types into the
 * pure-JVM build.  See
 * [docs/adr/2026-07-16-input-parsers-drift-risk.md](../docs/adr/2026-07-16-input-parsers-drift-risk.md)
 * for the drift history (3 copies of phone regex pre-2026-07-16).
 *
 * Migration (2026-07-16): moved from `app/.../ActionDecl.kt`'s
 * private `PhoneExtractor` + `InputParsers` objects.  Regex
 * semantics unchanged.
 */
object InputParsers {
    // Mobile: 1[3-9] + 9 digits (covers all 3 Chinese carriers
    // incl. 14x/15x/16x/17x/18x/19x series).
    val MOBILE_REGEX = Regex("""1[3-9]\d{9}""")
    // 400 / 800 service line.  Format: 400/800 + (3-4 digits) +
    // (3-4 digits), possibly hyphenated.
    val SERVICE_REGEX = Regex("""(?:400|800)[\s-]?\d{3,4}[\s-]?\d{3,4}""")
    // Landline: area code 3-4 digits + 7-8 digits, possibly
    // hyphenated, optionally leading 0 (sometimes present,
    // sometimes not — sign posters are inconsistent).
    val LANDLINE_REGEX = Regex("""\b0?\d{3,4}[\s-]?\d{7,8}\b""")

    /** Reuse the regex chain (mobile → 400/800 → landline).
     *  Returns the parsed number as a string (digits + hyphens
     *  where the format has separators), or null when no
     *  plausible number appears in title/detail/details[].value. */
    fun phoneNumber(bubble: Bubble): String? {
        // Concatenate all surfaces the model produced into one
        // search corpus.  Title gets a slight boost (the model
        // often puts the number there) by being first.
        val corpus = buildString {
            append(bubble.title).append('\n')
            append(bubble.detail).append('\n')
            bubble.details.forEach { d -> append(d.value).append('\n') }
        }
        // Order matters — first match wins on each call.
        MOBILE_REGEX.find(corpus)?.value?.let { return it }
        SERVICE_REGEX.find(corpus)?.value?.let {
            return it.replace(Regex("""[\s-]"""), "-")
        }
        LANDLINE_REGEX.find(corpus)?.value?.let {
            return it.replace(Regex("""[\s-]"""), "")
        }
        return null
    }

    /** For `open_in_maps` (and any future location-aware
     *  action).  Maps app accepts free-form queries, so we
     *  don't need to validate as a strict address — anything
     *  non-blank works.  But the **priority** matters: callers
     *  historically got the LLM action phrase (`"导航去仙桃市仙桃
     *  大道上岛咖啡"`) instead of the recognized address because
     *  `bubble.title` short-circuited before any address scan.
     *
     *  Priority chain (2026-07-17 rewrite after user-reported
     *  open_in_maps bug — see
     *  `~/.claude/plans/sorted-meandering-pond.md`):
     *
     *  1. **`bubble.details[]` address scan** — pick the first row
     *     whose value matches an address keyword (路/街/大道/巷/弄/
     *     号/区/县/市/省/村/镇/栋/座/层/室).  LLM writes each visible
     *     text element with bbox as a Detail row, so addresses
     *     like `洪山区珞喻路370号` or `屯村东路350号` land here
     *     verbatim.  This is the highest-signal source.
     *  2. **Simplified `bubble.title`** — strip navigation-verb
     *     prefixes (`导航去` / `导航到` / `去` / `找` / `到` / `在` /
     *     `这里是` / `我在这里` / `查看` / `打开`) and return the
     *     rest if non-blank.  Handles cases where LLM wrote the
     *     address directly as the title (`"仙桃市仙桃大道"`).
     *  3. **Full `bubble.detail`** — verbatim content description.
     *     The previous `detail.take(40)` truncated real addresses
     *     mid-token (`"...上岛咖啡西餐厅"` → `"...上岛咖"`) — that
     *     was a regression source, removed.
     *  4. **Last-resort detail row** — typically the storefront name
     *     on `route_to` fixtures (`"向前20米 藏方养生馆"`).  Lets the
     *     user tap-and-search, even though no street exists.
     *
     *  **Crucially** this parser no longer returns `"附近"` /
     *  empty string.  Body-level UX (Toast on unresolvable query)
     *  is the right place for that decision — see
     *  `app/.../ActionDecl.kt` `open_in_maps` body.
     *
     *  Returns null when the bubble has no extractable address OR
     *  no useful text.  Eval-side `EvalRunner.defaultRequiredInputs`
     *  uses this non-null check as the "is query present" gate;
     *  the new parser still returns non-null for every fixture that
     *  had a non-null result under the old parser, so eval won't
     *  silently drop `r_inputs_complete` credit. */
    fun locationQuery(bubble: Bubble): String? {
        // (1) details[] address-keyword scan
        val addressRegex = Regex("""\S*(路|街|大道|巷|弄|号|区|县|市|省|村|镇|栋|座|层|室)\S*""")
        bubble.details.firstOrNull { d ->
            d.value.isNotBlank() && addressRegex.containsMatchIn(d.value)
        }?.let { return it.value.trim() }

        // (2) Simplified title — strip navigation-verb prefixes
        val strippedTitle = bubble.title
            .replace(Regex("""^(导航去|导航到|去|找|到|在|这里是|我在这里|查看|打开)\s*"""), "")
            .trim()
        if (strippedTitle.isNotBlank() && strippedTitle.length >= 2) {
            return strippedTitle
        }

        // (3) Full detail (no truncation)
        bubble.detail.takeIf { it.isNotBlank() }?.let { return it.trim() }

        // (4) Last-resort: any non-blank detail row (route_to storefront names)
        return bubble.details.firstOrNull { it.value.isNotBlank() }?.value?.trim()
    }

    /** For all `copy_*` text-share actions.  Concatenates
     *  title + detail so the share-sheet payload matches
     *  what the existing bodies build inline today.
     *  Returns null only when the bubble is empty (no title,
     *  no detail, no detail rows) — extremely rare since
     *  every recognized bubble has at least a title or
     *  detail string. */
    fun textContent(bubble: Bubble): String? =
        buildString {
            append(bubble.title.takeIf { it.isNotBlank() } ?: "")
            if (isNotEmpty() && bubble.detail.isNotBlank()) append('\n')
            append(bubble.detail)
        }.takeIf { it.isNotBlank() }

    /**
     * For the `redact_id` action. Returns the first id-document-shaped
     * string found in title/detail/details[].value, or null when none.
     *
     * Patterns recognized:
     *   - Chinese resident-ID card: 18-digit, last char may be `X`/`x`
     *     (GB 11643-1999). Regex `\d{17}[\dXx]` — must appear in
     *     numeric context (preceded by space / start / punctuation,
     *     followed by space / end / punctuation) to avoid matching
     *     phone number substrings.
     *   - Explicit keyword hits: "身份证", "营业执照", "驾驶证",
     *     "车牌号" — covers fixtures where the LLM has paraphrased
     *     the number as "张三 身份证 130123..." and the number got
     *     OCR'd as separate tokens.
     *
     * Conservative on purpose: a false negative costs one missed
     * `redact_id` rescue; a false positive forces a privacy-irrelevant
     * chip on a bubble the LLM intentionally omitted (e.g. a clinic
     * sign mentioning "ID" in passing). Rescue is add-only and the
     * LLM's `action_ids` stays authoritative, so a spurious rescue
     * chip doesn't block the LLM's chosen chips.
     */
    fun idDocument(bubble: Bubble): String? {
        val corpus = buildString {
            append(bubble.title).append('\n')
            append(bubble.detail).append('\n')
            bubble.details.forEach { d -> append(d.value).append('\n') }
        }
        // Keyword hit first — cheap, covers paraphrased content.
        val keywordRegex = Regex("""身份证|营业执照|驾驶证|车牌""")
        keywordRegex.find(corpus)?.value?.let { return it }
        // Numeric ID hit — bounded to avoid phone-number substrings.
        // Phone numbers are 11 digits, IDs are 18 — no overlap.
        val idRegex = Regex("""(?<![\d])\d{17}[\dXx](?![\d])""")
        idRegex.find(corpus)?.value?.let { return it }
        return null
    }

    /**
     * For the `scan_to_pay` action. Returns a hit-string when the
     * bubble text suggests a payment QR / 收款码 / 付款码 surface,
     * or null otherwise.
     *
     * Pattern: explicit Chinese keyword hits — "收款码", "付款码",
     * "扫一扫", "转账", "收款二维码", "微信收款", "支付宝付款".
     * Returns the matched keyword (not the QR image itself — the
     * QR isn't in `bubble.content`; it's only on the original
     * image, which `ActionDef.body` of `scan_to_pay` reads at
     * fire-time). The bubble-text hit is the rescue trigger, not
     * the actual QR data.
     *
     * Conservative on purpose: this regex is the ONLY thing standing
     * between a phone-number-on-a-payment-flyer and an accidental
     * `scan_to_pay` chip. False positive → user sees a payment chip
     * they didn't ask for. False negative → missed rescue, soft-hint
     * already covers the common case via `payment_qr` intent.
     */
    fun paymentQr(bubble: Bubble): String? {
        val corpus = buildString {
            append(bubble.title).append('\n')
            append(bubble.detail).append('\n')
            bubble.details.forEach { d -> append(d.value).append('\n') }
        }
        val payRegex = Regex("""收款码|付款码|扫一扫|转账|收款二维码|微信收款|支付宝付款""")
        return payRegex.find(corpus)?.value
    }
}