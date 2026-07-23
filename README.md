# CloudMind v6 拖拽上传 + 视图切换 + AI 审查接口版

技术栈保持为：Vue3 + Vite 前端、Spring Boot 3 后端、Docker MySQL 元数据、Docker MinIO 对象存储。

本版本仍然不包含：分享链接、共享文件夹、在线编辑。

## 新增/恢复功能

### 普通用户网盘

- 新建文件夹
- 上传文件
- 拖拽上传文件/文件夹
- 上传文件夹
- 文件/文件夹重命名
- 移动、复制、删除
- 回收站恢复与永久删除
- PDF、图片、音频、视频、文本预览
- 音视频播放倍速控制
- 图片相册视图
- 同名覆盖上传生成历史版本
- 历史版本恢复
- 文件摘要、标签、正文搜索
- 列表视图 / 大缩略图视图切换
- 用户可查看标签和预览摘要，但审查状态只在管理后台显示
- 支持手动重新生成摘要和标签
- 用户容量显示：总空间、已用空间、未用空间组合显示条
- 弹性上传规则：配额容量本身不变，但系统允许单次上传/复制后最多临时达到配额的 150%

### 管理后台

管理员账号登录后可进入“管理后台”。

- 用户管理
  - 查看用户权限等级：USER / VIP / SVIP / ADMIN
  - 查看账号状态
  - 查看总容量、已用容量、未用容量
  - 新增用户
  - 修改权限等级
  - 修改网盘配额
  - 重置密码，重置后会直接显示新密码
  - 封禁/解封账号
  - 删除用户及其文件
  - 当前登录管理员自己的那一栏不会显示重置密码、封禁、删除等危险操作

- 全站违规审查
  - 第一级显示用户
  - 第二级进入该用户的文件夹/文件
  - 支持像文件夹一样逐级展开
  - 支持审查预览
  - 无法预览的文件支持下载审查
  - 支持手动标记正常/异常/待审查
  - 审查状态对普通用户不可见
  - 支持单文件 AI/规则审查
  - 支持全站 AI/规则审查

- AI 接口配置
  - 管理后台可配置 DeepSeek / OpenAI 兼容 API
  - 保存 Base URL、模型名和 API Key
  - 支持连接测试
  - 未配置 Key 时自动退回本地关键词规则审查

## 启动

先复制环境变量模板并填写随机密码：

```bash
copy .env.example .env
```

必须设置 MySQL、MinIO 凭据。需要创建首个管理员时，同时填写
`CLOUDMIND_BOOTSTRAP_ADMIN_USERNAME` 和不少于 14 位的
`CLOUDMIND_BOOTSTRAP_ADMIN_PASSWORD`。管理员首次登录后必须立即修改密码。

先启动 MySQL 和 MinIO：

```bash
docker compose up -d mysql minio
```

启动后端：

```bash
cd backend
mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

项目不再包含默认管理员账号或默认密码。生产环境请使用
`SPRING_PROFILES_ACTIVE=prod`，并由部署平台注入数据库、MinIO 和
`CLOUDMIND_AI_API_KEY` 等敏感信息。

已有数据库在首次使用生产配置前，执行一次：

```bash
mysql -u <管理员> -p <数据库名> < backend/deploy/001-security-batch1.sql
```

开发配置的 `ddl-auto=update` 会自动补充该字段；生产配置使用
`ddl-auto=validate`，不会静默修改数据库结构。

如果旧数据库表结构和新版字段冲突，可以清空开发环境数据卷：

```bash
docker compose down -v
docker compose up -d mysql minio
```
