# 离线评测报告：post-upgrade-v2

- 开始时间：2026-09-17T07:49:34.504633900Z
- 结束时间：2026-09-17T07:54:15.971739700Z
- 用例数：77

## 指标总览

| 指标 | 本次（post-upgrade-v2） | 基线（baseline） |
|---|---|---|
| 可答题数 / 拒答题数 | 67 / 10 | 未采集 |
| Hit@5（仅可答题） | 100.0% | 未采集 |
| 引用准确率（仅可答题） | 100.0% | 未采集 |
| 拒答正确率（文档里没有答案时） | 100.0% | 未采集 |
| 答案相关性（0-3，评 2 次取均值） | 2.955 | 未采集 |
| P95 延迟（ms） | 4598 | 未采集 |
| 平均单次成本（微元） | 10886 | 改造前无计量能力 |
| 模型调用次数（含打分） | 231 | 未采集 |
| 降级次数 | 0 | 未采集 |

> 基线说明：`eval/reports/eval-report-baseline.json` 不存在。
> 按执行文档要求，基线应在动手改造前用同一份评测集跑出来；
> 若要补跑：切到改造前的 commit，用同一份评测集与同一模型运行本跑批，tag 用 baseline。

## 逐条结果

| 用例 | 空间 | 类型 | Hit@5 | 引用命中 | 相关性 | 延迟(ms) |
|---|---|---|---|---|---|---|
| hb-001 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2877 |
| hb-002 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1608 |
| hb-003 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1347 |
| hb-004 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1564 |
| hb-005 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1316 |
| hb-006 | eval-handbook | 问答 | PASS | PASS | 3.0 | 910 |
| hb-007 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1189 |
| hb-008 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2079 |
| hb-009 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1282 |
| hb-010 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2005 |
| hb-011 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2676 |
| hb-012 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2877 |
| hb-013 | eval-handbook | 问答 | PASS | PASS | 2.0 | 2934 |
| hb-014 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1924 |
| hb-015 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1182 |
| hb-016 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2229 |
| hb-017 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1660 |
| hb-018 | eval-handbook | 拒答 | — | — | 3.0 | 1777 |
| hb-019 | eval-handbook | 拒答 | — | — | 3.0 | 1879 |
| hb-020 | eval-handbook | 问答 | PASS | PASS | 3.0 | 975 |
| hb-021 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2566 |
| rel-001 | eval-release | 问答 | PASS | PASS | 3.0 | 1237 |
| rel-002 | eval-release | 问答 | PASS | PASS | 3.0 | 886 |
| rel-003 | eval-release | 问答 | PASS | PASS | 3.0 | 1734 |
| rel-004 | eval-release | 问答 | PASS | PASS | 3.0 | 1523 |
| rel-005 | eval-release | 问答 | PASS | PASS | 3.0 | 1091 |
| rel-006 | eval-release | 问答 | PASS | PASS | 3.0 | 1124 |
| rel-007 | eval-release | 问答 | PASS | PASS | 3.0 | 1635 |
| rel-008 | eval-release | 问答 | PASS | PASS | 3.0 | 2647 |
| rel-009 | eval-release | 问答 | PASS | PASS | 2.0 | 1521 |
| rel-010 | eval-release | 问答 | PASS | PASS | 3.0 | 1566 |
| rel-011 | eval-release | 问答 | PASS | PASS | 3.0 | 1649 |
| rel-012 | eval-release | 问答 | PASS | PASS | 3.0 | 2678 |
| rel-013 | eval-release | 拒答 | — | — | 3.0 | 1476 |
| rel-014 | eval-release | 问答 | PASS | PASS | 3.0 | 1454 |
| rel-015 | eval-release | 问答 | PASS | PASS | 3.0 | 1037 |
| trav-001 | eval-travel | 问答 | PASS | PASS | 3.0 | 1372 |
| trav-002 | eval-travel | 问答 | PASS | PASS | 3.0 | 1897 |
| trav-003 | eval-travel | 问答 | PASS | PASS | 3.0 | 774 |
| trav-004 | eval-travel | 问答 | PASS | PASS | 3.0 | 1903 |
| trav-005 | eval-travel | 问答 | PASS | PASS | 3.0 | 5268 |
| trav-006 | eval-travel | 问答 | PASS | PASS | 3.0 | 1854 |
| trav-007 | eval-travel | 问答 | PASS | PASS | 3.0 | 2059 |
| trav-008 | eval-travel | 问答 | PASS | PASS | 3.0 | 1958 |
| trav-009 | eval-travel | 问答 | PASS | PASS | 3.0 | 2176 |
| trav-010 | eval-travel | 问答 | PASS | PASS | 3.0 | 3913 |
| trav-011 | eval-travel | 问答 | PASS | PASS | 3.0 | 4598 |
| trav-012 | eval-travel | 问答 | PASS | PASS | 3.0 | 2086 |
| trav-013 | eval-travel | 问答 | PASS | PASS | 3.0 | 2126 |
| trav-014 | eval-travel | 问答 | PASS | PASS | 3.0 | 851 |
| trav-015 | eval-travel | 问答 | PASS | PASS | 3.0 | 2155 |
| trav-016 | eval-travel | 问答 | PASS | PASS | 3.0 | 6611 |
| trav-017 | eval-travel | 问答 | PASS | PASS | 3.0 | 3783 |
| trav-018 | eval-travel | 问答 | PASS | PASS | 3.0 | 3730 |
| trav-019 | eval-travel | 问答 | PASS | PASS | 3.0 | 4435 |
| trav-020 | eval-travel | 问答 | PASS | PASS | 3.0 | 4534 |
| trav-021 | eval-travel | 拒答 | — | — | 3.0 | 1443 |
| trav-022 | eval-travel | 问答 | PASS | PASS | 3.0 | 1004 |
| trav-023 | eval-travel | 问答 | PASS | PASS | 3.0 | 778 |
| trav-024 | eval-travel | 问答 | PASS | PASS | 3.0 | 2361 |
| hb-022 | eval-handbook | 拒答 | — | — | 3.0 | 1007 |
| rel-016 | eval-release | 拒答 | — | — | 3.0 | 1321 |
| rel-017 | eval-release | 拒答 | — | — | 3.0 | 1541 |
| trav-025 | eval-travel | 拒答 | — | — | 3.0 | 935 |
| trav-026 | eval-travel | 拒答 | — | — | 3.0 | 1650 |
| cs-001 | eval-handbook | 问答 | PASS | PASS | 3.0 | 3380 |
| cs-002 | eval-travel | 问答 | PASS | PASS | 3.0 | 1255 |
| hard-001 | eval-travel | 问答 | PASS | PASS | 3.0 | 4511 |
| hard-002 | eval-travel | 问答 | PASS | PASS | 1.5 | 4146 |
| hard-003 | eval-travel | 问答 | PASS | PASS | 3.0 | 3325 |
| hard-004 | eval-release-v2 | 问答 | PASS | PASS | 3.0 | 2521 |
| hard-005 | eval-release-v2 | 问答 | PASS | PASS | 3.0 | 3241 |
| hard-006 | eval-release-v2 | 问答 | PASS | PASS | 3.0 | 2094 |
| hard-007 | eval-release-v2 | 问答 | PASS | PASS | 3.0 | 3433 |
| hard-008 | eval-release-v2 | 拒答 | — | — | 3.0 | 5627 |
| hard-009 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1925 |
| hard-010 | eval-travel | 问答 | PASS | PASS | 3.0 | 3507 |

> 拒答题（文档里没有答案）共 10 条，其中 10 条正确拒答。这类用例没有期望文档，**不计入 Hit@5 与引用准确率的分母**。
