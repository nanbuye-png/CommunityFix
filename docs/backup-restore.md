# 本地备份与隔离恢复

本轮补充：独立 MinIO 的停写协调备份、镜像/前端版本快照、全新 Compose 恢复、38 表/4 对象及附件 API 哈希验证已完成。命令与数据库恢复顺序见 [本地完整验收指南](local-acceptance.md)，实际指标见 [本轮报告](local-acceptance-results.md)。下文保留本地文件开发模式。

`deploy/backup-local.py` 使用 Python 3.11+ 标准库及 MySQL 容器内的工具，适用于目前的 MySQL + 本地私有图片开发模式。MySQL root 密码从容器已有环境读取，不放入主机命令行或输出；恢复账号密码随机生成，通过标准输入传入。

备份包含数据库和图片中的住户信息，应限制备份目录访问、另存到受控的独立位置。默认 `.local/backups` 被项目忽略规则覆盖；`deploy/.env` 不纳入备份，部署配置应通过单独受控渠道保存。

## 创建备份

先停止所有业务实例、写入脚本和定时任务，保持 MySQL 运行。当前低内存模式只需停止运行 `deploy/run-backend.ps1` 的业务服务；完整 Cloud 环境须停止所有 backend 实例。不要在后台仍有上传、清理或迁移时声明写入已停止。

在 `CommunityFix` 目录执行：

```powershell
python deploy/backup-local.py backup --writers-stopped
# 立即从普通本机终端重新启动后端，保证手机可访问
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/run-backend.ps1
```

默认读取 `communityfix-mysql-1` 的 `communityfix` 库及 `.local/uploads`。可用 `--container` 指定容器，备份子命令可传 `--database`、`--files`、`--output`。容器必须允许通过其 `MYSQL_ROOT_PASSWORD` 执行本地维护。

脚本使用 `mysqldump --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF`，依据 [MySQL 8.4 官方文档](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)。备份期间不执行 DDL；数据库快照本身不保证外部图片一致性，因此还要求停止所有写入及清理任务，比较备份前后的数据指纹、原图片哈希和复制后的图片哈希。

每次创建全新的 UTC 时间戳目录，包含 `database.sql.gz`、`files/`、`manifest.json`。清单记录所有表计数、迁移版本、数据指纹、压缩文件哈希、图片大小及 SHA-256。所有 ATTACHED 图片必须存在且大小匹配；任一源变化、引用缺失或复制错误都会失败，不发布有效清单。失败目录不视为可恢复备份。

## 隔离恢复演练

```powershell
python deploy/backup-local.py restore-drill .local/backups/<备份目录名>
```

脚本先验证备份完整性，然后创建随机命名的 `cf_restore_<随机值>` 数据库和临时账号。导入账号只获临时库权限，恢复命令不接受现有数据库作为目标。恢复后核对所有表计数、全部行指纹、附件引用及每张图片的恢复副本哈希；最后删除本次临时库和账号，成功时生成 `restore-report.json`。不会启动应用、SLA 扫描或微信发送任务。

仅对本工具生成且来源可信的备份执行演练；哈希用于检测损坏，不提供来源认证。该命令验证数据库和文件内容，不将备份覆盖到在线环境。生产切换还需隔离验证环境、关闭外部消息任务、核对账号权限和真实附件 API，再决定是否切换；避免恢复旧 outbox 后重复发通知。

## 已执行结果与范围

2026-10-17，北京时间约 20:08，备份目录为 `.local/backups/20261017T120816Z-b481a885`。MySQL 8.4.5；38 张表（37 张业务表加 Flyway 历史）、14 张图片、14 条 ATTACHED 引用；备份 6.390 秒、隔离恢复及校验 7.375 秒，全部通过。临时库和账号已删除；业务服务恢复后健康状态 UP，管理后台 HTTP 200。

同时验证：未传 `--writers-stopped` 时拒绝创建备份；向恢复命令传在线库目标参数被拒绝；在临时副本中分别损坏压缩备份和图片，均在建库前被拒绝；通过只读查询确认演练库和账号已删除。检查结果保存在 `.local/backup-guard-checks.json`。本次没有切换在线数据库，也没有启动副本应用。耗时不能视为包含主机重建、网络恢复和应用验收的生产 RTO；尚未验证每日备份调度和 RPO。

生产使用 MinIO，不能把本地目录脚本直接当作 MinIO 备份。仍需在可用的 MinIO 环境中停止写入并保留快照引用的对象，演练 MySQL + MinIO 的协调恢复；按计划配置独立备份位置、7 个每日与 4 个每周保留周期，再实测 RPO≤24 小时、RTO≤2 小时。
