# 本地完整容器验收

命令在 `CommunityFix` 根目录执行。该流程使用随机 `cf-accept-<12 hex>` Compose 项目、独立数据库卷和 MinIO 卷，仅绑定 `127.0.0.1`。不读取 `deploy/.env`，不改变原开发 MySQL、原附件目录或密码配置。报告保存在每次运行独立的本地输出目录。

## 前置条件和入口

需要 Docker Compose、Python 3、Node/npm、JDK 21、Maven；前端依赖已按锁文件安装。固定依赖镜像为 MySQL 8.4.5、Redis 7.4.2-alpine、Nginx 1.28.0-alpine、MinIO RELEASE.2025-04-22T22-12-26Z，Java 构建基础镜像见各模块 Dockerfile。

```powershell
# 保护检查不需要 Docker
python -B deploy/test-acceptance.py

# 重新执行全部后端测试，创建并自动清理随机 schema/user
python -B deploy/test-backend-isolated.py --container communityfix-mysql-1 --db-port 3307

# 创建环境。将输出的随机名称填入下一行
python -B deploy/acceptance.py init
$run = 'cf-accept-填入实际12位编号'
python -B deploy/acceptance.py config --run $run
python -B deploy/acceptance.py build --run $run
python -B deploy/acceptance.py up --run $run
python -B deploy/acceptance.py wait --run $run
python -B deploy/acceptance.py verify --run $run
python -B deploy/acceptance.py fault --run $run
```

默认输出目录为 `.local/acceptance/<run>/`；可给每个命令传入相同的 `--output-dir D:/path/to/acceptance`。`init --port-offset 200` 将全部默认端口加 200，供多个独立环境并存。`--run` 选择目标环境，拒绝普通开发项目名称、目录穿越、缺失或不匹配的所有权标记。

默认端口：管理端 18088，H5 18089，Gateway 18081，业务实例 18080/18082，Eureka 18761，MySQL 13307，MinIO 19000。管理端和 H5 的 `/api` 均经 Nginx 转发至同一 Gateway。

`config.env` 自动生成 32 字符测试密码和独立 JWT 密钥。测试角色 `admin/property/resident/worker` 使用该测试密码，辅助隔离角色为 `resident2/property2`。读取本地文件即可登录，禁止把该文件和原始备份发布到报告。启动仍执行至少 12 字符、最多 72 个 UTF-8 字节的原密码校验。先启动首个业务实例完成种子数据，再启动第二个实例。

AI 指向容器内固定 JSON 模拟服务；微信 app ID、secret、模板均置空。脚本清除继承的相关环境变量，不会沿用用户的真实供应商配置。Redis 就绪检查通过，但当前业务核心仍使用 MySQL 共享状态。

## 官方 MinIO 镜像不可获取时

先保留 pull 的错误和目标版本。不要改全局 Docker 代理或替换为未知第三方镜像。本次 Docker 镜像入口返回 403/401，采用官方发布标签对应源码构建：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/build-minio-source.ps1
python -B deploy/acceptance.py init --minio-image communityfix-minio:source-20250422
```

脚本校验官方 GitHub 仓库提交 `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`，拒绝有修改的源码工作区，使用 Go 1.24.2-bookworm 编译，保留许可证、源码标签、二进制 SHA-256。默认仅给临时构建容器指定 DNS `1.1.1.1`；可通过 `-DnsServer ''` 使用 Docker 默认 DNS，不修改全局配置。运行镜像从 scratch 复制二进制及 CA 证书。它是本地源码构建镜像，不冒充官方分发镜像。源码或工具链下载失败时记录阻塞，不将 MinIO 场景计为通过。

## 回归范围与浏览器复验

若 Java 基础镜像网络中断，而本机已有该验收项目的完整备份，可复用**经过镜像 ID 核对的旧业务运行时**打包已测试的新代码：

```powershell
python -B deploy/test-backend-isolated.py
python -B deploy/rebuild-backend-from-snapshot.py --run $run --backup .local/acceptance/<run>/backup-<12hex>
```

该备用命令离线 Maven 打包，将新 jar 放入备份记录的本地镜像，继承原非 root 用户和运行时；不下载其他镜像。输出记录基础镜像 ID、结果镜像 ID、jar 和源码哈希。Maven 本地缓存不足、快照镜像缺失或 ID 不符会失败。网络恢复后仍可按正常 `build` 流程重新完整构建。

`verify` 依次检查服务健康及 Eureka 双实例、四角色登录、私有桶及并发首次上传、两个实例读取同一附件、入口图片 URL、无效/过期签名、损坏和超限图片；再执行绑定审批、报修到评价、投诉及返修、跨居民和跨小区拒绝访问。并发检查覆盖提交幂等、工人容量、SLA 去重、AI 共享限流，随后检查临时附件清理、对象缺失和 MinIO 停机后的 503 及恢复。

`fault` 停止第一个业务实例，通过网关采样 `/api/auth/me`，要求连续 30 次成功后记录恢复时间，最后重启被停实例。中间的非 200 单列在 `instanceFailure.observations` 中，不混入正常负载压测。该场景不承诺零请求损失。

浏览器用独立会话登录四角色：管理员检查用户管理；居民提交报修并采纳模拟 AI 建议；物业审核并派单；维修人员接单、开始、提交结果；居民确认完成并评价。每一步核对页面状态刷新，记录截图和 JavaScript 错误。自动 HTTP 检查与真实浏览器点击分别记录，不能互相冒充。微信包构建不等于手机人工操作验收。

outbox 集成测试使用真实 MySQL 和模拟 `WechatMessageSender`，验证两个 worker 对象竞争同一任务、过期租约被新 worker 接管、旧 attempts 快照不能误领、重试成功和第五次失败终止。容器中的真实微信发送保持关闭；该证据不是两个 JVM 向微信公网投递的测试。外部投递仍采用至少一次语义。

## 协调备份与隔离恢复

```powershell
python -B deploy/acceptance.py backup --run $run
# 路径使用上一步输出的实际值
python -B deploy/acceptance.py restore --run $run --backup .local/acceptance/<run>/backup-<12hex>
```

备份先停止两个业务实例（包括定时任务），保持 MySQL 和 MinIO 运行；保存 SQL gzip、对象内容与 Content-Type、表计数/逐行指纹、附件引用、对象哈希、Flyway 历史、前端静态文件及哈希、各运行容器的镜像 ID 和专用快照标签。前后再次核对源数据一致，最后重启两个实例。备份目录包含私有测试配置，放在忽略目录中。

恢复先验证备份所有权和数据库/对象/前端文件哈希，再创建新的随机项目，默认端口偏移 100；不提供覆盖在线数据库的目标参数。使用仅有新库权限的应用账号导入，拒绝含数据库切换语句的 SQL。恢复对象后核对全部表指纹、引用、对象哈希，然后以备份中固定镜像 ID 和静态产物重新部署，验证管理员/居民登录以及已关联图片的 API 下载哈希。无已关联图片时拒绝判定附件恢复通过。恢复副本在 finally 中删除容器、网络和卷，源备份保留。

这是“回到备份版本”的实测路径：新建空库 → 导入该版本数据库快照 → 恢复对象 → 启动对应应用镜像和前端产物。不得对较新结构的数据直接启动旧二进制来代替数据库恢复，也不把 Flyway 的校验或前向迁移当作自动降级。没有新数据库迁移的重部署可使用当前镜像重新创建；涉及结构升级时应再次独立演练。

## 正式性能场景

性能必须使用**另一个新建项目**，`repair_order` 非空会被拒绝。关闭或清理功能验收环境后，可复用默认端口；并存时使用端口偏移。`build` 会复用 Docker 构建缓存。

```powershell
python -B deploy/acceptance.py init --port-offset 200 --minio-image communityfix-minio:source-20250422
$perf = 'cf-accept-填入新的实际编号'
python -B deploy/acceptance.py build --run $perf
python -B deploy/acceptance.py up --run $perf
python -B deploy/acceptance.py performance --run $perf
python -B deploy/acceptance.py down --run $perf
python -B deploy/acceptance.py report --run $perf
```

正式参数固定：初始 100,000 工单、100 个独立用户、120 秒预热、600 秒采样，每用户请求间隔 0.2 秒。80% 居民、20% 物业，初始工单 80% 已关闭/20% 待审核；列表、详情、通知、创建、取消、本人和统计七类接口经 Nginx → Gateway → 两业务实例访问。测量前要求两个实例均已注册 UP。

可在另一个空项目传 `--short`（15 秒预热、45 秒采样），该结果 `formalDuration=false`，不能代替正式场景。采样包含测量窗口内开始、在窗口结束后才完成的请求。所有非 200 和返回内容不符合预期均计失败；总体和每类接口分别要求 P95 < 2000ms、错误率 < 1%。故障注入单独报告。

`progress.json` 保存进度，`performance.json` 保存环境、运行脚本哈希、镜像 ID、请求统计和阈值结果。负载发生器与 Docker 共享本机，不能替代指定 4 核/8 GB 服务器结果。

## 清理与报告

```powershell
python -B deploy/acceptance.py status --run $run
python -B deploy/acceptance.py down --run $run
python -B deploy/acceptance.py report --run $run
```

`down` 先核对容器 Compose 项目标签，再删除该项目的容器、网络和命名卷。不会执行全局 prune，也不删除镜像、备份或结果目录。源代码、构建缓存、快照镜像和备份保留用于复现；删除它们需另行选择明确路径。`report` 仅导出状态记录并替换配置中的密码/密钥，原始日志和 config.env 不导出。

所有失败均保留为 FAIL 和 `<command>-error.log`；外部下载不可用要注明阻塞原因与重试命令，不能把跳过计为成功。完整本地结果与正式 HTTPS、微信发布、手机人工操作、服务器复测和大规模人工 AI 评测分别记录。
