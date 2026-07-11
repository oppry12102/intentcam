#!/usr/bin/env python3
"""
Re-scan intent_all.json to discover every distinct intent class present in RCTW-171.

V2 (after first pass): tightened clusters, dropped overly broad ones (brand_latin),
added a few missing classes (traffic_sign, parking, medicine, lottery), then ranked
by hit count to produce a TOP-20 expansion shortlist for IntentDecl.
"""
from __future__ import annotations
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INFILE = ROOT / "intent_all.json"
OUTFILE = ROOT / "intent_clusters.json"


# Cluster definitions, organized by user-action potential.
# Each rule is (regex, label); the script joins rule-by-rule with "|" to combine
# across rules in one cluster while preserving each rule's own alternation.
CLUSTERS: dict[str, list[tuple[str, str]]] = {
    # ---------- existing baseline intents (sanity check) ----------
    "location": [
        (r"路|街|道|巷|弄|号|栋|楼|层|室|店|商厦|广场|中心|镇|园|区|村|大厦|大楼|花园|城", "addr"),
    ],
    "solve": [
        (r"\b解\b|方程|公式|\b求\b|答案|计算|证明|\b题\b|=\s*\d|÷|×|∫|∑", "math"),
    ],

    # ---------- contact / reachability ----------
    "phone": [
        (r"1[3-9]\d{9}", "cell"),
        (r"\d{3,4}-\d{7,8}", "landline"),
        (r"电话:|Tel:|手机:|热线:|订购电话|咨询电话", "kw"),
    ],
    "instant_message": [
        (r"微信|加微|vx:|V信|公众号|qq\.|QQ:|QQ号", "wx"),
        (r"\b[1-9]\d{4,10}\b.*?(?:QQ|qq|加我|联系)", "qqnum"),
    ],
    "url": [
        (r"https?://|www\.\w+\.\w+|[a-z0-9-]+\.(com|cn|net|org|io)", "url"),
        (r"@[a-z0-9-]+\.[a-z]{2,}|@.*?\.com|邮箱:|E-?mail", "email"),
    ],
    "payment_qr": [
        (r"扫一扫|扫码支付|支付宝|微信支付|收款码|付款码", "pay"),
    ],

    # ---------- commerce / sales ----------
    "shopping_promo": [
        (r"特价|促销|优惠|打折|满减|秒杀|亏本|清仓|甩卖|转让|红包|抵用券|代金券|减|送|限时|抢购|直降", "promo"),
    ],
    "price": [
        (r"￥\s*\d+|\d+\s*元|\d+\.\d+\s*元|¥\s*\d+|\d+\s*块|\$\s*\d+", "currency"),
        (r"单价|售价|原价|现价|折后价|会员价|总价|特惠价", "kw"),
    ],
    "shopping_product": [
        (r"批发|零售|厂家直销|一件代发|包邮|代购|微商|同款|新款|爆款|工厂|全新", "ecom"),
    ],
    "coupon_voucher": [
        (r"优惠券|代金券|抵扣券|兑换码|激活码|核销码|提货券", "voucher"),
    ],

    # ---------- real estate / classifieds ----------
    "real_estate_rental": [
        (r"出售|出租|求租|求购|二手房|楼盘|物业|户型|平米|中介|房东|合租|房源|月租|押一付三|拎包入住|南北通透|精装修|简装|毛坯|小区", "estate"),
    ],
    "recruit_hiring": [
        (r"招聘|诚聘|求职|高薪|待遇|包住|包吃|兼职|全职|月薪|诚招|急招|招工", "recruit"),
    ],
    "lost_found": [
        (r"寻人|寻物|寻狗|寻猫|失物招领|招领启事|找宠物", "lost"),
    ],

    # ---------- food / hospitality ----------
    "menu_food": [
        (r"菜单|菜品|主菜|副食|小炒|套餐|套餐价|面食|汤类|主厨|招牌菜|特色菜|配料|主料", "menu"),
        (r"火锅|烧烤|麻辣|串串|烤肉|烤鱼|小龙虾|披萨|汉堡|寿司|拉面|自助|海鲜", "dish"),
    ],
    "ingredients_recipe": [
        (r"配料表|成分表|配料:|成分:|原料:|含量:|营养成分|热量|蛋白质|脂肪|碳水|卡路里|做法|步骤|烹饪|食谱", "ingredients"),
    ],

    # ---------- transport / logistics ----------
    "transit": [
        (r"CRH|和谐号|动车|高铁|取票|检票|候车厅|出发|到达|站台|车次", "train"),
        (r"航班|登机|候机|起飞|降落|值机|托运|行李|航站楼|登机口|机票", "flight"),
        (r"公交站|地铁站|换乘|\d+号线|末班车|首班车|公交线路", "transit"),
    ],
    "logistics": [
        (r"快递|物流|顺丰|圆通|中通|申通|韵达|百世|京东物流|德邦|极兔|寄件|收件", "logistics"),
    ],
    "traffic_sign": [
        (r"限速|禁停|禁止停车|禁止鸣笛|禁止左转|禁止右转|单行线|人行横道|红绿灯|违章|罚款|扣分", "traffic"),
        (r"\d+\s*km/h|\d+公里|限行|单双号|ETC|高速|收费站", "road"),
    ],
    "parking": [
        (r"停车场|停车位|停车费|P\d+|车位|泊车", "parking"),
    ],
    "vehicle_plate": [
        (r"[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤川青藏琼宁使领][A-Z][A-Z0-9]{5,6}", "plate"),
    ],

    # ---------- service / institution ----------
    "service_institution": [
        (r"医院|诊所|药店|卫生所|急救中心|门诊|住院|挂号|病房", "medical"),
        (r"学校|中学|小学|幼儿园|大学|学院|附中|附小|分校|校区|培训中心|辅导", "edu"),
        (r"派出所|政府|工商|税务|法院|检察院|街道办事处|居委会|物业|管理局", "gov"),
        (r"银行|ATM|支行|分行|信用社|理财|贷款|存款|转账", "bank"),
    ],
    "warning_safety": [
        (r"请勿|禁止|警告|危险|注意|严禁|小心|安全|消防|防火|急救|避险", "warning"),
    ],
    "emergency_call": [
        (r"\b110\b|\b119\b|\b120\b|\b122\b|报警电话|火警|急救电话|匪警", "sos"),
    ],

    # ---------- time / schedule ----------
    "hours_schedule": [
        (r"营业时间|营业中|开店|开门|关门|AM|PM|:\d{2}\s*[-~]\s*\d{2}:\d{2}", "hours"),
    ],
    "date_time": [
        (r"\d{4}\s*年|\d+\s*月\d+\s*[日号]|周[一二三四五六日末]|周一|周二|周末|法定节假日|元旦|春节|清明|端午|中秋|国庆", "date"),
    ],

    # ---------- branding / advertising ----------
    "advertising_design": [
        (r"广告|传媒|策划|设计|VI|LOGO|标志设计|品牌设计|包装设计|广告设计|画册|宣传册", "ads"),
    ],
    "english_dominant": [
        # text where english words take a large share (5+ consecutive latin chars
        # in the middle of the string, not just acronyms).
        (r"[A-Za-z]{6,}\s+[A-Za-z]{6,}", "latin6"),
    ],

    # ---------- event / promotion occasion ----------
    "event_show": [
        (r"演唱会|演出|话剧|音乐会|舞台剧|开幕式|闭幕式|巡演|嘉宾|主持人|票务|大剧院|剧院|音乐厅", "event"),
        (r"\d+\.\d+\s*开票|演出时间|演出地点|票务信息|订票热线", "ticket"),
    ],
    "celebration_greeting": [
        (r"恭喜|祝|贺|春节|圣诞|中秋|元宵|端午|七夕|国庆|周年庆|开业大吉|店庆|恭贺|祝福", "festive"),
    ],

    # ---------- social / life occasion ----------
    "love_dating": [
        (r"相亲|征婚|交友|婚介|单身|寻找|缘分|约会|丘比特|恋爱", "dating"),
    ],
    "gaming_lottery": [
        (r"彩票|双色球|大乐透|体彩|福彩|刮刮乐|中奖|开奖|投注", "lottery"),
        (r"游戏代练|代肝|代抽|抽卡|原神|崩坏|王者|上分|排位|陪玩", "gaming"),
    ],

    # ---------- medical / drug ----------
    "medicine_drug": [
        (r"处方|非处方|用法用量|适应症|不良反应|禁忌|国药准字|批准文号|主治功能|功效", "med"),
    ],

    # ---------- legal / notice ----------
    "notice_announcement": [
        (r"公告|通知|通告|告示|启事|声明|告示牌|敬告|公示", "notice"),
    ],
    "legal_contract": [
        (r"合同|协议|条款|违约|甲方|乙方|签字|盖章|签订|生效", "legal"),
    ],

    # ---------- document / id ----------
    "id_document": [
        (r"\d{17}[\dXx]|\d{15}|身份证|营业执照|注册号|统一社会信用代码", "id"),
    ],
    "patent_id": [
        (r"ZL\s*\d+[\.\s\d]*\d|专利号|专利证书|发明专利|实用新型|外观设计|知识产权", "patent"),
    ],
    "ip_address": [
        # IPv4 dotted-quad — keep narrow
        (r"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}", "ipv4"),
    ],

    # ---------- signage / pure display ----------
    "direction_arrow": [
        (r"→|←|↑|↓|东|南|西|北|入口|出口|前方|左转|右转|直行", "dir"),
    ],
}


def main() -> int:
    data = json.loads(INFILE.read_text())
    items = data["items"]

    cluster_counts: Counter[str] = Counter()
    cluster_examples: dict[str, list[str]] = defaultdict(list)
    text_per_cluster: dict[str, set[str]] = defaultdict(set)
    intent_overlap: Counter[str] = Counter()

    auto_counts: Counter[str] = Counter(x["auto_intent"] for x in items)

    flat_rules: list[tuple[str, re.Pattern]] = []
    for cluster, rules in CLUSTERS.items():
        combined = "|".join(pattern for pattern, _ in rules)
        flat_rules.append((cluster, re.compile(combined)))

    for it in items:
        text = it.get("matched_text") or ""
        if not text:
            continue
        for cluster, rx in flat_rules:
            if rx.search(text):
                cluster_counts[cluster] += 1
                if len(cluster_examples[cluster]) < 4:
                    cluster_examples[cluster].append(text[:90])
                text_per_cluster[cluster].add(text)
                intent_overlap[f"{it['auto_intent']}+{cluster}"] += 1

    # ---- TOP-20 ranking ----
    # Score = (count) * log10(n+10) — gentle upweighting of rare intents so a 100-hit
    # niche isn't drowned by 6x more `location`. Count is still primary.
    import math
    scored = []
    for k, v in cluster_counts.items():
        score = v * math.log10(v + 10)
        scored.append((k, v, score))
    scored.sort(key=lambda r: r[2], reverse=True)

    top20 = scored[:20]

    result = {
        "summary": {
            "total_items": len(items),
            "auto_intent_counts": dict(auto_counts),
            "clusters_evaluated": len(CLUSTERS),
            "cluster_image_counts_desc": [{"cluster": k, "count": v} for k, v in cluster_counts.most_common()],
        },
        "ranking_top20": [
            {
                "rank": i + 1,
                "cluster": k,
                "image_count": v,
                "score": round(s, 2),
                "rationale": RATIONALE.get(k, ""),
            }
            for i, (k, v, s) in enumerate(top20)
        ],
        "examples_top5_per_cluster": {
            c: cluster_examples[c] for c, _, _ in top20
        },
        "intersection_with_auto_intent_top40": dict(intent_overlap.most_common(40)),
    }
    OUTFILE.write_text(json.dumps(result, ensure_ascii=False, indent=2))

    print(f"Total items: {len(items)}, Auto intents: {dict(auto_counts)}")
    print(f"\n=== TOP 20 candidate intents (by count × log10 score) ===\n")
    for i, (k, v, s) in enumerate(top20, 1):
        print(f"  {i:2d}. {k:28s} {v:5d} images  score={s:6.2f}")
    return 0


RATIONALE = {
    "location": "Street-level address, shop name with 店/号, recognizable district. Already wired → open_in_maps.",
    "phone": "Cellphone (1[3-9]xxxxxxxxx), landline (xxx-xxxxxxxx), or contact keyword. Action: open dialer.",
    "instant_message": "微信/QQ contact or QR. Action: open_wechat (privacy-aware).",
    "url": "Explicit web URL or .com/.cn/.net mention. Action: open_browser.",
    "payment_qr": "扫一扫/收款码/付款码/支付宝/微信支付. Action: notify pay (do not auto-trigger).",
    "shopping_promo": "促销/打折/满减/秒杀/亏本/清仓/甩卖. Action: copy_to_clipboard for sharing.",
    "price": "Explicit currency (￥/元/块/$). Action: copy price for shopping list.",
    "shopping_product": "批发/零售/代购/微商 e-commerce. Action: copy product details.",
    "coupon_voucher": "优惠券/代金券/兑换码. Action: extract and store voucher code.",
    "real_estate_rental": "二手房/出租/转让/楼盘. Action: copy listing details.",
    "recruit_hiring": "招聘/诚聘/招工/兼职. Action: copy job posting.",
    "lost_found": "寻人/寻物/失物招领. Action: copy contact info.",
    "menu_food": "菜品/火锅/烧烤/餐厅 or 套餐/招牌菜. Action: translate menu.",
    "ingredients_recipe": "配料表/成分表/营养含量 or 步骤/烹饪. Action: copy ingredients/recipe.",
    "transit": "CRH/动车/高铁/航班/公交车/地铁. Action: lookup train/flight route.",
    "logistics": "快递/物流/顺丰/圆通. Action: track shipment.",
    "traffic_sign": "限速/禁停/红绿灯/ETC. Action: explain rule.",
    "parking": "停车场/P车牌/停车费. Action: open maps to parking.",
    "vehicle_plate": "京津沪...+ALPHA+5DIGITS pattern. Action: copy plate.",
    "service_institution": "医院/学校/政府/银行. Action: open maps + hours.",
    "warning_safety": "请勿/禁止/警告/危险. Action: explain warning text.",
    "emergency_call": "110/119/120/122 emergency numbers. Action: auto-confirm dial.",
    "hours_schedule": "营业时间/AM/PM/HH:MM-HH:MM. Action: copy hours.",
    "date_time": "年/月/日/周N/节假日. Action: add_to_calendar.",
    "advertising_design": "广告/策划/设计/VI/LOGO. Action: copy agency contact.",
    "english_dominant": "6+6 latin words in a row (not acronyms). Action: translate.",
    "event_show": "演唱会/演出/剧院/票务. Action: add to calendar.",
    "celebration_greeting": "恭喜/春节/中秋/祝福/开业大吉. Action: copy greeting.",
    "love_dating": "相亲/征婚/约会. Action: copy contact (sensitive).",
    "gaming_lottery": "彩票/双色球 or 游戏代练/原神/陪玩. Action: no action (info-only).",
    "medicine_drug": "处方/用法用量/国药准字. Action: copy dosage (with disclaimer).",
    "notice_announcement": "公告/通知/通告/启事. Action: translate/copy.",
    "legal_contract": "合同/条款/甲方乙方. Action: copy text.",
    "id_document": "身份证/营业执照/注册号. Action: warn + redact.",
    "patent_id": "ZL+number / 专利号. Action: lookup patent online.",
    "ip_address": "Dotted-quad IPv4. Action: ping_lookup or copy.",
    "direction_arrow": "→/入口/出口/左转. Action: integrate with location.",
    "solve": "Math problem (>2 strong keywords). Action: solve step-by-step.",
}


if __name__ == "__main__":
    sys.exit(main())
