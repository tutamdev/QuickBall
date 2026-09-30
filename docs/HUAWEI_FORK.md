# QuickBall – bản fork cá nhân cho Huawei Mate 20 Pro / HarmonyOS 4.0

Tài liệu này mô tả phân tích source gốc, các thay đổi, thiết lập bắt buộc trên Huawei và checklist kiểm thử trên máy thật.

## 1. Phân tích repository gốc

| Thành phần | File | Vai trò |
|---|---|---|
| Activity | `ui/MainActivity.kt` | Chỉ là UI cài đặt (Compose + Navigation). Không cần để bóng hoạt động. |
| AccessibilityService | `core/QuickBallService.kt` | **Toàn bộ runtime**: tạo overlay, nhận sự kiện cửa sổ, thực thi action. |
| Overlay | `QuickBallService.createSystemWindowParams()` | `WindowManager.addView` với `TYPE_ACCESSIBILITY_OVERLAY` → **không cần** quyền "Hiển thị trên ứng dụng khác" (SYSTEM_ALERT_WINDOW). |
| Views | `ui/floating/FloatTouchView`, `SideKickView`, `FloatPanelView` | Bóng, thanh cạnh (pill), menu quạt. |
| Actions | `domain/handlers/QuickBallActionHandler.kt` | `performGlobalAction` (Home/Back/Recents/Notification/Quick Settings/Power/Screenshot/Lock), `AudioManager` (âm lượng), `Settings.System` (độ sáng). |
| State | `domain/AppPreference.kt` | SharedPreferences `quick_ball_prefs` (vị trí bóng, màu, danh sách app tự ẩn `selected_apps`, …). |
| Accessibility config | `res/xml/accessibility_service_config.xml` | Chỉ `typeWindowsChanged|typeWindowStateChanged`, **không** `canRetrieveWindowContent`. Tác giả gốc đã gỡ quyền đọc nội dung cửa sổ (commit 27e9e8d) để giảm cảnh báo "rủi ro cao" từ app ngân hàng. Fork này **giữ nguyên** config đó. |

Không có Service thường, không có BootReceiver, không có foreground service.

### Tính năng tự ẩn đã có sẵn – nhưng bị lỗi

Repo gốc đã có màn hình "Hide automatically" và `autoHideApps`. Hai lỗi:

1. `onForegroundPackageChanged()` **bỏ qua sự kiện đầu tiên sau khi rời một app bị loại trừ**. Hệ quả: rời app ngân hàng về launcher, bóng vẫn ẩn cho tới khi có thêm một sự kiện cửa sổ nữa.
2. Mọi `TYPE_WINDOW_STATE_CHANGED` đều được coi là "đổi app foreground" – kể cả **bàn phím**, dialog, toast. Mở bàn phím trong app ngân hàng → packageName là IME → bóng có thể hiện lại đè lên app.

### Vì sao Quick Ball chết khi clear khỏi Recent Apps

Trên HarmonyOS/EMUI, vuốt bỏ một app khỏi Recents (khi app chưa được "khóa") thực hiện gần như **force-stop** gói ứng dụng. Với AccessibilityService, AOSP xử lý force-stop bằng cách **gỡ dịch vụ khỏi danh sách trợ năng đang bật**. Vì toàn bộ Quick Ball chạy trong AccessibilityService nên:

- Process chết → overlay biến mất.
- Dịch vụ bị tắt trong Settings → **không tự khởi động lại**, kể cả sau reboot.
- App ở trạng thái "stopped" → Android **không gửi BOOT_COMPLETED** cho app cho tới khi người dùng mở nó.

`START_STICKY`, foreground service, `BOOT_COMPLETED` **không thể** chống force-stop. Đây là giới hạn của hệ điều hành, không phải lỗi code. Cách xử lý đúng: (a) tránh để thẻ app nằm trong Recents, (b) người dùng cấu hình Huawei, (c) khôi phục nhanh khi đã bị tắt.

> Ghi chú: hành vi chính xác của HarmonyOS 4.0 không có tài liệu công khai; phân tích trên dựa trên AOSP và hành vi phổ biến của EMUI. Hãy xác nhận bằng checklist ở mục 5.

## 2. Thay đổi trong fork

### Auto-hide (Excluded Apps)
- `core/visibility/ForegroundAppTracker.kt` (mới, thuần Kotlin, có unit test): chỉ coi là đổi foreground khi `className` là **Activity thật** của package đó (tra `PackageManager.getActivityInfo`, có cache). Bỏ qua bàn phím (danh sách IME đang bật), SystemUI, dialog/toast.
- `core/visibility/VisibilityRules.kt` (mới, có unit test): một hàm quyết định HIDE / SHOW / SHOW_STASHED.
- `QuickBallService`: dùng hai lớp trên; khi ẩn thì **gỡ hẳn các cửa sổ overlay** (`removeView`), khi hiện thì tạo lại. Không đụng tới trạng thái Accessibility hay bất kỳ permission nào.
- Màn hình "Ứng dụng loại trừ": tìm theo tên **hoặc package**, hiện package name, áp dụng ngay (`ACTION_REFRESH`), ghi chú trung thực rằng Accessibility vẫn bật.
- Lưu trữ: giữ nguyên key SharedPreferences cũ `selected_apps` → danh sách cũ không bị mất.

### Chạy nền
| Cơ chế | Mặc định | Ghi chú |
|---|---|---|
| Ẩn khỏi Recents (`AppTask.setExcludeFromRecents`) | **Bật** | Không có thẻ trong Recents ⇒ "Xóa tất cả" không force-stop Quick Ball. API chính thức. |
| Quick Settings tile | – | Bật/tắt bóng; nếu dịch vụ đã bị tắt → mở app để bạn bật lại Trợ năng. |
| BootReceiver (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) | – | Trường hợp bình thường hệ thống tự bind dịch vụ trợ năng. Receiver chỉ phát hiện dịch vụ bị tắt và gửi thông báo "chạm để bật lại". |
| Thông báo giữ hoạt động (foreground service `specialUse`) | Tắt | Tùy chọn. Không bắt buộc vì AccessibilityService đã có độ ưu tiên cao. Được khởi động lại khi mở app, khi dịch vụ kết nối và sau `BOOT_COMPLETED`. |
| State | – | Thêm `lastServiceConnectedAt`, các cờ mới; vị trí bóng vẫn lưu như cũ. |

### Settings UI "Chạy nền & Huawei"
Trạng thái: Accessibility, Sửa cài đặt hệ thống, Device Admin (không cần – Khóa màn hình dùng `GLOBAL_ACTION_LOCK_SCREEN`), Tối ưu pin, Khởi chạy ứng dụng (không thể kiểm tra tự động). Mỗi mục thiếu có nút mở đúng trang Settings.

### Quick Actions
Không cần thêm code: Volume +/–, Brightness, Lock, Power menu, Screenshot, Home, Back, Recents, Notification shade, Quick Settings **đều đã có** trong repo gốc và dùng API chính thức. Chọn chúng trong "Chọn phím tắt".

### Khác
- Thêm ngôn ngữ **Tiếng Việt** (dịch đầy đủ). Chọn trong biểu tượng ngôn ngữ ở màn hình chính.
- GitHub Actions `.github/workflows/build-debug.yml`: unit test + lint + build APK debug + kiểm tra manifest.

### Những gì fork này KHÔNG làm (cố ý)
Không che giấu Accessibility Service, không giả mạo permission, không tự bật lại Trợ năng, không thêm `canRetrieveWindowContent`, không can thiệp phát hiện bảo mật của app ngân hàng, không root. Nếu một app ngân hàng từ chối chạy khi có dịch vụ trợ năng đang bật, auto-hide **sẽ không** thay đổi điều đó — cách duy nhất là tạm tắt Quick Ball trong Trợ năng khi dùng app đó.

## 3. Thiết lập trên Huawei Mate 20 Pro (HarmonyOS 4.0)

Tên menu có thể khác đôi chút tùy bản ROM.

1. **Cài đặt → Ứng dụng → Khởi chạy ứng dụng → Quick Ball** → tắt "Quản lý tự động" → bật cả ba: **Tự khởi chạy**, **Khởi chạy phụ**, **Chạy trong nền**.
2. **Cài đặt → Pin → (⋮) Tối ưu hóa pin** (hoặc Ứng dụng → Quick Ball → Pin) → **Không cho phép**.
3. **Cài đặt → Trợ năng → Trợ năng → Quick Ball → Bật.** Kiểm tra lại sau mỗi lần cài APK mới.
4. **Recents**: mặc định fork ẩn Quick Ball khỏi Recents. Nếu bạn tắt tùy chọn này, hãy kéo thẻ Quick Ball **xuống** trong Recents để hiện biểu tượng khóa.
5. **Thêm ô Quick Ball** vào Cài đặt nhanh (kéo thanh thông báo → biểu tượng bút chỉnh sửa).
6. (Tùy chọn) Tắt "Dọn dẹp khi khóa màn hình" nếu Trình quản lý điện thoại có mục này.

Các thiết lập 1, 2, 4, 6 **không thể** thay đổi bằng code (Huawei không cung cấp API). App chỉ mở đúng trang để bạn bật.

### Vì sao không có "tự bật lại Trợ năng"
Về kỹ thuật có thể tự ghi lại `enabled_accessibility_services` nếu cấp `WRITE_SECURE_SETTINGS` qua ADB. Fork này **cố ý không làm**: đó là tự thay đổi trạng thái Accessibility do hệ thống quyết định và cần một quyền nhạy cảm không thực sự cần thiết. Khi dịch vụ bị tắt, app chỉ báo cho bạn và mở đúng trang Trợ năng — bạn là người bật lại.

## 4. Build

Môi trường tạo bản sửa không truy cập được Android SDK/Google Maven nên **chưa build được APK**. Cách build:

- **GitHub (khuyến nghị)**: push fork → tab *Actions* → "Build debug APK" → tải artifact `quickball-debug-apk`.
- **Máy tính**: `./gradlew testDebugUnitTest lintDebug assembleDebug` (JDK 17+, Android SDK).

Lưu ý cài đặt: APK debug ký bằng khóa khác bản gốc → phải **gỡ bản QuickBall gốc** trước (mất cài đặt cũ). Muốn cài song song, thêm `applicationIdSuffix = ".huawei"` cho buildType debug.

## 5. Checklist kiểm thử trên máy thật

Theo dõi log: `adb logcat -s QuickBallService KeepAliveService BootReceiver`

| # | Kịch bản | Kỳ vọng |
|---|---|---|
| 1 | Thêm VCB/BIDV/MB vào danh sách, mở app | Bóng biến mất ngay |
| 2 | Trong app đó mở bàn phím, kéo thanh thông báo | Bóng **không** hiện lại |
| 3 | Về màn hình chính / chuyển sang app khác | Bóng hiện lại ngay ở sự kiện đầu tiên |
| 4 | Bỏ app khỏi danh sách khi app đang chạy nền, quay lại | Bóng hiện |
| 5 | Tắt/mở màn hình, mở khóa | Bóng đúng trạng thái; nếu đang ở app loại trừ vẫn ẩn |
| 6 | Mở Quick Ball rồi mở Recents | Không có thẻ Quick Ball (khi bật "Ẩn khỏi Gần đây") |
| 7 | Tắt "Ẩn khỏi Gần đây", vuốt bỏ Quick Ball | Nếu bóng mất: Trợ năng → Quick Ball đang tắt? Ghi lại kết quả. Chạm ô Quick Ball / mở app để khôi phục |
| 8 | Reboot khi dịch vụ đang bật | Bóng tự xuất hiện sau khi mở khóa, **không** cần mở app |
| 9 | Reboot sau khi đã bị force-stop | Có thể không có thông báo (app đang "stopped"). Chạm ô Quick Ball hoặc mở app |
| 10 | Cài APK mới đè lên | Bóng trở lại; nếu không, có thông báo "Quick Ball đã bị tắt" |
| 11 | Để máy qua đêm, màn hình tắt | Sáng hôm sau bóng vẫn còn |
| 12 | Volume +/–, Brightness, Lock | Hoạt động như bản gốc |
