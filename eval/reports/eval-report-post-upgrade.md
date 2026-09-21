# 离线评测报告：post-upgrade

- 开始时间：2026-09-16T14:50:29.561398100Z
- 结束时间：2026-09-16T14:54:01.837333500Z
- 用例数：67

## 指标总览

| 指标 | 本次（post-upgrade） | 基线（baseline） |
|---|---|---|
| 可答题数 / 拒答题数 | 58 / 9 | 未采集 |
| Hit@5（仅可答题） | 100.0% | 未采集 |
| 引用准确率（仅可答题） | 100.0% | 未采集 |
| 拒答正确率（文档里没有答案时） | 100.0% | 未采集 |
| 答案相关性（0-3，评 2 次取均值） | 2.955 | 未采集 |
| P95 延迟（ms） | 4077 | 未采集 |
| 平均单次成本（微元） | 10068 | 改造前无计量能力 |
| 模型调用次数（含打分） | 201 | 未采集 |
| 降级次数 | 0 | 未采集 |

> 基线说明：`eval/reports/eval-report-baseline.json` 不存在。
> 按执行文档要求，基线应在动手改造前用同一份评测集跑出来；
> 若要补跑：切到改造前的 commit，用同一份评测集与同一模型运行本跑批，tag 用 baseline。

## 逐条结果

| 用例 | 空间 | 类型 | Hit@5 | 引用命中 | 相关性 | 延迟(ms) |
|---|---|---|---|---|---|---|
| hb-001 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2195 |
| hb-002 | eval-handbook | 问答 | PASS | PASS | 3.0 | 846 |
| hb-003 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1122 |
| hb-004 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1205 |
| hb-005 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1620 |
| hb-006 | eval-handbook | 问答 | PASS | PASS | 3.0 | 882 |
| hb-007 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2113 |
| hb-008 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1460 |
| hb-009 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1235 |
| hb-010 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1626 |
| hb-011 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1173 |
| hb-012 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2096 |
| hb-013 | eval-handbook | 问答 | PASS | PASS | 2.0 | 1901 |
| hb-014 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2280 |
| hb-015 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1192 |
| hb-016 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1533 |
| hb-017 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1544 |
| hb-018 | eval-handbook | 拒答 | — | — | 3.0 | 1254 |
| hb-019 | eval-handbook | 拒答 | — | — | 3.0 | 1030 |
| hb-020 | eval-handbook | 问答 | PASS | PASS | 3.0 | 1082 |
| hb-021 | eval-handbook | 问答 | PASS | PASS | 3.0 | 2082 |
| rel-001 | eval-release | 问答 | PASS | PASS | 3.0 | 795 |
| rel-002 | eval-release | 问答 | PASS | PASS | 3.0 | 1715 |
| rel-003 | eval-release | 问答 | PASS | PASS | 3.0 | 859 |
| rel-004 | eval-release | 问答 | PASS | PASS | 3.0 | 1519 |
| rel-005 | eval-release | 问答 | PASS | PASS | 3.0 | 1959 |
| rel-006 | eval-release | 问答 | PASS | PASS | 3.0 | 1594 |
| rel-007 | eval-release | 问答 | PASS | PASS | 3.0 | 635 |
| rel-008 | eval-release | 问答 | PASS | PASS | 3.0 | 1715 |
| rel-009 | eval-release | 问答 | PASS | PASS | 2.0 | 1736 |
| rel-010 | eval-release | 问答 | PASS | PASS | 3.0 | 1590 |
| rel-011 | eval-release | 问答 | PASS | PASS | 3.0 | 1354 |
| rel-012 | eval-release | 问答 | PASS | PASS | 3.0 | 2214 |
| rel-013 | eval-release | 拒答 | — | — | 3.0 | 1073 |
| rel-014 | eval-release | 问答 | PASS | PASS | 3.0 | 936 |
| rel-015 | eval-release | 问答 | PASS | PASS | 3.0 | 1627 |
| trav-001 | eval-travel | 问答 | PASS | PASS | 3.0 | 1579 |
| trav-002 | eval-travel | 问答 | PASS | PASS | 3.0 | 1368 |
| trav-003 | eval-travel | 问答 | PASS | PASS | 3.0 | 874 |
| trav-004 | eval-travel | 问答 | PASS | PASS | 3.0 | 1609 |
| trav-005 | eval-travel | 问答 | PASS | PASS | 3.0 | 2526 |
| trav-006 | eval-travel | 问答 | PASS | PASS | 2.0 | 1412 |
| trav-007 | eval-travel | 问答 | PASS | PASS | 3.0 | 2630 |
| trav-008 | eval-travel | 问答 | PASS | PASS | 3.0 | 2159 |
| trav-009 | eval-travel | 问答 | PASS | PASS | 3.0 | 1841 |
| trav-010 | eval-travel | 问答 | PASS | PASS | 3.0 | 4427 |
| trav-011 | eval-travel | 问答 | PASS | PASS | 3.0 | 2292 |
| trav-012 | eval-travel | 问答 | PASS | PASS | 3.0 | 1671 |
| trav-013 | eval-travel | 问答 | PASS | PASS | 3.0 | 1551 |
| trav-014 | eval-travel | 问答 | PASS | PASS | 3.0 | 1174 |
| trav-015 | eval-travel | 问答 | PASS | PASS | 3.0 | 1553 |
| trav-016 | eval-travel | 问答 | PASS | PASS | 3.0 | 12677 |
| trav-017 | eval-travel | 问答 | PASS | PASS | 3.0 | 3946 |
| trav-018 | eval-travel | 问答 | PASS | PASS | 3.0 | 3736 |
| trav-019 | eval-travel | 问答 | PASS | PASS | 3.0 | 4077 |
| trav-020 | eval-travel | 问答 | PASS | PASS | 3.0 | 2362 |
| trav-021 | eval-travel | 拒答 | — | — | 3.0 | 1700 |
| trav-022 | eval-travel | 问答 | PASS | PASS | 3.0 | 1230 |
| trav-023 | eval-travel | 问答 | PASS | PASS | 3.0 | 1749 |
| trav-024 | eval-travel | 问答 | PASS | PASS | 3.0 | 2245 |
| hb-022 | eval-handbook | 拒答 | — | — | 3.0 | 1340 |
| rel-016 | eval-release | 拒答 | — | — | 3.0 | 1630 |
| rel-017 | eval-release | 拒答 | — | — | 3.0 | 1570 |
| trav-025 | eval-travel | 拒答 | — | — | 3.0 | 1076 |
| trav-026 | eval-travel | 拒答 | — | — | 3.0 | 4416 |
| cs-001 | eval-handbook | 问答 | PASS | PASS | 3.0 | 3057 |
| cs-002 | eval-travel | 问答 | PASS | PASS | 3.0 | 2193 |

> 拒答题（文档里没有答案）共 9 条，其中 9 条正确拒答。这类用例没有期望文档，**不计入 Hit@5 与引用准确率的分母**。
