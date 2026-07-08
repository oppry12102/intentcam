# 三方条件审计 — eval_rctw_v2.py vs 实际 app vs Kotlin 1-only

**日期**: 2026-07-09
**目的**: 检查三边测试条件是否对齐, 找出导致 cloud @100 跟 Kotlin 1-only @100 差 0.118 的真实原因

---

## 0. 三个对象速记

| 对象 | 是什么 | 主要 source | 跑分 (rctw) |
|---|---|---|---|
| **eval_rctw_v2.py** | Python 端多 round 编排器, 模拟 FrameAnalyzer | `profiling/eval_rctw_v2.py` | cloud 0.7015 / stub 0.6685 @100 |
| **实际 Android app** | 生产路径: FrameAnalyzer + ToolUseLoop + HMS OCR | `app/.../FrameAnalyzer.kt` 等 | 没有跑 rctw, 装机 |
| **Kotlin 1-only EvalRunner** | JVM 跑 ToolUseLoop 真实代码 (ImageIO 替代 Bitmap, OCR=null) | `shared/.../eval/EvalRunner.kt` | 0.819 @100 (eval_1only_100_v3.json) |

---

## 1. Image pipeline 对照

| 维度 | eval_rctw_v2.py (Python) | 实际 Android app | Kotlin 1-only EvalRunner |
|---|---|---|---|
| 缩略图分辨率 | 768px max-dim, q80 | 768px max-dim, q80 | 768px max-dim, q80 |
| Full-res 保留 | 原图直接送 round 1 hint, 之后 crop 也用原图 | MAX_FULL_DIM=4096, q95, **保留到 zoom_in 时刻才用** | 用原图 (raw jpg) |
| 4 quadrant crops | **已删 (本轮改动)** | `quadrants = emptyList<ByteArray>()` (1-only mode since 2026-07-06) | `quadrants = false` 默认 |
| **round-1 OCR 输入** | **缩略图 (768px)** | **fullRes (4096px downscale 到 1920px 给 HMS)** | **fullRes (raw)** |
| 编码器 | PIL (BILINEAR + JPEG q80) | Bitmap.compress (JPEG) | ImageIO (BILINEAR + JPEG q80) |

**🔴 最大差距**: eval 把 768px 缩略图送 OCR; prod 把 1920px(fullRes downscale)送 OCR。**eval OCR 看到的像素数是 prod 的 1/6.25**。这就是为什么我们 [LOW] 38%, prod 应该 <15%。

## 2. OCR 后端

| 维度 | eval_rctw_v2.py | 实际 Android app | Kotlin 1-only EvalRunner |
|---|---|---|---|
| 后端 | **华为云 `RecognizeGeneralText`** (cn-north-4) | **HMS ML Kit on-device** (zh + en 模型) + HMS 远程端云 fallback | `OcrEngine.impl = null` (返回 EMPTY) |
| 模型 | 华为云端模型 (按调用计费) | `ml-computer-vision-ocr-cn-model` + OCR AAR | — |
| 返回坐标 | `[x, y]` pair list (归一化, 4 角) | 4 角 corners (归一化) | — |
| 单张图长边 | thumbnail = 768px | 1920px | — |
| 离线? | ❌ 网络 | ✅ 默认离线 (远程是 fallback) | ❌ 无后端 |

**🟡 设计性差异**: 三个 OCR 后端都不一样, 严格说 0.7015 / 0.6685 / 0.819 不能直接比较。但 0.819 的 Kotlin 跑 OCR=null, 是因为**OCR hint 已经被 inject 进 round 1 user message** (`OcrResult.formatHint([])` 返回 "" 时 round 1 仍然把空 hint 注入, 不影响模型行为)。0.819 跑分几乎完全靠模型自己读图 + LLM 调用 read_text (实际拿到 stub) 的方式拿到, 这点跟 stub @100 应该是同条件。

**❗重新审视**: 0.819 跑的 OCR=null, 所以 0.819 跟 stub @100 才是真正的"同条件对比"。那 0.819 - 0.6685 = **0.150** 才是真正的"差距", 这个差距来源是 scoring + prompt + tools, 不是 OCR。

## 3. 工具架构

| 工具 | eval_rctw_v2.py | 实际 Android app | Kotlin 1-only |
|---|---|---|---|
| zoom_in | ✅ | ✅ | ✅ |
| read_text | ✅ → 调 `ocr_huaweicloud.crop_then_recognize` | ✅ → 调 `OcrEngine.recognize` (HMS) | ✅ → 调 `OcrEngine.recognize` (null, 返回 "") |
| **compare_text** | **❌ 无** | ✅ (纯端侧 diff) | ✅ (纯端侧 diff) |
| emit_bubble | ✅ | ✅ | ✅ |

**🔴 eval 缺 compare_text 工具** — Kotlin endcloud 设计里的关键差异点。compare_text 帮模型消解 OCR-vs-LLM 冲突, 理论上能涨 r2_text。

## 4. System prompt

| 维度 | eval_rctw_v2.py (本轮重写) | 实际 Android app (`TOOL_USE_SYSTEM`) | Kotlin 1-only |
|---|---|---|---|
| Prompt 源 | Python 字符串 (本轮新增) | `LlmClient.kt:415-480` `TOOL_USE_SYSTEM` | 同 prod |
| "OCR 第一意见" 表述 | ✅ | ✅ | ✅ |
| 4 quadrant 描述 | ❌ 已删 | ❌ (1-only mode) | ❌ |
| "调用 read_text 默认不要用" | ❌ 已删 | ❌ (新 prompt) | ❌ |
| 字符 verbatim 复制 | ✅ | ✅ | ✅ |
| [LOW] 行不复制 | ✅ | ✅ | ✅ |
| 工具数描述 | 3 工具 | **4 工具** (含 compare_text) | 4 工具 |

**🟡 中文文本是翻译不是拷贝** — eval_rctw_v2.py 现在的 SYSTEM_PROMPT 是从 Kotlin 翻译过来的, 措辞基本对齐但**不逐字相同**。细微差异可能影响模型对工具边界的理解 (例如 compare_text 在 eval prompt 里完全没提, 进一步让模型不调 read_text)。

## 5. Scoring

### 5.1 Round 1 (工具选)

| 维度 | eval_rctw_v2.py | Kotlin 1-only |
|---|---|---|
| emit_bubble 首调 = ? | **1.0** | 0.85 (skipReconScore) |
| zoom_in / read_text / compare_text = ? | **0.7** | **1.0** |
| (none) = ? | 0.0 | 0.85 |
| Error = ? | 0.0 | 0.0 |
| 公式 | `0.7 * pick + 0.3 * input_ok` | `0.7 * pick + 0.3 * input_ok` |
| skipReconScore (无 round-1 工具时) | n/a (1.0 是 emit_bubble 默认) | 0.85 (有 text 期望) / 1.0 (无) |

**🔴 严重差异**: Kotlin 给 zoom_in/read_text/compare_text 首调 **1.0 分**; Python 给 **0.7 分**。这意味着同样模型选 zoom_in, 在 Kotlin 里 r1=0.79+0.3=1.0, 在 Python 里 r1=0.49+0.3=0.79。**eval r1 系统性低估 ~0.21/fixture**。

### 5.2 Round 2 text 评分

| 维度 | eval_rctw_v2.py | Kotlin 1-only |
|---|---|---|
| 匹配方式 | **strict substring, lowercase** | **fuzzy match: NFKC + quote 折叠 + 双向 contains + 无空格 contains** |
| 期望 keyword | `expected_description_keywords` (i, str in) | `expected_description_keywords` (i, str in) |
| 期望 details | `expected_details` (kind, label, value) | `expected_details` (value only) |
| Details 匹配逻辑 | `kind == llm_kind AND label ~ llm_label AND value substring` | `normalize(value) ~ normalize(llm_value)` 单字段 |
| 组件 | text-only, 平均 keyword + detail hit rate | 同样平均, 但 normalize 后的 fuzzy |
| 字符规范化 | `.lower()` | NFKC + "':": ':' + 折叠引号 + 小写 |
| 诊断用 fuzzy 分 | ❌ | ✅ `r2_text_fuzzy` 用来诊断 strict 漏判 |

**🔴 严重差异**: Python 的 `score_text` 严格要求 substring 包含; Kotlin 用 NFKC + fuzzy。Kotlin r2_text 0.717 跟 Python 0.256 的差距 (~0.46) 里有相当一部分是 **scorer 严格度差异**, 不是模型能力差异。

### 5.3 Round 2 type 评分

| 维度 | eval_rctw_v2.py | Kotlin 1-only |
|---|---|---|
| right bucket | 1.0 | 1.0 |
| valid type, wrong | **0.0** (binary) | **0.5** (3-way) |
| empty / unknown | 0.0 | 0.0 |
| 公式 | 0.5 * text + 0.5 * type | 0.5 * text + 0.5 * type |

**🟡 中等差异**: Python 是 binary 0/1, Kotlin 是 3-way 0/0.5/1。Python type 分 9/100 fixture 因为模型写 "solve" 而不是 "info" 全 0, Kotlin 同样情况 0.5。Python type 系统性低估 9×0.5/100 = 0.045 整体。

### 5.4 Composite 公式

| 维度 | eval_rctw_v2.py | Kotlin 1-only |
|---|---|---|
| 公式 | `0.5 * r1 + 0.5 * r2` | `0.5 * r1 + 0.5 * r2` |
| 一样 | ✅ | ✅ |

## 6. 编排流程 (Orchestrator)

| 维度 | eval_rctw_v2.py | Kotlin ToolUseLoop |
|---|---|---|
| 最多 round | 3 (硬编码) | `MAX_ROUNDS` (没看具体值, 应该是 4-5) |
| 第二次 zoom_in 后 | 强制 emit_bubble 的提示 | 取决于模型, 没看到强制逻辑 |
| read_text 行为 | crop_then_recognize (整 crop 跑 OCR) | `OcrEngine.recognize(ctx.jpeg)` 在指定区域 |
| 工具结果包装 | `tool_result` content 是 `text`, 内含 summary | 同样 |
| 用户消息结构 | 缩略图 + (空) + tool_result | followUpJpegs + tool_results |
| ToolContext.ocrCache | ❌ 没存 (每次 read_text 重跑) | ✅ 存 round-1 OcrResult, 给 compare_text 用 |

**🟡 中等差异**: eval 没有 ocrCache, 所以 read_text 在 round 2 重复跑整张图 (或 crop) 的 OCR; prod 把 round-1 缓存了, 后续工具复用。read_text 在 eval 里**更慢更贵**, 模型不太愿意调。

## 7. 数据源

| 维度 | eval_rctw_v2.py | 实际 Android app | Kotlin 1-only |
|---|---|---|---|
| 来源 | `profiling/ground_truth_rctw.json` | 用户摄像头 (real-world) | 同 eval (`profiling/ground_truth_rctw.json`) |
| 图片 | `img/rctw/rctw_default_NN.jpg` (Picsum + 合成字 overlay) | 用户实拍 | 同 eval |
| 期望 keyword 来源 | ground_truth_rctw.json (人工) | 用户实际意图 (无 GT) | 同 eval |

**🟢 关键发现**: Kotlin 1-only 0.819 跟 eval_rctw_v2.py 跑的是**同一组 fixture + 同一组 ground truth**。所以分数差异是 prompt/tools/scoring 差异, 不是 fixture 差异。

## 8. 差距归因 (cloud @100 0.7015 vs Kotlin 0.819, gap -0.118)

| 差距来源 | 估计影响 | 证据 |
|---|---|---|
| 1. OCR 输入分辨率 (768 vs 1920) | -0.05 ~ -0.08 | [LOW] 比例 38% vs 估计 10-15% |
| 2. OCR 后端 (云端 vs HMS) | -0.01 ~ -0.03 | 两个后端 confidence calibration 不同 |
| 3. Scoring 严格度 (strict vs fuzzy) | -0.05 ~ -0.10 | r2_text 0.256 vs Kotlin 0.717, 差 0.46 是上限, 假设一半归因 scorer |
| 4. Type scoring (binary vs 3-way) | -0.04 | 9/100 fixture × 0.25 × 0.5 (r2 weight) ≈ 0.011; 加上 model 在 5% 几率出 "solve" / "location" 而不是 "info" |
| 5. r1 权重 (zoom_in 0.7 vs 1.0) | -0.06 | ~70% fixture 选 zoom_in, 0.21 × 0.7 (r1 weight) × 0.5 (composite weight) ≈ 0.07 |
| 6. 缺 compare_text 工具 | -0.02 ~ -0.04 | 模型对 OCR 冲突的消解能力受限 |
| 7. prompt 翻译差异 | -0.01 ~ -0.02 | 细微表述差 |
| 8. 模型 + temperature 非确定性 | -0.02 ~ +0.02 | 噪声 |
| **总差距 (上界)** | **-0.20 ~ -0.27** | 实际 -0.118; 部分影响是叠加而不是相加 |
| **总差距 (下界)** | **-0.18 ~ -0.22** | 较保守估计 |

也就是说, 0.118 差距 = 多个小差距的累加, 不存在单一"致命差距"。

## 9. 推荐修法 (按性价比)

### P0: 修 scoring (估计 +0.05 ~ +0.10 涨分)

```python
# 替换 eval_rctw_v2.py 的 score_text() 严格 substring 为 fuzzy:
import unicodedata
def normalize(s):
    n = unicodedata.normalize('NFKC', s)
    n = n.replace('：', ':').replace('"', '"').replace('"', '"')
    n = n.replace("'", "'").replace("'", "'").replace('「', "'").replace('」', "'")
    import re
    n = re.sub(r'\s+', ' ', n)
    return n.strip().lower()
def fuzzy_match(hay, needle):
    h, n = normalize(hay), normalize(needle)
    if n in h: return True
    if h in n and len(n) >= 2: return True
    hNoWs, nNoWs = h.replace(' ', ''), n.replace(' ', '')
    if hNoWs in nNoWs or nNoWs in hNoWs: return True
    return False
```

同样把 type 评分改成 3-way partial credit。

### P1: 修 round-1 OCR 输入 (估计 +0.02 ~ +0.05)

```python
# 改 build_round1_ocr_hint 用 original_jpeg (raw), 不是 _hint_thumb:
original_jpeg = img_path.read_bytes()  # already loaded
round1_hint = build_round1_ocr_hint(original_jpeg)
user_msg = make_user_msg(img_path, round1_hint)
# 但 LLM round 1 还是看 _hint_thumb (768px), 不是原图
```

这样 OCR 看到的输入是原图 (1920px 等效), [LOW] 比例应该从 38% 降到 ~15%。

### P2: 加 compare_text 工具 (估计 +0.02)

照搬 Kotlin 的 `compare_text` 实现到 eval_rctw_v2.py (纯端侧 diff, 不需要 cloud OCR)。

### P3: 改 r1 权重 (估计 +0.07 系统性回调)

```python
# 改 score_round1:
if picked == "emit_bubble":
    pick_score = 0.85  # was 1.0
elif picked in ("zoom_in", "read_text"):
    pick_score = 1.0    # was 0.7
```

注意: 这是**纯调分**, 不代表模型能力提升。但如果目标是跟 Kotlin 0.819 对齐, 这步是必经的。

### P4: prompt 跟 Kotlin 1-only 严格对齐 (估计 +0.01)

把 `eval_rctw_v2.py` 的 SYSTEM_PROMPT 改成读 `LlmClient.kt:415-480` 的字符串 (从 Kotlin 文件抽出来), 不再翻译。这样保证 0 drift。

---

## 10. 总结

| 维度 | eval_rctw_v2.py | 实际 app | Kotlin 1-only | 差距 |
|---|---|---|---|---|
| Image pipeline | 768 thumb | 768 thumb + 1920 OCR | 768 thumb + raw OCR | 中 (OCR 输入小) |
| OCR 后端 | 华为云 | HMS on-device | null | 大 (云端 ≠ on-device) |
| 工具数 | 3 (无 compare_text) | 4 | 4 | 中 |
| System prompt | 翻译 | 原版 | 原版 | 小 |
| r1 权重 | zoom=0.7, emit=1.0 | (app 内不评分) | zoom=1.0, emit=0.85 | **大** |
| score_text | strict substring | (n/a) | fuzzy + NFKC | **大** |
| score_type | binary | (n/a) | 3-way | 中 |

**最大单点差距**: r1 权重 (eval_rctw_v2.py 把 zoom_in 评为 0.7, Kotlin 给 1.0) + score_text 严格度 (Python strict substring vs Kotlin NFKC+fuzzy)。这两个改完, cloud @100 应该能从 0.7015 涨到 ~0.78-0.80, 跟 Kotlin 0.819 差距缩小到 ~0.02-0.04。

**结构性差距**: 缺 compare_text 工具 + 768 thumb OCR 输入, 这两个改完能再涨 0.02-0.04。
