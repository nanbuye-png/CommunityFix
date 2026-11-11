# 构建与验证

所有命令从仓库根目录执行。测试报告与临时资源放在 `.local/`，不随源码上传。

## 前端

```powershell
npm --prefix admin-web ci
npm --prefix admin-web run build
npm --prefix miniapp ci
npm --prefix miniapp run typecheck
npm --prefix miniapp run build:h5

# 仅检查模拟器构建；真机需改为手机可达地址
$env:VITE_API_BASE='http://127.0.0.1:8080/api'
npm --prefix miniapp run build:mp-weixin
```

前端依赖以 lockfile 为准。构建通过证明类型和打包可完成，不代表手机登录、微信订阅或真实支付已经验收。

## 后端隔离回归

需要 JDK 21、Maven、Python 3.11+ 和运行中的 Docker。先按开发指南启动 `communityfix-mysql-1`（本机映射端口 3307）：

```powershell
python deploy/test-backend-isolated.py
```

脚本生成独立 MySQL 库和账号，以及临时 Redis、RabbitMQ 容器，运行全部后端测试后清理。报告写到 `.local/acceptance/tests-*/report.json`。可以通过 `--container` 和 `--db-port` 指定现有开发 MySQL。

测试使用本地模拟供应商，不消耗真实 AI 或微信额度。不要对生产数据库直接运行集成测试，也不要用 `run-backend.ps1 -Test` 代替此隔离入口。

单独构建 Java 模块可运行：

```powershell
mvn -B -DskipTests package
```

该命令跳过测试，不能代替隔离回归。

## 容器与脚本

```powershell
docker compose --env-file deploy/.env.example -f deploy/compose.yml config --quiet
python deploy/test_init_dev.py
python deploy/test-acceptance.py
python deploy/test-performance-local.py
```

完整容器验收（包含网关、存储和恢复）见 [操作指南](local-acceptance.md)。性能脚本 `deploy/performance-local.py` 在临时数据库生成合成工单，应先运行 `--help` 确认参数和压力规模；需要最新打包的后端及足够机器资源。

AI 合成评测样例位于 [evaluation/repair](../evaluation/repair/README.md)。`deploy/evaluate-real-ai.py` 和 `deploy/evaluate-support-chat.py` 会使用真实供应商，需另行配置并控制调用成本，不能当作无外部依赖的单元测试运行。

## 上线前人工验证

至少覆盖居民报价确认、师傅接单、完工待验收、验收后账单四个提醒节点，以及重复提交、草稿恢复、报价版本冲突、断网重试、权限隔离和附件访问。服务器部署后还需使用真实手机检查 Wi-Fi / 移动数据登录与订阅授权，并执行备份恢复演练。
