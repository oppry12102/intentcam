# Intent Top-20 — Expansion Shortlist for IntentDecl

**Generated**: 2026-07-11
**Source**: `profiling/scan_intents.py` over `profiling/intent_all.json` (8,034 RCTW-171 train images)
**Output**: `profiling/intent_clusters.json` (full counts + samples + intersections)

This is the second pass: the first `build_intent_all.py` heuristic recognized only 3
intents (`info` 6,536 / `location` 1,493 / `solve` 5). Everything else falls into the
generic `info` bucket. Re-scanning with **35 regex clusters** surfaces the actionable
intent vocabulary that's already hiding in the dataset.

---

## Methodology

Each regex cluster corresponds to a *potential IntentDecl entry*: a class of text that
hints at a user goal distinct enough to warrant its own action. An image can belong to
many clusters (a storefront sign might match `location`, `phone`, `hours_schedule`,
`english_dominant` simultaneously — that's fine, we count per-cluster).

**Score**: `count × log10(count + 10)`. Count is primary; the log mild upweights niches
so a 100-hit `patent_id` doesn't get drowned by 6× more `location`. The TOP-20 is what
falls out of this rank, NOT a manual list.

**Stop condition**: ship a 20-intent vocabulary. Below-20 candidates (21-35) are listed
for transparency, not for this round.

---

## TOP-20 candidate intents

Rank by impact score. `#unique` = how many distinct texts matched. `r3-action` = the
candidate Android `Intent` action(s) we'd wire first.

| #  | Intent              | Count | % of 8034 | Candidate action(s)            | Notes |
|----|---------------------|-------|-----------|--------------------------------|-------|
|  1 | `location`          | 2533  | 31.5 %    | `open_in_maps` *(shipped)*     | The 1,493 already-tagged location + 1,191 in `info` with 2+ addr keywords that the heuristic missed (e.g., single `号` / `店` / `店 + 城`). |
|  2 | `direction_arrow`   | 895   | 11.1 %    | fold into `location`           | Mostly sub-feature of location (587/895 overlap with auto-location). Don't make a separate intent — keep `direction_arrow` as a secondary detector that **boosts** location confidence. |
|  3 | `phone`             | 645   | 8.0 %     | `ACTION_DIAL`                  | Cellphone + landline + `电话:` keyword. **Privacy question**: don't auto-dial; copy-or-prompt. |
|  4 | `english_dominant`  | 523   | 6.5 %     | (translation tool, transverse) | Pure/long English. Probably a *transverse tool* applied to any intent, not a new intent itself. Hold for decision. |
|  5 | `service_institution` | 514 | 6.4 %    | `open_in_maps` + `copy`        | 医院/学校/政府/银行/派出所. Already has an address → open_in_maps makes sense. |
|  6 | `warning_safety`    | 509   | 6.3 %     | `translate` (high-stakes)      | 请勿/禁止/警告/危险. Most users want to UNDERSTAND these, not act on them; translation tool wins. |
|  7 | `shopping_promo`    | 351   | 4.4 %     | `copy_to_clipboard`            | 促销/打折/满减. Most natural: copy the deal text to share. |
|  8 | `price`             | 327   | 4.1 %     | `copy_to_clipboard` (per line) | ￥/元/块/$. Pull out the line that has the price. |
|  9 | `menu_food`         | 308   | 3.8 %     | `translate` + itemize          | Restaurant or dish vocabulary. Translate the menu OR list dishes. |
| 10 | `advertising_design` | 186  | 2.3 %     | (info / generic)               | 广告/策划/设计/LOGO. Generic agency copy — probably no Action, just info. |
| 11 | `date_time`         | 178   | 2.2 %     | `add_to_calendar`              | 年/月/日/周N/节假日 + date patterns. |
| 12 | `hours_schedule`    | 140   | 1.7 %     | `save_reminder`                | 营业时间:HH:MM-HH:MM. Save as a content card. |
| 13 | `real_estate_rental` | 131  | 1.6 %     | `copy_to_clipboard`            | 二手房/出租/楼盘. Copy listing, sensitive (might leak address of owner). |
| 14 | `shopping_product`  | 114   | 1.4 %     | `web_search`                   | 批发/零售/代购/微商. Search the brand name. |
| 15 | `celebration_greeting` | 112 | 1.4 %    | `copy_to_clipboard`            | 恭喜/春节/中秋/开业大吉. Copy greeting. |
| 16 | `transit`           | 110   | 1.4 %     | `web_search` + `add_to_calendar` | CRH/航班/公交站/地铁. Lookup schedule. |
| 17 | `url`               | 110   | 1.4 %     | `ACTION_VIEW` *(web browser)*  | Explicit URL or .com/.cn. |
| 18 | `recruit_hiring`    | 97    | 1.2 %     | `copy_to_clipboard`            | 招聘/诚聘/招工. Copy job posting. |
| 19 | `instant_message`   | 96    | 1.2 %     | `copy_to_clipboard` *(no IM)*  | 微信/QQ contact text. **Don't auto-launch WeChat** — copy text & let user paste. |
| 20 | `parking`           | 75    | 0.9 %     | `open_in_maps`                 | 停车场/P车牌/停车费. |

**Counts are non-exclusive**: a sign can match `location + phone + hours + english_dominant`. Sum of column 2 > 8034. Total **distinct** images hit by at least one cluster is ~6,400 (≈80 %).

---

## Recommended ship order (next 3 sprints)

For actionable IntentDecl + ActionDecl growth. Pick 2-3 to add each cycle.

| Phase | Intent         | Why                                | Action to wire          | Test set to build          |
|-------|----------------|------------------------------------|--------------------------|----------------------------|
| **A** | `phone`        | Highest count. Privacy-safe default (`copy` first, `dial` only on user confirm). | `copy_to_clipboard` + `ACTION_DIAL` (consent dialog) | `phone_20.tsv` |
| **A** | `warning_safety` | Universal "translate this" need. Pairs well with Phase 2 OCR architecture that already trusts OCR text verbatim. | No new tool — reuse existing `translate`. | `warning_20.tsv` |
| **B** | `menu_food`    | Restaurant OCR is a 2026 spam use case (you come out of a restaurant, scan the menu, get an English version). | `translate` + structured list. | `menu_20.tsv` |
| **B** | `hours_schedule` | Trivial parse → save as content bubble. Same `applicableFamilies` plumbing. | `save_reminder` or content bubble with structured field. | `hours_20.tsv` |
| **C** | `real_estate_rental`, `recruit_hiring` | Sensitive PII; both are "copy this text to clipboard for the user to act elsewhere". Same ActionDef, different IntentDef. | `copy_to_clipboard` | `classifieds_20.tsv` (mix both) |

`direction_arrow` (#2), `english_dominant` (#4), `advertising_design` (#10), `service_institution` (#5) **do not become new intents** — they fold into existing intents as **secondary detectors** or transverse tools.

`location` (#1) is already shipped. `solve` (5 hits, ranked 21+ anyway) is too rare to expand without a synthetic fixture set.

---

## Below top-20 (transparency)

Clusters that ranked 21-35. Not shipped this round, but if the next data refresh
brings in more context (test set OCR, ICDAR-2015 photo-text, etc.) some will jump.

| #   | Cluster                  | Count | Why excluded |
|-----|--------------------------|-------|--------------|
|  21 | payment_qr               | 49    | Privacy risk; users are wary of "scan QR then pay now" pattern. Action decision needs product input. |
|  22 | traffic_sign             | 49    | Sub-feature of location/warning. Fold as detector, not intent. |
|  23 | vehicle_plate            | 41    | Niche. Could ship with `id_document` (#29) under one PII-safe intent. |
|  24 | notice_announcement      | 32    | Generic "公告/通知". Hard to action. |
|  25 | gaming_lottery           | 32    | Sensitive (gambling + game-cheating ads). Default to no-action. |
|  26 | event_show               | 19    | Sub-feature of date_time (#11). |
|  27 | love_dating              | 15    | Sensitive. Default to no-action. |
|  28 | logistics                | 14    | Tracker lookup needs API; ship only if we have a tracking backend. |
|  29 | id_document              | 13    | Privacy. ID-card detection needs explicit consent framework. |
|  30 | ingredients_recipe       | 11    | Subset of menu_food / product research; ship under food first. |
| 31-35 | event_show sub, medicine_drug, traffic_sign sub, patent_id, ip_address, etc. | <11 each | Too sparse for a 20-fixture suite. |

---

## Caveats

1. **Counts are upper bounds**: a regex cluster fires on ANY text containing the keyword. Many locations also match `phone` (contact listed) and `hours_schedule` (hours listed). Per-fixture exclusive-classification is a follow-up.
2. **Chinese-only is wrong**: the scan is CJK-optimized (RCTW-171 is Chinese scene text). The 20 intents work for `zh`; for `en`/`ja`/`ko` we'd need locale-aware variants.
3. **Action choice is provisional**: `copy_to_clipboard` is a safe default, but `web_search` (vs. specific app deep-link) is often the right call once we know what app the user has installed.
4. **Privacy is unaddressed in this scan**: 11 of 35 clusters (phone, instant_message, real_estate_rental, id_document, love_dating, vehicle_plate, payment_qr, gaming_lottery, medicine_drug, patent_id, ingredients_recipe) touch PII. Ship order MUST respect the consent framework already started for `open_in_maps`.

---

## Reproduce

```bash
python3 profiling/scan_intents.py   # writes profiling/intent_clusters.json
```

The script's clusters are intentionally **declarative** (a Python dict at the top) — to
add or split an intent, edit `CLUSTERS` and re-run. Same model as
`build_intent_all.py`: the script owns the regex, the JSON owns the assignments.

---

## Cross-links

- [[Intent-arg framework ship 2026-07-11]] — the 1 (location) → N (top 20) jump is the **next** move after this framework lands.
- [[Intent-all index RCTW-171 2026-07-11]] — `intent_all.json` is the data this scan consumes.
- [[Intent↔Action framework plan]] — the v0 IntentDecl + ActionDef design that this expansion plugs into.
