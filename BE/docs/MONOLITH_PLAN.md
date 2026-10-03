# Kế hoạch: gom backend microservice → modular monolith (deploy free)

## Context

**Hiện trạng:**
- 7 tiến trình Spring Boot: discovery_server (Eureka), api_gateway, auth, user, exam, material, ai.
- 5 database Neon riêng: auth_db, user_db, exam_db, material_db, ai_db.
- Trước đây chạy bằng docker compose trên VPS Contabo. **VPS đã ngừng** vì hết tiền.
- FE Next.js chạy trên **Vercel**. FE gọi `/api/*` rồi Next rewrite sang `BE_URL`.

**Mục tiêu:** gom lại để chạy được trên hạ tầng **miễn phí**, không phải sửa code FE.

| Thành phần | Nơi chạy | Giới hạn cần thiết kế quanh |
|---|---|---|
| Backend | **Render** free web service (Docker) | 512MB RAM, 0.1 CPU, ngủ sau 15 phút không có request (thức dậy mất khoảng 1 phút), không có disk lâu dài, mỗi workspace 750 giờ chạy/tháng. **Băng thông 5GB/tháng** (từ 1/8/2026), nên không cho file nặng đi qua Render |
| DB | **Neon** free, 1 database `itshiken_db` | 0.5GB/project, **100 CU-giờ compute/tháng** (khoảng 400 giờ ở 0.25 CU), 5GB truyền dữ liệu/tháng. Neon tự ngủ sau 5 phút không có kết nối, và **bắt buộc phải được ngủ**, nếu không sẽ hết giờ compute |
| PDF tài liệu | **Cloudflare R2**, bucket public qua `files.itshiken.app` | 10GB, 1 triệu lượt ghi và 10 triệu lượt đọc/tháng, không tính phí băng thông. Phải gắn thẻ hoặc PayPal. Domain phải dùng DNS của Cloudflare. Backend chỉ trả redirect tới R2, không đọc PDF |
| Frontend | **Vercel** (giữ nguyên) | Chỉ đổi env `BE_URL` thành URL Render |

### Thứ tự làm và lý do

Mỗi việc một nhánh. Nhánh refactor làm trước; các nhánh sau đều tạo từ `main` **sau khi** refactor đã merge.

| Thứ tự | Nhánh | Phase | Nội dung |
|---|---|---|---|
| 1 | `refactor/monolith` (nhánh hiện tại) | 0–7 | Refactor thuần, giữ nguyên hành vi 100%. Mọi bảng ở schema `public`, PDF vẫn đọc từ ổ đĩa. Kết thúc bằng **mốc tương đương** (7.2), dọn microservice (7.3), tài liệu (7.4), merge (7.5) |
| 2 | `refactor/db-schemas` | 8 | Chia schema bằng migration Flyway `V2`. Vẫn giữ nguyên hành vi, so baseline |
| 3 | `fix/x-user-id-spoofing` | F1 | Sửa lỗi đầu tiên, bắt buộc trước deploy |
| 4 | `feature/r2-materials` | 9 | PDF lên Cloudflare R2. Thay đổi hành vi có chủ đích (file trả 302 thay cho 200) |
| 5 | `deploy/render` | 10 | Chỉ merge khi 1–4 đã ở trên `main`, vì Render tự deploy từ `main` |
| sau | `fix/<tên>` | F2… | Mỗi lỗi trong "Việc để sau" một nhánh, sửa dần sau khi production ổn định |

Nhánh 2, 3, 4 không phụ thuộc nhau về code nên làm song song được. Nên merge 2 trước 4, vì bước 9.3 truy vấn `material.learning_materials` (tên có schema).

### Quyết định đã chốt
- **Modular monolith**: các module `common`, `identity` (auth + user gộp lại), `exam`, `material`, `ai`, và `app` (module duy nhất có main class).
- **1 database, chia schema theo module** (ở Phase 8): `identity`, `exam`, `material`, `ai`. Extension `vector` và bảng lịch sử Flyway nằm ở `public`.
- **Dữ liệu cũ chỉ là dev/demo**, nên chỉ copy *nội dung*: đề, câu hỏi, topic, tài liệu, knowledge_chunks.
  - **Đã đổi (2026-10-04): GIỮ cả user và lịch sử làm bài.** Đã copy thêm `users`, `credentials`, `exam_attempts`, `attempt_answers`, `exam_ratings`, `user_topic_mastery`. `refresh_tokens` vẫn bỏ (token cũ vô hiệu vì `JWT_SECRET` mới). Mật khẩu là bcrypt nên user cũ đăng nhập lại bình thường.
  - Vì vậy DB mới **có sẵn 53 user**. Chỗ nào trong plan ghi "DB mới chưa có user" thì không còn đúng.
- **Ingest RAG chỉ chạy ở máy local**: bật `INGEST_ENABLED=true` và ghi thẳng vào Neon. Trên Render thì chặn, vì 3 lý do:
  - code đọc PDF từ ổ đĩa, mà Render không có PDF;
  - `POST /ingest` là request đồng bộ kéo dài hàng chục phút;
  - endpoint chưa có xác thực admin.
  Dữ liệu `knowledge_chunks` đã copy sẵn nên không cần ingest ngay. Nếu sau này cần ingest thường xuyên thì làm workflow GitHub Actions bấm tay.
- **Cloudflare R2 là bản gốc của PDF** (từ Phase 9): `Material_Data` bị **xoá khỏi git** sau khi upload và xác minh.
  - Khi cần ingest thì script tải PDF từ R2 về `BE/.materials-cache/` (đã gitignore).
  - Lịch sử git vẫn còn 82MB, không viết lại lịch sử.
  - **Bạn nên giữ thêm một bản sao lưu** (zip lên Drive).
- Mỗi việc một nhánh (xem bảng "Thứ tự làm và lý do"). Render tự deploy từ `main`, nên chỉ merge `deploy/render` khi mọi nhánh cần thiết đã vào `main`.

### Hợp đồng với FE (không được phá)
1. Response JSON được bọc: `{success, statusCode, message, data, path, timestamp, responseTime}`. FE luôn đọc `.data`.
2. Cookie `access_token` (JWT HS256, `sub` = userId) và `refresh_token`.
3. Khi gọi endpoint được bảo vệ mà thiếu hoặc hết hạn token thì nhận **401**, FE sẽ tự gọi refresh.
4. `/api/materials/{id}/file[?download=true]` được dùng trực tiếp làm `href`/`iframe`:
   - Phase 3–8: trả PDF đọc từ ổ đĩa như cũ;
   - từ Phase 9: trả **302** tới `https://files.itshiken.app/...`. Tải về thì thêm tiền tố `download/`.
   PDF và SSE (`/api/coach/**/stream`) **không** bị bọc envelope.

### Hành vi của gateway mà monolith phải tự làm (nguồn: `api_gateway/src/main/...`)
- **JWT**: đọc cookie `access_token` rồi gắn header `X-User-Id` xuống controller. Token sai hoặc thiếu thì đi tiếp như ẩn danh, **trừ** các protected path thì trả 401.
  - Gateway **bỏ qua** request `OPTIONS` và các path `/api/auth/**`, `/actuator/**` (không xử lý JWT gì cả).
  - Token hợp lệ: gán đè `X-User-Id`. Token sai hoặc thiếu: **giữ nguyên** `X-User-Id` client tự gửi (lỗ hổng có sẵn, giữ nguyên cho tới bước F1).
  - Protected path: `GET /api/users/me`, `POST /api/exams`, `PUT|DELETE /api/exams/**`, `POST /api/exams/*/submit`, `POST /api/exams/*/rating`, `GET /api/attempts/me/**`, `POST /api/materials`, `PUT|DELETE /api/materials/**`.
  - Body 401: message `"Bạn chưa đăng nhập hoặc phiên đăng nhập đã hết hạn"`.
- **Envelope** (`ResponseWrapperFilter.java`): chỉ bọc khi content-type là JSON; bỏ qua `/actuator`.
  - `success = status < 400`.
  - Khi thành công: `message = body.message ?: "OK"`, `data = body.data` nếu body có key `data`, ngược lại `data = body`.
  - Khi lỗi: `message = body.message ?: "Đã có lỗi xảy ra"`, `data = null`.
  - Luôn có thêm `path`, `timestamp` (ISO Instant), `responseTime` (`"N ms"`).
- **CORS**: origin lấy từ `cors.allowed-origins`, `allowCredentials(true)`. Thực tế trình duyệt chỉ gọi domain Vercel rồi Vercel proxy sang Render, nên CORS gần như không dùng tới, nhưng vẫn giữ để chạy local.
  - Gateway chọn origin theo profile: `dev` chỉ có `http://localhost:3000` và `:3001`; `prod` chỉ có `ALLOWED_ORIGINS`.
- **Header**: gateway gán đè (`set`) 4 header `nosniff`, `X-Frame-Options SAMEORIGIN`, `Referrer-Policy strict-origin-when-cross-origin`, `Permissions-Policy geolocation=(), microphone=(), camera=()` lên mọi response.
  - Ngoài 4 header đó, response của auth, user, exam và ai còn có các header mặc định của Spring Security riêng từng service: `Cache-Control: no-cache, no-store, ...`, `Pragma`, `Expires`, `X-XSS-Protection`.
  - **material không có Spring Security**, nên response của material **không có** các header mặc định đó.
- **`X-Request-Id`**: gateway chỉ gắn vào request gửi xuống service để ghi log, **không** gắn vào response trả cho client.
- Bỏ hẳn, không làm lại: Eureka, load balancer, circuit breaker, retry, warmup scheduler.

### Nguyên tắc: refactor phải giữ nguyên hành vi 100%
- Trên nhánh `refactor/monolith` (Phase 1–7) và `refactor/db-schemas` (Phase 8), mọi request phải cho ra **cùng status, cùng body, cùng header** như stack cũ. Kể cả các lỗi có sẵn: phải giữ nguyên lỗi.
- Thấy chỗ nào muốn sửa cho đúng: **chỉ ghi vào mục "Việc để sau"** ở cuối plan (ghi file, hiện tượng, cách sửa đề xuất). **Không sửa ngay.**
- Chỉ bắt đầu sửa sau **mốc tương đương** (bước 7.2), tức là khi đã chứng minh hai bản cho ra kết quả giống nhau, và **trên nhánh `fix/*` riêng** (Phase F). Mỗi nhánh sửa một việc, có kiểm tra riêng. Như vậy hỏng ở đâu thì biết ngay là do việc sửa đó, không lẫn với refactor.
- Chỉ những khác biệt trong danh sách dưới đây là được phép, vì không thể giữ khi bỏ gateway hoặc gộp process. Gặp khác biệt nào ngoài danh sách thì **dừng và báo**.

### Khác biệt bắt buộc (không tránh được, đã chấp nhận)
| Khác biệt | Lý do không giữ được |
|---|---|
| Không còn timeout, retry, circuit breaker của gateway. Cũ: request chậm quá 30s có thể bị cắt thành 503 "Dịch vụ tạm thời không khả dụng", GET lỗi 503 được thử lại 2 lần. Mới: request chạy tới khi xong | Gateway bị bỏ. Cố tình dựng lại các lỗi timeout thì vô nghĩa |
| Path không có route. Cũ: gateway trả 404 dạng JSON của WebFlux, không bọc envelope. Mới: 404 qua `/error` của Spring, có bọc envelope | Không còn router của gateway |
| `/api/ai/admin/**`. Cũ: không có route nên 404. Mới: 403 (`denyAll`) khi `INGEST_ENABLED=false` | Ingest phải chặn được từ ngoài nhưng vẫn mở được ở local |
| `/api/ai-coach/**`. Cũ: chỉ gọi nội bộ, ngoài vào là 404. Mới: xoá controller ở 7.1, ngoài vào vẫn 404 (dạng `/error`) | ai gọi thẳng service Java, không còn qua HTTP |
| Đăng ký với tên đã tồn tại. Cũ: 500, message là lỗi `DecodeException` của Feign. Mới: 500, message `"Người dùng đã tồn tại"` | Không còn Feign. Status vẫn giữ là 500 |
| Một pool kết nối chung (5) thay cho mỗi service một pool; Tomcat tối đa 40 thread | Gói free của Render (512MB) và Neon (100 CU-giờ). Không đổi kết quả của request, chỉ ảnh hưởng khi rất nhiều người dùng cùng lúc |

---

## Cấu trúc đích (sau Phase 10)

```
BE/
├── pom.xml                 # parent: modules common, identity, exam, material, ai, app
├── common/   com.edu.common       JwtService (generate + verify), JwtAuthFilter, ApiEnvelopeAdvice, RequestTimingFilter
├── identity/ com.edu.identity     .auth.* (từ auth_service) + .user.* (từ user_service)     → schema identity
├── exam/     com.edu.exam         (git mv từ exam-service, giữ nguyên package)              → schema exam
├── material/ com.edu.material     (git mv từ material_service, đổi package)                 → schema material
├── ai/       com.edu.ai           (git mv từ ai-service, giữ nguyên package)                → schema ai
├── app/      com.edu.ItshikenApplication, SecurityConfig, application.yaml, db/migration/{V1__baseline, V2__split_schemas}.sql, Dockerfile
├── tools/materials-sync/          script Node (S3 API): upload PDF lên Cloudflare R2 và tải PDF về cache local
└── .materials-cache/              (gitignore) bản PDF tải từ R2 về, chỉ dùng để ingest local
render.yaml                        # (gốc repo) blueprint của Render
```

Phụ thuộc giữa các module:
- `app` → tất cả module còn lại.
- `identity` → `common`.
- `ai` → `exam` (gọi `AICoachService.analyzeUserKnowledge` trực tiếp).

Quy tắc chung cho mọi module domain:
- **Không** có `@SpringBootApplication`, `application.yaml`, `spring-boot-maven-plugin`, Eureka hay Feign.
- `@RestControllerAdvice` phải đặt tên class riêng và có `basePackages` của chính module đó.
- Phase 1–7 **không** đụng tới schema: entity giữ nguyên `@Table(name = ...)`, native query giữ nguyên tên bảng.

Main class nằm ở package `com.edu`, nên component scan, entity scan và repository scan tự phủ hết mọi module.

Bảng phân schema (áp dụng ở Phase 8):

| Schema | Bảng |
|---|---|
| `identity` | users, credentials, refresh_tokens |
| `exam` | categories, exams, questions, topics, topic_edges, exam_ratings, exam_attempts, attempt_answers, user_topic_mastery |
| `material` | learning_materials |
| `ai` | knowledge_chunks |

---

## Cách đọc từng bước

Mỗi bước có:
- **Model**: model nên dùng để thực thi.
- **Làm**: các việc cần làm.
- **Kiểm tra**: điều phải đúng thì mới được commit.
- **Commit**: commit message.

Hết mỗi bước là **DỪNG**, để bạn kiểm tra rồi mới sang bước sau.

Khi buộc phải làm khác plan, ghi vào đầu bước đó (dạng `> **Làm khác plan:** ...`) và **chỉ ghi 2 thứ**: làm khác cái gì, và tại sao (gặp vấn đề gì). Không ghi trạng thái tạm thời (port đang bận, tiến trình đang chạy), việc chưa làm mà plan không yêu cầu, hay kết quả kiểm tra.

Quy ước chạy:
- **Máy này không có `mvn`/`java` trên PATH.** Chạy Maven bằng wrapper (PowerShell, đứng ở `BE/`):
  `$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; .\exam-service\mvnw.cmd -f pom.xml <goals>`.
  - Sau bước 6.1 đường dẫn wrapper thành `.\exam\mvnw.cmd`.
  - Bước 7.3 sẽ đưa wrapper ra `BE/`.
  - Trong plan, chỗ nào ghi `mvn ...` thì hiểu là lệnh wrapper này.
- `JAVA_HOME` là **JDK 25**, còn Docker build dùng JDK 21. Từ JDK 23, Lombok **chỉ chạy** khi pom khai báo `annotationProcessorPaths`, nên mọi module dùng Lombok phải có cấu hình này.
- `BE/CLAUDE.md` hiện chỉ là bản tạm trỏ về plan này; bước 7.4 sẽ viết lại. Các tài liệu kiến trúc microservice cũ đã bị xoá (vẫn còn trong git history). Nếu gặp tài liệu nào mâu thuẫn với plan thì **tin plan**.
- Chạy app local gồm 2 lệnh:
  1. `mvn -q -pl app -am install -DskipTests` (build app và các module nó phụ thuộc, cài vào `~/.m2`);
  2. `mvn -q -pl app spring-boot:run`.
  - **Không** gộp thành `-pl app -am spring-boot:run`: làm vậy Maven chạy goal `run` trên cả các module thư viện và báo lỗi không tìm thấy main class.
  - Port **8080**, khớp `BE_URL=http://localhost:8080` của FE dev.
  - Env đọc từ `BE/app/.env` (đã gitignore nhờ `**/.env`).
  - Thư mục làm việc là `BE/app`, nên đường dẫn tương đối trong yaml tính từ đó.
- Lệnh psql/pg_dump chạy qua `docker run --rm postgres:17 ...`. Dùng `postgres:18` nếu báo lệch version. Chuỗi kết nối dạng `postgresql://user:pass@host/db?sslmode=require`.
  - Dùng host **direct** của Neon (không có `-pooler`), cả cho `DB_URL` của app.
    - Neon khuyến nghị kết nối direct cho migration và pg_dump. Pooler là PgBouncer ở chế độ transaction, không hỗ trợ `SET` và advisory lock theo session, mà Flyway chạy migration lúc app khởi động.
    - Pool app chỉ có 5 kết nối nên dùng direct không vấn đề gì.
- **So với baseline** (từ bước 4.1 trở đi):
  - sau khi chạy phần Kiểm tra của bước, chạy thêm `node BE/docs/baseline/capture.mjs --base http://localhost:8080 --out BE/docs/baseline/monolith`;
  - so với `BE/docs/baseline/microservices` cho **các kịch bản của module đã chuyển**. Kịch bản của module chưa chuyển sẽ lỗi, bỏ qua;
  - khác biệt nằm ngoài danh sách được phép trong `baseline/README.md` thì **dừng và báo**, không tự sửa cho khớp;
  - thư mục `baseline/monolith` không commit (thêm vào `.gitignore`).
- **Không bao giờ commit secret** (DB password, key R2, API key). Secret chỉ để trong `.env` hoặc dashboard.

---

## Phase 0 — Chuẩn bị

### [x] 0.1 Tạo nhánh và đưa plan vào repo
> Kết quả baseline (2026-10-03): mọi module build pass, **trừ `material_service`**. Module này lỗi `cannot find symbol getXxx()/log` vì Lombok không chạy trên JDK 25 khi pom thiếu `annotationProcessorPaths`. Bước 3.1 sẽ thêm cấu hình này. `api_gateway` không nằm trong reactor.

### [x] 0.2 Chạy stack microservice cũ ở local
> **Làm khác plan:** không chạy bằng docker compose, mà chạy 7 service bằng run configuration của IntelliJ.
> - Env khai báo trong run config, vì `spring-dotenv` không thấy `BE/.env` (chi tiết ở `baseline/README.md`).
> - Biến nào không khai báo thì lấy mặc định trong yaml, nên khác compose ở 2 chỗ: gateway và auth chạy profile `dev` thay cho `prod`, và `COOKIE_SECURE` là `false`.
> - `BE/docker-compose.yml` không bị sửa.
- Model: Sonnet.
- Mục đích: xác nhận hiện trạng trước khi refactor. Lỗi nào có sẵn từ trước thì không bị đổ cho refactor. Cũng kiểm tra luôn 5 DB cũ còn kết nối được, vì Phase 1 phải dump từ chúng.
- **Quy tắc: không sửa code Java cũ** (code này sắp bị xoá).
  - Chỉ được sửa `BE/.env`, và nếu cần thì sửa biến môi trường trong `BE/docker-compose.yml`. Thay đổi ở compose chỉ để chạy local, **không commit** (`git checkout BE/docker-compose.yml` khi xong).
  - Service nào lỗi vì code thì ghi lại (tên service, log lỗi), rồi **dừng hỏi** xem có cần nó cho baseline hay không.
- Làm:
  - Tạo `BE/.env` từ `BE/.env.example` (bạn cung cấp giá trị):
    - URL 5 DB cũ, `DB_USERNAME`, `DB_PASSWORD`;
    - `JWT_SECRET`: chuỗi bất kỳ ≥ 32 ký tự. Không cần đúng secret cũ, vì mọi service đọc chung biến này;
    - `ALLOWED_ORIGINS=http://localhost:3000`;
    - `GEMINI_API_KEY`, `LLM_PROVIDER=gemini`.
  - Đứng ở `BE/`, chạy `docker compose up --build -d`, rồi xem `docker compose ps` và log của từng service.
- Kiểm tra:
  - `http://localhost:8761` (Eureka) thấy đủ các service: API-GATEWAY, AUTH-SERVICE, USER-SERVICE, EXAM-SERVICE, MATERIAL-SERVICE, AI-SERVICE.
  - `curl localhost:8080/api/exams` trả envelope có dữ liệu.
  - Báo cáo service nào chạy, service nào lỗi, kèm lý do.
- Commit: không có (`.env` đã gitignore).

### [x] 0.3 Chụp baseline response của stack cũ
> **Làm khác plan:** stack cũ chạy bằng IntelliJ (xem 0.2), nên:
> - cookie trong baseline không có `Secure` và có `SameSite=Lax`. Monolith ở local cũng mặc định `COOKIE_SECURE=false`, nên cookie phải **giống hệt**, không còn là khác biệt được phép;
> - xong thì tắt các run config trong IntelliJ thay cho `docker compose down`.
- Model: Sonnet.
- Làm:
  - Tạo `BE/docs/baseline/capture.mjs`, chạy bằng Node có sẵn của FE (dùng `fetch` có sẵn, không cần dependency). Tham số: `--base http://localhost:8080 --out <thư mục>`.
  - Script đi qua các kịch bản dưới đây. Mỗi kịch bản ghi 1 file JSON gồm: `method`, `path`, `status`, các header `content-type`, `cache-control`, `x-frame-options`, `location`, `set-cookie`, và `body`.
    - `set-cookie` chỉ giữ tên cookie và các thuộc tính (Path, Max-Age, HttpOnly, SameSite), **bỏ giá trị token**.
    - Trong body, bỏ `timestamp` và `responseTime`. Mảng chỉ giữ 2 phần tử đầu, để file nhỏ và so sánh được.
  - Kịch bản:
    1. **Ẩn danh:** `GET /api/categories`, `/api/exams`, `/api/exams/popular`, `/api/exams/<id>`, `/api/exams/<id>/questions`, `/api/exams/<id>/rating`, `/api/materials?page=0&size=5`, `/api/materials/<id>`, `HEAD /api/materials/<id>/file`, `/api/users/999999` (lỗi).
    2. **Lỗi xác thực:**
       - `GET /api/users/me` và `POST /api/materials` không có cookie (401);
       - `GET /api/exams/<id>/rating` kèm header giả `X-User-Id: 1` (ghi lại lỗ hổng hiện tại).
    3. **Có đăng nhập:**
       - đăng ký user test với email ngẫu nhiên, rồi login (giữ cookie trong bộ nhớ);
       - `GET /api/users/me`;
       - `POST /api/exams/<id>/submit` với vài đáp án, rồi `GET /api/attempts/me/summary` và `GET /api/attempts/<attemptId>`;
       - `POST /api/exams/<id>/rating`;
       - `POST /api/auth/refresh` (gửi cookie `refresh_token`).
    4. **AI:**
       - `GET /api/coach/<userId>/analysis`;
       - `GET /api/coach/node/<topicId>/explain/stream?userId=<id>`: chỉ đọc 2 giây đầu, ghi content-type và vài event đầu.
       - Bỏ qua `learning-path` để đỡ tốn quota Gemini.
  - Lấy `<id>` bằng cách đọc từ chính response danh sách. Không hardcode.
  - Chạy với `--out BE/docs/baseline/microservices` khi stack cũ đang chạy.
  - Ghi `BE/docs/baseline/README.md`:
    - cách chạy script;
    - danh sách kịch bản **đang lỗi sẵn** ở stack cũ, nếu có;
    - các khác biệt **được phép** khi so với monolith sau này:
      - id, email user test, số liệu attempt, rating;
      - kịch bản giả mạo `X-User-Id`: monolith không còn đọc header giả;
      - `GET /api/users/999999`: `user_service` cũ trả HTTP 200 (lỗi có sẵn), nên envelope ghi `success:true`. Monolith trả status lỗi thật và `success:false`;
      - ~~thuộc tính cookie `Secure`/`SameSite`~~: đã bỏ, xem ghi chú đầu bước;
      - header `cache-control` trên endpoint material: `material_service` cũ không có Spring Security, còn monolith thêm `no-store`;
      - từ Phase 9: `HEAD /api/materials/<id>/file` đổi từ 200 PDF sang 302 tới `files.itshiken.app`.
  - **Để stack cũ chạy tiếp**, chờ bạn kiểm tra FE xong.
- Kiểm tra (bạn tự làm, khi stack cũ vẫn đang chạy):
  - Mở FE local (`npm run dev`, `BE_URL=http://localhost:8080`) và đi các luồng chính: đăng ký, đăng nhập, xem đề, làm bài, lịch sử, xem và tải PDF, AI coach.
  - Ghi những luồng **đang hỏng sẵn** vào `baseline/README.md`.
  - Xong thì tắt stack cũ (xem ghi chú đầu bước).
- Commit: `test: capture microservice response baseline`. File baseline không chứa token hay mật khẩu; kiểm tra lại trước khi commit.

## Phase 1 — Database mới (không cần code Java)

### [x] 1.1 Tạo DB (bạn tự làm)
- Model: không cần (bạn làm tay).
- Làm:
  - Trên Neon, xem **dung lượng project đang dùng**. Free khoảng 0.5GB, mà bước 1.3 sẽ nhân đôi `knowledge_chunks` (phần lớn dung lượng nằm ở `ai_db`).
    - **Không xoá DB cũ nào trước khi xong bước 1.4**, vì bước 1.2 cần schema từ đủ 5 DB.
    - Nếu không đủ chỗ thì dừng và hỏi.
  - Tạo database `itshiken_db`, rồi chạy `CREATE EXTENSION IF NOT EXISTS vector;` trên đó.
  - Tạo `BE/app/.env`, mỗi dòng dạng `KEY=value`, **không có dấu nháy** (để bước 10.1 dùng được làm `--env-file` của docker):
    - `DB_URL=jdbc:postgresql://<host direct>/itshiken_db?sslmode=require`, `DB_USERNAME`, `DB_PASSWORD`;
    - `JWT_SECRET`: chuỗi ngẫu nhiên **≥ 32 ký tự**, tạo mới cũng được vì user cũ bỏ;
    - `GEMINI_API_KEY`, `LLM_PROVIDER=gemini`.
- Kiểm tra: kết nối được DB mới.

### [x] 1.2 Dựng schema cũ vào DB mới (scratch, không commit)
> **Làm khác plan:**
> - `material_db` có **3 bảng thừa** `thread_likes`, `thread_media`, `thread_posts` (tính năng diễn đàn cũ, grep BE và FE không thấy code nào dùng). Đã bỏ bằng `--exclude-table='thread_*'`. Kết quả đúng 14 bảng.
- Model: Haiku.
- Làm: với từng DB cũ chạy lệnh dưới, pipe thẳng vào DB mới:
  `pg_dump "<old>" --schema-only --no-owner --no-privileges --exclude-table=flyway_schema_history --exclude-table=knowledge_chunks_backup_word_window | psql "<new>"`
  - Chạy trong 1 container: `docker run --rm postgres:17 sh -c '...'`.
  - Lỗi kiểu "already exists" của `schema public` hoặc `extension vector` thì bỏ qua được. Ghi lại các lỗi khác.
- Kiểm tra: `\dt public.*` ra đủ 14 bảng ở bảng phân schema.
  - Nếu có **thêm bảng nào khác** (ví dụ bảng sót lại của `question_service` hay `result-service` cũ, từng được `ddl-auto` tạo ra) thì liệt kê và **dừng hỏi** trước khi đi tiếp. Bảng nào còn ở đây sẽ đi thẳng vào V1 baseline.

### [x] 1.3 Copy dữ liệu nội dung
> **Làm khác plan:** copy thêm các bảng người dùng (xem mục "Quyết định đã chốt"):
> - user_db: `users`; auth_db: `credentials`; exam_db: `exam_attempts`, `attempt_answers`, `exam_ratings`, `user_topic_mastery`.
> - Số dòng khớp DB cũ: categories 4, topics 50, exams 5, questions 315, topic_edges 64, learning_materials 119, knowledge_chunks 5543, users 53, credentials 61, exam_attempts 179, attempt_answers 12325, exam_ratings 38, user_topic_mastery 271.
> - Sequence khớp `max(id)`: users 77, credentials 78, learning_materials 1256, knowledge_chunks 5544. Các bảng exam dùng id kiểu `varchar` nên không có sequence.
> - **Dữ liệu mồ côi:** vì không có khóa ngoại xuyên DB, một số user đã bị xóa từ trước nên có bản ghi trỏ vào user không tồn tại. Đã dọn bằng tay: xóa 33 `exam_attempts` (kèm 2380 `attempt_answers`), 12 `exam_ratings`, 41 `user_topic_mastery`, 11 `credentials`. Sau dọn: `exam_attempts` còn 146 dòng, mọi loại mồ côi bằng 0.
- Model: Sonnet.
- Làm: chạy `pg_dump --data-only --no-owner` rồi pipe vào DB mới:
  - từ exam_db: `-t categories -t topics -t exams -t questions -t topic_edges`;
  - từ material_db: `-t learning_materials`;
  - từ ai_db: `-t knowledge_chunks`.
- Kiểm tra:
  - `count(*)` của từng bảng bằng DB cũ.
  - Với `learning_materials` và `knowledge_chunks`: `max(id)` ≤ `last_value` của sequence. Nếu không thì chạy `setval` thủ công.
- Ghi số liệu count vào báo cáo của bước.

### [x] 1.4 Sinh `V1__baseline.sql` từ DB mới
> **Làm khác plan:** ngoài các dòng plan nêu, phải xóa thêm 2 dòng `\restrict <token>` và `\unrestrict <token>` do `pg_dump` bản mới tự thêm (là lệnh riêng của psql, Flyway không hiểu). Sau Opus review còn xóa thêm:
> - `SET transaction_timeout = 0;`: tham số chỉ có từ PG17, chạy V1 trên PG16 (Docker local, CI) sẽ lỗi. Giá trị 0 vốn là mặc định nên xóa không đổi hành vi.
> - `COMMENT ON EXTENSION vector ...`: đòi quyền owner của extension, nếu extension do role khác tạo thì cả migration rollback. Chỉ là chú thích nên xóa không mất gì.
- Model: Sonnet. Nhờ Opus review file SQL.
- Làm:
  - Chạy `pg_dump "<new>" --schema-only --no-owner --no-privileges`, ghi vào `BE/app/src/main/resources/db/migration/V1__baseline.sql`.
  - Sửa tay:
    - đảm bảo có `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;` trước mọi `CREATE TABLE`;
    - **xoá** dòng `SELECT pg_catalog.set_config('search_path', '', false);`;
    - xoá các lệnh liên quan tới `schema public` (`CREATE SCHEMA public`, `COMMENT ON SCHEMA public`) nếu có;
    - giữ nguyên các tham chiếu dạng `public.vector`.
  - Lý do không dùng lại migration cũ: Flyway cũ không dựng được DB mới. V3 chạy trước khi bảng `questions` tồn tại, bảng `topics` không có migration nào tạo, và `vector(768)` lệch với `vector(3072)` thật.
- Kiểm tra:
  - Chạy thử file trên một DB **trống** (tạo tạm `itshiken_tmp` trên Neon, xong thì xoá) bằng `psql -v ON_ERROR_STOP=1`: không lỗi, ra đủ 14 bảng.
  - Kiểu cột `embedding` khớp với DB thật.
- Commit: `feat(db): add consolidated baseline schema`.

## Phase 2 — Khung app

### [ ] 2.1 Module `app` chạy được, chưa có domain nào
- Model: Sonnet.
- Làm:
  - Parent `BE/pom.xml`: thêm module `app`. Giữ nguyên các module cũ, xoá dần ở các bước sau.
  - Tạo `app/pom.xml` (parent `com.edu:BE`) với các dependency:
    - `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `flyway-database-postgresql`, `postgresql`;
    - `spring-boot-starter-actuator`, `spring-dotenv`, `lombok`;
    - plugin `spring-boot-maven-plugin`, và `maven-compiler-plugin` có lombok trong annotationProcessorPaths (copy cấu hình từ `auth_service/pom.xml`).
  - Tạo `app/src/main/java/com/edu/ItshikenApplication.java` với `@SpringBootApplication @EnableCaching`.
  - Tạo `app/src/main/resources/application.yaml`:
    - server: `server.port: ${PORT:8080}` (Render tự cấp biến `PORT`), `server.compression` (copy từ exam), `server.tomcat.threads.max: 40`, `spring.application.name: itshiken-backend`;
    - datasource: `spring.datasource.url/username/password: ${DB_URL}/${DB_USERNAME}/${DB_PASSWORD}`;
    - Hikari (để Neon được ngủ khi rảnh): `maximum-pool-size: 5`, `minimum-idle: 0`, `idle-timeout: 240000`, `max-lifetime: 600000`, `connection-timeout: 15000`. **Không** đặt `keepalive-time`;
    - JPA: `ddl-auto: none`, `open-in-view: true`, dialect và batch copy từ exam;
    - Flyway: `baseline-on-migrate: true`, `baseline-version: 1`. DB đã có bảng ở `public`, nên Flyway chỉ đánh dấu baseline mà không chạy lại V1. DB trống thì Flyway chạy V1;
    - khác: `spring.cache.type: none`, `spring.sql.init.mode: never`, `spring.mvc.async.request-timeout: 120000`;
    - actuator: expose `health,info`, `management.health.db.enabled: false` (để ping health không đánh thức Neon).
  - Tạo `app/.env.example` liệt kê các biến của bước 1.1.
- Kiểm tra:
  - Chạy app theo 2 lệnh ở phần Quy ước chạy: khởi động không lỗi.
  - Log có dòng Flyway baseline version 1.
  - `curl localhost:8080/actuator/health` trả `UP`.
- Commit: `feat(app): add monolith application skeleton`.

## Phase 3 — Material (module đơn giản nhất, làm trước để có endpoint test)

### [ ] 3.1 Chuyển material_service → `material`
- Model: Sonnet.
- Làm:
  - `git mv material_service material`. `Material_Data` đi theo, nằm ở `BE/material/Material_Data`.
  - Đổi package `com.testwebsite.backend.material` → `com.edu.material`: dời thư mục bằng `git mv`, sửa dòng `package` và `import`.
  - Xoá:
    - `MaterialApplication.java`;
    - `config/CorsConfig.java`;
    - `src/main/resources/application.yaml` (đưa key `file.base-path: ${FILE_BASE_PATH:../material}` vào app yaml);
    - `src/main/resources/data.sql` (không bao giờ chạy, lại hỏng encoding).
  - `material/pom.xml`:
    - artifactId `material_service` → `material`;
    - đổi `spring-boot-starter-web` → `spring-boot-starter-webmvc`;
    - bỏ eureka-client và `spring-boot-maven-plugin`;
    - thêm cấu hình compiler cho lombok.
  - Parent pom: `material_service` → `material`. `app/pom.xml` thêm dependency `com.edu:material`.
- Kiểm tra:
  - `curl "localhost:8080/api/materials?page=0&size=5"` trả Page JSON có dữ liệu (chưa bọc envelope, đúng như mong đợi ở bước này).
  - `curl -I localhost:8080/api/materials/<id>/file` trả 200 `application/pdf`.
- Commit: `refactor(material): move material service into monolith module`.

## Phase 4 — Hạ tầng chung thay cho gateway

### [ ] 4.1 Module `common` và envelope response
> **Sửa lại ở 4.3:** `RequestTimingFilter` không được gắn `X-Request-Id` vào response (gateway cũ không làm vậy).
- Model: Sonnet. Đây là hợp đồng với FE, nên nhờ Opus review.
- Làm:
  - Tạo module `common` (package `com.edu.common`, packaging jar). Dependency: `spring-boot-starter-webmvc`, lombok.
  - `web/RequestTimingFilter` (`OncePerRequestFilter`, order cao nhất): ghi attribute `startTime`, thêm header `X-Request-Id` (12 ký tự hex), log `METHOD path status Nms`.
  - `web/ApiEnvelopeAdvice` (`@RestControllerAdvice`, implement `ResponseBodyAdvice<Object>`) làm **đúng như** `ResponseWrapperFilter` của gateway (xem phần Context):
    - chỉ bọc khi `selectedContentType` tương thích `application/json`;
    - bỏ qua path bắt đầu bằng `/actuator`;
    - lấy status từ `((ServletServerHttpResponse) response).getServletResponse().getStatus()`;
    - chuyển body sang cây JSON bằng ObjectMapper do Boot tự cấu hình (Boot 4 dùng Jackson 3, package `tools.jackson.databind`), rồi dựng envelope;
    - nếu body là `null` thì coi như `{}`;
    - **không** bọc khi converter được chọn là `StringHttpMessageConverter`. Converter đó chỉ ghi được `String`, trả về một Map sẽ gây `ClassCastException`;
    - `path`: nếu request đang ở lượt xử lý lỗi (attribute `jakarta.servlet.error.request_uri` có giá trị) thì lấy path gốc từ attribute đó, ngược lại lấy `getRequestURI()`. Lỗi không có handler riêng đi qua `/error` của Boot, mà path `/error` thì vô nghĩa với FE.
  - `app` thêm dependency `common`.
- Kiểm tra:
  - `GET /api/materials` trả `{success:true,statusCode:200,message:"OK",data:{content:[...]},path,timestamp,responseTime}`.
  - `GET /api/materials/999999` trả lỗi có `success:false` và `data:null`.
  - `/api/materials/<id>/file` vẫn trả PDF nhị phân, không bị bọc.
- Commit: `feat(common): add api response envelope and request logging`.

### [ ] 4.2 Security: JWT filter, protected path, CORS, header
> **Sửa lại ở 4.3:** không xoá `X-User-Id` của client (việc đó dời sang F1), header của material phải giống `material_service` cũ, CORS chọn origin như profile của gateway.
- Model: Sonnet. Nhờ Opus review.
- Làm:
  - `common`: thêm `spring-boot-starter-security`, `jjwt-api` và `jjwt-impl`/`jjwt-jackson` (runtime).
  - `common/security/JwtService`: gộp `auth_service/.../security/JwtService.java` (`generateToken`) với `api_gateway/.../security/JwtGatewayService.java` (`extractUserIdIfValid`). Key `jwt.secret`, `jwt.expiration`.
  - `common/security/JwtAuthFilter` (`OncePerRequestFilter`):
    - bọc request bằng `HttpServletRequestWrapper` để **luôn xoá** `X-User-Id` của client;
    - nếu cookie `access_token` hợp lệ thì gắn lại `X-User-Id = sub` và set `SecurityContext` (principal = userId, không có authority);
    - **không** đánh dấu `@Component`, mà tạo bằng `new` trong `SecurityConfig`. Nếu là bean, Boot tự đăng ký nó thêm một lần làm filter servlet, chạy *trước* Spring Security. Khi đó `SecurityContextHolderFilter` ghi đè mất authentication, còn `OncePerRequestFilter` thì bỏ qua lượt chạy thứ hai, nên mọi protected path đều 401.
  - `app/config/SecurityConfig` (bean `securityFilterChain` duy nhất của cả app):
    - `csrf.disable`, STATELESS, tắt httpBasic và formLogin;
    - `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`;
    - các protected path ở phần Context dùng `authenticated()`, phần còn lại `permitAll()`;
    - `/api/ai/admin/**` dùng `denyAll()`, trừ khi `app.admin.ingest-enabled=true`;
    - `authenticationEntryPoint` trả 401 JSON đã bọc envelope sẵn, với message ở phần Context;
    - headers: `frameOptions.sameOrigin`, referrer-policy và permissions-policy như gateway;
    - `cors` dùng bean `CorsConfigurationSource`: origin `http://localhost:3000`, `http://localhost:3001` và `${ALLOWED_ORIGINS:}` (domain Vercel, nhiều domain cách nhau bằng dấu phẩy; bỏ phần tử rỗng sau khi tách), `allowCredentials(true)`, các method `GET, POST, PUT, PATCH, DELETE, OPTIONS`.
  - Thêm `jwt.*`, `cors.allowed-origins`, `app.admin.ingest-enabled: ${INGEST_ENABLED:false}` vào app yaml.
- Kiểm tra:
  - `POST /api/materials` không có cookie trả 401 envelope.
  - `GET /api/materials` trả 200.
  - Header response có `X-Frame-Options: SAMEORIGIN`.
  - Response API có `Cache-Control: no-cache, no-store, ...` (mặc định của Spring Security). **Không được tắt**: Vercel cache response của external rewrite theo header này (với project tạo từ 6/4/2026, hoặc đã bật trong dashboard). Thiếu nó thì dữ liệu của user này có thể bị trả cho user khác.
  - `POST /api/ai/admin/ingest` trả 403.
- Commit: `feat(security): replace gateway auth with in-app jwt filter`.

### [ ] 4.3 Gỡ các thay đổi hành vi đã làm ở 4.1 và 4.2
- Model: Sonnet. Nhờ Opus review.
- Bối cảnh: theo nguyên tắc "giữ nguyên hành vi 100%" (phần Context), 4 chỗ trong code hiện tại đang làm khác gateway cũ. Bước này trả chúng về đúng như cũ. Muốn sửa lỗi thì để tới Phase F (nhánh `fix/*`) và ghi vào mục "Việc để sau".
- Làm:
  1. **`common/.../security/JwtAuthFilter.java`**: làm đúng như `api_gateway/.../filter/JwtAuthGatewayFilter.java`:
     - request `OPTIONS` và path khớp `/api/auth/**` hoặc `/actuator/**`: đi tiếp **nguyên trạng**, không đọc cookie, không đụng header;
     - token hợp lệ: gán đè `X-User-Id = sub` (wrapper hiện tại) và set `SecurityContext`;
     - token sai hoặc thiếu: `chain.doFilter(request, response)` với **request gốc**, không bọc wrapper, để `X-User-Id` client gửi vẫn còn.
     - Sửa javadoc của class cho khớp. Ghi lỗ hổng vào mục "Việc để sau" (đã có sẵn, xem cuối plan).
  2. **`common/.../web/RequestTimingFilter.java`**: **không** gắn `X-Request-Id` vào response. Nếu cần id thì chỉ đưa vào dòng log.
  3. **`app/.../config/SecurityConfig.java`, header của material**: tách thành 2 `SecurityFilterChain`:
     - chain thứ nhất, `@Order(1)`, `securityMatcher("/api/materials/**")`: cùng CSRF, session, CORS, JWT filter và các rule `authenticated()` cho material, nhưng `headers(h -> h.defaultsDisabled()...)` rồi chỉ bật lại 4 header của gateway (`contentTypeOptions`, `frameOptions.sameOrigin`, `referrerPolicy`, `permissionsPolicy`). Như vậy response material không có `Cache-Control`, `Pragma`, `Expires`, `X-XSS-Protection`, đúng như `material_service` cũ;
     - chain thứ hai: phần còn lại, giữ như hiện tại (Spring Security mặc định + 4 header gateway), đúng như auth, user, exam, ai cũ.
     - Đưa phần cấu hình chung (CSRF, session, CORS, JWT filter, entry point 401) vào một hàm dùng chung để 2 chain không lệch nhau.
  4. **CORS**: bỏ `localhost` cứng trong code. Origin chỉ lấy từ `cors.allowed-origins`, và app yaml đặt `cors.allowed-origins: ${ALLOWED_ORIGINS:http://localhost:3000,http://localhost:3001}`. Không có env thì giống profile `dev` của gateway, có env thì giống profile `prod`.
  5. **`BE/docs/baseline/README.md`**:
     - bỏ 3 mục khỏi "Khác biệt được phép": `spoof-x-user-id`, `user-not-found`, `cache-control` của material. Giờ 3 kịch bản này phải **giống hệt** baseline;
     - thêm dòng trỏ về bảng "Khác biệt bắt buộc" trong plan.
- Kiểm tra:
  - Chạy capture, so với baseline các kịch bản material: phải **giống hệt**, kể cả header `cache-control` (không có).
  - `curl -I` một endpoint material: không có `X-Request-Id`, `Pragma`, `Expires`, `X-XSS-Protection`; có đủ 4 header của gateway.
  - Các kiểm tra của 4.2 vẫn pass: 401 envelope, 403 ingest, `X-Frame-Options: SAMEORIGIN`.
  - Kịch bản `spoof-x-user-id` sẽ được so ở bước 6.1, khi exam đã chuyển sang.
- Commit: `fix(common): restore gateway-identical behavior for jwt, headers and cors`.

## Phase 5 — Identity (auth + user)

### [ ] 5.1 user_service → `identity` (phần user)
- Model: Sonnet.
- Làm:
  - `git mv user_service identity`.
  - Dời package `com.edu.user_service` → `com.edu.identity.user`.
  - Xoá: `UserServiceApplication`, `config/SecurityConfig`, `application.yaml`.
  - **Giữ nguyên** lớp bọc response riêng của user để hành vi không đổi. Cũ: `user_service` bọc response một lần (`GlobalResponseInterceptor`), rồi gateway bọc thêm lần nữa. Mới: đúng 2 lớp đó, theo đúng thứ tự.
    - `interceptor/GlobalResponseInterceptor`: giữ code, chỉ thêm `@RestControllerAdvice(basePackages = "com.edu.identity.user")` để nó không bọc response của module khác, và `@Order(Ordered.HIGHEST_PRECEDENCE)` để nó chạy **trước** `ApiEnvelopeAdvice`.
    - `ApiEnvelopeAdvice` (common): thêm `@Order(Ordered.LOWEST_PRECEDENCE)`, để nó luôn là lớp ngoài cùng, giống gateway.
    - `config/WebConfig` + `interceptor/RequestTimeInterceptor`: giữ, nhưng chỉ đăng ký cho `/api/users/**`. Attribute `startTime` của nó không trùng tên với `REQUEST_START_TIME` của common.
  - Đổi tên `exception/GlobalExceptionHandler` → `UserExceptionHandler` với `@RestControllerAdvice(basePackages="com.edu.identity.user")`. **Giữ nguyên thân handler**: vẫn trả `ErrorResponse` với HTTP 200. Lỗi này có sẵn ở `user_service` cũ, đã ghi trong "Việc để sau".
  - pom:
    - artifactId đổi thành `identity`;
    - bỏ eureka, `spring-boot-starter-data-jdbc`, jjwt, `spring-boot-maven-plugin`;
    - thêm dependency `common` và cấu hình compiler cho lombok.
  - Parent pom: `user_service` → `identity`. app thêm dependency `identity`.
- Kiểm tra (so với baseline, phải **giống hệt**):
  - `GET /api/users/999999`: HTTP 200, envelope `success:true`, `data` chứa object lỗi có `"Success": false`. Đây đúng là lỗi có sẵn của stack cũ.
  - `GET /api/users/<id có thật>` (DB mới có sẵn 53 user cũ): `success:true`, `data` là profile.
- Commit: `refactor(identity): move user service into identity module`.

### [ ] 5.2 Gộp auth_service vào `identity`, bỏ Feign
- Model: Sonnet.
- Làm:
  - `git mv` toàn bộ `auth_service/src/main/java/com/edu/auth_service/*` → `identity/src/main/java/com/edu/identity/auth/`, và test tương ứng sang `identity/src/test/...`. Sửa `package` và `import`.
  - Xoá:
    - `AuthServiceApplication`;
    - `auth/security/JwtService` (dùng bản trong common);
    - `auth/client/**` và `auth/config/FeignConfig`;
    - `application*.yaml`;
    - `auth_service/.env`: chứa secret, không mang theo.
  - `auth/security/SecurityConfig` thay bằng `auth/config/PasswordConfig`, chỉ giữ bean `passwordEncoder` (`BCryptPasswordEncoder(8)`).
  - Đổi tên `GlobalExceptionHandler` → `AuthExceptionHandler` với `basePackages="com.edu.identity.auth"`.
  - `AuthServiceImpl.register`:
    - gọi thẳng `UserService.createUser(new com.edu.identity.user.dto.CreateUserRequest(...))` và lấy `user.getId()`;
    - bỏ `UserResponse` và `isSuccess`;
    - **không** thêm `@Transactional`. Cũ: tạo user và lưu credential là 2 lần commit riêng ở 2 service. Mới: `UserService.createUser` và `credentialRepository.save` vẫn mỗi cái tự commit, giống hệt. Gộp transaction là sửa lỗi, đã ghi ở "Việc để sau".
    - Validation không bị mất: cũ có `@Valid CreateUserRequest` ở controller user (tên 3–20 ký tự, email đúng định dạng). `RegisterRequest` của auth đã kiểm tra đủ các điều đó (và thêm nữa) trước khi vào service.
  - Gộp dependency từ `auth_service/pom.xml` vào `identity/pom.xml` (validation, security, data-jpa), bỏ openfeign, feign-hc5, eureka, oauth2-resource-server.
  - Đưa key `jwt.refresh-expiration`, `cookie.secure: ${COOKIE_SECURE:false}` vào app yaml.
  - Xoá module `auth_service` khỏi parent pom và xoá thư mục còn lại.
- Kiểm tra: `mvn -q -pl app -am package -DskipTests` pass, app khởi động được.
- Commit: `refactor(identity): merge auth service and call user service in-process`.

### [ ] 5.3 Sửa test của identity
- Model: Sonnet.
- Làm:
  - `AuthServiceTest`: mock `UserService` thay cho `UserServiceClient`.
  - Xoá `AuthServiceApplicationTests` (contextLoads, cần DB và Eureka).
  - `JwtServiceTest`: chuyển sang module common.
  - Thêm dependency test vào `identity/pom.xml` (`spring-boot-starter-test`, `spring-security-test`) và `common/pom.xml` (`spring-boot-starter-test`). Hiện `user_service` không có dependency test nào.
- Kiểm tra: `mvn -q -pl identity,common -am test` xanh.
- Commit: `test(identity): adapt auth tests to in-process user service`.

### [ ] 5.4 Kiểm thử E2E auth (không sửa code)
- Model: Haiku.
- Làm: dùng curl với cookie jar (`-c/-b`) chạy lần lượt:
  - `POST /api/auth/register`;
  - `POST /api/auth/login` (có `Set-Cookie access_token` và `refresh_token`);
  - `GET /api/users/me` (200, data là user vừa tạo);
  - `GET /api/users/me` kèm header `X-User-Id: 1` mà không có cookie (phải trả 401: giả mạo bị chặn);
  - `POST /api/auth/refresh`.
- Kiểm tra: tất cả đúng như trên, và bảng `users` cùng `credentials` có dòng mới. Lỗi nào phát sinh thì ghi lại, **không tự sửa**, dừng lại báo cáo.

## Phase 6 — Exam

### [ ] 6.1 exam-service → `exam`
- Model: Sonnet.
- Làm:
  - `git mv exam-service exam`. Package `com.edu.exam` giữ nguyên.
  - Xoá:
    - `ExamServiceApplication`;
    - `security/SecurityConfig`;
    - `application.yaml`;
    - toàn bộ `db/migration` (đã thay bằng V1 baseline);
    - `ExamServiceApplicationTests`.
  - Đổi tên `GlobalExceptionHandler` → `ExamExceptionHandler` với `basePackages="com.edu.exam"`.
  - pom:
    - artifactId `exam-service` → `exam`;
    - bỏ eureka, jjwt, devtools, flyway (cả các dependency test flyway), `spring-boot-maven-plugin`;
    - **giữ** cấu hình mapstruct trong annotationProcessorPaths.
  - Parent pom: `exam-service` → `exam`. app thêm dependency `exam`.
- Kiểm tra:
  - `GET /api/categories`, `/api/exams`, `/api/exams/popular`, `/api/exams/<id>/questions` trả envelope có dữ liệu.
  - Gọi endpoint tìm kiếm đề có dùng native query `findBySearch`/`findByCategoryAndSearch`. Xem `ExamController` để biết tên tham số.
  - Với cookie của bước 5.4: `POST /api/exams/<id>/submit`, `GET /api/attempts/me/summary`, `POST /api/exams/<id>/rating` (native UPDATE) đều 200.
  - Các lệnh đó khi không có cookie trả 401.
- Commit: `refactor(exam): move exam service into monolith module`.

## Phase 7 — AI

### [ ] 7.1 ai-service → `ai`, gọi exam trực tiếp
- Model: Sonnet.
- Làm:
  - `git mv ai-service ai`. Package `com.edu.ai` giữ nguyên.
  - Xoá:
    - `AIServiceApplication`, `config/SecurityConfig`;
    - `application.yml` (đưa các nhóm key `llm`, `anthropic`, `gemini`, `rag` vào app yaml; `rag.ingest.source-path: ${RAG_SOURCE_PATH:../material/Material_Data}`). **Giữ** `src/main/resources/prompts/`, vì `PromptBuilder` đọc 2 file `.md` trong đó từ classpath;
    - `db/migration`;
    - `clients/ExamServiceClient`;
  - **Giữ** `dtos/AICoachAnalysisDTO`, `dtos/NodeDTO`, `dtos/EdgeDTO` của ai, không thay bằng bản của exam. Lý do: `NodeDTO` của ai khai báo field theo thứ tự khác bản của exam, nên JSON trả về FE sẽ đổi thứ tự key.
  - `CoachService`: inject `com.edu.exam.services.AICoachService` và `ObjectMapper` (Jackson 3 của Boot) thay cho Feign client. Thay cả 5 chỗ `examServiceClient.getAnalysis(userId)` bằng một hàm riêng:
    `objectMapper.convertValue(aiCoachService.analyzeUserKnowledge(userId), com.edu.ai.dtos.AICoachAnalysisDTO.class)`.
    Cách này lặp lại đúng việc Feign làm trước đây: chuyển DTO của exam sang JSON rồi đọc lại thành DTO của ai. Trước khi sửa, kiểm tra kiểu của tham số `userId`.
  - **`open-in-view` của ai**: ai cũ đặt `spring.jpa.open-in-view: false`, các service khác dùng mặc định `true`. Spring đặt Hibernate ở chế độ `DELAYED_ACQUISITION_AND_HOLD`. Vì vậy khi `open-in-view` bật, request giữ kết nối DB tới khi kết thúc, kể cả lúc chờ Gemini hàng chục giây. Để giữ đúng như cũ:
    - app yaml đổi thành `spring.jpa.open-in-view: false`;
    - trong `app` thêm một `WebMvcConfigurer` đăng ký `OpenEntityManagerInViewInterceptor` (qua `addWebRequestInterceptor`) cho mọi path, **trừ** `/api/coach/**` và `/api/ai/**`;
    - như vậy auth, user, exam, material vẫn có open-in-view như cũ, còn ai thì không.
  - Xoá `exam/controllers/AICoachController` (không còn ai gọi qua HTTP).
  - pom:
    - artifactId `ai-service` → `ai`;
    - bỏ eureka, openfeign, `spring-boot-maven-plugin`;
    - thêm dependency `com.edu:exam`;
    - thêm `com.fasterxml.jackson.core:jackson-databind` tường minh, **không ghi version** (Boot 4 vẫn quản lý version Jackson 2), vì code ai dùng Jackson 2 mà trước đây nó đến gián tiếp qua Eureka/Feign. `convertValue` ở trên dùng Jackson 3 của Boot, là cách gần nhất với converter Feign dùng trước đây;
    - **giữ** `pdfbox`.
  - Parent pom: `ai-service` → `ai`. app thêm dependency `ai`.
- Kiểm tra (đăng nhập user, đã nộp ít nhất 1 bài):
  - `GET /api/coach/<userId>/analysis` trả nodes và edges.
  - `GET /api/coach/<userId>/learning-path?daysRemaining=7` chạy được (native vector query, có gọi Gemini).
  - `curl -N "localhost:8080/api/coach/node/<topicId>/explain/stream?userId=<id>"` trả event SSE từng phần, không bị bọc.
  - So với baseline kịch bản `analysis`: body giống hệt, **kể cả thứ tự key** trong mỗi node.
  - Chạy với `INGEST_ENABLED=true`: `GET /api/ai/admin/ingest/status` trả 200 (**không** gọi POST ingest).
  - **Cuối phase**: chạy FE local (`npm run dev`) trỏ vào monolith và đi hết luồng người dùng: đăng ký, đăng nhập, xem đề, làm bài, lịch sử, tài liệu PDF, AI coach.
- Commit: `refactor(ai): move ai service into monolith and call exam in-process`.

### [ ] 7.2 Mốc tương đương 100% (không sửa code)
- Model: Sonnet.
- Làm:
  - Chạy capture trên monolith, so **toàn bộ** kịch bản với `baseline/microservices`.
  - Mọi khác biệt phải nằm trong bảng "Khác biệt bắt buộc" (phần Context), hoặc là id, email, số liệu attempt/rating của user test.
  - Bạn tự đi lại các luồng trên FE local như ở 0.3, so với những gì đã ghi trong `baseline/README.md`. Các luồng hỏng sẵn ở stack cũ thì phải **hỏng y như cũ**.
- Kiểm tra: không còn khác biệt nào ngoài danh sách. Nếu còn thì **dừng** và sửa phần refactor cho khớp, không sửa theo hướng "cho đúng".
- Ghi kết quả (ngày chạy, số kịch bản khớp) vào đầu bước này.
- Commit: `test: confirm monolith matches microservice baseline` (chỉ cập nhật plan).

### [ ] 7.3 Xoá phần microservice còn sót
- Model: Haiku.
- Điều kiện: 7.2 đã pass. Từ đây không cần chạy lại stack cũ trên nhánh này nữa; nếu cần thì chạy từ tag `pre-monolith` (bước 7.5).
- Làm:
  - `git rm -r api_gateway discovery_server`.
  - Xoá:
    - các Dockerfile cũ trong module;
    - `BE/docker-compose.yml`;
    - `.github/workflows/deploy.yml` (deploy qua SSH lên VPS Contabo đã chết; giữ lại thì mỗi lần merge vào `main` sẽ có một job lỗi);
    - `BE/.env.example` cũ (biến của từng service trên VPS). Bản mới là `BE/app/.env.example`.
  - `git mv exam/mvnw exam/mvnw.cmd exam/.mvn` ra `BE/`, rồi xoá wrapper trong các module khác.
  - Parent pom:
    - modules chỉ còn `common, identity, exam, material, ai, app`;
    - bỏ BOM `spring-cloud-dependencies` và repository `spring-milestones` nếu không còn dùng (grep `spring-cloud` để chắc).
  - Thêm `.github/workflows/ci.yml`: trên `pull_request` và `push`, setup JDK 21, chạy `mvn -B package` trong `BE/`.
    - Dùng `mvn` có sẵn trên runner Ubuntu, **không** dùng `./mvnw`: file `mvnw` lưu trong git với mode `100644` (không có quyền thực thi), chạy trên Linux sẽ bị "Permission denied".
- Kiểm tra:
  - `.\mvnw.cmd -q clean package` ở `BE/` pass (có chạy test).
  - `grep -rE --exclude-dir=docs "eureka|feign|lb://|contabo" BE .github` không còn kết quả.
  - Chạy lại capture: vẫn giống kết quả 7.2.
- Commit: `chore: remove gateway, discovery server and vps deployment`.

### [ ] 7.4 Tài liệu kiến trúc monolith
- Model: Sonnet.
- Làm (chỉ mô tả những gì **đang có trên nhánh này**; schema, R2 và deploy do nhánh của chúng tự bổ sung tài liệu):
  - Viết lại `BE/CLAUDE.md` (thay bản tạm): các module, hợp đồng envelope và JWT, protected path, cách chạy local, env, nguyên tắc "sửa lỗi theo từng nhánh `fix/*`".
  - Nếu cần, thêm `BE/ai/README.md` ngắn mô tả RAG và AI coach (bản cũ đã bị xoá vì sai), kèm quy trình **ingest local**: chạy app với `INGEST_ENABLED=true`, `RAG_SOURCE_PATH` trỏ vào thư mục PDF, gọi `POST localhost:8080/api/ai/admin/ingest`. Request chạy lâu, dùng curl với timeout lớn.
  - Ghi quy tắc **migration mới**: file `V<n>__<mô tả>.sql` trong `app/src/main/resources/db/migration`.
  - Sửa phần backend trong `README.md` ở gốc repo: bỏ khung cảnh báo "đang refactor", mô tả monolith. Phần deploy ghi "chưa deploy, xem plan Phase 10".
- Commit: `docs: describe modular monolith backend`.

### [ ] 7.5 Merge nhánh refactor vào `main` (bạn làm)
- Làm:
  - **Trước khi merge**, gắn tag cho bản microservice cuối cùng trên `main`: `git tag pre-monolith main && git push origin pre-monolith`. Sau này cần chạy lại stack cũ (chụp lại baseline) thì `git worktree add ../itshiken-old pre-monolith`.
  - Mở PR `refactor/monolith` → `main`, chờ CI xanh, merge.
  - Lúc này **chưa** có gì được deploy: VPS cũ đã chết, Render chưa tạo (Phase 10). Production vẫn như hiện tại.
- Kiểm tra: `main` build xanh trên CI. Các nhánh sau đều tạo từ `main` mới này.

## Phase 8 — Chia schema theo module
> **Nhánh `refactor/db-schemas`**, tạo từ `main` sau bước 7.5: `git checkout main && git pull && git checkout -b refactor/db-schemas`. Vẫn là refactor giữ nguyên hành vi, nên vẫn so baseline.

### [ ] 8.1 Migration V2 chuyển bảng sang schema, sửa entity và native query
- Model: Sonnet. Nhờ Opus review.
- Làm (tất cả trong **1 commit**, vì code và DB phải khớp nhau):
  - Tạo `app/src/main/resources/db/migration/V2__split_schemas.sql`:
    - `CREATE SCHEMA IF NOT EXISTS identity;` và tương tự cho `exam`, `material`, `ai`;
    - với mỗi bảng: `ALTER TABLE public.<bảng> SET SCHEMA <schema>;` theo bảng phân schema (14 dòng). Sequence `OWNED BY`, index và constraint sẽ đi theo bảng. Extension `vector` ở lại `public`.
  - Thêm `schema = "..."` vào `@Table` của 14 entity:
    - identity: `User`, `Credential`, `RefreshToken`;
    - exam: `Attempt`, `AttemptAnswer`, `Category`, `Exam`, `ExamRating`, `Question`, `Topic`, `TopicEdge`, `UserTopicMastery`;
    - material: `LearningMaterial`;
    - ai: `KnowledgeChunk`.
  - Ghi rõ schema trước tên bảng trong native query:
    - `exam/.../repositories/ExamRepository.java`: 3 query, gồm cả `UPDATE exams ...`. Đọc kỹ từng query, mọi bảng đều thành `exam.<bảng>`;
    - `exam/.../repositories/ExamRatingRepository.java`: 1 query;
    - `ai/.../repositories/KnowledgeChunkRepository.java`: 1 query, thành `ai.knowledge_chunks`. **Giữ** `CAST(... AS vector)`, vì `public` vẫn có trong `search_path`.
  - Grep `nativeQuery|createNativeQuery|JdbcTemplate` trong `BE/` để chắc không sót query SQL nào khác.
  - Flyway không cần cấu hình thêm. Bảng lịch sử vẫn ở `public`.
- Kiểm tra:
  - Ghi `count(*)` của 14 bảng trước khi chạy.
  - Khởi động app: log Flyway chạy V2 thành công. Postgres chạy DDL trong transaction, nên nếu lỗi thì tự rollback.
  - `\dt public.*` chỉ còn `flyway_schema_history`. Count không đổi.
  - Chạy lại các curl kiểm tra của 5.4, 6.1 và 7.1, tất cả phải pass.
  - **DB trống**: tạo tạm `itshiken_tmp`, trỏ `DB_URL` vào đó và khởi động app. Flyway chạy V1 rồi V2 không lỗi, bảng nằm đúng schema. Xong thì xoá DB tạm.
  - Chạy capture, so **toàn bộ** kịch bản: phải giống kết quả 7.2.
  - `BE/CLAUDE.md`: thêm bảng phân schema, và quy tắc "tên bảng trong migration và native query luôn ghi kèm schema".
- Commit: `refactor(db): split tables into per-module schemas`.
- Xong thì mở PR `refactor/db-schemas` → `main`, CI xanh thì merge.

## Phase 9 — PDF lên Cloudflare R2 (`files.itshiken.app`)
> **Nhánh `feature/r2-materials`**, tạo từ `main` (sau 7.5; nên sau cả Phase 8 để bước 9.3 đọc được `material.learning_materials`). Đây là **thay đổi hành vi có chủ đích**: file tài liệu trả 302 thay cho 200. Ngoài kịch bản file, mọi kịch bản baseline khác phải giữ nguyên. Bước 9.1 và 9.2 làm tay trên Cloudflare, làm sớm lúc nào cũng được.

Lý do chọn R2:
- Vercel Blob **luôn** gắn `x-frame-options: DENY` cho file public, nên iframe ở `FE/src/views/MaterialDetail.tsx:81` sẽ trống.
- R2 không gắn header đó: free 10GB, không tính phí băng thông.
- URL `r2.dev` chỉ dùng để thử (có giới hạn tốc độ), nên dùng domain riêng. Gói free của Cloudflare bắt buộc domain phải dùng DNS của Cloudflare.

Nút **tải xuống**: R2 không có tham số kiểu `?download=1`, và presigned URL không chạy với domain riêng. Vì vậy mỗi file được upload **2 bản**:
- `Material_Data/<path>` với `Content-Disposition: inline` để xem;
- `download/Material_Data/<path>` với `attachment` để tải.

### [ ] 9.1 Chuyển DNS của `itshiken.app` từ name.com sang Cloudflare (bạn làm, model hướng dẫn)
- Model: Haiku (hướng dẫn từng bước, không tự sửa gì).
- Làm:
  - **Trước khi đổi**, chụp hoặc ghi lại **toàn bộ** bản ghi DNS đang có ở name.com:
    - A của `@` hiện là `216.198.79.1` (Vercel);
    - CNAME `www`;
    - TXT xác minh của Vercel, nếu có;
    - MX, SPF, DKIM, nếu domain có dùng email;
    - các subdomain khác.
  - Ở name.com, nếu **DNSSEC đang bật thì tắt trước**. Đổi nameserver khi DNSSEC còn bật sẽ làm domain không phân giải được.
  - Tạo tài khoản Cloudflare (free) → **Add a domain** `itshiken.app` → chọn gói Free. Cloudflare tự quét bản ghi; so với danh sách đã ghi và bổ sung chỗ thiếu.
    - Các bản ghi trỏ về **Vercel** phải để **DNS only (mây xám)**, không bật proxy. Vercel cần tự cấp SSL và tự làm CDN.
  - Ở name.com, đổi nameserver sang 2 nameserver Cloudflare đưa ra.
- Kiểm tra:
  - `nslookup -type=NS itshiken.app` ra nameserver của Cloudflare. Có thể mất từ vài phút tới 24 giờ.
  - Cloudflare báo domain **Active**.
  - `https://itshiken.app` vẫn mở bình thường, và Vercel → Domains vẫn báo **Valid**.
  - Email (nếu có) vẫn nhận được.

### [ ] 9.2 Tạo bucket R2 và gắn `files.itshiken.app` (bạn làm, model hướng dẫn)
- Model: Haiku (hướng dẫn).
- Điều kiện: bước 9.1 đã Active.
- Làm:
  - Cloudflare → **R2** → bật R2. Phải thêm thẻ hoặc PayPal; trong hạn mức free thì không bị trừ tiền.
  - Tạo bucket `itshiken-materials`.
  - Bucket → Settings → **Custom Domains** → **Connect Domain** `files.itshiken.app`. Cloudflare tự tạo bản ghi DNS.
  - **Không** bật `r2.dev`.
  - R2 → **Manage API tokens** → tạo token quyền **Object Read & Write**, chỉ cho bucket này. Ghi lại `Access Key ID`, `Secret Access Key` và `Account ID`.
  - Tạo `BE/tools/materials-sync/.env` (đã gitignore nhờ `**/.env`) với `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET=itshiken-materials`.
  - **Zip `BE/material/Material_Data` lên Google Drive** để làm bản sao lưu.
- Kiểm tra: `curl -I https://files.itshiken.app/abc` trả HTTP qua HTTPS hợp lệ. 404 là bình thường, vì bucket còn trống.

### [ ] 9.3 Script đồng bộ PDF với R2 và upload lần đầu
- Model: Sonnet.
- Làm:
  - Tạo `BE/tools/materials-sync/` gồm `package.json` (dependency `@aws-sdk/client-s3`, `dotenv`) và `sync.mjs`.
    - `S3Client({ region: 'auto', endpoint: 'https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com', credentials })`, đọc từ `.env` cạnh script, không hardcode.
  - `node sync.mjs upload --dir <thư mục chứa Material_Data> [--dry-run]`:
    - duyệt mọi file;
    - với mỗi file, `PutObject` **2 lần**:
      - key `Material_Data/<path tương đối, dùng />` với `ContentDisposition: inline; filename*=UTF-8''<tên file đã encode>`;
      - key `download/Material_Data/<...>` với `ContentDisposition: attachment; filename*=UTF-8''<...>`;
    - **luôn đặt `ContentType`** theo đuôi file (`.pdf` → `application/pdf`, `.doc` → `application/msword`, còn lại `application/octet-stream`). S3 API không tự đoán, thiếu là trình duyệt không hiển thị;
    - key `Material_Data/...` trùng `file_url` trong DB sau khi bỏ dấu `/` đầu;
    - in số file thành công và thất bại.
  - `node sync.mjs download --out <thư mục>`:
    - `ListObjectsV2` với prefix `Material_Data/`, phân trang bằng `ContinuationToken`;
    - `GetObject` từng file, giữ nguyên cấu trúc thư mục;
    - bỏ qua file đã có cùng kích thước;
    - in tổng số file và tổng dung lượng.
    - **Không** tải prefix `download/`.
  - Thêm `.materials-cache/` vào `.gitignore`.
  - Chạy `upload --dir BE/material --dry-run` trước, rồi chạy thật. Khoảng 490 lượt ghi (Class A), trong khi free có 1 triệu/tháng.
- Kiểm tra:
  - Lấy danh sách `file_url` trong `material.learning_materials` (bỏ những dòng bắt đầu bằng `http`). Với từng dòng, `curl -sI "https://files.itshiken.app/<url-encoded path>"`:
    - trả 200, `content-type: application/pdf`, `content-disposition: inline...`;
    - **không có** `x-frame-options` (nếu có, ghi lại và **dừng**);
    - bản `download/...` có `content-disposition: attachment...`.
  - Báo số dòng thiếu file nếu có.
- Commit: `feat(tools): add script to sync material files with Cloudflare R2`. Chỉ commit script, `package.json`, `package-lock.json` và `.gitignore`. **Không** commit `.env`.

### [ ] 9.4 `MaterialFileController` trả redirect tới R2
- Model: Sonnet.
- Làm:
  - Thay toàn bộ phần đọc đĩa và Range trong `material/.../controller/MaterialFileController.java` bằng logic sau:
    - không tìm thấy material thì trả 404;
    - nếu `fileUrl` bắt đầu bằng `http` thì `302` tới đúng URL đó;
    - ngược lại thì `302` tới `${file.public-base-url}/` + (`download/` nếu `download=true`) + `UriUtils.encodePath(fileUrl bỏ "/" đầu, UTF_8)`;
    - header `Cache-Control: public, max-age=3600`.
  - App yaml: bỏ `file.base-path`, thêm `file.public-base-url: ${MATERIAL_FILES_BASE_URL:https://files.itshiken.app}`. Thêm `MATERIAL_FILES_BASE_URL` vào `.env.example`.
- Kiểm tra:
  - `curl -I localhost:8080/api/materials/<id>/file` trả 302, và `Location` (dạng `https://files.itshiken.app/Material_Data/...`) mở được PDF.
  - Thêm `?download=true` thì Location có tiền tố `download/`, và trình duyệt tải file về.
  - **Mở FE local** (`npm run dev`, BE_URL=:8080), vào trang chi tiết tài liệu: PDF hiển thị được trong iframe. Nếu không hiển thị thì **dừng và báo**, kèm header của response R2.
- Commit: `feat(material): serve material files from Cloudflare R2 via redirect`.

### [ ] 9.5 Xoá `Material_Data` khỏi git, ingest đọc từ cache
- Model: Haiku.
- Điều kiện: bước 9.4 đã pass, và bạn đã có bản zip sao lưu (bước 9.2).
- Làm:
  - Chạy `node BE/tools/materials-sync/sync.mjs download --out BE/.materials-cache` để chứng minh R2 đủ file. Đây là bản local dùng cho ingest sau này.
  - So số file và tổng dung lượng giữa `BE/.materials-cache/Material_Data` và `BE/material/Material_Data`. Phải bằng nhau, nếu lệch thì **dừng**.
  - `git rm -r --cached BE/material/Material_Data`, rồi xoá thư mục khỏi ổ đĩa. Bản local đã nằm ở `.materials-cache`.
  - App yaml: đổi thành `rag.ingest.source-path: ${RAG_SOURCE_PATH:../.materials-cache/Material_Data}`.
  - `git status` không được thấy file nào trong `.materials-cache`.
- Kiểm tra:
  - App vẫn chạy.
  - `/api/materials/<id>/file` vẫn 302 tới `files.itshiken.app`.
  - `git ls-files | grep -c Material_Data` ra `0`.
  - Tài liệu (`BE/CLAUDE.md` hoặc `BE/material/README.md`):
    - quy trình **thêm tài liệu mới**: (1) upload PDF bằng `tools/materials-sync upload`; nếu ghi đè file cũ cùng tên thì vào Cloudflare → Caching → **Purge** URL đó, vì Cloudflare cache file PDF; (2) thêm dòng vào `material.learning_materials` với `file_url = /Material_Data/...`; (3) muốn AI dùng được tài liệu đó thì ingest local;
    - quy trình **ingest local**: chạy `sync.mjs download --out BE/.materials-cache` trước, rồi làm như hướng dẫn ở 7.4.
  - `baseline/README.md`: thêm kịch bản `HEAD /api/materials/<id>/file` vào mục "Khác biệt có chủ đích", ghi rõ Phase 9.
- Commit: `chore(material): remove material files from git, Cloudflare R2 is the source`.
- Xong thì mở PR `feature/r2-materials` → `main`, CI xanh thì merge.

## Phase 10 — Deploy free và dọn dẹp
> **Nhánh `deploy/render`**, tạo từ `main`. **Điều kiện trước khi merge nhánh này** (vì Render tự deploy khi `main` có `render.yaml`): `main` đã có `refactor/monolith`, `refactor/db-schemas`, `feature/r2-materials` và `fix/x-user-id-spoofing` (bước F1).

### [ ] 10.1 Dockerfile cho Render
- Model: Sonnet.
- Làm:
  - `BE/app/Dockerfile`, build context `BE/`:
    - stage build `maven:3.9.6-eclipse-temurin-21-alpine`, chạy `mvn -B clean package -pl app -am -DskipTests`;
    - stage run `eclipse-temurin:21-jre-alpine`, user `spring`, copy `app/target/*.jar`;
    - `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"` (ép JVM vừa 512MB và khởi động nhanh hơn trên CPU yếu);
    - `EXPOSE 8080`.
  - `BE/.dockerignore`: thêm `.materials-cache/`, `**/.env`, `**/node_modules/`, `tools/`, `docs/`.
  - Tạo `render.yaml` ở gốc repo:
    - `services: - type: web, name: itshiken-backend, runtime: docker, plan: free, branch: main`;
    - `dockerfilePath: ./BE/app/Dockerfile`, `dockerContext: ./BE`, `healthCheckPath: /actuator/health`;
    - `envVars` với `sync: false` cho `DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, GEMINI_API_KEY, ANTHROPIC_API_KEY, MATERIAL_FILES_BASE_URL, ALLOWED_ORIGINS`;
    - `COOKIE_SECURE=true`, `LLM_PROVIDER=gemini`.
  - Mở tài liệu blueprint của Render để kiểm tra đúng tên field trước khi viết.
- Kiểm tra:
  - `docker build -f app/Dockerfile -t itshiken-be .` (đứng ở `BE/`). Image không chứa PDF: `docker run --rm --entrypoint sh itshiken-be -c "find / -name '*.pdf' | head"` không ra gì.
  - `docker run --rm -m 512m --env-file app/.env -p 8080:8080 itshiken-be` khởi động OK. `docker stats` cho thấy RAM < ~450MB sau khi gọi vài API.
  - Ghi lại thời gian khởi động.
- Commit: `build: add render deployment for monolith`.

### [ ] 10.2 (đã chuyển) Xoá phần microservice
> Đã chuyển sang bước 7.3, trên nhánh refactor.

### [ ] 10.3 Tài liệu deploy
- Model: Sonnet.
- Làm: trong `BE/CLAUDE.md` và `README.md` gốc, thay dòng "chưa deploy" (từ 7.4) bằng cách deploy thật:
  - Render: blueprint `render.yaml`, danh sách env, health check, giới hạn gói free (ngủ sau 15 phút, 512MB);
  - Vercel: env `BE_URL`;
  - lưu ý không tắt `Cache-Control: no-store` của Spring Security (xem 4.2).
- Commit: `docs: describe render and vercel deployment`.

### [ ] 10.4 Deploy backend lên Render (bạn làm, model hướng dẫn)
- Model: Haiku (hướng dẫn).
- Làm:
  - Kiểm tra đủ điều kiện ở đầu Phase 10, rồi merge PR `deploy/render` → `main`.
  - Trên Render chọn **New → Blueprint**, trỏ vào repo, rồi nhập các env có `sync: false`. `ALLOWED_ORIGINS` là domain FE trên Vercel.
- Kiểm tra:
  - Deploy xanh.
  - `https://<service>.onrender.com/actuator/health` trả `UP`.
  - `https://<service>.onrender.com/api/exams` trả envelope có dữ liệu.

### [ ] 10.5 Trỏ FE trên Vercel sang Render (bạn làm)
- Model: Haiku (hướng dẫn).
- Làm:
  - Vercel → project FE → Settings → Environment Variables (Production): đặt `BE_URL=https://<service>.onrender.com`. Env của Vercel sẽ ghi đè giá trị tạm trong `FE/.env.production`.
  - Redeploy FE.
- Kiểm tra: trên domain thật, đi hết luồng: đăng ký, đăng nhập, xem đề, làm bài, lịch sử, tài liệu PDF (iframe và tải về), AI coach (cả stream).
  - Lần gọi đầu sau khi Render ngủ sẽ chậm (khoảng 1 phút). Nếu Vercel trả 504 trong lúc Render đang thức dậy thì ghi lại, bước 10.6 sẽ xử lý.
  - Gọi `/api/users/me` hai lần bằng hai tài khoản khác nhau: mỗi lần phải ra đúng user của mình. Header `x-vercel-cache` không được là `HIT`.

### [ ] 10.6 (Tuỳ chọn) Giữ backend không ngủ
- Model: Haiku (hướng dẫn).
- Làm: tạo job miễn phí trên cron-job.org, cứ 10 phút gọi `GET https://<service>.onrender.com/actuator/health`.
  - 750 giờ/tháng của Render đủ chạy 1 service cả tháng (744 giờ).
  - Health không chạm DB (`management.health.db.enabled: false`), và Hikari `minimum-idle: 0`, nên Neon vẫn ngủ được khi không có người dùng.
  - Đánh đổi: nếu sau này thêm service free thứ hai trên Render thì sẽ hết giờ.
- Kiểm tra: sau 30 phút không dùng, mở site vẫn nhanh. Dashboard Neon vẫn thấy compute được suspend.

### [ ] 10.7 Dọn tài nguyên cũ (bạn làm)
- Làm:
  - Sau vài ngày chạy ổn, xoá 5 DB cũ trên Neon.
  - **Rotate mật khẩu Neon**: mật khẩu cũ đang lộ trong git history (yaml, `auth_service/.env`).
  - Huỷ hẳn VPS Contabo nếu chưa huỷ.

---

## Phase F — Sửa lỗi có sẵn (mỗi lỗi một nhánh `fix/*`)

Quy trình chung cho **mỗi** lỗi trong mục "Việc để sau":
1. Tạo nhánh từ `main` mới nhất: `git checkout -b fix/<tên-ngắn>`.
2. Chỉ sửa đúng lỗi đó. Thấy lỗi khác thì ghi thêm vào "Việc để sau", không sửa chung.
3. Chạy capture, so với baseline: **chỉ** kịch bản liên quan được đổi, mọi kịch bản khác giữ nguyên. Ghi kịch bản đổi vào mục "Khác biệt do sửa lỗi" trong `baseline/README.md`, kèm tên nhánh.
4. Xoá mục đó khỏi "Việc để sau", rồi PR → `main`.

### [ ] F1 Sửa lỗ hổng giả mạo `X-User-Id` (bắt buộc trước Phase 10)
- Nhánh: `fix/x-user-id-spoofing`.
- Model: Sonnet.
- Điều kiện: `refactor/monolith` đã merge (bước 7.5).
- Lý do làm trước deploy: tự gửi header là đọc được rating của người khác. Lỗ hổng này cũng có trên production cũ.
- Làm:
  - `JwtAuthFilter`: với **mọi** request đi qua filter (kể cả token sai hoặc thiếu), bọc request để xoá `X-User-Id` client gửi; token hợp lệ thì gắn lại từ JWT. Path bị gateway bỏ qua (`OPTIONS`, `/api/auth/**`, `/actuator/**`) cũng xoá header.
  - `baseline/README.md`: chuyển `spoof-x-user-id` vào mục "Khác biệt do sửa lỗi".
- Kiểm tra:
  - Kịch bản `spoof-x-user-id`: không còn trả rating của user 1.
  - Mọi kịch bản khác giống kết quả trên `main` trước khi sửa.
- Commit: `fix(security): drop client-supplied X-User-Id header`.

---

## Verification tổng
- Bước 0.2 và 0.3: chạy stack cũ và chụp baseline. Đây là chuẩn để so sánh cho mọi bước sau.
- Sau mỗi bước: `mvn -q -pl app -am package -DskipTests`, rồi khởi động app và curl theo phần Kiểm tra của bước đó. Từ 4.1 trở đi, so thêm với baseline.
- Cuối Phase 7: chạy FE local trỏ vào monolith local và đi hết luồng người dùng.
- Bước 8.1: chạy lại toàn bộ curl và thử dựng DB trống từ V1 + V2.
- Bước 7.2 (**mốc tương đương**): **toàn bộ** kịch bản baseline phải khớp, chỉ được khác ở bảng "Khác biệt bắt buộc".
- Phase F: mỗi nhánh `fix/*` so lại baseline, để chắc chỉ kịch bản liên quan thay đổi.
- Bước 10.1: chạy container giới hạn 512MB, mô phỏng Render.
- Bước 10.5: kiểm thử trên domain thật.

---

## Cách để Opus lên kế hoạch, model rẻ hơn thực thi

1. **Lên kế hoạch (Opus, đã xong)**: plan nằm ở `BE/docs/MONOLITH_PLAN.md`, session nào cũng đọc được.
2. **Đổi model để thực thi**: gõ `/model` và chọn **Sonnet**. Đây là mặc định nên dùng, vì Spring Boot 4 có nhiều chỗ đổi tên mà Haiku dễ đoán sai. Chỉ dùng **Haiku** cho các bước đánh dấu Haiku.
3. **Mỗi bước một session sạch**: gõ `/clear` trước mỗi bước. Rồi dán prompt mẫu:
   > Đọc `BE/docs/MONOLITH_PLAN.md`. Chỉ thực hiện **bước X.Y**: làm đúng mục "Làm", chạy mục "Kiểm tra", nếu pass thì commit đúng message ghi trong bước, đánh dấu `[x]` cạnh bước đó trong file plan. Sau đó DỪNG và báo cáo kết quả kiểm tra. Nếu gặp tình huống plan không lường trước (lỗi compile lạ, plan mâu thuẫn với code), **dừng và hỏi**, không tự thiết kế lại. Trên nhánh `refactor/*`, **không sửa bất kỳ lỗi nào của code cũ**, kể cả khi thấy rõ là sai: chỉ ghi vào mục "Việc để sau".
4. **Review**: bạn tự kiểm tra (curl, FE). Với các bước có ghi "nhờ Opus review" (1.4, 4.1, 4.2, 8.1), chạy `/model` → Opus, `/clear`, rồi nhờ: "Review commit HEAD so với bước X.Y trong `BE/docs/MONOLITH_PLAN.md`, chỉ ra chỗ sai lệch".
5. **Khi model rẻ bị kẹt** quá 2 lần ở cùng một lỗi: đổi sang Opus cho riêng lỗi đó, sửa xong thì đổi lại.

---

## Việc để sau (sửa ở Phase F, mỗi mục một nhánh `fix/*`; mục ghi "trước deploy" thì làm trước Phase 10)

Đây là các lỗi **có sẵn ở stack cũ**, được giữ nguyên trong lúc refactor theo nguyên tắc "giữ nguyên hành vi 100%". Mỗi mục sửa trên **một nhánh `fix/*` riêng** theo quy trình ở Phase F, sau mốc 7.2. Ai thấy lỗi mới trong lúc làm thì ghi thêm vào đây, không sửa ngay.

- **[bắt buộc trước deploy → F1] Giả mạo `X-User-Id`**: `JwtAuthFilter` (giống gateway cũ) giữ header client gửi khi không có token hợp lệ, nên tự gửi `X-User-Id` là đọc được rating của người khác (`GET /api/exams/<id>/rating`).
- **Lỗi user trả HTTP 200**: `identity/.../user/exception/UserExceptionHandler` trả `ErrorResponse` với status 200, `GlobalResponseInterceptor` bọc thành `{"Success": false, ...}` (chữ S hoa), nên envelope ngoài cùng ghi `success:true`. Sửa: trả status thật, rồi bỏ lớp bọc riêng của user (`GlobalResponseInterceptor`, `RequestTimeInterceptor`, `WebConfig`).
- **Đăng ký không nguyên tử**: `AuthServiceImpl.register` tạo user rồi mới lưu credential, 2 lần commit riêng. Lưu credential lỗi thì để lại user mồ côi. Sửa: `@Transactional`.
- **Kiểm tra trùng theo tên, không theo email**: `UserService.createUser` kiểm tra `existsByName` và ném `RuntimeException` (thành 500). Sửa: kiểm tra theo email (auth đã kiểm tra email trong `credentials`), trả 409.
- **`POST /api/users` công khai**: ai cũng tạo được bản ghi user mà không có credential. Trước kia endpoint này dành cho auth gọi nội bộ. Sửa: bỏ endpoint, hoặc chặn ở `SecurityConfig`.
- **AI coach tin `userId` trên URL**: `/api/coach/{userId}/...` và `?userId=` không kiểm tra người gọi, nên ai cũng xem và kích hoạt phân tích cho user khác. `GET .../analysis` còn **ghi** vào `user_topic_mastery` (GET có tác dụng phụ). Sửa: lấy userId từ `X-User-Id`, thêm các path này vào danh sách protected.
- **Ghi dữ liệu không cần quyền admin**: `POST/PUT/DELETE /api/exams/**` và `/api/materials/**` chỉ cần đăng nhập. Ingest chỉ được chặn bằng cờ `INGEST_ENABLED`, không có xác thực admin.
- **Path traversal ở file tài liệu**: `MaterialFileController` không kiểm tra đường dẫn sau khi `resolve` còn nằm trong `file.base-path`. Lỗi này tự hết sau bước 9.4, vì không còn đọc ổ đĩa.
- **Thiếu khoá ngoại**: `credentials.user_id`, `exam_attempts.user_id`, `exam_ratings.user_id`, `user_topic_mastery.user_id` trước kia nằm ở DB khác nên không có FK (đã gây ra dữ liệu mồ côi, xem 1.3). Giờ chung một DB nên thêm FK được, bằng migration mới.
- **Cookie `refresh_token`**: `AuthController` ghi cứng max-age 30 ngày, không đọc `jwt.refresh-expiration`.
- **DTO trùng giữa ai và exam**: `AICoachAnalysisDTO`, `NodeDTO`, `EdgeDTO` có ở cả hai module (giữ lại ở 7.1 để JSON không đổi thứ tự key). Gộp về một bản. Thứ tự key trong JSON của `/api/coach/*/analysis` sẽ đổi, FE đọc theo tên nên không ảnh hưởng.
- Cookie auth: đổi `.sameSite(cookieSecure ? "None" : "Lax")` thành `.sameSite("Lax")` cố định, `Secure` vẫn theo `COOKIE_SECURE`. Trình duyệt luôn gọi `/api` cùng domain qua rewrite của Vercel nên không cần `None`, mà `Lax` an toàn hơn trước CSRF. Kiểm tra lại đăng nhập, refresh và AI coach trên domain thật.
