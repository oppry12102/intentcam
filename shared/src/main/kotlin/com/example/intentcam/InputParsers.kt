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
     *  non-blank from the bubble's title or detail works.
     *  Prefers title (the model often puts the storefront
     *  name / address there). */
    fun locationQuery(bubble: Bubble): String? =
        bubble.title.takeIf { it.isNotBlank() }
            ?: bubble.detail.takeIf { it.isNotBlank() }?.take(40)
            ?: bubble.details.firstOrNull { it.value.isNotBlank() }?.value

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
}