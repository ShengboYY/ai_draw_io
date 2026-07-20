"""Authored specifications for the diverse article-style material RAG fixtures."""

from __future__ import annotations


# Each document uses a distinct subject, language mix and layout pattern so retrieval quality cannot
# be inferred from a single writing template. Page boundaries are authored and stable for evaluation.
DIGITAL_DOCUMENTS = [
    {
        "source": "realistic-water-audit",
        "documentFamily": "coastal-water-audit",
        "version": "v1",
        "split": "development",
        "language": "zh",
        "filename": "realistic-coastal-water-audit-v1.pdf",
        "pages": [
            {
                "title": "滨海供水损耗审计报告",
                "subtitle": "CWA-2026-09 - 中文长文档与跨语言检索夹具",
                "sections": [
                    ("执行摘要", "本次审计覆盖滨海新区 12 个压力分区，观察期为 2026 年 1 月 8 日至 6 月 30 日。项目组把流量计记录、维修工单、夜间最小流量测试和现场复核记录进行交叉核对，目的是区分真实漏损、计量误差与计划性冲洗用水。审计编号为 CWA-2026-09；旧培训案例 CWA-2026-90 与本报告无关。"),
                    ("核心判断", "分区调压和两处主干管修复后，标准化日均漏损量下降，但不同区域的改善程度并不一致。北岸区的变化主要来自阀门修复，南渠区则受到工业用户夜间用水波动影响。报告不建议仅根据单日最低流量调整全网压力。"),
                    ("证据规则", "数值结论以校准后的流量记录为准。维修人员的描述用于解释异常，但不能替代仪表数据。表格中的分区值、正文中的全网值以及附录中的演示数具有不同证据范围；回答问题时必须先确认请求的是哪个对象和哪个时间段。"),
                    ("范围限制", "本报告不包含居民姓名、联系电话、缴费账号或建筑门禁信息。审计也没有评估饮用水化学指标，因此不能用本报告回答水质合规问题。相关主题词的出现不代表存在对应事实。"),
                ],
            },
            {
                "title": "1. 基线方法与漏损边界",
                "subtitle": "跨表计校准、缺失区间和相近数值",
                "sections": [
                    ("测量边界", "基线包含进入 12 个压力分区的供水量与经过验证的计费、消防测试和计划冲洗用水。项目组统一了夏令时、表计时钟漂移和维护停机标记，并排除两次经批准的消防演练。任何无法追溯到原始记录的手工修正都没有进入正式数据集。"),
                    ("基线结果", "在匹配的改造前观察周中，全网标准化日均漏损量为 6.8 兆升。日中位数为 6.6 兆升，暴雨后的单日最高值达到 8.4 兆升。正式比较使用 6.8 兆升这一日均值，而不是附近出现的中位数或峰值。"),
                    ("计量不确定性", "综合校准不确定性估计为每日正负 0.2 兆升。三个短时缺口通过相邻有效区间插补，插补数据占基线总量不到 0.7%。移除所有插补区间后，结论方向不变，但报告仍保留该敏感性结果供复核。"),
                    ("易混淆数据", "市政绿化系统另有每日 5.9 兆升的独立统计，该系统不属于本审计边界。培训材料中的 20% 降幅只是计算示例。检索系统如果仅匹配“日均”或“漏损”主题，可能错误选择这些邻近数值。"),
                ],
            },
            {
                "title": "2. 改造后结果",
                "subtitle": "跨页比较的第二部分",
                "sections": [
                    ("观察结果", "在匹配的改造后观察周中，全网标准化日均漏损量为 5.1 兆升。下降主要集中在凌晨低需求时段，且没有伴随用户端低压投诉增加。该值必须与前页的 6.8 兆升基线结合，才能回答改造前后比较问题。"),
                    ("派生变化", "两个经验证日均值相差 1.7 兆升，相对基线下降 25.0%。内部计算使用未四舍五入的小时数据，报告发布一位小数的日均值，以便人工复算。任何把 5.9 兆升绿化数据当作基线的计算都会得到错误结论。"),
                    ("替代解释", "季节性需求、两家工业用户的生产安排和降雨都可能影响原始总量。匹配设计和压力分区对照降低了这些因素的影响，但不能完全建立因果关系。敏感性分析得到 22.6% 至 26.4% 的下降区间。"),
                    ("后续监测", "未来六个月保持同一测量边界，同时记录夜间最小流量、爆管次数、用户低压投诉和计划冲洗量。若扩大分区范围，必须从扩展当日建立新的时间序列，不能与当前基线直接拼接。"),
                ],
            },
            {
                "title": "3. 分区运行统计",
                "subtitle": "原生文本密集表格与表后解释",
                "sections": [
                    ("表格说明", "表 2 汇总改造后各分区的压力、夜间最小流量和验证漏点。压力是运行描述，不是自动停水阈值；漏点数来自完成复核的工单。"),
                ],
                "table": {
                    "headers": ["分区", "平均压力", "夜间最小流量", "验证漏点", "责任组"],
                    "rows": [
                        ["北岸 A", "46 kPa", "1.8 ML/d", "3", "管网一组"],
                        ["北岸 B", "44 kPa", "1.5 ML/d", "2", "管网一组"],
                        ["南渠 C", "51 kPa", "2.3 ML/d", "5", "现场服务组"],
                        ["南渠 D", "49 kPa", "1.9 ML/d", "4", "现场服务组"],
                        ["港东 E", "42 kPa", "1.2 ML/d", "1", "资产维护组"],
                        ["港东 F", "43 kPa", "1.4 ML/d", "2", "资产维护组"],
                    ],
                    "colWidths": [78, 78, 105, 68, 104],
                },
                "afterSections": [
                    ("读取提示", "南渠 C 的夜间最小流量最高，为 2.3 ML/d，同时有 5 个验证漏点。北岸 A 的平均压力较低，但这不等于其漏损一定更少。回答表格问题时需要保持行列关系。"),
                ],
            },
            {
                "title": "4. 阀门治理与责任边界",
                "subtitle": "双栏政策页、日期冲突与审批角色",
                "layout": "columns",
                "sections": [
                    ("运行权限", "值班调度员可以在确认爆管后执行紧急隔离，但永久改变分区压力曲线必须由供水运行负责人批准。采购人员可以确认阀门到货，不能宣布现场整改完成。"),
                    ("完成期限", "关键阀门标签和方向标识必须在 2026 年 12 月 18 日前完成更换，责任团队为资产维护组。供应商计划中的 11 月 18 日是首批标签交付日期，不是最终完成期限。"),
                    ("告警规则", "连续四个 15 分钟区间的夜间流量超过分区基线 35% 时启动人工复核。单个区间超限仅保留观察记录，不能直接生成爆管结论。"),
                    ("会议记录", "治理委员会有七名投票成员，法定人数为五名。6 月会议有六名成员出席，另有三名观察员。九人总出席数不能替代法定投票人数。"),
                    ("证据留存", "校准文件、原始日志和批准记录保存 36 个月。现场照片的缩略图可以用于导航，但最终引用必须指向包含时间和位置元数据的原始版本。"),
                ],
            },
            {
                "title": "5. 版本说明与否定证据",
                "subtitle": "被拒绝的草案值、限制与拒答范围",
                "sections": [
                    ("版本状态", "本报告为 1.0 版，于 2026 年 8 月 14 日批准。5 月草案曾建议把复核阈值设置为基线以上 30%，委员会认为误报过多并予以否决。批准版本使用连续四个区间超过 35% 的规则。"),
                    ("适用限制", "六个月观察期不足以评估罕见的大范围停水，也不能证明同一调压方案适用于高层住宅区。工业用户的生产变化仍可能影响南渠分区，因此该分区需要更长时间的独立观察。"),
                    ("无答案边界", "报告没有列出居民账户、住宅地址、支付信息、工作人员私人电话或水质实验室结果。它也没有指定保险机构。系统应明确说明证据不足，而不是从常识或其他资料补全。"),
                    ("引用要求", "基线与改造后比较必须同时引用第 2 页和第 3 页。表格问题应引用第 4 页对应行列；阈值问题应引用批准版本，若提到 30% 必须明确它是已拒绝草案。"),
                ],
            },
        ],
    },
    {
        "source": "realistic-solar-manual",
        "documentFamily": "helios-inverter-manual",
        "version": "v1",
        "split": "validation",
        "language": "mixed",
        "filename": "realistic-helios-inverter-manual-v1.pdf",
        "pages": [
            {
                "title": "Helios HX-48 Commissioning Manual",
                "subtitle": "HXM-48-2026 - bilingual procedural document",
                "sections": [
                    ("Purpose", "This manual defines commissioning and maintenance steps for the Helios HX-48 grid-connected inverter. It is intended for trained technicians working under an approved isolation permit. The equipment family identifier is HX-48; HX-84 appears in a training appendix and is not compatible with the procedures in this edition."),
                    ("Document control", "Edition 1.3 was approved on 22 September 2026. Field annotations do not change the controlled procedure until engineering publishes a new edition. Screenshots may assist navigation, but settings must be verified against the displayed firmware version and the approved parameter table."),
                    ("Safety boundary", "This synthetic manual does not authorize real electrical work. It contains no site access codes, customer credentials or emergency phone numbers. Test questions must be answered only from the authored fixture and must not be treated as operational advice."),
                    ("Reading conventions", "WARNING denotes a potential safety consequence, CAUTION denotes equipment damage and NOTE records useful context. A numbered step is mandatory only within its procedure. Similar values in troubleshooting examples are deliberately retained as retrieval negatives."),
                ],
            },
            {
                "title": "1. Safe shutdown sequence",
                "subtitle": "Numbered procedure with time-dependent evidence",
                "sections": [
                    ("Preparation", "Record the active power, DC voltage and firmware build before changing state. Open the approved work order and confirm that the serial number on the enclosure matches the asset record. A screen label alone is insufficient because replacement control boards may retain an earlier site name."),
                    ("Isolation", "Set the operating mode to STANDBY, open the AC isolator and then open the DC disconnect. After both sources are open, wait 7 minutes before removing the service cover. The nearby 70-second delay applies only to a communications restart and must not be used for electrical isolation."),
                    ("Verification", "Measure the specified test points with an approved meter and confirm the indicator is dark. If voltage remains above the documented limit, replace the cover, secure the work area and escalate through the site procedure. Do not shorten the waiting period because a display has turned off."),
                    ("Return to service", "Reinstall all barriers, close the DC disconnect, close the AC isolator and leave STANDBY only after the self-test completes. Record the self-test identifier and final power value in the work order."),
                ],
            },
            {
                "title": "2. Mechanical and electrical settings",
                "subtitle": "Native table, units and near-value distractors",
                "sections": [
                    ("Fastener control", "Use a calibrated tool and record the final value. The DC terminal torque is 6.2 N m. A legacy HX-36 bulletin specifies 5.8 N m, but that value is not approved for HX-48 equipment."),
                ],
                "table": {
                    "headers": ["Connection", "Conductor", "Torque", "Inspection", "Reference"],
                    "rows": [
                        ["DC positive", "35 mm2", "6.2 N m", "each service", "HX48-T01"],
                        ["DC negative", "35 mm2", "6.2 N m", "each service", "HX48-T02"],
                        ["AC phase", "50 mm2", "8.4 N m", "annual", "HX48-T03"],
                        ["Protective earth", "25 mm2", "7.1 N m", "annual", "HX48-T04"],
                        ["Signal shield", "1.5 mm2", "0.8 N m", "installation", "HX48-T05"],
                    ],
                    "colWidths": [92, 72, 72, 92, 104],
                },
                "afterSections": [
                    ("Limits", "The maximum continuous AC current is 69 A at the nominal test condition. A commissioning screen may briefly display 76 A during its simulated check; that screen value is not the continuous rating."),
                ],
            },
            {
                "title": "3. Control path overview",
                "subtitle": "Raster-only process diagram",
                "sections": [
                    ("Diagram scope", "Figure 3 shows the control path used during startup. Node names and arrow destinations are present only in the raster diagram. The surrounding paragraph intentionally avoids restating the route so visual evaluation cannot be bypassed through the text layer."),
                    ("Failure handling", "A failed insulation check stops the sequence and preserves the event record. The technician must identify the failed stage before restarting; repeated start commands are not a substitute for diagnosis."),
                ],
                "diagram": {
                    "filename": "helios-control-path.png",
                    "title": "HX-48 STARTUP CONTROL PATH",
                    "nodes": ["PRECHECK", "INSULATION TEST", "GRID SYNC", "RUN"],
                    "edgeLabels": ["permit valid", "test passed", "phase locked"],
                },
            },
            {
                "title": "4. 固件与告警处理",
                "subtitle": "中文说明、版本约束与连续条件",
                "sections": [
                    ("固件范围", "本版手册批准的最低固件版本为 4.7.2。版本 4.7.1 可以读取历史记录，但不能启用新的绝缘测试流程。升级完成后必须重新读取设备身份信息，确认型号仍为 HX-48。"),
                    ("温度告警", "散热器温度连续三个采样周期达到或超过 82 摄氏度时进入人工复核。单次 82 摄氏度记录只产生观察事件。培训屏幕中的 28 摄氏度是界面演示值，不是告警阈值。"),
                    ("复位条件", "只有在温度回落、风扇自检通过且工单记录完整后才能复位。远程操作员可以发起诊断，现场技术人员负责确认物理通风路径。两种角色不能互相替代。"),
                    ("记录保留", "固件包校验值、参数导出和升级结果至少保存 24 个月。截图缺少结构化参数时只能作为辅助证据，不能单独证明设置正确。"),
                ],
            },
            {
                "title": "5. Troubleshooting and exclusions",
                "subtitle": "Symptoms, misleading matches and no-answer controls",
                "layout": "columns",
                "sections": [
                    ("No grid sync", "Confirm phase order and grid window before replacing hardware. A communication timeout can appear nearby in the log but does not prove a phase error."),
                    ("Repeated fan alarm", "Inspect airflow and connector seating. Do not change the 82 degree review rule to suppress alerts."),
                    ("Rejected draft", "An engineering draft proposed a 5-minute cover wait. Safety review rejected it; the approved shutdown sequence remains 7 minutes."),
                    ("Unsupported facts", "The manual contains no installer password, private service number, site address or electricity tariff. Those questions require abstention."),
                    ("Citation practice", "Procedural answers cite the numbered sequence, parameter answers cite the table row and visual route answers cite the diagram region."),
                ],
            },
        ],
    },
    {
        "source": "realistic-cold-chain-report",
        "documentFamily": "aurora-cold-chain-validation",
        "version": "v1",
        "split": "holdout",
        "language": "en",
        "filename": "realistic-aurora-cold-chain-validation-v1.pdf",
        "pages": [
            {
                "title": "Aurora Food Cold-Chain Validation",
                "subtitle": "ACV-26-114 - experimental report with figures and footnotes",
                "sections": [
                    ("Objective", "This study evaluates insulated food containers on the Aurora regional route under matched loading and weather conditions. The validation identifier is ACV-26-114. It does not cover medicine, biological samples or consumer refrigeration, and its findings must not be generalized beyond the defined route and packaging system."),
                    ("Study design", "Twenty-four monitored shipments were paired by departure window, payload class and forecast temperature. Twelve used the baseline liner and twelve used the revised liner. Data loggers recorded at two-minute intervals, while handling events were confirmed from depot scans and vehicle records."),
                    ("Primary outcome", "The primary metric is the time-weighted mean internal temperature during the transport window. Excursion counts and door-open duration are secondary outcomes. A packaging change is considered useful only when it improves temperature control without increasing loading errors."),
                    ("Evidence limits", "Driver comments explain unusual stops but do not replace logger data. The report contains no customer identities, delivery addresses, commercial prices or access credentials. Questions requesting those facts are unsupported."),
                ],
            },
            {
                "title": "1. Baseline shipments",
                "subtitle": "First half of the matched comparison",
                "sections": [
                    ("Baseline result", "Across the twelve baseline-liner shipments, the time-weighted mean internal temperature was 5.6 degrees C. The median shipment mean was 5.5 degrees C and the highest single-shipment mean was 6.4 degrees C. The matched comparison uses 5.6 degrees C."),
                    ("Exposure context", "Outdoor temperature ranged from 18.2 to 31.7 degrees C. Two routes included planned depot waits, and one included an unplanned traffic stop. These events remained in the primary analysis because equivalent handling categories were represented after the packaging change."),
                    ("Logger quality", "All twenty-four primary loggers passed calibration checks. Two backup loggers contained short gaps, but neither supplied a primary outcome. Clock alignment was corrected before pairing, and the original timestamps remain available in the audit record."),
                    ("Nearby values", "A warehouse energy dashboard reports 4.9 degrees C for a fixed cool room. That value has a different measurement boundary and is not the shipment baseline. A training slide uses 6.0 degrees C as an example specification."),
                ],
            },
            {
                "title": "2. Revised-liner shipments",
                "subtitle": "Second half of the matched comparison",
                "sections": [
                    ("Observed result", "Across the twelve revised-liner shipments, the time-weighted mean internal temperature was 4.8 degrees C. Door-open duration was similar to baseline, and loading-error checks did not increase. This result must be combined with the preceding page for a before-and-after answer."),
                    ("Derived change", "The validated means differ by 0.8 degrees C, equivalent to a 14.3 percent reduction relative to baseline. The report publishes one-decimal means and calculates the percentage from unrounded logger aggregates."),
                    ("Robustness", "Removing the longest traffic delay changed the reduction by less than 0.1 degrees C. Pair-level results favored the revised liner in ten of twelve pairs. The study remains too small to estimate rare seal failures."),
                    ("Recommendation", "Continue the revised liner for a three-month operational trial while preserving the same logger placement and pairing rules. Changing the payload mix or sensor location would create a new series."),
                ],
            },
            {
                "title": "3. Excursions by route",
                "subtitle": "Native table with route-level evidence",
                "sections": [
                    ("Definition", "An excursion is a continuous interval above 7.0 degrees C lasting at least ten minutes. Shorter observations remain in the raw series but do not count toward the table."),
                ],
                "table": {
                    "headers": ["Route", "Shipments", "Baseline", "Revised", "Longest revised"],
                    "rows": [
                        ["Aurora North", "6", "4", "1", "12 min"],
                        ["Lake Junction", "6", "3", "2", "18 min"],
                        ["Pine Ridge", "6", "5", "2", "16 min"],
                        ["Coastal Link", "6", "2", "0", "0 min"],
                    ],
                    "colWidths": [105, 70, 74, 70, 112],
                },
                "afterSections": [
                    ("Interpretation", "Pine Ridge recorded five baseline excursions and two revised-liner excursions. Coastal Link recorded no revised-liner excursion, but its shorter route prevents a direct claim that packaging alone caused the result."),
                    ("Handling review", "The Lake Junction revised excursions coincided with an extended depot handover. The event was retained because the door scans and logger sequence were complete."),
                ],
            },
            {
                "title": "4. Sensor-position analysis",
                "subtitle": "Raster-only chart with unique positions",
                "sections": [
                    ("Figure scope", "Figure 4 compares mean temperatures at four sensor positions used only in a supplemental test. The position names and values are not repeated in native text. Visual evaluation must read the plotted labels rather than infer a value from the primary outcome."),
                    ("Interpretation boundary", "The supplemental test used one instrumented load and is descriptive. It helps identify internal gradients but does not replace the twenty-four-shipment comparison."),
                ],
                "chart": {
                    "filename": "aurora-sensor-position-chart.png",
                    "title": "Supplemental mean temperature by sensor position",
                    "values": [["Lid pocket", 6.1], ["Core center", 4.3], ["Door edge", 5.8], ["Base corner", 4.7]],
                    "valueSuffix": " C",
                },
            },
            {
                "title": "5. Approval, limitations and negative evidence",
                "subtitle": "Version status, rejected criteria and citation rules",
                "layout": "columns",
                "sections": [
                    ("Approval", "Report version 1.0 was approved on 3 November 2026 by the Packaging Validation Lead. A September draft is not an approved source."),
                    ("Rejected setpoint", "The draft defined excursions above 6.5 degrees C for five minutes. Reviewers rejected that rule. The approved definition is above 7.0 degrees C for at least ten minutes."),
                    ("Limitations", "The sample supports route-specific comparison but not rare-failure rates, other foods or different container sizes."),
                    ("Unsupported requests", "No customer, price, delivery address, driver phone number or depot access code appears in this report."),
                    ("Citation rule", "Before-and-after answers cite pages 2 and 3; route counts cite page 4; sensor-position values cite the chart region on page 5."),
                ],
            },
        ],
    },
]

# Two additional families keep validation and holdout from depending on one authoring domain each.
# Their page templates deliberately differ from the operational-report template used above.
DIGITAL_DOCUMENTS.extend([
    {
        "source": "realistic-forest-study",
        "documentFamily": "meridian-forest-restoration-study",
        "version": "v1",
        "split": "validation",
        "language": "en",
        "filename": "realistic-meridian-forest-study-v1.pdf",
        "template": "paper",
        "pages": [
            {
                "title": "Meridian Forest Restoration Study",
                "subtitle": "MFR-2026-08 | paired-plot working paper",
                "layout": "columns",
                "sections": [
                    ("Abstract", "This paired-plot study estimates early canopy recovery after invasive shrub removal in the Meridian reserve. Forty plots were matched by slope, pre-treatment canopy and soil class; twenty received treatment and twenty remained comparison plots. The study identifier is MFR-2026-08. MFR-2026-80 is a nursery inventory and is outside this analysis."),
                    ("Primary question", "The prespecified outcome is absolute canopy-cover change after two growing seasons. Seedling density and soil moisture are secondary measures. Results describe this reserve and treatment protocol; they do not establish a universal restoration rate for other forests, climates or removal methods."),
                    ("Evidence hierarchy", "Calibrated hemispherical photographs supply canopy values. Field notes explain storms and access interruptions but cannot replace measurements. A value printed in the nursery appendix uses a different population. Answers must preserve plot group, season and unit rather than selecting a nearby percentage."),
                    ("Negative boundary", "The study contains no landowner names, volunteer phone numbers, gate codes, grant bank details or endangered-species coordinates. Those requests are unsupported even when related words occur in the paper."),
                ],
            },
            {
                "title": "1. Sampling and baseline",
                "subtitle": "Methods, exclusions and the first comparison value",
                "layout": "columns",
                "sections": [
                    ("Pair construction", "Candidate plots were divided into five-hectare blocks and matched before treatment assignment. Photographs were taken within forty minutes of solar noon using the same lens and leveling protocol. Four reserve-edge plots were excluded before assignment because road widening changed their light environment."),
                    ("Baseline canopy", "Mean canopy cover in the twenty treatment plots was 41.2 percent at baseline. The paired comparison plots averaged 41.5 percent. The primary before-and-after calculation for the treated group uses 41.2 percent, not the nearby comparison mean or the 42.1 percent value from a pilot calibration."),
                    ("Quality control", "Two analysts independently reviewed horizon masks. Disagreements exceeding two percentage points were reprocessed from the original image. All accepted photographs retained camera identifier, capture time and plot code; exported thumbnails were navigation aids only."),
                    ("Missing observations", "One second-season visit was delayed by flooding but completed inside the registered sampling window. No primary canopy image was imputed. Three missing soil-moisture readings affect only the secondary analysis."),
                ],
            },
            {
                "title": "2. Two-season outcomes",
                "subtitle": "Treatment result and robustness checks",
                "sections": [
                    ("Observed canopy", "After two growing seasons, mean canopy cover in the treatment plots was 55.7 percent. The absolute increase from the 41.2 percent baseline was 14.5 percentage points. This is an absolute point change; describing it as a 14.5 percent relative increase would be incorrect."),
                    ("Comparison plots", "Comparison plots increased from 41.5 to 46.0 percent during the same period. The study reports a paired treatment contrast of 10.0 percentage points after block adjustment. Natural recovery therefore explains part, but not all, of the treated change."),
                    ("Robustness", "Removing the two storm-damaged plots reduced the treated increase by 0.6 percentage points. An alternative segmentation model changed group means by less than 0.4 points. Neither analysis reversed the direction of the treatment contrast."),
                    ("Interpretation", "Early canopy recovery is consistent with reduced shrub competition, but two seasons are insufficient to assess mature structure, fire behavior or long-term species composition. Continued measurement is required before changing reserve-wide policy."),
                ],
            },
            {
                "title": "3. Plot-block summary",
                "subtitle": "Native-text table with block-level outcomes",
                "sections": [
                    ("Table definition", "Change is the absolute canopy-cover difference between baseline and season two. Seedlings are validated native-tree stems per hectare. Values belong to their block row and should not be combined across columns."),
                ],
                "table": {
                    "headers": ["Block", "Plots", "Canopy change", "Seedlings/ha", "Review flag"],
                    "rows": [
                        ["North Ridge", "8", "+13.8 points", "740", "complete"],
                        ["Fern Gully", "8", "+16.1 points", "920", "complete"],
                        ["Quartz Slope", "8", "+11.7 points", "610", "storm note"],
                        ["Creek Bend", "8", "+15.4 points", "880", "complete"],
                        ["West Spur", "8", "+15.5 points", "805", "complete"],
                    ],
                    "colWidths": [96, 55, 100, 90, 94],
                },
                "afterSections": [
                    ("Block reading", "Fern Gully recorded the largest canopy increase, 16.1 percentage points, and 920 validated seedlings per hectare. Quartz Slope has the smallest increase, but its storm note prevents attributing that difference to treatment alone."),
                ],
            },
            {
                "title": "4. Governance and limitations",
                "subtitle": "Approval state, rejected rule and abstention scope",
                "layout": "columns",
                "sections": [
                    ("Approval", "Working paper version 1.0 was approved for internal evaluation on 9 December 2026 by the Reserve Science Lead. Public-policy adoption requires a separate review and is not implied by this approval."),
                    ("Rejected rule", "A draft proposed success whenever canopy rose by 12 percent. Reviewers rejected that relative rule. The approved study reports absolute percentage-point change and retains the registered paired-plot analysis."),
                    ("Monitoring", "Plots will be photographed each spring for four additional years. A plot is retired only after a documented boundary change; poor outcomes are not a reason for removal."),
                    ("Limitations", "The reserve experienced one unusually wet season. Results do not estimate wildlife abundance, carbon-credit value, wildfire probability or neighboring property impacts."),
                    ("Citation", "Baseline and outcome answers cite pages 2 and 3. Block questions cite the table row on page 4. Unsupported personal or financial requests require abstention."),
                ],
            },
        ],
    },
    {
        "source": "realistic-museum-condition-memo",
        "documentFamily": "arcadia-museum-condition-memo",
        "version": "v1",
        "split": "holdout",
        "language": "mixed",
        "filename": "realistic-arcadia-condition-memo-v1.pdf",
        "template": "field_memo",
        "pages": [
            {
                "title": "Arcadia Textile Condition Memo",
                "subtitle": "ACM-26-041 | collection-care assessment",
                "sections": [
                    ("Object and purpose", "This memo records the pre-display condition of the woven panel catalogued as AT-1936-17. The assessment identifier is ACM-26-041 and the inspection occurred on 7 October 2026. AT-1963-17 is a photographic negative referenced in an older index and is not the object assessed here."),
                    ("Method", "Conservators examined the panel under diffuse and raking light, compared calibrated color targets and mapped distortions on a fixed grid. Observations describe condition; they do not authenticate authorship, assign market value or authorize treatment."),
                    ("Overall finding", "The panel is structurally stable enough for a limited display if light exposure and mounting controls are followed. Localized edge weakness requires support, while the central woven field shows no active fiber loss."),
                    ("Privacy boundary", "No donor address, insurer account, alarm code, staff private number or purchase price appears. Those questions require a no-answer result, not an inference from catalog context."),
                ],
            },
            {
                "title": "Observed condition register",
                "subtitle": "Location-coded findings and treatment priority",
                "sections": [
                    ("Register scope", "Locations use the memo grid, not compass directions in the gallery. Priority describes conservation scheduling and is not a monetary or safety rating."),
                ],
                "table": {
                    "headers": ["Grid", "Finding", "Extent", "Priority", "Proposed support"],
                    "rows": [
                        ["A2", "edge split", "38 mm", "high", "stitched backing"],
                        ["B4", "creased weft", "62 mm", "medium", "humidification"],
                        ["C3", "old stain", "21 mm", "low", "monitor only"],
                        ["D1", "loose fringe", "44 mm", "medium", "net overlay"],
                    ],
                    "colWidths": [54, 98, 66, 66, 148],
                },
                "afterSections": [
                    ("Priority reading", "Grid A2 contains a 38 mm edge split and is the only high-priority finding. The 62 mm crease at B4 is longer but remains medium priority because fibers are not broken."),
                    ("Excluded mark", "A 73 mm pencil line belongs to the temporary photography board and is not part of the textile. It was recorded so image-based searches do not confuse it with damage extent."),
                ],
            },
            {
                "title": "Display exposure decision",
                "subtitle": "Cumulative limit, review cadence and nearby values",
                "layout": "columns",
                "sections": [
                    ("Approved light limit", "Cumulative visible-light exposure must not exceed 12,000 lux-hours during the display period. Gallery staff record the meter total each opening day. The nearby 1,200 lux-hour value is a weekly training exercise and is not the object limit."),
                    ("Review trigger", "A conservator reviews the display when cumulative exposure reaches 9,000 lux-hours or if the A2 split grows by 3 mm, whichever occurs first. A single meter reading does not represent cumulative exposure."),
                    ("Display length", "The planned display runs for eight weeks, subject to the cumulative limit. Closing days still count in the calendar duration but add no exposure when the case lighting remains off."),
                    ("Responsibilities", "Gallery technicians record meter values; the Textile Conservator approves any schedule change. Registration staff may update location records but cannot increase the exposure limit."),
                    ("Instrument check", "The logger identifier is LX-442 and its calibration expires on 28 February 2027. LX-424 is the retired device shown in a procurement note."),
                ],
            },
            {
                "title": "Mounting and transport controls",
                "subtitle": "Sequence, roles and bilingual handling note",
                "sections": [
                    ("Mount sequence", "Attach the stitched backing before the panel is lifted from the examination board. Place the backed panel on the rigid carrier, secure the upper edge first and then close the remaining tabs without tensioning the original textile."),
                    ("Transport environment", "The approved transport range is 18 to 22 degrees C and 45 to 55 percent relative humidity. A loading-bay sensor may briefly read outside this range; the case logger determines whether the object environment remained compliant."),
                    ("中文操作说明", "搬运前必须确认 A2 区域的衬背已经固定，并由纺织品保护员签字。登记人员可以核对藏品编号，但不能替代保护员批准。正式藏品编号为 AT-1936-17。"),
                    ("Incident rule", "If a tab opens or the carrier tilts beyond ten degrees, stop movement and return the carrier to the examination stand. Do not continue merely because no new split is visible."),
                ],
            },
            {
                "title": "Authorization and evidence limits",
                "subtitle": "Controlled revision and unsupported requests",
                "layout": "columns",
                "sections": [
                    ("Authorization", "Memo revision 1.1 was authorized on 15 October 2026 by the Head of Conservation. A 10 October working copy lacked the A2 support requirement and is superseded."),
                    ("Completion", "The stitched backing is due by 24 October 2026. The 20 October date in the courier schedule is a crate-delivery milestone, not treatment completion."),
                    ("Evidence package", "Closure requires the signed condition map, backing photograph, final light-meter plan and registrar location update. An email summary alone is incomplete."),
                    ("Limitations", "This memo does not establish provenance, authenticity, financial value, legal ownership or long-term color stability."),
                    ("Citation", "Condition answers cite the register, exposure answers cite page 3 and authorization answers cite this controlled revision."),
                ],
            },
        ],
    },
])


SCANNED_DOCUMENT = {
    "source": "realistic-rail-scan",
    "documentFamily": "metro-rail-inspection-scan",
    "version": "v1",
    "split": "guard_visual_ocr",
    "language": "mixed",
    "filename": "realistic-metro-rail-inspection-scan-v1.pdf",
    "pages": [
        {
            "title": "METRO RAIL INSPECTION REVIEW",
            "subtitle": "MRI-2026-31 | image-only field report",
            "sections": [
                ("Scope", "The inspection covered the East Loop between chainage 14.2 km and 19.8 km on 16 August 2026. Teams reconciled ultrasonic readings, photographs and work orders before accepting a defect. The report identifier is MRI-2026-31; MRI-2026-13 is a training form."),
                ("Method", "Each suspect indication was measured twice by different operators. Readings were retained with equipment identity, direction of travel and local rail temperature. A photograph alone did not establish depth."),
                ("Boundary", "This synthetic report contains no passenger records, employee phone numbers, access codes or live infrastructure details. It is designed only for OCR and retrieval evaluation."),
            ],
        },
        {
            "title": "1. EAST LOOP FINDINGS",
            "subtitle": "nearby dimensions and decision thresholds",
            "sections": [
                ("Verified indication", "At chainage 17.6 km, the verified surface indication measured 23 mm in length. The nearby 32 mm value belongs to a calibration notch and is not a track defect."),
                ("Review threshold", "Manual engineering review is required when a verified indication reaches 20 mm or when growth exceeds 4 mm between inspections. A single noisy trace does not satisfy either condition."),
                ("Action", "The 23 mm indication was entered into the engineering queue and protected by an interim speed restriction. The report does not state that the rail failed."),
            ],
        },
        {
            "title": "2. 中文复核记录",
            "subtitle": "扫描中文段落与相近编号",
            "sections": [
                ("区段信息", "中文复核记录的区段编号为 CN-RAIL-74，检查范围对应东环线 17.6 公里附近。培训页中的 CN-RAIL-47 是演示编号，不能作为正式引用。"),
                ("处置条件", "当裂纹长度达到 20 毫米或两次检查增长超过 4 毫米时进入工程复核。一次信号异常只保留观察记录。"),
                ("复查时间", "下一次现场复查安排在 2026 年 8 月 23 日 05:40，由轨道完整性组负责。8 月 22 日是设备校准日期，不是现场复查时间。"),
            ],
        },
        {
            "title": "3. DEFECT REGISTER",
            "subtitle": "scanned table and row relationships",
            "sections": [
                ("Table", "Location | Length | Change | Status | Owner\nEL-17.6 | 23 mm | +5 mm | ENGINEERING REVIEW | Integrity Team\nEL-16.1 | 12 mm | +1 mm | MONITOR | Night Inspection\nEL-18.4 | 8 mm | NEW | VERIFY | Ultrasonic Team\nEL-15.3 | none | 0 mm | CLOSED | Integrity Team"),
                ("Reading note", "The status belongs to the same row as the location. EL-17.6 is the only row assigned to ENGINEERING REVIEW."),
            ],
        },
        {
            "title": "4. ACTION TRACKING",
            "subtitle": "deadlines, owners and misleading dates",
            "sections": [
                ("Immediate control", "The interim restriction remains until engineering signs the disposition. Operations can apply the restriction but cannot close the defect."),
                ("Completion deadline", "The engineering disposition is due by 18:00 on 20 August 2026. The maintenance planning meeting on 19 August is not the disposition deadline."),
                ("Evidence package", "The final package requires both ultrasonic traces, measurement photographs and the signed disposition. A thumbnail image or unsigned worksheet is incomplete evidence."),
            ],
        },
        {
            "title": "5. LIMITATIONS AND NO-ANSWER CONTROL",
            "subtitle": "scanned negative evidence",
            "sections": [
                ("Limitations", "The review covers one route segment and cannot estimate system-wide defect rates. Weather and rail temperature differ from the previous inspection."),
                ("Rejected draft", "A draft note proposed a 25 mm review threshold. Engineering rejected it; the approved threshold remains 20 mm."),
                ("Unsupported requests", "No passenger count, ticket revenue, staff phone number, depot password or residential address is present. The correct response to those questions is no answer."),
            ],
        },
    ],
}


ADDITIONAL_FACTS = [
    {"anchorId": "water-audit-id", "source": "realistic-water-audit", "version": "v1", "page": 1, "modality": "text", "goldMatch": "CWA-2026-09", "queries": ["滨海供水审计编号是什么？", "What is the coastal water audit identifier?"]},
    {"anchorId": "water-period", "source": "realistic-water-audit", "version": "v1", "page": 1, "modality": "text", "goldMatch": "2026 年 1 月 8 日至 6 月 30 日", "queries": ["供水审计观察期是什么时候？", "What observation period did the water audit cover?"]},
    {"anchorId": "water-baseline", "source": "realistic-water-audit", "version": "v1", "page": 2, "modality": "text", "goldMatch": "日均漏损量为 6.8 兆升", "queries": ["改造前全网标准化日均漏损量是多少？", "What was baseline standardized daily water loss?"]},
    {"anchorId": "water-post", "source": "realistic-water-audit", "version": "v1", "page": 3, "modality": "text", "goldMatch": "日均漏损量为 5.1 兆升", "queries": ["改造后全网标准化日均漏损量是多少？", "What was post-upgrade standardized daily water loss?"]},
    {"anchorId": "water-zone-c-flow", "source": "realistic-water-audit", "version": "v1", "page": 4, "modality": "table", "goldMatch": "2.3 ML/d", "queries": ["南渠 C 的夜间最小流量是多少？", "What minimum night flow is listed for South Canal C?"]},
    {"anchorId": "water-deadline", "source": "realistic-water-audit", "version": "v1", "page": 5, "modality": "text", "goldMatch": "2026 年 12 月 18 日前", "queries": ["阀门标签更换的最终期限是什么？", "What is the final valve-label replacement deadline?"]},
    {"anchorId": "water-threshold", "source": "realistic-water-audit", "version": "v1", "page": 5, "modality": "text", "goldMatch": "连续四个 15 分钟区间", "queries": ["夜间流量在什么条件下启动人工复核？", "What sustained condition triggers manual water-flow review?"]},
    {"anchorId": "water-approved", "source": "realistic-water-audit", "version": "v1", "page": 6, "modality": "text", "goldMatch": "2026 年 8 月 14 日批准", "queries": ["供水审计 1.0 版何时批准？", "When was the water audit version 1.0 approved?"]},
    {"anchorId": "solar-model", "source": "realistic-solar-manual", "version": "v1", "page": 1, "modality": "text", "goldMatch": "HX-48", "queries": ["Which inverter model does the manual cover?", "手册适用的逆变器型号是什么？"]},
    {"anchorId": "solar-edition", "source": "realistic-solar-manual", "version": "v1", "page": 1, "modality": "text", "goldMatch": "Edition 1.3 was approved on 22 September 2026", "queries": ["When was edition 1.3 approved?", "手册 1.3 版何时批准？"]},
    {"anchorId": "solar-wait", "source": "realistic-solar-manual", "version": "v1", "page": 2, "modality": "text", "goldMatch": "wait 7 minutes", "queries": ["How long must a technician wait before removing the service cover?", "拆下维修盖板前必须等待多久？"]},
    {"anchorId": "solar-torque", "source": "realistic-solar-manual", "version": "v1", "page": 3, "modality": "table", "goldMatch": "6.2 N m", "queries": ["What torque is specified for the HX-48 DC terminal?", "HX-48 直流端子的规定扭矩是多少？"]},
    {"anchorId": "solar-current", "source": "realistic-solar-manual", "version": "v1", "page": 3, "modality": "text", "goldMatch": "maximum continuous AC current is 69 A", "queries": ["What is the maximum continuous AC current?", "最大连续交流电流是多少？"]},
    {"anchorId": "solar-visual-route", "source": "realistic-solar-manual", "version": "v1", "page": 4, "modality": "visual_flow", "goldMatch": "INSULATION TEST->GRID SYNC", "queries": ["Which stage follows INSULATION TEST in the diagram?", "流程图中绝缘测试之后是什么阶段？"]},
    {"anchorId": "solar-firmware", "source": "realistic-solar-manual", "version": "v1", "page": 5, "modality": "text", "goldMatch": "最低固件版本为 4.7.2", "queries": ["批准的最低固件版本是什么？", "What is the minimum approved firmware version?"]},
    {"anchorId": "solar-temperature", "source": "realistic-solar-manual", "version": "v1", "page": 5, "modality": "text", "goldMatch": "连续三个采样周期达到或超过 82 摄氏度", "queries": ["散热器温度在什么条件下进入人工复核？", "What temperature condition triggers manual review?"]},
    {"anchorId": "cold-id", "source": "realistic-cold-chain-report", "version": "v1", "page": 1, "modality": "text", "goldMatch": "ACV-26-114", "queries": ["What is the cold-chain validation identifier?", "冷链验证报告编号是什么？"]},
    {"anchorId": "cold-sample", "source": "realistic-cold-chain-report", "version": "v1", "page": 1, "modality": "text", "goldMatch": "Twenty-four monitored shipments", "queries": ["How many monitored shipments were included?", "验证包含多少次受监测运输？"]},
    {"anchorId": "cold-baseline", "source": "realistic-cold-chain-report", "version": "v1", "page": 2, "modality": "text", "goldMatch": "mean internal temperature was 5.6 degrees C", "queries": ["What was the baseline mean internal temperature?", "基线平均内部温度是多少？"]},
    {"anchorId": "cold-post", "source": "realistic-cold-chain-report", "version": "v1", "page": 3, "modality": "text", "goldMatch": "mean internal temperature was 4.8 degrees C", "queries": ["What was the revised-liner mean temperature?", "改进内衬的平均内部温度是多少？"]},
    {"anchorId": "cold-pine-excursions", "source": "realistic-cold-chain-report", "version": "v1", "page": 4, "modality": "table", "goldMatch": "Pine Ridge recorded five baseline excursions and two revised-liner excursions", "queries": ["How many baseline and revised excursions did Pine Ridge record?", "Pine Ridge 的基线和改进内衬超温次数分别是多少？"]},
    {"anchorId": "cold-visual-core", "source": "realistic-cold-chain-report", "version": "v1", "page": 5, "modality": "visual_chart", "goldMatch": "Core center|4.3 C", "queries": ["What mean temperature does the chart show at Core center?", "图表中 Core center 的平均温度是多少？"]},
    {"anchorId": "cold-definition", "source": "realistic-cold-chain-report", "version": "v1", "page": 4, "modality": "text", "goldMatch": "above 7.0 degrees C lasting at least ten minutes", "queries": ["How does the approved report define an excursion?", "批准报告如何定义超温事件？"]},
    {"anchorId": "cold-approval", "source": "realistic-cold-chain-report", "version": "v1", "page": 6, "modality": "text", "goldMatch": "approved on 3 November 2026", "queries": ["When was the cold-chain report approved?", "冷链报告何时批准？"]},
    {"anchorId": "rail-id", "source": "realistic-rail-scan", "version": "v1", "page": 1, "modality": "ocr", "goldMatch": "MRI-2026-31", "queries": ["What is the scanned rail report identifier?", "扫描轨道报告的编号是什么？"]},
    {"anchorId": "rail-date", "source": "realistic-rail-scan", "version": "v1", "page": 1, "modality": "ocr", "goldMatch": "16 August 2026", "queries": ["When was the East Loop inspected?", "东环线何时接受检查？"]},
    {"anchorId": "rail-length", "source": "realistic-rail-scan", "version": "v1", "page": 2, "modality": "ocr", "goldMatch": "23 mm in length", "queries": ["What was the verified indication length at 17.6 km?", "17.6 公里处的验证缺陷长度是多少？"]},
    {"anchorId": "rail-threshold", "source": "realistic-rail-scan", "version": "v1", "page": 2, "modality": "ocr", "goldMatch": "reaches 20 mm", "queries": ["What length triggers manual engineering review?", "达到多少毫米会启动人工工程复核？"]},
    {"anchorId": "rail-cn-id", "source": "realistic-rail-scan", "version": "v1", "page": 3, "modality": "ocr", "goldMatch": "CN-RAIL-74", "queries": ["中文复核记录的区段编号是什么？", "What section identifier appears in the Chinese scan?"]},
    {"anchorId": "rail-recheck", "source": "realistic-rail-scan", "version": "v1", "page": 3, "modality": "ocr", "goldMatch": "2026 年 8 月 23 日 05:40", "queries": ["下一次现场复查安排在什么时候？", "When is the next field reinspection scheduled?"]},
    {"anchorId": "rail-row-status", "source": "realistic-rail-scan", "version": "v1", "page": 4, "modality": "ocr_table", "goldMatch": "EL-17.6 | 23 mm | +5 mm | ENGINEERING REVIEW", "queries": ["What status is assigned to EL-17.6?", "EL-17.6 的状态是什么？"]},
    {"anchorId": "rail-deadline", "source": "realistic-rail-scan", "version": "v1", "page": 5, "modality": "ocr", "goldMatch": "18:00 on 20 August 2026", "queries": ["When is the engineering disposition due?", "工程处置的截止时间是什么？"]},
    {"anchorId": "forest-id", "source": "realistic-forest-study", "version": "v1", "page": 1, "modality": "text", "goldMatch": "MFR-2026-08", "queries": ["What is the forest study identifier?", "森林恢复研究的编号是什么？"]},
    {"anchorId": "forest-sample", "source": "realistic-forest-study", "version": "v1", "page": 1, "modality": "text", "goldMatch": "Forty plots", "queries": ["How many plots were included in the study?", "研究一共包含多少个样地？"]},
    {"anchorId": "forest-baseline", "source": "realistic-forest-study", "version": "v1", "page": 2, "modality": "text", "goldMatch": "41.2 percent at baseline", "queries": ["What was baseline canopy cover in treatment plots?", "处理组样地的基线冠层覆盖率是多少？"]},
    {"anchorId": "forest-outcome", "source": "realistic-forest-study", "version": "v1", "page": 3, "modality": "text", "goldMatch": "55.7 percent", "queries": ["What was treatment-plot canopy cover after two seasons?", "两个生长季后处理组冠层覆盖率是多少？"]},
    {"anchorId": "forest-change", "source": "realistic-forest-study", "version": "v1", "page": 3, "modality": "text", "goldMatch": "14.5 percentage points", "queries": ["What absolute canopy increase did treatment plots show?", "处理组冠层覆盖率绝对增加了多少个百分点？"]},
    {"anchorId": "forest-block", "source": "realistic-forest-study", "version": "v1", "page": 4, "modality": "table", "goldMatch": "Fern Gully recorded the largest canopy increase, 16.1 percentage points", "queries": ["Which block had the largest canopy increase?", "哪个区块的冠层增长最大？"]},
    {"anchorId": "forest-approval", "source": "realistic-forest-study", "version": "v1", "page": 5, "modality": "text", "goldMatch": "9 December 2026", "queries": ["When was working paper version 1.0 approved?", "工作论文 1.0 版何时批准？"]},
    {"anchorId": "forest-rejected-rule", "source": "realistic-forest-study", "version": "v1", "page": 5, "modality": "text", "goldMatch": "reviewers rejected that relative rule", "queries": ["Was the draft 12 percent success rule approved?", "草案中的 12% 成功规则是否获批？"]},
    {"anchorId": "museum-id", "source": "realistic-museum-condition-memo", "version": "v1", "page": 1, "modality": "text", "goldMatch": "ACM-26-041", "queries": ["What is the condition memo identifier?", "藏品状况备忘录编号是什么？"]},
    {"anchorId": "museum-object", "source": "realistic-museum-condition-memo", "version": "v1", "page": 1, "modality": "text", "goldMatch": "AT-1936-17", "queries": ["What is the assessed textile catalogue number?", "接受评估的纺织品藏品编号是什么？"]},
    {"anchorId": "museum-a2", "source": "realistic-museum-condition-memo", "version": "v1", "page": 2, "modality": "table", "goldMatch": "Grid A2 contains a 38 mm edge split", "queries": ["What finding and extent are recorded at grid A2?", "A2 网格记录了什么状况及长度？"]},
    {"anchorId": "museum-light-limit", "source": "realistic-museum-condition-memo", "version": "v1", "page": 3, "modality": "text", "goldMatch": "12,000 lux-hours", "queries": ["What is the cumulative visible-light limit?", "累计可见光暴露上限是多少？"]},
    {"anchorId": "museum-review-trigger", "source": "realistic-museum-condition-memo", "version": "v1", "page": 3, "modality": "text", "goldMatch": "reaches 9,000 lux-hours", "queries": ["At what cumulative exposure is conservation review triggered?", "累计曝光达到多少时启动保护复核？"]},
    {"anchorId": "museum-environment", "source": "realistic-museum-condition-memo", "version": "v1", "page": 4, "modality": "text", "goldMatch": "18 to 22 degrees C and 45 to 55 percent relative humidity", "queries": ["What transport temperature and humidity range is approved?", "批准的运输温湿度范围是什么？"]},
    {"anchorId": "museum-authorization", "source": "realistic-museum-condition-memo", "version": "v1", "page": 5, "modality": "text", "goldMatch": "authorized on 15 October 2026", "queries": ["When was memo revision 1.1 authorized?", "备忘录 1.1 修订版何时获批？"]},
    {"anchorId": "museum-deadline", "source": "realistic-museum-condition-memo", "version": "v1", "page": 5, "modality": "text", "goldMatch": "due by 24 October 2026", "queries": ["When is the stitched backing due?", "缝制衬背的完成期限是什么？"]},
]


ADDITIONAL_MULTI_CASES = [
    {
        "category": "multi_evidence",
        "split": "development",
        "sourceVersion": "realistic-water-audit:v1",
        "anchorIds": ["water-baseline", "water-post"],
        "groupId": "before_after",
        "expectedPages": [2, 3],
        "queries": [("zh", "改造前后全网日均漏损量分别是多少，下降了多少？"),
                    ("en", "What were daily water losses before and after the upgrade, and how much did they fall?")],
    },
    {
        "category": "multi_evidence",
        "split": "validation",
        "sourceVersion": "realistic-solar-manual:v1",
        "anchorIds": ["solar-wait", "solar-torque"],
        "groupId": "safe_service_values",
        "expectedPages": [2, 3],
        "queries": [("en", "For HX-48 service, how long is the cover wait and what DC terminal torque is required?"),
                    ("zh", "维修 HX-48 时，盖板等待时间和直流端子扭矩分别是多少？")],
    },
    {
        "category": "multi_evidence",
        "split": "holdout",
        "sourceVersion": "realistic-cold-chain-report:v1",
        "anchorIds": ["cold-baseline", "cold-post"],
        "groupId": "before_after",
        "expectedPages": [2, 3],
        "queries": [("en", "What were baseline and revised-liner mean temperatures, and what was the reduction?"),
                    ("zh", "基线和改进内衬平均温度分别是多少，降低了多少？")],
    },
    {
        "category": "multi_evidence",
        "split": "validation",
        "sourceVersion": "realistic-forest-study:v1",
        "anchorIds": ["forest-baseline", "forest-outcome"],
        "groupId": "before_after",
        "expectedPages": [2, 3],
        "queries": [("en", "What were treatment canopy cover before and after restoration, and what was the absolute change?"),
                    ("zh", "恢复处理前后冠层覆盖率分别是多少，绝对变化是多少？")],
    },
    {
        "category": "multi_evidence",
        "split": "holdout",
        "sourceVersion": "realistic-museum-condition-memo:v1",
        "anchorIds": ["museum-a2", "museum-light-limit"],
        "groupId": "condition_and_limit",
        "expectedPages": [2, 3],
        "queries": [("en", "What damage is recorded at A2 and what cumulative light limit applies?"),
                    ("zh", "A2 区域记录了什么损伤，累计光照上限是多少？")],
    },
]


# No-answer cases are assigned to every realistic family so abstention is not learned from one guide.
ADDITIONAL_NO_ANSWER_CASES = [
    {"sourceVersion": "realistic-harbor-report:v1", "query": "What employee private phone number appears in the harbor report?"},
    {"sourceVersion": "realistic-water-audit:v1", "query": "供水审计中居民的缴费账号是什么？"},
    {"sourceVersion": "realistic-solar-manual:v1", "query": "What installer password does the HX-48 manual provide?"},
    {"sourceVersion": "realistic-cold-chain-report:v1", "query": "冷链报告中的客户送货地址是什么？"},
    {"sourceVersion": "realistic-forest-study:v1", "query": "What volunteer phone number is listed in the forest study?"},
    {"sourceVersion": "realistic-museum-condition-memo:v1", "query": "藏品状况备忘录中的保险账户是什么？"},
    {"sourceVersion": "realistic-rail-scan:v1", "query": "What depot password is present in the scanned rail report?"},
]
