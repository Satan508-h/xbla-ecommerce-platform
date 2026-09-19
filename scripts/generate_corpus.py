# -*- coding: utf-8 -*-
"""
生成 RAG 知识库的仿真语料（路线图 3.1）。

输出到 data/corpus/，四种格式各若干份：

    售后政策汇编.docx      Word，用真正的 Heading 1/2/3 样式
    商品导购指南.md        Markdown，用 # 号
    促销活动规则.md        Markdown
    售后FAQ.xlsx           Excel，两列问答表
    商品说明书-星辰X1.pdf   PDF，用文档大纲（bookmark）标记标题

★ 为什么【没有】售后政策汇编.pdf（2026-09-19 移除）

    它原来是和 .docx 一起生成的，用来演示「同一份文档的两种格式会走两条
    不同的标题提取路径」。但它在知识库里变成了**同一内容的第二份副本**，
    而重复内容对检索是实打实的伤害：

      · 两份几乎逐字相同的切片长期同时占据 Top-5 名额，挤掉别的答案；
      · PDF 提取会带硬换行（「用户在本平台下单即⏎视为已阅读」），
        bigram 在换行处被切断，反而比 .docx 那份更差；
      · 评测集的 ground truth 被迫写 allow_multiple 去绕它。

    而 PDF 的大纲提取路径**由 商品说明书-星辰X1.pdf 完整覆盖**
    （实测：两者产出的 heading_path 都是「一级 > 二级」两层），
    所以删掉它不丢任何演示点。

    ⚠️ 入库侧的去重（KbIngestServiceImpl.findReusableByHash）拦不住这种情况 ——
    它比的是**文件字节**的哈希，而 .docx 和 .pdf 字节不同。
    那个机制是「别重复花向量化的钱」，不是「别让同一内容进两次库」，
    内容层面的重复只能在语料源头解决。

★ 为什么四种格式都要生成

    因为 Apache Tika 对这四种格式的**标题提取路径完全不同**（实测结论，
    不是推测）：

        .docx  → 正文里直接有 <h1>/<h2>/<h3> 标签        （策略① 读标签名）
        .pdf   → 正文里一个 h 标签都没有，标题被抽成
                 <body> 末尾的嵌套 <ul>（文档大纲）        （策略② 大纲 + 文本匹配）
        .md    → Tika 没有 Markdown 解析器，整个文件
                 塞进一个 <p>，# 号原样保留               （策略③ 行首模式）
        .xlsx  → sheet 名变成 <h1>，数据在 <table> 里      （表格行拼成文本）

    只生成一种格式的话，另外三条路径永远得不到验证 ——
    而那正是最容易出错、且出错后**不报错只是检索质量变差**的地方。

★ 为什么脚本不连数据库

    商品数据由「数据库同步」那条链路（source_type=2）负责，
    本脚本专注在**天然以文件形态存在的文档**上：政策、规则、指南、FAQ。
    这样脚本零额外依赖（不需要 psycopg）、可复现，
    也不和数据库同步路径产生内容上的重复。

用法：
    python scripts/generate_corpus.py

依赖：
    pip install reportlab python-docx openpyxl
"""

from __future__ import annotations

import os
import sys
from datetime import datetime

# 输出目录：项目根目录下的 data/corpus
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "data", "corpus")

# ----------------------------------------------------------------
# ★ 固定时间戳：让生成的文件【字节确定】
#
# 为什么这件事很重要（踩过一次，2026-09-19）：
#
#   入库侧的「内容没变就不重复入库」（KbIngestServiceImpl.findReusableByHash）
#   比的是**文件字节的 SHA-256**。而 docx / xlsx / PDF 三种格式都会把
#   【创建时间】写进文件头 —— 于是「内容一模一样但重新生成了一次」的文件
#   **哈希完全不同**，会被当成新文档再灌一遍。
#
#   症状是：跑一次 generate_corpus.py + POST /scan，知识库里就多出 3 份
#   逐字相同的重复文档，而且真的会再调一次向量化接口花钱。
#   重复内容会长期占据 Top-5 名额，挤掉别的答案。
#
#   修法：把三种格式的时间戳都钉死成同一个值。
#   这样「重新生成 + 重新扫描」是幂等的 —— 内容没变就跳过，变了才重灌。
# ----------------------------------------------------------------
FIXED_TIMESTAMP = datetime(2026, 1, 1, 0, 0, 0)


# ================================================================
# 语料内容
#
# 刻意写得像真实的电商文档：有章节层级、有具体数字、有例外条款。
# 太短的语料切不出几个切片，验证不出「标题层级切分」的效果；
# 全是套话的语料则会让阶段 7 的检索评测失去区分度。
# ================================================================

AFTER_SALE_DOC = {
    "title": "休伯利安平台售后服务政策汇编",
    "sections": [
        {
            "heading": "第一章 总则",
            "level": 1,
            "subs": [
                {
                    "heading": "1.1 适用范围",
                    "level": 2,
                    "paragraphs": [
                        "本政策适用于休伯利安平台所有自营商品及平台认证的第三方商家商品。"
                        "用户在本平台下单即视为已阅读并同意本政策全部条款。",
                        "港澳台及海外地区的配送订单不适用本政策中的退货条款，"
                        "具体规则以订单详情页展示的说明为准。",
                    ],
                },
                {
                    "heading": "1.2 政策效力",
                    "level": 2,
                    "paragraphs": [
                        "本政策与商品详情页中的特殊说明不一致时，以商品详情页的特殊说明为准。"
                        "例如部分商品在详情页明确标注了「不支持七天无理由退货」，"
                        "则该商品不适用本政策中关于无理由退货的条款。",
                    ],
                },
            ],
        },
        {
            "heading": "第二章 七天无理由退货",
            "level": 1,
            "subs": [
                {
                    "heading": "2.1 适用条件",
                    "level": 2,
                    "paragraphs": [
                        "自用户签收商品之日起七天内，商品保持完好且不影响二次销售的，"
                        "用户可以申请无理由退货。七天为自然日，签收当日不计入。",
                        "「商品完好」指的是：商品本身、配件、赠品、说明书、"
                        "保修卡齐全，商品无使用痕迹、无划痕、无污损，"
                        "原包装完整（拆封但未损坏包装的视为包装完整）。",
                    ],
                },
                {
                    "heading": "2.2 不适用无理由退货的商品",
                    "level": 2,
                    "paragraphs": [
                        "以下商品不适用七天无理由退货：消费者定作的商品；"
                        "鲜活易腐的商品；在线下载或者消费者拆封的音像制品、"
                        "计算机软件等数字化商品；交付的报纸、期刊。",
                        "此外，以下商品经消费者在购买时确认，可以不适用七天无理由退货："
                        "拆封后易影响人身安全或者生命健康的商品；"
                        "拆封后易导致商品品质发生改变的商品；"
                        "一经激活或者试用后价值贬损较大的商品；"
                        "销售时已明示的临近保质期的商品、有瑕疵的商品。",
                        "手机、平板电脑、笔记本电脑等数码产品，"
                        "一旦激活（开机并完成初始化设置）即视为价值贬损较大，"
                        "不支持无理由退货，但支持因质量问题产生的退换货。",
                    ],
                },
                {
                    "heading": "2.3 退款时限与方式",
                    "level": 2,
                    "paragraphs": [
                        "商品退回并经平台验收合格后，退款将在三个工作日内发起。"
                        "退款将原路返回至用户支付时使用的账户。",
                        "使用银行卡支付的订单，退款到账时间取决于发卡银行的处理速度，"
                        "通常为三到十五个工作日。使用平台余额支付的订单，"
                        "退款实时到账。",
                    ],
                },
            ],
        },
        {
            "heading": "第三章 换货与维修",
            "level": 1,
            "subs": [
                {
                    "heading": "3.1 换货条件",
                    "level": 2,
                    "paragraphs": [
                        "商品自签收之日起十五天内出现质量问题的，用户可以申请换货。"
                        "换货商品需保持外观完好，配件齐全。",
                        "因用户使用不当造成的损坏不在换货范围内。"
                        "是否属于质量问题由平台合作的品牌售后网点检测后判定。",
                    ],
                },
                {
                    "heading": "3.2 保修服务",
                    "level": 2,
                    "paragraphs": [
                        "手机、笔记本电脑类商品享受整机一年、主要部件两年的保修服务。"
                        "家用电器类商品享受整机一年、核心部件三年的保修服务。",
                        "保修期自签收之日起计算。保修期内非人为损坏的故障免费维修，"
                        "人为损坏的维修需收取材料费和人工费。",
                    ],
                },
            ],
        },
        {
            "heading": "第四章 退换货流程",
            "level": 1,
            "subs": [
                {
                    "heading": "4.1 申请方式",
                    "level": 2,
                    "paragraphs": [
                        "用户在「我的订单」页面找到对应订单，点击「申请售后」，"
                        "选择退货或换货，填写申请原因并上传商品照片，提交后等待审核。",
                        "审核通常在二十四小时内完成。审核通过后，"
                        "系统会推送寄回地址和物流单号填写入口。",
                    ],
                },
                {
                    "heading": "4.2 寄回与验收",
                    "level": 2,
                    "paragraphs": [
                        "用户需在审核通过后七天内将商品寄回，超时未寄回的申请将自动关闭。"
                        "寄回运费由责任方承担：质量问题由平台承担，"
                        "无理由退货由用户承担。",
                        "平台在收到商品后三个工作日内完成验收。"
                        "验收不通过的，商品将原路退回并说明原因。",
                    ],
                },
            ],
        },
    ],
}

PROMOTION_DOC = """# 休伯利安平台促销活动规则

本规则适用于休伯利安平台在活动期间开展的各类促销活动。
参与活动即视为同意本规则的全部条款。

## 一、满减活动

### 1.1 活动形式

满减活动指订单金额达到指定门槛后直接减免固定金额。
例如「满 3000 减 300」，指订单商品总额达到 3000 元时，
结算金额自动减去 300 元。

### 1.2 门槛计算规则

门槛金额按**商品实付金额**计算，不含运费、不含已使用的优惠券抵扣部分。
也就是说，如果一件商品售价 3200 元，已使用一张 200 元的券，
那么参与满减计算的金额是 3000 元，刚好达到门槛。

### 1.3 叠加规则

满减活动可以与店铺优惠券叠加使用，但不与同类满减活动叠加。
同一笔订单只能享受一档满减，系统会自动选择优惠力度最大的一档。

## 二、优惠券使用规则

### 2.1 有效期

优惠券的有效期以券面标注为准，过期后自动失效，不予补发。
部分活动券的有效期只有二十四小时，请在领取后尽快使用。

### 2.2 使用限制

每笔订单限用一张优惠券。优惠券不可拆分使用，不可兑换现金，不可转赠他人。
发生退货时，已使用的优惠券不予退还；但如果是整单退货且券仍在有效期内，
券会退回用户的账户。

### 2.3 适用范围

标注了适用类目的优惠券只能在对应类目的商品上使用。
全场通用券可以用于所有自营商品，但不能用于话费充值、虚拟商品等特殊品类。

## 三、限时秒杀

### 3.1 活动时间

限时秒杀通常在每天的 10:00、14:00、20:00 三个整点开始，每场持续两小时。
具体时间以活动页面展示为准。

### 3.2 库存与限购

秒杀商品数量有限，售完即止。每个账号每场活动限购一件。
同一收货地址、同一支付账号视为同一用户。

### 3.3 订单取消

秒杀订单需要在十五分钟内完成支付，超时未支付的订单将自动取消，
释放的库存会重新进入秒杀池。

## 四、价格保护

### 4.1 保价范围

自营商品在签收后十五天内发生降价的，用户可以申请价格保护，退还差价。
保价仅适用于同一商品在同一平台的价格变动。

### 4.2 不适用情形

以下情形不适用价格保护：使用优惠券、满减等促销手段后的价格；
秒杀、清仓等限时活动的价格；因用户等级不同而展示的会员价；
商品下架前的清仓价格。

### 4.3 申请方式

在「我的订单」中找到对应订单，点击「申请价保」，
系统会自动比对该商品当前价格，符合条件的即时退还差价至原支付账户。
"""

SHOPPING_GUIDE_DOC = """# 休伯利安平台商品选购指南

这份指南帮助你在众多商品中快速找到适合自己的那一款。
内容按使用场景组织，如果你已经明确知道自己要买什么，可以直接跳到对应章节。

## 一、按使用场景选购

### 1.1 适合送长辈的商品

给长辈挑选礼物，重点考虑三件事：操作是否简单、字够不够大、售后是否方便。

智能手机方面，屏幕尺寸在 6.5 英寸以上、支持字体放大的机型更合适。
长辈通常不习惯复杂的交互，系统层面是否有「简易模式」很关键。
另外要留意电池容量，长辈常常忘记充电，5000mAh 以上的机型更省心。

家用电器方面，带语音控制的产品对长辈很友好，不用学遥控器怎么按。
扫地机器人要选带自动集尘的，否则每次倒尘盒对老人来说是负担。

保健器械方面，电子血压计、按摩椅这类产品要选操作面板字大、
按键少的型号。测量类的器械建议选有医疗器械注册证的品牌。

### 1.2 适合商务人士的商品

商务场景下，便携性和续航是第一位的。
笔记本电脑建议选重量在 1.3 公斤以内、续航八小时以上的型号。
屏幕比例 16:10 的机型在查看文档和表格时显示的行数更多。

手机方面，支持双卡双待和通话录音是商务用户的高频需求。
如果经常出差，还要留意是否支持多频段 5G 和全球漫游。

### 1.3 适合学生群体的商品

学生用户预算通常有限，性价比是首要考虑。
笔记本电脑选 4000 到 6000 元价位段，处理器性能足够应付日常学习，
显卡如果不打游戏可以不选独立显卡，把钱花在内存和固态硬盘上更划算。

平板电脑适合记笔记和看课件，建议选支持手写笔的型号，
并且留意手写笔是标配还是需要单独购买。

## 二、参数怎么看

### 2.1 手机参数

处理器型号决定了手机的流畅度上限，但日常使用中，
内存和存储空间的影响往往更直接。8GB 内存起步，256GB 存储起步是当前的合理配置。

刷新率指的是屏幕每秒重绘的次数，单位是赫兹。
120Hz 的屏幕在滑动时明显比 60Hz 顺滑，但也会更耗电。

电池容量用毫安时表示，数值越大理论续航越长，
但实际续航还取决于屏幕尺寸、处理器功耗和系统优化。

### 2.2 笔记本电脑参数

轻薄本的处理器后缀通常是 U 或 P，标压处理器后缀是 H。
H 系列性能更强但更耗电，如果你主要在家或办公室使用，
标压处理器是更好的选择。

屏幕色域决定了颜色的准确度，设计类专业建议选 100% sRGB 以上的屏幕。
普通办公使用 45% NTSC 色域的屏幕就足够了。

固态硬盘的接口类型影响读写速度，PCIe 4.0 比 PCIe 3.0 快一倍左右，
但在日常办公场景下体感差异不明显。

### 2.3 家用电器参数

看能效等级，一级能效最省电。虽然一级能效的产品售价通常更高，
但按十年的使用周期计算，省下的电费往往能覆盖差价。

看噪音值，单位是分贝。卧室使用的电器建议选 35 分贝以下的型号，
客厅使用 45 分贝以下即可接受。

## 三、购买时机

### 3.1 大促节点

平台的大型促销通常集中在几个时间点：年中大促、双十一、双十二、年货节。
这些时间点的价格通常是全年低点，但要注意部分商家会先涨价再打折。

### 3.2 新品与旧款

数码产品的新品发布后，上一代产品通常会降价，
这是入手旧款的好时机。性能差距在日常使用中往往感知不到，
但价格可能相差百分之二十以上。

## 四、常见误区

### 4.1 像素越高拍照越好

不是。照片质量取决于传感器尺寸、镜头素质和算法调校，
像素数只决定了照片的分辨率。一台 1200 万像素的大底传感器手机，
成像质量通常优于 6400 万像素的小底手机。

### 4.2 核心数越多越快

不是。处理器的性能取决于架构、制程和主频，核心数量只是其中一个维度。
八核的低端处理器可能不如四核的中端处理器。
"""

FAQ_ROWS = [
    ("问题", "答案", "分类"),
    ("退货需要多长时间", "审核通过后，商品寄回并验收合格，退款在三个工作日内发起", "退货"),
    ("退款多久到账", "原路退回。余额支付实时到账，银行卡支付三到十五个工作日", "退款"),
    ("拆封了还能退货吗", "普通商品拆封但包装完整的可以退；数码产品激活后不支持无理由退货", "退货"),
    ("运费谁承担", "质量问题由平台承担，无理由退货由用户承担", "退货"),
    ("怎么申请售后", "在「我的订单」中找到订单，点击「申请售后」填写信息并上传照片", "售后"),
    ("审核要多久", "通常在二十四小时内完成审核", "售后"),
    ("换货的条件是什么", "签收后十五天内出现质量问题，外观完好、配件齐全即可申请换货", "换货"),
    ("保修期是多久", "手机笔记本整机一年主要部件两年；家电整机一年核心部件三年", "保修"),
    ("人为损坏保修吗", "不在免费保修范围，维修需收取材料费和人工费", "保修"),
    ("优惠券可以叠加吗", "满减活动可以和店铺券叠加，但同类满减活动之间不能叠加", "促销"),
    ("优惠券过期了能补吗", "不能，过期自动失效不予补发，请在有效期内使用", "促销"),
    ("退货后优惠券退吗", "整单退货且券仍在有效期内会退回账户，部分退货不退", "促销"),
    ("秒杀没付款会怎样", "十五分钟内未支付订单自动取消，库存释放回秒杀池", "促销"),
    ("价保怎么申请", "在订单页面点击「申请价保」，系统自动比价，符合条件即时退差价", "价保"),
    ("所有商品都支持价保吗", "不是，秒杀价、清仓价、会员价、用券后的价格都不在价保范围", "价保"),
    ("可以开电子发票吗", "可以，下单时可选择电子发票，发货后发送到预留邮箱", "发票"),
    ("发票开错了能重开吗", "可以，在订单详情页申请重开，原发票作废后重新开具", "发票"),
    ("怎么查询物流", "在「我的订单」中点击订单查看物流详情，已发货订单会显示物流单号", "物流"),
    ("支持修改收货地址吗", "未发货订单可以修改，已发货订单需联系客服协商", "物流"),
    ("会员等级有什么用", "等级越高享受的折扣力度越大，钻石会员可享受专属客服", "会员"),
]

MANUAL_SECTIONS = [
    ("产品简介", 0, [
        "星辰 X1 是休伯利安科技推出的旗舰智能手机，"
        "搭载 6.78 英寸 2K 分辨率屏幕，支持 120Hz 自适应刷新率。",
        "机身采用航空级铝合金中框与康宁大猩猩玻璃背板，"
        "重量 198 克，厚度 8.2 毫米，支持 IP68 级防尘防水。",
    ]),
    ("外观与按键", 1, [
        "机身右侧从上到下依次是音量加键、音量减键和电源键。"
        "电源键同时集成了指纹识别功能，轻触即可解锁。",
        "机身底部是 USB Type-C 接口和扬声器开孔。"
        "机身顶部保留了红外发射器，可以当作遥控器使用。",
        "机身左侧是 SIM 卡槽，使用取卡针垂直插入小孔即可弹出。"
        "卡槽支持双 nano-SIM 卡，不支持存储卡扩展。",
    ]),
    ("开机与初始化", 1, [
        "长按电源键三秒开机。首次开机会进入初始化向导，"
        "依次完成语言选择、Wi-Fi 连接、账号登录和指纹录入。",
        "初始化过程中可以选择从旧手机迁移数据，"
        "支持通过数据线或无线方式迁移联系人、照片和应用。",
        "如果想跳过某些步骤，可以在每一步的右下角点击「稍后设置」。"
        "但指纹和锁屏密码建议在初始化时完成，否则部分应用无法使用。",
    ]),
    ("充电与电池", 1, [
        "本机支持 120W 有线快充和 50W 无线快充。"
        "使用原装充电器时，从零电量充至百分之百约需二十五分钟。",
        "为延长电池寿命，系统默认开启了智能充电保护，"
        "会在电量达到百分之八十后降低充电速度。"
        "这个行为是正常的，不是充电故障。",
        "电池容量为 5500mAh。在中等使用强度下，"
        "可以满足一天半的续航需求。重度游戏场景下约可使用六小时。",
    ]),
    ("常见问题", 2, [
        "手机发热怎么办：高负载场景下机身温度上升属于正常现象。"
        "建议避免边充电边玩大型游戏，并取下手机壳辅助散热。",
        "屏幕出现残影怎么办：OLED 屏幕长时间显示同一静态画面可能产生残影。"
        "建议开启自动亮度，并缩短屏幕自动锁屏时间。",
        "无法充电怎么办：先检查充电器和数据线是否损坏，"
        "再清理充电接口的灰尘。若仍无法充电，请联系售后服务。",
    ]),
]


# ================================================================
# 生成器
# ================================================================

def _normalize_ooxml(path: str) -> None:
    """
    把 docx / xlsx 这类 OOXML 包（本质是 zip）里的时间戳全部钉死，使字节可复现。

    ★ 为什么必须在【生成之后】再重写一遍，而不是给两个库设属性

      实测（2026-09-19，python-docx + openpyxl 3.1.5）：

        · **zip 条目自带的时间戳** —— 两个库都写「此刻」，**没有开关可关**。
          docx 有 17 个条目、xlsx 有 9 个，全部带着当前时间，是哈希不稳定的主因。
        · **core.xml 的 <dcterms:modified>** —— openpyxl 在 save() 里把它
          覆盖成 datetime.utcnow()，显式给 wb.properties.modified 赋值【实测无效】。

      逐库去设属性既不可靠、换版本还会悄悄失效（这次就踩了：第一次测"通过"
      是因为两次生成间隔不到一秒，时间戳碰巧相同 —— 假阴性）。
      改为生成后统一重写，行为稳定、看得见、可断言。

    ⚠️ 只改两个日期字段和条目时间戳，其它字节（正文、样式、缩略图）原样保留。
    """
    import re
    import zipfile

    with zipfile.ZipFile(path) as zin:
        entries = [(info, zin.read(info.filename)) for info in zin.infolist()]

    fixed = FIXED_TIMESTAMP.timetuple()[:6]
    iso = FIXED_TIMESTAMP.strftime("%Y-%m-%dT%H:%M:%SZ")
    date_tag = re.compile(r"(<dcterms:(?:created|modified)[^>]*>)[^<]*(</dcterms:(?:created|modified)>)")

    tmp = path + ".normalizing"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            if info.filename == "docProps/core.xml":
                text = date_tag.sub(lambda m: m.group(1) + iso + m.group(2),
                                    data.decode("utf-8"))
                data = text.encode("utf-8")
            new = zipfile.ZipInfo(info.filename, date_time=fixed)
            new.compress_type = zipfile.ZIP_DEFLATED
            new.external_attr = info.external_attr
            new.internal_attr = info.internal_attr
            new.create_system = info.create_system
            zout.writestr(new, data)

    os.replace(tmp, path)


def _register_chinese_font():
    """
    注册 reportlab 自带的中文 CID 字体。不注册的话中文会变成黑方块。

    ★ 同时把 reportlab 切成 **invariant 模式**，让产出的 PDF 字节确定。

    PDF 文件头里有 /CreationDate 和 /ModDate，reportlab 默认写「此刻」，
    另外还会生成一个基于随机的 /ID —— 两者都让同一个文档每次导出的
    字节都不同，于是入库侧的字节哈希去重失效（见 FIXED_TIMESTAMP 的说明）。

    reportlab 的 invariant 模式就是为「可复现构建」设计的：它把时间戳
    固定、/ID 用内容派生，产出完全确定。必须在 build() 之前设置。
    """
    from reportlab import rl_config
    rl_config.invariant = 1

    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    for name in ("STSong-Light", "STHeiti-Regular"):
        try:
            pdfmetrics.registerFont(UnicodeCIDFont(name))
        except Exception:
            pass


def make_docx(path: str) -> None:
    """Word 文档：用真正的 Heading 样式（POI 会映射成 <h1>/<h2>/<h3>）"""
    from docx import Document
    from docx.shared import Pt

    doc = Document()

    # 只写「身份类」字段。时间类字段（created / modified）和 zip 条目时间戳
    # 统一由 _normalize_ooxml 处理 —— 那里有实测原因，别在这里重复设
    props = doc.core_properties
    props.last_modified_by = "generate_corpus.py"
    props.revision = 1

    doc.add_heading(AFTER_SALE_DOC["title"], level=1)

    for section in AFTER_SALE_DOC["sections"]:
        doc.add_heading(section["heading"], level=section["level"] + 0)
        for sub in section["subs"]:
            doc.add_heading(sub["heading"], level=sub["level"] + 0)
            for para in sub["paragraphs"]:
                doc.add_paragraph(para)

    doc.save(path)
    _normalize_ooxml(path)


def make_manual_pdf(path: str) -> None:
    """
    产品说明书 PDF：带文档大纲的 PDF。

    ★ 关键点：标题必须写进 PDF 的 **outline（大纲）**，因为 Tika 提取 PDF 标题
      唯一可靠的来源就是它 —— PDF 格式本身不存储「这是标题」这个信息，
      正文里 Tika 也永远不会生成 <h1> 标签（实测确认）。
      没有大纲的 PDF，标题层级就彻底提取不出来，只能退化成定长切分。

    ⚠️ 实测产出的 heading_path 是「星辰 X1 智能手机用户手册 > 产品简介」，
      也就是**标题 + 一级小节**两层。原 docstring 写的是「三级大纲」，
      但下面两个分支都是 h2（`h2 if level == 0 else h2`），实际只有一级小节 ——
      这里按实际行为更正，不再声称三级。
    """
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer

    _register_chinese_font()

    h1 = ParagraphStyle("H1", fontName="STSong-Light", fontSize=20, leading=28, spaceAfter=14)
    h2 = ParagraphStyle("H2", fontName="STSong-Light", fontSize=14, leading=22, spaceAfter=8)
    body = ParagraphStyle("Body", fontName="STSong-Light", fontSize=10.5, leading=17, spaceAfter=6)

    seq = [0]

    class OutlineDoc(BaseDocTemplate):
        def afterFlowable(self, flowable):
            if not isinstance(flowable, Paragraph):
                return
            name = flowable.style.name
            if name not in ("H1", "H2"):
                return
            level = int(name[1]) - 1
            text = flowable.getPlainText()
            seq[0] += 1
            key = f"m-{seq[0]}"
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, level=level, closed=(level > 0))

    doc = OutlineDoc(path)
    frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="normal")
    doc.addPageTemplates([PageTemplate(id="main", frames=[frame])])

    story = [Paragraph("星辰 X1 智能手机用户手册", h1), Spacer(1, 8)]
    for heading, level, paragraphs in MANUAL_SECTIONS:
        story.append(Paragraph(heading, h2 if level == 0 else h2))
        for para in paragraphs:
            story.append(Paragraph(para, body))
    doc.build(story)


def make_md(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def make_xlsx(path: str, rows: list) -> None:
    from openpyxl import Workbook
    from openpyxl.styles import Font

    wb = Workbook()

    # 只写「身份类」字段。⚠️ 实测：即使在这里把 properties.modified 设成固定值，
    # openpyxl 也会在 save() 时覆盖掉它 —— 时间字段全部交给 _normalize_ooxml
    wb.properties.creator = "generate_corpus.py"
    wb.properties.lastModifiedBy = "generate_corpus.py"

    ws = wb.active
    ws.title = "售后FAQ"
    for row in rows:
        ws.append(list(row))
    # 首行加粗，模拟真实的表头。
    # 注意直接赋新 Font 而不是 cell.font.copy(bold=True) ——
    # 后者在 openpyxl 3.1 已废弃，会打一堆 DeprecationWarning 干扰输出
    for cell in ws[1]:
        cell.font = Font(bold=True)
    # 列宽调宽一点，方便人工查看
    ws.column_dimensions["A"].width = 26
    ws.column_dimensions["B"].width = 60
    ws.column_dimensions["C"].width = 10
    wb.save(path)
    _normalize_ooxml(path)


# ================================================================
# 主流程
# ================================================================

def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)

    tasks = [
        ("售后政策汇编.docx", lambda p: make_docx(p)),
        ("商品说明书-星辰X1.pdf", lambda p: make_manual_pdf(p)),
        ("商品导购指南.md", lambda p: make_md(p, SHOPPING_GUIDE_DOC)),
        ("促销活动规则.md", lambda p: make_md(p, PROMOTION_DOC)),
        ("售后FAQ.xlsx", lambda p: make_xlsx(p, FAQ_ROWS)),
    ]

    ok = 0
    failed = 0
    for name, fn in tasks:
        path = os.path.join(OUT_DIR, name)
        try:
            fn(path)
            size = os.path.getsize(path)
            print(f"  [OK]   {name:<24} {size:>8} bytes")
            ok += 1
        except ImportError as e:
            print(f"  [缺失依赖] {name}: {e}")
            print("           请先执行：pip install reportlab python-docx openpyxl")
            failed += 1
        except Exception as e:
            print(f"  [失败] {name}: {type(e).__name__}: {e}")
            failed += 1

    print()
    print(f"输出目录：{OUT_DIR}")
    print(f"成功 {ok} 份，失败 {failed} 份")

    if failed:
        return 1
    print()
    print("下一步：POST /api/kb/documents/scan 把它灌进知识库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
