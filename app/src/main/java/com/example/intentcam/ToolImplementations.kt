package com.example.intentcam

import org.json.JSONArray
import org.json.JSONObject

/**
 * Register the default tool set the orchestrator uses.  Most tools
 * share a generic body — every "interpret_image" variant just tells
 * the model "in round 2 look at the picture and answer this X" — so
 * the bodies collapse into one factory; only [navigate_to_block]
 * keeps a custom body because it can return `needsUserInput = true`.
 *
 * Descriptions stay tool-specific: they're how we steer the model
 * toward the right category at temp=0.  See README "Prompt design"
 * for why we keep the descriptions verbose despite the bodies being
 * identical.
 */
fun ToolRegistry.registerDefaultTools() {

    /**
     * Shared body for tools whose only job is to confirm to the model
     * what task it's doing.  The model already has the captured image
     * in round 2's message history; it just needs the category hint to
     * phrase the answer.  This is intentionally trivial — every
     * interesting decision lives in the model's vision + reasoning, not
     * in on-device code.
     */
    fun interpretBody(toolName: String): ToolBody = { _, _ ->
        ToolResult(
            toolSummary = "用户想了解 $toolName，请在 round 2 直接给出答案。" +
                "看完图像后调 emit_bubble 把结构化字段（scene/intent/type/confidence）填好。",
            finalBubble = false,
        )
    }

    // ── 1. default_describe ───────────────────────────────────────
    // Fallback when no specialized tool fits.  Rarely picked.
    register(
        ToolDef(
            name = "default_describe",
            description = "无法确定用户意图时回退到这里。看完画面后直接调 emit_bubble 给出一段中文 JSON 摘要。",
            inputSchema = schemaEmpty(),
            body = interpretBody("default_describe"),
        )
    )

    // ── 2. identify_animal_or_plant ───────────────────────────────
    register(
        ToolDef(
            name = "identify_animal_or_plant",
            description = "画面主体是一只动物或一株植物（宠物、花卉、树木、昆虫、鸟、鱼）。" +
                "用户想识别种类、了解习性或养护方法时调这个。",
            inputSchema = schemaWithString(
                "species_hint", "用户可能想知道的子问题（品种/毒性/养护频率/花期）"
            ),
            body = interpretBody("identify_animal_or_plant"),
        )
    )

    // ── 3. identify_product ──────────────────────────────────────
    register(
        ToolDef(
            name = "identify_product",
            description = "画面是带品牌名/包装设计/价格标签的完整商品（整瓶、整盒、整袋食品饮料或日用品）。" +
                "用户想知道这是什么品牌、查配料、查保质期、查价格、找购买链接或对比同类商品时调这个。" +
                "关键判别：画面必须是真正的『商品』（带品牌标识、瓶身/盒装设计、产品名清晰），而不是纯文字表格/面板。" +
                "如果是纯英文 Nutrition Facts 表、英文说明书、英文合同、外文菜单等『文字内容为主』的画面，不要调这个 — 调 translate_text（要翻译）或 read_manual（要读懂）。",
            inputSchema = schemaWithEnum(
                "focus",
                "用户最关心的子问题",
                listOf("ingredients", "expiry", "price", "link", "compare"),
            ),
            body = interpretBody("identify_product"),
        )
    )

    // ── 4. navigate_to_block ─────────────────────────────────────
    // Needs user-supplied destination when `mode` = directions.
    register(
        ToolDef(
            name = "navigate_to_block",
            description = "画面是路牌、地图、街道场景，想知道『我在哪』『怎么去某地』或附近有什么时调这个。" +
                "如果用户没说要导航去哪，mode 选 where_am_i；如要导航但目的地未知，destination 留空，工具会要求用户补充。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("destination", JSONObject().apply {
                        put("type", "string")
                        put("description", "目的地（街道名、地标、地址），用户没说则留空")
                    })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("where_am_i").put("directions").put("nearby"))
                        put("description", "查询类型")
                    })
                })
                put("additionalProperties", false)
            },
            body = { _, input ->
                val mode = input.optString("mode", "where_am_i")
                val dest = input.optString("destination", "").trim()
                if (mode == "directions" && dest.isBlank()) {
                    ToolResult(
                        toolSummary = "需要用户提供目的地",
                        needsUserInput = true,
                        userInputPrompt = "你想导航去哪里？",
                    )
                } else {
                    ToolResult(
                        toolSummary = if (dest.isBlank()) {
                            "用户在问『我在哪』"
                        } else {
                            "用户想导航去 $dest"
                        },
                        finalBubble = false,
                    )
                }
            },
        )
    )

    // ── 4b. ask_user ─────────────────────────────────────────────
    // Generic "ask the user" tool.  Any tool body (or the model itself
    // when it can't pick a clearer path) can call this to surface a
    // free-form question.  The orchestrator routes the typed reply
    // back via the same `userText` channel as navigate_to_block.
    register(
        ToolDef(
            name = "ask_user",
            description = "画面里有多种可能走向、用户意图不明确、或者你需要某个具体子问题才能继续时调这个。" +
                "question 字段是给用户看的问题（中文，一句话，≤30字）。" +
                "调完这个工具会弹出输入框；用户输入后会作为 userText 回到下一轮，" +
                "那时再决定调哪个具体工具或直接 emit_bubble。" +
                "**不要**用纯文本问问题 — 必须用 tool_use 让 UI 弹真正的输入框。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "给用户看的问题（中文，一句话）")
                    })
                })
                put("required", JSONArray().put("question"))
                put("additionalProperties", false)
            },
            body = { _, input ->
                val question = input.optString("question", "").ifBlank { "请补充信息" }
                ToolResult(
                    toolSummary = "等待用户回答：$question",
                    needsUserInput = true,
                    userInputPrompt = question,
                )
            },
        )
    )

    // ── 4c. zoom_in ──────────────────────────────────────────────
    // Detail-on-demand.  When the model needs more pixels for a
    // specific region (e.g. "the SC license number in the top-right
    // is too small to read"), it calls this with a normalized rect.
    //
    // Two modes:
    //  - source = "last" (default): crop the most recent image —
    //    either the original full-res (round 1) or the previous
    //    zoom_in's crop.  Coords are RELATIVE to that.  Calling
    //    zoom_in twice with default mode gives a chained crop of a
    //    crop (iterative zoom-in).
    //  - source = "original": crop the original full-res photo.
    //    Coords are ABSOLUTE.  Use this when the model wants
    //    sibling views of different parts of the original in the
    //    same round.
    //
    // Multi-zoom in one round is supported: every zoom_in call
    // returns its own followUpJpeg, and the orchestrator attaches
    // all of them to the next user message.  The body's chain state
    // advances with each call, so within a single round the
    //    (n+1)-th zoom_in crops the (n)-th call's output.
    register(
        ToolDef(
            name = "zoom_in",
            description = "画面里某个区域你看不清/需要更细的细节时调这个。" +
                "用归一化坐标 (x, y, w, h) ∈ [0, 1] 给出区域，x/y 是左上角，w/h 是宽高。" +
                "右上角四分之一 = {x: 0.5, y: 0, w: 0.5, h: 0.5}。" +
                "**source 字段**：默认 \"last\"（裁上一张你看到的图 — 坐标是相对的，支持链式放大）；" +
                "选 \"original\" 时裁原始照片（坐标是绝对的，看原图的不同区域 — sibling 模式）。" +
                "focus 字段是你想在那个区域找什么（一句话，便于在工具结果里复读）。" +
                "**一次 round 可以调多次**：每个调用独立产出一个放大图，下一轮 user message 会把它们都附上，按调用顺序排列。" +
                "**链式放大**：连续调 zoom_in（用默认 source），第二次会裁第一次的结果，可以无限迭代。" +
                "**不要**用纯文本说『让我再看看』 — 必须用 tool_use 触发真正的裁剪。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                        put("description", "左边缘归一化坐标 [0, 1]（相对于 source 指定的图）")
                    })
                    put("y", JSONObject().apply {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                        put("description", "上边缘归一化坐标 [0, 1]")
                    })
                    put("w", JSONObject().apply {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                        put("description", "宽度归一化坐标 [0, 1]")
                    })
                    put("h", JSONObject().apply {
                        put("type", "number")
                        put("minimum", 0)
                        put("maximum", 1)
                        put("description", "高度归一化坐标 [0, 1]")
                    })
                    put("source", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("last").put("original"))
                        put("description", "裁哪张图。last=上一张你看到的（链式，坐标相对），original=原图（sibling，坐标绝对）")
                    })
                    put("focus", JSONObject().apply {
                        put("type", "string")
                        put("description", "这个区域里你想找什么（一句话）")
                    })
                })
                put("required", JSONArray().put("x").put("y").put("w").put("h"))
                put("additionalProperties", false)
            },
            body = { ctx, input ->
                val x = input.optDouble("x", 0.0).toFloat()
                val y = input.optDouble("y", 0.0).toFloat()
                val w = input.optDouble("w", 0.0).toFloat().coerceAtLeast(0.05f)
                val h = input.optDouble("h", 0.0).toFloat().coerceAtLeast(0.05f)
                val focus = input.optString("focus", "").ifBlank { "细节" }
                val source = input.optString("source", "last")
                val sourceJpeg = if (source == "original") ctx.originalFullRes else ctx.jpeg
                val crop = cropJpegRegion(sourceJpeg, x, y, w, h)
                if (crop == null) {
                    ToolResult(
                        toolSummary = "zoom_in 失败：无法裁剪 source=$source (${x}, ${y}, ${w}, ${h})",
                        finalBubble = false,
                    )
                } else {
                    ToolResult(
                        toolSummary = "已放大区域 (${x}, ${y}, ${w}, ${h}) source=$source，" +
                            "focus=$focus，请用更高分辨率查看这部分并继续回答。",
                        finalBubble = false,
                        followUpJpeg = crop,
                    )
                }
            },
        )
    )

    // ── 5. scan_qr_code ──────────────────────────────────────────
    // LLM reads QR from the image directly in round 2.
    register(
        ToolDef(
            name = "scan_qr_code",
            description = "画面里有二维码（QR），想解码（链接、Wi-Fi、联系信息、加好友等）时调这个。" +
                "模型在 round 2 直接从图片里读 QR 内容即可，不需要本地扫码。",
            inputSchema = schemaEmpty(),
            body = interpretBody("scan_qr_code"),
        )
    )

    // ── 6. translate_text ────────────────────────────────────────
    register(
        ToolDef(
            name = "translate_text",
            description = "画面主体是外语文字内容（英文/日文等），用户想把外文翻译成中文时调这个。" +
                "适用：纯英文 Nutrition Facts 表、英文 UI 文字、外文菜单、英文歌词/字幕、外文海报标题等以『文字内容为主』的画面。" +
                "不适用：如果是带品牌名/包装设计的完整商品（食品、饮料、日用品包装），调 identify_product。" +
                "不适用：如果是说明书/合同/菜谱等多栏文档，调 read_manual。" +
                "不适用：如果是医疗/健康设备读数（血压、血糖、体温），调 read_device_reading。",
            inputSchema = schemaWithString("target_lang", "目标语言，默认中文"),
            body = interpretBody("translate_text"),
        )
    )

    // ── 7. solve_problem ─────────────────────────────────────────
    register(
        ToolDef(
            name = "solve_problem",
            description = "画面里有数学题、方程式或逻辑题，用户要答案或过程时调这个。" +
                "operation 选 solve 表示要答案；verify 验证答案；factor 因式分解；show_work 展示步骤。",
            inputSchema = schemaWithEnum(
                "operation",
                "求解类型",
                listOf("solve", "verify", "factor", "show_work"),
            ),
            body = interpretBody("solve_problem"),
        )
    )

    // ── 8. read_screen ───────────────────────────────────────────
    register(
        ToolDef(
            name = "read_screen",
            description = "画面是手机/电脑屏幕截图（通知中心、控制中心、App 页面），用户想知道显示了什么意思时调这个。",
            inputSchema = schemaEmpty(),
            body = interpretBody("read_screen"),
        )
    )

    // ── 9. read_manual ───────────────────────────────────────────
    register(
        ToolDef(
            name = "read_manual",
            description = "画面是说明书、操作步骤文档、菜谱、合同、药品用法用量等多栏/段落型文档，用户想读懂内容时调这个。" +
                "关键判别：内容以段落/编号步骤呈现（『Step 1: ...』『Warning: ...』），不是单个数值读数。" +
                "不适用：如果是单个数值（如血压 128/82），那是 read_device_reading。" +
                "不适用：如果是处方药标签（不是设备，是药品包装），选这个 read_manual。" +
                "不适用：如果是纯英文 UI/菜单/歌词等单层文字，那是 translate_text。",
            inputSchema = schemaEmpty(),
            body = interpretBody("read_manual"),
        )
    )

    // ── 10. read_device_reading ──────────────────────────────────
    register(
        ToolDef(
            name = "read_device_reading",
            description = "画面是医疗/健康设备显示屏的单个数值读数（血压计 128/82、体重秤 73.4kg、体温计 36.8°C、血糖仪、BMI 显示器等）。" +
                "画面特征：一个大数字 + 单位 + 时间戳。关键判别：用户问的是『这个数值是什么意思』『是否正常』。" +
                "不适用：如果是说明书/操作步骤文档（『Step 1: ...』），那是 read_manual。" +
                "不适用：如果是处方药标签（不是设备，是药品包装），调 read_manual。",
            inputSchema = schemaEmpty(),
            body = interpretBody("read_device_reading"),
        )
    )

    // ── 11. emit_bubble ──────────────────────────────────────────
    // The "final answer" tool.  Round 2 must call this with structured
    // bubble fields; the orchestrator uses them directly to build the
    // Bubble shown in the UI.  Pushes structured-output generation to
    // the LLM — no local JSON parsing.
    register(
        ToolDef(
            name = "emit_bubble",
            description = "最终把用户意图的摘要填到这里 —— 必须调这个工具来结束识别流程。" +
                "scene: 画面看到了什么（一句话）; intent: 用户最可能的意图（动宾短语 ≤12 字）;" +
                "type: info / location / solve; confidence: 0.0~1.0。" +
                "可选 action_chips: 0-3 个 follow-up 操作建议，会作为可点 chip 出现在详情页。" +
                "每个 chip = {label(≤8字), tool(已在 tools[] 里), tool_input(传给该 tool 的 JSON)}。" +
                "chip tool 必须从 tools[] 里已有的工具里选，常见的：identify_product/translate_text/" +
                "solve_problem/read_manual/read_device_reading/scan_qr_code/navigate_to_block/ask_user。" +
                "**不要**用纯文本回答，必须用 tool_use。",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("scene", JSONObject().apply {
                        put("type", "string")
                        put("description", "画面看到了什么，一句话")
                    })
                    put("intent", JSONObject().apply {
                        put("type", "string")
                        put("description", "用户最可能的意图，动宾短语 ≤12 字")
                    })
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("info").put("location").put("solve"))
                        put("description", "意图类别")
                    })
                    put("confidence", JSONObject().apply {
                        put("type", "number")
                        put("description", "置信度 0.0~1.0")
                    })
                    put("action_chips", JSONObject().apply {
                        put("type", "array")
                        put("description", "0-3 个 follow-up 操作建议（用户可点的 chip）")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("label", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "按钮文字，≤8 字")
                                })
                                put("tool", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "要调的工具名（必须在 tools[] 里）")
                                })
                                put("tool_input", JSONObject().apply {
                                    put("type", "object")
                                    put("description", "传给该 tool 的 input JSON")
                                })
                            })
                            put("required", JSONArray().put("label").put("tool"))
                            put("additionalProperties", false)
                        })
                        put("maxItems", 3)
                    })
                })
                put("required", JSONArray().put("scene").put("intent").put("type").put("confidence"))
                put("additionalProperties", false)
            },
            body = { _, input ->
                val chips = mutableListOf<ActionChip>()
                val chipArr = input.optJSONArray("action_chips")
                if (chipArr != null) {
                    for (i in 0 until chipArr.length()) {
                        val chipObj = chipArr.optJSONObject(i) ?: continue
                        val label = chipObj.optString("label", "").take(20)
                        val tool = chipObj.optString("tool", "")
                        if (label.isBlank() || tool.isBlank()) continue
                        val toolInput = chipObj.optJSONObject("tool_input") ?: JSONObject()
                        chips.add(
                            ActionChip(
                                label = label,
                                toolName = tool,
                                toolInputJson = toolInput.toString(),
                            )
                        )
                    }
                }
                ToolResult(
                    toolSummary = "emit_bubble",
                    finalBubble = true,
                    type = input.optString("type", "info").ifBlank { "info" },
                    title = input.optString("intent", "").ifBlank { "未识别" },
                    detail = input.optString("scene", ""),
                    confidence = input.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f),
                    chips = chips,
                )
            },
        )
    )
}

// ── Schema helpers ────────────────────────────────────────────────
//
// JSON schema for an object that takes no input fields.
private fun schemaEmpty(): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject())
    put("additionalProperties", false)
}

// JSON schema for `{ name: string, ... }`.
private fun schemaWithString(name: String, description: String): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put(name, JSONObject().apply {
                put("type", "string")
                put("description", description)
            })
        })
        put("additionalProperties", false)
    }

// JSON schema for `{ name: <enum>, ... }`.
private fun schemaWithEnum(
    name: String,
    description: String,
    values: List<String>,
): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply {
        put(name, JSONObject().apply {
            put("type", "string")
            put("enum", JSONArray().apply { values.forEach { put(it) } })
            put("description", description)
        })
    })
    put("additionalProperties", false)
}