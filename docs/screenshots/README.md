# 截图说明

本目录用于存放平台 UI 截图。截图需手动捕获。

## 需要的截图

| 文件名 | 内容 |
|--------|------|
| `desktop-main.png` | 桌面端首页对话界面（浅色 UI，知识空间下拉框可见） |
| `desktop-auth.png` | 桌面端注册/登录弹窗 |
| `desktop-settings.png` | 桌面端设置弹窗（API Key 双模式配置） |
| `desktop-kb-qa.png` | 桌面端知识空间问答（回答 + 引用 + 置信度） |
| `desktop-doc-upload.png` | 桌面端文档上传弹窗（文件列表 + 解析状态） |
| `mobile-main.png` | 移动端首页对话界面 |

## 捕获方法

1. 启动应用：`./mvnw spring-boot:run`
2. 浏览器打开 http://localhost:8080
3. 逐个功能页面截图，保存到本目录

## 历史变更

- 2026-09-08: 删除旧深色主题截图 `web-ui-demo.jpg`，当前 UI 已更新为浅色企业知识库风格
