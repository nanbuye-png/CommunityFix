# 本地演示账号配置

仓库不提供本机真实密码。运行 `python deploy/init-dev.py` 生成本地 `deploy/.env`，由开发者在本机查看和保管配置，不要提交该文件。

| 账号 | 角色 | 密码配置 |
|---|---|---|
| admin | 系统管理员 | BOOTSTRAP_PASSWORD |
| property / property2 | 物业管理员 | DEMO_PROPERTY_PASSWORD，未配置时使用 BOOTSTRAP_PASSWORD |
| resident / resident2 | 居民 | DEMO_RESIDENT_PASSWORD，未配置时使用 BOOTSTRAP_PASSWORD |
| worker | 维修人员 | DEMO_WORKER_PASSWORD，未配置时使用 BOOTSTRAP_PASSWORD |

启用 DEMO_ENABLED 才创建演示数据；已有账号是否同步密码由 DEMO_SYNC_PASSWORDS 控制。密码至少 12 个字符、最多 72 个 UTF-8 字节。不要将隔离测试脚本里的固定测试密码用于真实部署。

H5 注册也支持自行创建居民或维修人员账号，详情见 [自主注册说明](self-registration-2026-10-30.md)。

本机账号表、数据库备份、运行日志、私人数据清理记录和未经检查的验收截图不纳入 Git 仓库。部分历史验收文档中的本地证据链接仅在原开发机器上可用。
