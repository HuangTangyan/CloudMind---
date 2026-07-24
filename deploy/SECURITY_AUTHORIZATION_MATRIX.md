# CloudMind 接口权限与资源归属矩阵

本文档是生产发布前的权限基线。新增接口时，必须同步更新本矩阵并补充自动化测试。

## 角色说明

- `PUBLIC`：无需登录，但登录、注册、刷新接口仍受限流保护。
- `USER`：已登录普通用户。
- `ADMIN`：已登录管理员。
- `OWNER`：资源的 `ownerId` 必须等于当前登录用户 ID。

## 接口矩阵

| 接口范围 | PUBLIC | USER | ADMIN | 资源归属约束 |
| --- | --- | --- | --- | --- |
| `POST /api/auth/register` | 允许 | 允许 | 允许 | 不适用；用户名唯一 |
| `POST /api/auth/login` | 允许 | 允许 | 允许 | 不适用；失败次数受限 |
| `POST /api/auth/refresh` | 允许 | 允许 | 允许 | 刷新令牌必须属于同一会话族 |
| `POST /api/auth/logout` | 允许 | 允许 | 允许 | 仅撤销提交的有效令牌 |
| `GET /api/auth/me` | 拒绝 | 允许 | 允许 | 仅返回当前用户 |
| `POST /api/auth/change-password` | 拒绝 | 允许 | 允许 | 仅修改当前用户；成功后撤销全部会话 |
| `GET /api/auth/sessions` | 拒绝 | 允许 | 允许 | 仅返回当前用户的会话设备 |
| `DELETE /api/auth/sessions/{familyId}` | 拒绝 | 允许 | 允许 | 仅撤销当前用户拥有的会话族 |
| `/api/files/**` | 拒绝 | 允许 | 允许 | 普通业务操作必须同时匹配当前 `ownerId` |
| `/api/knowledge/**` | 拒绝 | 允许 | 允许 | 问答检索范围必须限定在当前用户拥有的文件 |
| `/api/admin/**` | 拒绝 | 拒绝 | 允许 | 管理员操作；角色变更后立即撤销目标用户全部会话 |

## 强制实现规则

1. Controller 只负责输入与角色门禁，资源所有权必须在 Service/Repository 查询条件中强制执行。
2. 禁止先按全局主键查询，再在 Controller 中补做所有权判断；必须优先使用 `id + ownerId` 组合查询。
3. 管理员接口统一位于 `/api/admin/**`，由 Spring Security 的 `ROLE_ADMIN` 规则拦截。
4. 用户密码或角色变化后，撤销该用户全部访问令牌与刷新令牌。
5. 会话撤销必须同时匹配 `userId + familyId`，不得只按客户端传入的会话族 ID 操作。
6. 每类规则至少保留一项拒绝路径测试，防止未来重构时出现越权回归。

## 当前自动化证据

- `SecurityWebLayerTest`：未登录访问、普通用户访问管理接口、CORS 与安全响应头。
- `ResourceOwnershipSecurityTest`：文件详情、历史版本、知识问答范围均使用带 `ownerId` 的查询。
- `AuthServiceTest`：角色变更撤销会话、设备会话列表、用户范围内的会话撤销。
