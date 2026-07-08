# 端云 OCR 评测报告 — eval_rctw_v2.py @100

**日期**: 2026-07-09
**目标 fixture**: `profiling/ground_truth_rctw.json` 全 100 支 (default 类别)
**命令**:
```bash
python3 -u profiling/eval_rctw_v2.py --resize 768 --quality 80 --limit 0 \
  --out profiling/eval_rctw_v2_{cloud,stub}_100.json
```

---

## 1. 改动总览

`profiling/eval_rctw_v2.py` 镜像 Kotlin 端云协同识别 (`TOOL_USE_SYSTEM` @ `LlmClient.kt:415-480`):

| 维度 | 旧 | 新 |
|---|---|---|
| SYSTEM_PROMPT 叙事 | "read_text 默认不要用, OCR 不可靠" | "OCR 是第一意见, round-1 hint 是 verbatim 字符基准" |
| read_text 描述 | 默认兜底, 不要调 | 局部 OCR 重扫, 仅 [LOW] / 漏识时调 |
| round 1 图片 | 1 全图 + 4 四象限裁剪 | 1 全图 + OCR hint 文本块 (top-30 行, conf 降序, [LOW] 标 conf<0.5) |
| emit_bubble details schema | `{kind, label, value}` | 同上 + 可选 `bbox?` 字段 (占位, 暂不参与评分) |
| Telemetry | composite / r1 / r2_text / r2_type | + `picked_tool_r1` / `read_text_calls` / `zoom_in_calls` / `emit_bubble_emitted` / `r1_hint_blocks` / `r1_hint_low_count` |
| `run_orchestrator()` 返回 | `(final_resp, follow_ups)` | `(final_resp, follow_ups, tool_inventory)` |

新增模块级函数:
- `format_hint(blocks, max_lines=30, low_threshold=0.5)` — Python 翻译 `OcrResult.formatHint()`
- `build_round1_ocr_hint(jpeg_bytes)` — 包装 `ocr_hw.recognize()`, env 缺失时返回 placeholder

`profiling/ocr_huaweicloud.py` 本轮无改动 (上一轮已修过 bbox shape bug + 加 SDK 缺失降级)。

---

## 2. 核心跑分 (@100, --resize 768 --quality 80, temp=0)

### 2.1 主表

| 模式 | composite | Δ vs Kotlin 1-only @100 (0.819) | Δ vs Kotlin endcloud @20 (0.835) |
|---|---|---|---|
| **cloud** (华为云 OCR 启用) | **0.7015** | -0.118 | -0.134 |
| **stub** (env 缺失走降级)    | **0.6685** | -0.151 | -0.167 |
| **Δ (cloud - stub)**         | **+0.0330** | — | — |

47 fixture cloud 胜 / 15 cloud 负 / 38 平。
最大赢家 +0.292 (rctw_default_15), 最大输家 -0.250 (rctw_default_48)。

### 2.2 组件拆解

| metric | cloud | stub | Δ | 说明 |
|---|---|---|---|---|
| r1 (round-1 工具选) | 0.855 | 0.794 | **+0.061** | 涨 — emit_bubble 直出比例高 |
| r2_text (emit_bubble 文本匹配) | 0.256 | 0.176 | **+0.080** | 涨 — 模型把 OCR hint 复制进 content |
| r2_type (emit_bubble type=info) | 0.840 | 0.910 | **-0.070** | 略跌 — content 变长后 type 推理受影响 |

### 2.3 vs 旧 @20 跑 (同一 eval, 旧 prompt 阶段)

| 模式 | @20 旧 | @100 新 | 跨期提升 |
|---|---|---|---|
| cloud | 0.690 | 0.7015 | +0.012 |
| stub | 0.668 | 0.6685 | +0.000 |
| **Δ (cloud - stub)** | +0.022 (噪声内) | **+0.033** (真实信号) | +0.011 |

跨过 @20 的噪声门槛 (Δ +0.022), 涨到真实信号 (Δ +0.033)。

### 2.4 跟 Kotlin 端云协同基线对比 (生产对照)

| metric | Python cloud @100 (我们) | Kotlin endcloud @20 (生产) | 差距 |
|---|---|---|---|
| composite | 0.7015 | **0.835** | -0.134 |
| r1 | 0.855 | 0.953 | -0.098 |
| r2_text | 0.256 | 0.717 | **-0.461** |
| r2_type | 0.840 | 0.875 | -0.035 |
| 工具数 | 3 | 4 (含 compare_text) | -1 |
| OCR hint 注入 | ✅ | ✅ | 0 |
| 缩略图分辨率 | 768px | 捕获时 (通常 ≤768) | ~0 |

**r2_text 是主战场** (0.256 vs 0.717, 差 0.461); r2_type 接近 (0.840 vs 0.875); r1 也接近 (0.855 vs 0.953)。

---

## 3. 工具调用 telemetry

| metric | cloud | stub |
|---|---|---|
| read_text 调用总数 | 7 | 12 |
| zoom_in 调用总数 | 240 | 370 |
| emit_bubble 触发 (多 round) | 65/100 | 35/100 |
| round-1 picked=zoom_in | 67 | 92 |
| round-1 picked=emit_bubble | 31 | 2 |
| round-1 picked=read_text | **2** | **6** |
| round-1 hint blocks 总数 | 488 | 0 (降级) |
| round-1 [LOW] hint 行 | 186 (38%) | — |

要点:
- **read_text 真的开始被调了** (cloud 7/100, stub 12/100, vs 旧 0/20)
- **stub 反而 read_text 调得多** — 没 OCR hint 时模型更想验证; 拿到真 hint 后直接读, 不再调 read_text
- **38% hint 行是 [LOW]** — 768px 缩略图 OCR 解析合成书法吃力
- **emit_bubble 触发率 cloud 65% vs stub 35%** — 有 hint 后, 模型更愿意直接 emit (说明它信任 hint)

---

## 4. per-fixture 极端差

### 4.1 Cloud 涨幅最大 (top 10)

| fixture | cloud | stub | Δ |
|---|---|---|---|
| rctw_default_15 | 0.825 | 0.532 | +0.292 |
| rctw_default_44 | 0.645 | 0.395 | +0.250 |
| rctw_default_33 | 0.875 | 0.645 | +0.230 |
| rctw_default_18 | 0.863 | 0.645 | +0.218 |
| rctw_default_90 | 0.875 | 0.676 | +0.199 |
| rctw_default_12 | 0.838 | 0.645 | +0.193 |
| rctw_default_31 | 0.645 | 0.457 | +0.188 |
| rctw_default_71 | 0.812 | 0.645 | +0.167 |
| rctw_default_95 | 0.875 | 0.708 | +0.167 |
| rctw_default_17 | 0.800 | 0.645 | +0.155 |

### 4.2 Cloud 跌幅最大 (top 5)

| fixture | cloud | stub | Δ |
|---|---|---|---|
| rctw_default_48 | 0.457 | 0.708 | -0.250 |
| rctw_default_68 | 0.520 | 0.739 | -0.219 |
| rctw_default_56 | 0.500 | 0.645 | -0.145 |
| rctw_default_11 | 0.625 | 0.770 | -0.145 |
| rctw_default_08 | 0.625 | 0.770 | -0.145 |

跌幅来源推测: OCR hint 给出 calligraphic [LOW] 字符, 模型按 prompt "不 verbatim 复制 [LOW]" drop 掉, 而 stub 没 hint 时模型自己读图勉强能拼出; 失分集中在 r2_text。

---

## 5. 关键判断

1. **强措位 prompt 奏效, 但不充分** — 旧 @20 里 read_text 0/20; 新 @100 里 cloud 7/100。真实涨幅在 r2_text +0.080, 不在工具调用次数。
2. **LLM 仍偏 zoom_in** (67% round-1 picked=zoom_in) — "OCR 第一意见" 表述没压过"自己看图"的本能。这是结构性瓶颈, prompt 改得更狠也未必能完全翻。
3. **r2_text 涨不上去的另一原因** — 488 个 hint 行里 38% 是 [LOW]; 模型看到 [LOW] 行按 prompt 要求"不 verbatim 复制", 所以即使有 hint 也不直接拼进 content。这是设计上防止 OCR 噪声, 代价是涨分慢。
4. **stub 跑也"涨"了** (0.668 → 0.6685) — 说明新 prompt 自身不依赖 OCR 也能小幅改善; 老的 "默认不要用 read_text" 表述本身在压分。
5. **r2_type 跌 0.070** — 同一 fixture 在 cloud 模式下更可能 emit_bubble.type 写成非 "info" (拼了更多 content 后, type 推理受影响)。需要单独看 fixture 级别排查。
6. **跟 Kotlin 0.835 的差距 0.134** 主要是 r2_text (-0.461); 我们环境没 compare_text 工具, 也没像 Kotlin 那样 round 1 自动注入 OCR hint 时多轮 drill-down; 这两项是结构性差距。

---

## 6. 落点文件

```
/home/oppry/work/app3/profiling/eval_rctw_v2.py                          (改)
/home/oppry/work/app3/profiling/eval_rctw_v2_cloud_100.json              (新)
/home/oppry/work/app3/profiling/eval_rctw_v2_cloud_100.log               (新)
/home/oppry/work/app3/profiling/eval_rctw_v2_stub_100.json               (新)
/home/oppry/work/app3/profiling/eval_rctw_v2_stub_100.log                (新)
/home/oppry/work/app3/profiling/ocr_huaweicloud.py                       (本轮无改)
/home/oppry/.claude/plans/tender-hatching-lantern.md                     (设计文档)
/home/oppry/.claude/projects/-home-oppry-work-app3/memory/eval-baseline-2026-07-09b.md  (memory)
```

---

## 7. 下一步建议 (按性价比排)

1. **OCR 跑原图非缩略图** (最大可能立刻涨) — 改 `build_round1_ocr_hint(original_jpeg)` 替代 `_hint_thumb`; [LOW] 比例从 38% 应能降到 10-15%, 模型对 hint 信任度上去, r2_text 立即可涨。
2. **强制 round-1 read_text** — 改 prompt "MUST call read_text before emit_bubble"。风险: 模型过早放弃思考。需要实验找平衡点。
3. **加 compare_text 工具** — 镜像端云 4 工具架构, 多 1 轮 round-trip 但能消解 OCR-vs-LLM 冲突。
4. **改 score 加 bbox 匹配** — `score_text` 现在不看 `details[].bbox`, 加一层 OCR 衍生的细节行评分; r2_text 应能再涨。
5. **Kotlin 端 1-only @20 复测** — 用同 fixture 同 prompt 看是否能复制 0.835; 若不能, 差距在 score 函数 (Kotlin 的 `EvalRunner.kt` 评分更复杂)。

---

## 8. 一句话总结

新 prompt + round-1 OCR hint 注入让 cloud @100 跑到 **0.7015** (vs stub 0.6685, **Δ +0.033** — 真实信号), 但 LLM 仍偏 zoom_in; 跟 Kotlin 端云协同 0.835 还差 0.134, 主要在 r2_text (0.256 vs 0.717)。最大杠杆: OCR 跑原图而非缩略图, 切 [LOW] 比例。
