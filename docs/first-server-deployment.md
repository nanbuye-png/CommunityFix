# 第一次部署 CommunityFix：Ubuntu 22.04 / 2 核 4 GB

这份手册按“把 GitHub 项目部署到已有服务器，再用手机微信调试登录”编写。当前阶段使用公网 IP 和 HTTP 8088 端口，不要求购买域名或配置 HTTPS。它是开发联调环境，普通居民正式使用前仍需完成 HTTPS 和微信合法域名配置。

**先完成一节，确认成功标志，再进入下一节。命令报错时不要继续粘贴后续步骤。** 本文未替你登录或操作服务器，也不表示服务器已经部署成功。

## 先分清操作位置

| 标记 | 在哪里操作 | 用途 |
| --- | --- | --- |
| 云控制台 | 购买服务器的网站 | 查看公网 IP、远程连接、配置安全组、创建快照 |
| 服务器终端 | 云控制台远程连接窗口，或 SSH 登录后的 Linux 终端 | 安装 Docker、拉代码、配置和启动后端 |
| 自己电脑 | Windows PowerShell、微信开发者工具 | 构建小程序、扫码预览 |
| 手机 | 微信 | 验证登录和订阅通知 |

正文中的 `YOUR_SERVER_IP` 要换成云控制台显示的服务器公网 IP，不含 `http://`、端口或斜杠。手机不需要与服务器在同一个局域网。

本次最终地址：管理后台 `http://YOUR_SERVER_IP:8088`；小程序 API `http://YOUR_SERVER_IP:8088/api`。小程序安装包由微信开发者工具提供，后端和管理后台由服务器提供。

## 1. 登录服务器，确认机器状态

**位置：云控制台 → 服务器实例 → 远程连接。** 第一次建议直接使用厂商提供的网页终端。服务器登录账号和密码与项目管理员账号是两套不同的账号。

也可从自己电脑的 PowerShell 使用 SSH，用户名按云控制台提供的填写：

```powershell
ssh ubuntu@YOUR_SERVER_IP
```

若厂商账号是 `root`，改为 `ssh root@YOUR_SERVER_IP`；使用密钥登录时按厂商提供的密钥方式连接。首次连接应核对服务器指纹。输入密码时终端不显示字符是正常现象。

**以下 Linux 命令均在服务器执行。** 先检查：

```bash
cat /etc/os-release
uname -m
nproc
free -h
df -h /
whoami
```

预期系统为 Ubuntu 22.04，CPU 数量 2，内存约 4 GB。磁盘建议至少有 20 GB 可用空间，构建缓存、数据库和图片会继续占用磁盘。这是小规模测试的容量建议，不是已经实测的服务能力保证。

本文统一使用 root 终端执行部署，减少 `sudo` 和目录权限差异。如果 `whoami` 不是 `root`，执行：

```bash
sudo -i
whoami
```

预期第二条输出 `root`。若没有 sudo 权限，先通过云控制台取得有管理权限的账号。下次重新连接时也要确认当前身份。

## 2. 在云控制台配置访问端口

找到这台实例的“安全组”或“防火墙”，添加入方向规则：

| 协议 / 端口 | 来源 | 用途 |
| --- | --- | --- |
| TCP / 22（或实际 SSH 端口） | 自己电脑的公网 IP，使用 SSH 时配置 | 管理服务器 |
| TCP / 8088 | 联调期间可设 `0.0.0.0/0` | 电脑和手机访问项目 |

如果临时使用 `0.0.0.0/0`，表示所有 IPv4 来源都能访问该端口；测试完成后按实际访问范围调整。仅向单一电脑 IP 放行 8088 时，切换到手机移动数据通常会被拦截。

不需要向公网开放数据库 3306、Redis 6379、RabbitMQ 5672/15672、MinIO 9000/9001、后端 8080、网关 8081 和注册中心 8761。项目内部服务通过 Docker 网络通信。

查看系统防火墙状态：

```bash
command -v ufw >/dev/null && ufw status
```

若 UFW 已启用，可添加 `ufw allow 8088/tcp`。不要为解决访问问题关闭整台机器的防火墙，也不要在未放行 SSH 的情况下新启用 UFW。Docker 映射端口可能绕过 UFW 的普通规则，公网访问范围优先在云安全组中限制。[Docker 防火墙说明](https://docs.docker.com/engine/network/packet-filtering-firewalls/)

## 3. 安装 Git、Python 与 Docker

**位置：服务器 root 终端。** 先检查是否已有 Docker：

```bash
command -v docker
```

如果存在，继续运行 `docker version`、`docker compose version`、`docker ps`。三项正常就不必重装 Docker；仍需用下面的 apt 命令安装 Git、Python 等基础工具。若机器已运行其他 Docker 项目，不要卸载已有 Docker 或清理其数据。

```bash
apt-get update
apt-get install -y ca-certificates curl git python3 nano
```

**仅当尚未安装 Docker 时执行下面的安装块。** 本块按 Ubuntu 官方软件源方式配置 Docker；其他 Linux 系统不要照抄。[Docker Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/)

```bash
install -d -m 0755 /etc/apt/keyrings
curl --fail --show-error --location https://download.docker.com/linux/ubuntu/gpg --output /etc/apt/keyrings/docker.asc
chmod 0644 /etc/apt/keyrings/docker.asc
python3 - <<'PY'
import pathlib, subprocess
release = dict(line.split('=', 1) for line in pathlib.Path('/etc/os-release').read_text().splitlines() if '=' in line)
if release.get('ID', '').strip('"') != 'ubuntu':
    raise SystemExit('Stop: these instructions require Ubuntu.')
suite = release['VERSION_CODENAME'].strip('"')
arch = subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip()
pathlib.Path('/etc/apt/sources.list.d/docker.sources').write_text(
    f'Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: {suite}\n'
    f'Components: stable\nArchitectures: {arch}\nSigned-By: /etc/apt/keyrings/docker.asc\n')
PY
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
```

若出现包冲突或软件源无法访问，先解决该错误，不要删除已有容器作为处理办法。确认安装结果：

```bash
git --version
python3 --version
docker version
docker compose version
docker run --rm hello-world
```

**成功标志：** Docker 客户端和服务端都能响应，Compose 输出版本，最后出现 `Hello from Docker!`。如果最后一条拉取超时，说明镜像网络还没打通，不能继续当作安装完全成功。只使用可信的官方源或厂商提供的镜像加速服务，不用来源不明的代理或关闭 TLS 验证。

## 4. 将 GitHub 项目拉取到服务器

```bash
cd /opt
git clone https://github.com/nanbuye-png/CommunityFix.git
cd /opt/CommunityFix
git log -1 --oneline
ls
```

**成功标志：** 能看到 `README.md`、`backend`、`gateway`、`registry`、`admin-web`、`miniapp`、`deploy` 等目录。公开仓库无需把 GitHub 密码填进命令。

如果提示目录已存在，先检查 `git -C /opt/CommunityFix status`，不要删除目录重新克隆。GitHub 连接超时是服务器网络问题，先保留报错，不要把本机的整个 `.local`、`node_modules` 或密钥目录上传代替源码。

## 5. 生成服务器独立配置

服务器使用新的数据库和密码，不会自动带上你电脑上的小区、住户、订单和附件。本手册按全新数据库部署，不迁移本机业务数据。

不要在服务器运行 `deploy/init-dev.py` 来初始化这个环境：该脚本面向本地开发，会启用演示模式。下面从配置示例生成随机服务器凭据，并保持演示与模拟支付关闭。

**位置：服务器 `/opt/CommunityFix`。** 先输入真实公网 IP：

```bash
cd /opt/CommunityFix
read -r -p '请输入服务器公网 IPv4（不要带 http:// 或端口）: ' CF_PUBLIC_IP
export CF_PUBLIC_IP
```

完整复制下面这块，包括首尾的 `PY`；不要逐行修改 Python 缩进：

```bash
python3 - <<'PY'
from pathlib import Path
import ipaddress, os, re, secrets
ip = str(ipaddress.IPv4Address(os.environ['CF_PUBLIC_IP'].strip()))
target = Path('deploy/.env')
if target.exists():
    raise SystemExit('deploy/.env already exists; preserve it and edit with nano.')
content = Path('deploy/.env.example').read_text(encoding='utf-8')
values = {name: secrets.token_urlsafe(32) for name in (
    'DB_PASSWORD', 'DB_ROOT_PASSWORD', 'JWT_SECRET', 'BOOTSTRAP_PASSWORD',
    'MINIO_SECRET_KEY', 'REDIS_PASSWORD', 'RABBITMQ_PASSWORD')}
values.update(BOOTSTRAP_USERNAME='admin', WEB_PORT='8088',
              CORS_ORIGINS=f'http://{ip}:8088', DEMO_ENABLED='false',
              DEMO_SYNC_PASSWORDS='false', BILLING_MOCK_ENABLED='false')
for name, value in values.items():
    content, count = re.subn(r'^' + name + r'=.*$', name + '=' + value, content, flags=re.M)
    if count != 1:
        raise SystemExit('Unexpected template field: ' + name)
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as file:
    file.write(content)
print('Created deploy/.env. Secrets were not printed.')
PY
```

**成功标志：** 输出 `Created deploy/.env`。若提示 already exists，表示已有配置被保护，没有覆盖。不要删除已有配置来强行重跑，特别是数据库启动之后。

打开配置编辑：

```bash
nano deploy/.env
```

Nano 操作：方向键移动；`Ctrl+O` 保存，按 Enter 确认文件名；`Ctrl+X` 退出。

### 需要核对或填写的值

| 配置 | 你需要做什么 |
| --- | --- |
| `BOOTSTRAP_USERNAME` | 保持 `admin` 即可 |
| `BOOTSTRAP_PASSWORD` | 已生成随机密码，用密码管理器保存；首次登录后台用它 |
| `DB_PASSWORD`、`DB_ROOT_PASSWORD`、`JWT_SECRET`、`MINIO_SECRET_KEY`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD` | 已随机生成，初次部署无需手填 |
| `WEB_PORT` | 本文固定 `8088`，与安全组保持一致 |
| `CORS_ORIGINS` | 确认是 `http://实际公网IP:8088`，末尾没有 `/` |
| `WECHAT_APP_ID` | 填写自己的小程序 AppID，与电脑端 manifest 一致 |
| `WECHAT_APP_SECRET` | 在微信公众平台自己的小程序开发设置中取得，仅填入服务器 |
| 四项 `WECHAT_*_TEMPLATE_ID` | 使用你已申请的四个模板；当前示例已列出，仍需确认属于这个 AppID |
| `AI_ENDPOINT`、`AI_API_KEY`、`AI_MODEL` | 第一轮可全部留空，基本流程跑通后再同时填写 |
| `DEMO_ENABLED`、`DEMO_SYNC_PASSWORDS`、`BILLING_MOCK_ENABLED` | 保持 `false` |

其他值先保持示例默认。尤其不要把 Redis、RabbitMQ 或 MinIO 的容器内部地址改成服务器公网 IP；主 Compose 会为后端设置正确的内部服务名。

`AppSecret`、AI Key、数据库密码不要截图或发到聊天里，也不要提交 Git。微信模板 ID 和 AppID 不等于 AppSecret。代码仓库本身不会提供你的真实 AppSecret。

## 6. 为 4 GB 内存准备运行配置

三套 Java 服务与数据库、中间件会共享 4 GB 内存。下面是用于少量用户联调的起点，限制 Java 堆并缩小 MySQL 缓冲池，不能保证任意并发下都足够。

创建本机专用覆盖文件，不改仓库主 Compose：

```bash
mkdir -p .local
cat > .local/compose.server.yml <<'YAML'
services:
  backend:
    environment:
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx512m"
  gateway:
    environment:
      JAVA_TOOL_OPTIONS: "-Xms64m -Xmx256m"
  registry:
    environment:
      JAVA_TOOL_OPTIONS: "-Xms64m -Xmx128m"
  mysql:
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+00:00
      - --innodb-buffer-pool-size=134217728
      - --max-connections=80
YAML
```

JVM 堆上限不等于容器总内存上限；Java 的其他内存、中间件和系统仍会占内存。后面要观察 `docker stats --no-stream`。

创建命令简写，避免每次漏掉环境文件或内存配置：

```bash
cat > .local/cf.sh <<'SH'
cf() {
  (cd /opt/CommunityFix && docker compose --env-file deploy/.env -f deploy/compose.yml -f .local/compose.server.yml "$@")
}
SH
source .local/cf.sh
cf config --quiet
```

**成功标志：** `cf config --quiet` 没有错误。它只验证配置结构，不验证密码、微信凭据或镜像下载。不要把不带 `--quiet` 的完整配置输出发给别人，其中会展开真实密码。[Compose 环境变量说明](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

**每次重新 SSH 登录后，都先执行：**

```bash
cd /opt/CommunityFix
source .local/cf.sh
```

若出现 `cf: command not found`，说明当前终端尚未加载上述函数。

### 可选：首次构建内存不足时准备交换空间

先检查 `free -h` 和 `swapon --show`。如果已经有交换空间，不要重复创建。若没有，且磁盘剩余至少 2 GB，可创建专用 2 GB 交换文件。它可以缓解内存峰值，但会变慢，不能代替增加内存。

```bash
if [ -e /swapfile.communityfix ]; then
  echo '文件已存在，先检查，不覆盖。'
else
  fallocate -l 2G /swapfile.communityfix &&
  chmod 600 /swapfile.communityfix &&
  mkswap /swapfile.communityfix &&
  swapon /swapfile.communityfix &&
  printf '%s\n' '/swapfile.communityfix none swap sw 0 0' >> /etc/fstab
fi
swapon --show
```

若中途失败，保留报错，不重复修改 `/etc/fstab`。这一步是系统配置，不是在项目中创建普通缓存文件。

## 7. 构建管理后台

GitHub 保存源码，不保存 `dist`。缺少这一步，Nginx 无法提供管理页面。

使用一次性 Node.js 容器，不需要在服务器单独安装 Node：

```bash
cd /opt/CommunityFix
docker run --rm --mount "type=bind,source=$PWD/admin-web,target=/work" -w /work node:24-bookworm-slim sh -lc 'npm ci && npm run build'
test -f admin-web/dist/index.html && echo '管理后台构建成功'
```

**成功标志：** 构建退出成功，存在 `admin-web/dist/index.html`。首次运行会下载镜像和 npm 依赖。大文件 chunk 提示是警告；`npm ERR!`、退出码 137、`Killed` 是需要处理的失败。

## 8. 逐个构建 Java 服务，再启动项目

**位置：服务器。** 先构建，避免构建时业务服务占用大量内存：

```bash
cf build registry
cf build gateway
cf build backend
```

每条成功后再执行下一条。Java 与 Maven 都由 Docker 构建镜像提供，服务器无需另装 JDK。构建时需要访问容器镜像源和 Maven 仓库，第一次可能耗时较长。不要因为短时间没有新日志就删除容器或数据库。

构建全部通过后：

```bash
cf up -d
cf ps -a
```

`-d` 表示后台运行，关闭终端不会自动停止项目。预计会看到 mysql、redis、rabbitmq、minio、registry、backend、gateway、nginx 共 8 个服务。MySQL、Redis、RabbitMQ 应最终显示 healthy，其余至少持续 Up，不能一直 Restarting 或 Exited。

```bash
cf logs --tail=100 backend
cf logs --tail=60 gateway nginx
docker stats --no-stream
```

后端首次启动执行数据库迁移并创建管理员。注册发现可能有短暂延迟，先等 1～2 分钟再检查；若持续报错，应查看日志，而不是无限等待。

## 9. 分层检查是否真的成功

### 9.1 在服务器检查网页

```bash
curl -I http://127.0.0.1:8088/
```

预期 HTTP 200。这只证明网页服务正常。

### 9.2 在服务器检查后端链路

```bash
curl --fail --show-error http://127.0.0.1:8088/api/auth/registration-communities
```

预期返回 JSON，通常包含 `code: 200`；全新环境的小区列表为空是正常现象。这个请求经过 Nginx、网关和后端，比只看到登录页更能证明链路正常。

不要把公网 `/actuator/health` 返回的页面当健康结果：当前 Nginx 只代理 `/api/`，其他路径可能返回前端 HTML。

### 9.3 在自己电脑打开后台

浏览器访问 `http://YOUR_SERVER_IP:8088`。使用 `admin` 和服务器 `.env` 中的 `BOOTSTRAP_PASSWORD` 登录。

**成功标志：** 登录进入管理页面，可以查询用户列表。仅出现登录页还不算账号和数据库验证成功。

若服务器本机能访问，电脑不能访问，优先检查云安全组、端口、公网 IP 和服务器防火墙，不要先修改微信配置。

## 10. 初始化一套测试业务数据

`DEMO_ENABLED=false` 时只初始化管理员，不会复制电脑上的演示小区。请用明确标记的测试信息操作：

1. 系统管理员在“基础数据”创建测试小区、楼栋、单元和房屋。
2. 在“用户管理”创建物业账号，角色选择物业管理员（`ADMIN`）。
3. 在“小区授权”把该物业账号关联到测试小区。
4. 退出系统管理员，用物业账号登录。
5. 在“维修人员”建立测试维修账号，配置服务小区、技能与接单能力。
6. 在“参考价格”维护需要测试的服务分类、人工费和材料费。
7. 手机微信登录后申请绑定测试房屋，由物业在“房屋绑定审核”通过。

没有小区授权，物业看不到对应小区；没有绑定房屋，居民不能正常报修；没有参考价，自费工单可能需要物业手动补报价。这些都不是部署网络故障。

首次部署不建议通过直接编辑数据库创建这些关系。系统管理员和物业权限不同，系统管理员不自动代替物业执行全部工单操作。

## 11. 在自己电脑重新构建微信小程序

**注意：从本节开始切回自己电脑的 PowerShell，不是在服务器执行。**

1. 检查 `miniapp/src/manifest.json` 中 `mp-weixin.appid`，与服务器 `WECHAT_APP_ID` 完全一致。
2. 微信开发者工具使用有该小程序开发权限的微信号登录；其他测试微信号按平台要求加入相应成员列表。
3. 将 API 地址替换为服务器公网地址：

```powershell
Set-Location D:\chatgpt-project\CommunityFix
npm.cmd --prefix miniapp ci
$env:VITE_API_BASE='http://YOUR_SERVER_IP:8088/api'
npm.cmd --prefix miniapp run build:mp-weixin
```

把命令中的 `YOUR_SERVER_IP` 改为真实 IP。不要把 AppSecret 或 AI Key 设置成 `VITE_` 前端变量。

**成功标志：** 编译完成，输出目录为 `miniapp/dist/build/mp-weixin`。

在微信开发者工具中导入该目录，确认 AppID。调试用 HTTP 地址时启用“不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”。生成预览二维码，手机扫码后也在右上角菜单的开发/调试选项中开启调试模式，再重新进入。

开发调试跳过域名校验的方式见 [腾讯云微信小程序排障说明](https://mc.qcloudimg.com/static/qc_doc/27169e32ad1db1d2d88b1b47d21673ae/doc-WeChat%2BMini%2BProgram%2BDeveloper%2Btools-FAQ.pdf)。具体菜单可能随微信版本变化；这种方式只用于有开发调试条件的测试，不是普通居民的发布流程。

现在测试：

1. 点击微信登录，确认能进入个人页面。
2. 用 Wi-Fi 测一次，再用移动数据测一次。若安全组仅允许固定 IP，需要同时允许测试手机的实际来源。
3. 创建房屋绑定申请，物业审核后提交一张测试工单。

微信登录完成的是居民身份认证，不会自动把账号与原来的账号密码测试用户合并，也不会自动授予维修身份。维修人员使用已创建的账号密码登录测试。

## 12. 验证四个提醒节点

手机先订阅两组通知，再触发新业务。授权不会补发之前的提醒，一次订阅额度也不是永久授权。

| 操作 | 居民期望收到 |
| --- | --- |
| 生成 / 发送报价，待居民确认 | 订单确认提醒 |
| 师傅接单 | 接单成功通知 |
| 师傅提交完工 | 订单完成通知，用于进入验收 |
| 居民验收后产生待付账单 | 待支付提醒 |

核对服务器 `.env` 中四个模板及其字段，详见 [配置说明](configuration.md)。如果微信接口要求调用方 IP 白名单，应将服务器实际出口 IP 配置到小程序平台；这与“服务器合法域名”是两件事，也不应该填写手机的 IP。仅在平台要求或出现相应错误时按其提示处理。

系统管理员可在“通知投递”查看结果。`SENT` 表示微信接口接受，不保证用户已经阅读；失败需要看具体原因，不能通过反复新建订单替代排查。

保持模拟支付关闭时，可以检查待付账单和提醒，但不能完成真实付款。项目当前没有真实微信支付。本次不要为了走完整演示而往系统里支付真实资金。

## 13. 最后再开启 AI

**位置：服务器。** 编辑 `nano /opt/CommunityFix/deploy/.env`，同时填写：

```dotenv
AI_ENDPOINT=https://你的供应商/完整接口路径
AI_API_KEY=你自己的密钥
AI_MODEL=供应商支持的模型名称
```

三项是示意，不能原样粘贴；端点须兼容项目的 Chat Completions 协议，并填写完整请求 URL。报修和客服可先共用一组配置。不要只填其中一部分。

保存后运行：

```bash
cd /opt/CommunityFix
source .local/cf.sh
cf up -d --no-deps backend
cf logs --tail=60 backend
```

`up -d` 会按变化的配置重建容器，单纯 `restart` 不会加载新的容器环境变量。[Docker Compose restart 说明](https://docs.docker.com/reference/cli/docker/compose/restart/)

先发布几条常见问题，验证匹配时直接回答；再发送一条需要模型处理的问题。用少量测试确认供应商连通性和账单。`AI_DAILY_TOKEN_WARNING` 仅产生日志提醒，不是自动停用模型的消费上限。

## 14. 常用维护命令

重新登录服务器后先切换 root、进入项目并加载函数：

```bash
cd /opt/CommunityFix
source .local/cf.sh
```

| 目的 | 命令 |
| --- | --- |
| 查看运行状态 | `cf ps -a` |
| 看后端最近日志 | `cf logs --tail=100 backend` |
| 跟踪后端日志 | `cf logs -f --tail=50 backend`，Ctrl+C 只退出日志查看 |
| 查看内存 | `docker stats --no-stream`、`free -h` |
| 查看磁盘 | `df -h /`、`docker system df` |
| 临时停止项目 | `cf stop` |
| 启动已有项目 | `cf up -d` |
| 修改 `.env` 后生效 | `cf up -d`，按配置变化重建服务 |

不要运行 `docker compose down -v`、`docker volume prune` 或带 `--volumes` 的全局清理命令。它们可能删除数据库、附件或其他项目的数据。不要删除 `.env` 来重置密码：已有数据库密码和管理员密码不会因此自动修改。

### 第一次更新代码

更新前先备份，不建议第一次部署时立即练习回滚。

1. 记录 `git rev-parse HEAD`，保存当前配置和 `.local/compose.server.yml`。
2. 用 `cf stop` 停止本项目写入，通过云控制台为包含 Docker 数据目录和项目目录的所有相关磁盘创建一致的快照；确认完成后再启动。快照具体步骤以服务器厂商为准。
3. 确认备份能用于恢复；本地模式和 MinIO 模式的工具区别见 [备份说明](backup-restore.md)。
4. 执行 `git status --short`，确保没有未处理的源码改动，再运行 `git pull --ff-only`。
5. 按第 7、8 节重建前端和 Java 服务。4 GB 机器建议维护窗口内停止本项目后逐个构建。
6. `cf up -d`，重新执行第 9 节和一条测试工单。

Flyway 会自动迁移数据库。代码回退不代表数据库会自动回退，因此不要遇到故障就随意切换旧提交或删除迁移记录。

## 15. 遇到错误时按这一张表排查

| 现象 | 先检查什么 |
| --- | --- |
| SSH 连不上 | 公网 IP、SSH 用户、密钥或密码、SSH 端口及其安全组来源 |
| `docker compose` 不存在 | 是否安装 Compose 插件；不是旧的 `docker-compose` 命令 |
| `cf: command not found` | `source /opt/CommunityFix/.local/cf.sh` |
| 镜像 / Maven / npm 下载超时 | 服务器外网、DNS、可信源可达性；不修改业务代码解决下载问题 |
| 构建 `Killed` / 退出码 137 | `free -h`、`swapon --show`、`journalctl -k -n 100`；减少并行、按第 6 节处理内存 |
| 8088 被占用 | `ss -lntp`；确认占用者，不随意停止其他服务。改端口时同步安全组、`.env`、小程序地址 |
| Nginx 403 / 页面空白 | `admin-web/dist/index.html` 是否存在、前端是否成功构建、`cf logs nginx` |
| 登录页正常但 API 502 / 503 | backend/gateway/registry 是否正常、服务注册是否完成、日志有无启动错误 |
| 后端一直重启 | `cf logs --tail=100 backend`；查数据库、随机密码、迁移、模型配置及内存 |
| MySQL Access denied | 是否改过已初始化数据库的密码；改 `.env` 不会自动修改数据库用户密码 |
| 管理员密码不对 | 用服务器首次创建账号时的密码；不能用电脑本地配置的密码代替 |
| 手机“网络连接错误” | 手机先用浏览器打开服务器后台；核对构建包 API、公网端口及手机调试模式 |
| 手机请求到达后提示微信登录失败 | AppID/AppSecret 是否匹配、微信错误码、服务器能否访问微信接口；不要输出 AppSecret |
| 绑定或小区列表为空 | 新数据库尚未初始化业务数据，按第 10 节操作 |
| 微信通知不发 | 两组授权、模板归属和字段、当前订单状态、投递列表中的错误 |
| AI 未启用 / 失败 | 三项配置、完整端点、供应商额度、后端重建及日志；FAQ 回答不代表模型已连通 |

求助时提供：进行到第几节、执行的命令、`cf ps -a` 和相关服务最后几十行错误日志。先遮盖密码、密钥、Token、手机号和住户信息，不要发送完整 `.env`。

## 完成标准

- [ ] 电脑和手机浏览器都能打开公网后台。
- [ ] 管理员能登录，物业账号和小区授权已建立。
- [ ] 手机 Wi-Fi 和移动数据下均可微信登录。
- [ ] 居民绑定房屋后，可以完成报价确认、师傅接单、完工和验收。
- [ ] 已按订阅额度与订单状态核对四类提醒。
- [ ] 能上传并查看测试附件。
- [ ] 知道如何查看日志、重新加载配置、停止和启动项目。
- [ ] 已保存配置和密码，并安排了可恢复的备份。

这一阶段完成后，再安排域名、HTTPS、微信发布及真实支付等正式上线工作。
