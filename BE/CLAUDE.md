# CLAUDE.md (bản tạm trong lúc refactor)

Backend đang được chuyển từ microservice sang **modular monolith**, trên nhánh `refactor/monolith`.

- **Tài liệu duy nhất cần tin là `docs/MONOLITH_PLAN.md`.** Đọc plan trước khi sửa bất cứ thứ gì trong `BE/`.
- Chỉ làm đúng bước được giao trong plan. Nếu gặp tình huống plan không lường trước, **dừng và hỏi**, không tự thiết kế lại.
- Tài liệu kiến trúc microservice cũ đã bị xoá vì lỗi thời (vẫn còn trong git history). **Không** khôi phục chúng và **không** làm theo các pattern cũ:
  - không dùng Eureka hay Feign;
  - không thêm route vào gateway;
  - không tạo database riêng cho từng service.
- File này sẽ được viết lại ở bước 10.3 của plan.
