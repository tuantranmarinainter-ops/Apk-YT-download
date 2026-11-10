# YT Sub — tải YouTube & phụ đề Anh/Việt cho Android

App Android tải video / Shorts YouTube kèm phụ đề **Tiếng Anh**, **Tiếng Việt** hoặc **Song ngữ Anh–Việt**
(yt-dlp + FFmpeg chạy ngay trên điện thoại).

## Tải APK
Vào mục **[Releases mới nhất](../../releases/latest)** → tải file `YTSub-…-arm64.apk` → mở để cài
(cho phép "Cài ứng dụng không rõ nguồn gốc" nếu được hỏi). Máy 32-bit cũ dùng file `-arm32.apk`.

## Dùng
- Trong app YouTube bấm **Chia sẻ → YT Sub**, hoặc dán link vào app.
- Chọn: Video / MP3 / Chỉ phụ đề · chất lượng · phụ đề Anh / Việt / Song ngữ.
- Cách gắn phụ đề: file .srt riêng · nhúng vào MP4 · **in cứng lên video** (dòng Việt màu vàng,
  Shorts tự đẩy chữ lên cao tránh bị che).
- File lưu ở **Download/YTSub**. Bấm vào chữ "yt-dlp …" ở góc trên để cập nhật yt-dlp
  (app cũng tự cập nhật mỗi lần mở).

## Build
Mỗi lần push lên `main`, GitHub Actions tự build và tạo Release mới (`.github/workflows/build.yml`).
Yêu cầu Android 10 trở lên.

Chỉ tải nội dung bạn có quyền sử dụng.
