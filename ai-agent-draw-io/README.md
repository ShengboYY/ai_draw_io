# AI Draw.io Builder

AI Agent 智能绘图平台，支持通过自然语言生成可编辑的 Draw.io 图表。

## 本地 MySQL 开发库

后端项目根目录提供了 `docker-compose.yml`，用于启动一个本地 MySQL 开发库。

```bash
cd ai-agent-draw-io
docker compose up -d db
```

Docker Compose 会自动读取同目录 `.env`；`.env` 里的注释请使用 `#`。如果本地 `.env` 还没准备好，也可以先用示例配置启动：

```bash
docker compose --env-file .env.example up -d db
```

默认连接信息：

- Host 机器连接：`127.0.0.1:3307`
- Docker 内其他服务连接：`db:3306`
- Container：`ai-agent-draw-io`
- Database：`ai_draw_io`
- Username：`root`
- Password：读取本地 `.env` 的 `MYSQL_ROOT_PASSWORD`

如需修改端口、库名或密码，先复制 `.env.example` 为 `.env`，再调整 `MYSQL_*` 配置；`.env` 只用于本地开发，不提交到 Git。
