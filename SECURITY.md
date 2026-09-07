# Security Policy

## 报告安全问题

如果你发现安全漏洞，请**不要**公开提交 Issue。

通过以下方式私密报告：
- GitHub Security Advisory（推荐）
- 发邮件至仓库 owner

请在报告中包含：
- 问题描述和影响范围
- 复现步骤
- 建议的修复方案（如有）

我们会在 72 小时内确认收到，并在修复后公开致谢。

## 安全边界

本项目实现了以下安全措施：

| 措施 | 说明 |
|------|------|
| API Key 鉴权 | 所有写操作需 `X-API-Key` 请求头，常量时间比较 |
| 限流 | 按 IP 每分钟限制请求数，超出返回 429 |
| CORS 白名单 | 仅允许配置的来源 |
| 文件沙箱 | 文件操作限制在 `workspace/` 目录，路径穿越防护 |
| SSRF 防护 | HTTP 请求工具禁止内网/元数据地址 |
| 输入限制 | 消息长度上限 10000 字符，POST body 上限 20KB |

## 生产部署清单

- [ ] 修改 `AGENT_API_KEY` 为强随机值
- [ ] 配置 `ALLOWED_ORIGINS` 为实际域名
- [ ] 设置 `RATE_LIMIT` 为合理值
- [ ] 使用 HTTPS
- [ ] 使用 Qdrant + Redis 而非 InMemory
- [ ] 确认 `.env` 不被提交到 Git
