# CloudMind 后端

Spring Boot 3 + JPA + MySQL + MinIO。

主要接口：

- `/api/auth/**` 登录注册
- `/api/files/**` 普通用户文件管理
- `/api/admin/users/**` 管理员用户管理
- `/api/admin/audit/**` 管理员全站文件审查
- `/api/admin/files/{id}/preview` 管理员审查预览
- `/api/admin/files/{id}/download` 管理员下载审查

`cloud_file.review_status` 和 `cloud_file.review_note` 是给后续 AI 审查预留的字段。
