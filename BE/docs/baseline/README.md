# Baseline response của stack microservice

Dùng để so với monolith sau khi refactor (xem `MONOLITH_PLAN.md`, phần "So với baseline").

## Cách chạy

```
node BE/docs/baseline/capture.mjs --base http://localhost:8080 --out BE/docs/baseline/microservices
```

- `microservices/`: chụp từ stack cũ chạy bằng IntelliJ, không phải docker compose (đã commit). Xem phần cuối file.
- `monolith/`: chụp từ monolith (không commit, đã gitignore).

Mỗi file là một kịch bản: `method`, `path`, `status`, header (`content-type`, `cache-control`, `x-frame-options`, `location`), `set-cookie` (chỉ tên và thuộc tính, không có giá trị token) và `body` (bỏ `timestamp`, `responseTime`; mảng chỉ giữ 2 phần tử đầu).

## Kịch bản đang lỗi sẵn ở stack cũ

| Kịch bản | Hiện tượng | Ghi chú |
|---|---|---|
| `user-not-found` | HTTP 200, envelope `success:true` | `user_service` cũ luôn trả 200. |
| `register` (trùng tên) | 500 `DecodeException` | `user_service` từ chối tên đã tồn tại bằng HTTP 200 + body lỗi, `auth_service` không giải mã được. Script dùng tên ngẫu nhiên để tránh. |
| `spoof-x-user-id` | 200 | Header `X-User-Id` giả được chấp nhận (lỗ hổng gateway). |

## Khác biệt được phép khi so với monolith

- id, email user test, số liệu attempt, rating.
- Các khác biệt bắt buộc khác (timeout gateway, 404, `/api/ai/admin/**`, `/api/ai-coach/**`, đăng ký trùng tên, pool kết nối): xem bảng "Khác biệt bắt buộc" trong `MONOLITH_PLAN.md`.
- Từ Phase 9: `HEAD /api/materials/<id>/file` đổi từ 200 PDF sang 302 tới `files.itshiken.app`.

## Lưu ý khi chạy stack cũ bằng IntelliJ

`spring-dotenv` đọc `.env` từ thư mục làm việc của tiến trình, mà IntelliJ không đặt thư mục đó là `BE/`. Cần khai báo biến trực tiếp trong run configuration của từng service:

- `material_service`: `FILE_BASE_PATH=<đường dẫn tới BE/material_service>`
- `ai-service`: `GEMINI_API_KEY`, `AI_DB_URL` (thiếu `AI_DB_URL` thì RAG lỗi, Gemini nhận context rỗng và sinh văn bản lặp tới `MAX_TOKENS`)

Biến nào không khai báo thì lấy mặc định trong yaml, nên khác compose ở 2 chỗ:

- Gateway và auth chạy profile `dev` thay cho `prod`.
- `COOKIE_SECURE` là `false`, nên cookie không có `Secure` và có `SameSite=Lax` (compose đặt `true`, cho ra `Secure; SameSite=None`). Monolith ở local cũng mặc định `false`, nên cookie khi so phải **giống hệt** baseline.
