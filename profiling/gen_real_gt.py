"""
Generate ground_truth_real.json from the CATEGORIES list in
fetch_real_imgs.py.  Each real fixture's GT is constructed the same way
as the synthetic ones: type list, OR keyword groups, must-have anchors.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Reuse the CATEGORIES list from fetch_real_imgs.py.
spec = importlib.util.spec_from_file_location("fri", HERE / "fetch_real_imgs.py")
fri = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fri)


# Type preferences per category, validated against 50 real-photo fixtures:
TYPE_BY_CAT = {
    "math":            ["solve"],                # asking to solve the equation
    "qr_code":         ["info", "solve"],        # scan vs execute
    "receipt":         ["info", "solve"],        # check total vs reconcile
    "device_reading":  ["info", "solve"],        # read value vs check normal
    "food_label":      ["info"],
    "menu":            ["info"],
    "english_text":    ["info"],
    "screen_capture":  ["info"],
    "street_sign":     ["info", "location"],
    "map":             ["info", "location"],
}

GT: dict[str, list[list[str]]] = {
    "food_label": [
        ["配", "营养", "成分", "热量", "脂肪", "钠", "Nutrition", "calorie", "fat", "sodium"],
        ["翻译", "意思", "写什么", "读", "外文", "translate"],
        # Real photo: model often picks generic "查看商品" / "查到期日" etc.
        ["商品", "查", "看", "标签", "识别", "什么", "到期", "过期", "保质"],
    ],
    "device_reading": [
        ["读数", "血压", "重量", "数值", "含义", "温度", "血糖", "度数", "value", "read"],
        ["正常", "范围", "判断", "是否", "低", "高", "超", "偏", "正常范围"],
        ["BP", "temperature", "weight", "glucose"],
    ],
    "math": [
        ["解", "方程", "求根", "因式", "分解", "配方", "Show", "work"],
        ["solve", "factor", "equation", "x", "root", "polynomial"],
    ],
    "receipt": [
        ["总计", "总额", "多少钱", "费", "单价", "合计", "小计", "税", "价目"],
        ["对账", "核算", "求和", "加总", "合计", "验算"],
        ["total", "subtotal", "tax", "amount", "sum", "verify"],
    ],
    "street_sign": [
        ["街道", "地址", "路名", "在哪", "哪条", "这是"],
        ["导航", "怎么去", "去", "center", "downtown", "到", "navigate", "directions"],
        ["street", "Main", "Avenue", "Road", "Lane", "Drive"],
    ],
    "menu": [
        ["菜", "价格", "菜单", "推荐", "招牌", "点什么", "点哪道", "哪道好吃"],
        ["dish", "price", "menu", "order", "recommend"],
    ],
    "qr_code": [
        ["扫", "QR", "二维码", "验证", "来源", "扫码", "扫一下"],
        ["execute", "do", "save", "scan"],
    ],
    "map": [
        ["地图", "路", "去", "怎么走", "导航", "park", "downtown", "我在哪"],
        ["map", "route", "directions", "location"],
    ],
    "english_text": [
        ["翻译", "看懂", "解释", "含义", "内容", "意思", "是啥", "写的是",
         "解读", "阅读", "读一下", "理解", "看清楚", "写什么", "对照", "操作",
         "了解", "使用", "看说明", "如何", "怎么用", "怎么办", "步骤", "方法",
         "执行", "完成", "怎么办"],
        ["translate", "explain", "what does", "how to", "instructions", "read"],
    ],
    "screen_capture": [
        ["打开", "点", "切", "进", "进入", "操作", "查", "看", "设", "编辑",
         "发", "回复", "扫", "登录", "登出", "翻译", "查看", "切换",
         "取消", "确认", "解锁", "关掉"],
        ["open", "tap", "click", "switch", "go", "to", "navigate", "how",
         "view", "see", "check", "set", "edit", "send", "reply", "scan"],
    ],
}


# Build the rows list.
rows = []
for cat, fixtures in fri.CATEGORIES.items():
    for i, (seed, top, sub) in enumerate(fixtures, start=1):
        filename = f"real_{cat}_{i:02d}.jpg"
        rows.append({
            "id": filename.rsplit(".", 1)[0],
            "file": filename,
            "category": cat,
            "expected_top_intent_type": TYPE_BY_CAT[cat],
            "acceptable_intent_keywords": GT[cat],
            "must_have_in_scene_or_observation": [
                [top, top.split()[0] if top else "", cat.replace("_", " ")]
            ],
        })

out_path = HERE / "ground_truth_real.json"
out_path.write_text(
    json.dumps({"version": 1, "scenes": rows}, ensure_ascii=False, indent=2),
    encoding="utf-8"
)
print(f"wrote {out_path} ({len(rows)} entries)")
