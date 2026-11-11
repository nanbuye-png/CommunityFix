# 本地备份与隔离恢复

MinIO 与 MySQL 的协调备份、版本快照和恢复流程见 [完整容器验收](local-acceptance.md)。下文介绍本地文件开发模式。

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

生产使用 MinIO 时不能把本地目录脚本直接当作对象存储备份，应停止写入并演练数据库与对象的一致恢复。恢复耗时与可接受数据丢失范围需在实际环境验证。
