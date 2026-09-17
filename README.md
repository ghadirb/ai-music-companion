# AI Music Companion

اپلیکیشن اندروید پلیر موسیقی آفلاین هوشمند — Kotlin + Jetpack Compose + Media3 + Room.
بدون استریم، بدون سرور، بدون خروج داده از دستگاه. طبق سند پروژه («سند برنامه موسیقی آفلاین هوشمند اندروید.txt»).

## BUILD STATUS

- کل فاز MVP (طبق تعریف خودِ سند) نوشته و به این ریپو پوش شده است، از جمله Playlist دستی و پروفایل‌سازی دوره‌ای.
- **این پروژه در محیط ساخت این چت کامپایل/بیلد نشده** — سندباکس این محیط به `google()`/`mavenCentral()` و Android SDK دسترسی ندارد، پس نمی‌توان Gradle را همین‌جا اجرا کرد. کدها با دقت و طبق APIهای رسمی Media3/Room/Compose نوشته شده‌اند، اما تنها راه تایید قطعی، Build واقعی در Android Studio است.
- برای ساخت: پروژه را با **Android Studio (Koala/2024.1 یا جدیدتر)** باز کنید؛ Studio وابستگی‌ها را دانلود و Gradle Wrapper را خودکار همگام می‌کند (`gradle-wrapper.jar` عمداً در ریپو نیست چون در سندباکس قابل دانلود نبود).
- `minSdk = 26`, `compileSdk = targetSdk = 34`, Kotlin `1.9.24`, AGP `8.5.2`.

## FEATURE STATUS

پیاده‌سازی‌شده (واقعی، نه اسکلت خالی):
- اسکن کتابخانه از `MediaStore` (MP3/FLAC/WAV/M4A/OGG)
- پخش با Media3 ExoPlayer + `MediaSessionService` (کنترل نوتیفیکیشن/صفحه قفل)
- ناوبری کامل Compose با BottomBar + TopAppBar (عنوان و دکمه بازگشت پویا) برای همه صفحات سند: Home، Library، Artists (+ جزئیات خواننده)، Albums (+ جزئیات آلبوم)، Playlists (+ جزئیات پلی‌لیست)، Favorites، Settings، Player
- Room: Track / ListeningHistory / UserPreference / **Playlist / PlaylistTrackCrossRef** (v2)
- ثبت خودکار تاریخچه گوش‌دادن (شروع/پایان، درصد تکمیل، Skip، Replay) روی هر تغییر آهنگ
- Favorite (toggle از Library و Favorites)
- **Playlist دستی کامل**: ساخت، حذف، نمایش تعداد آهنگ، افزودن آهنگ از Library (با ساخت پلی‌لیست جدید در همان دیالوگ)، حذف آهنگ از پلی‌لیست، پخش از داخل پلی‌لیست
- Recommendation Engine (فاز اول سند): امتیازدهی بر اساس Favorite + گوش کامل + Replay − Skip → کارت «پیشنهاد امروز»
- **«آهنگ‌های فراموش‌شده» واقعی**: `MusicRepository.rediscoverTracks()` آهنگ‌های محبوب/کامل‌گوش‌داده‌شده‌ای که اخیراً پخش نشده‌اند را برمی‌گرداند → کارت «دوباره کشف کن» در Home
- **پروفایل‌سازی خودکار دوره‌ای**: `TasteProfileWorker` (WorkManager، هر ۲۴ ساعت، کاملاً آفلاین) `UserPreferenceEntity` را از تاریخچه بازسازی می‌کند — دقیقاً همان «بعد از چند روز استفاده پروفایل ساخته شود» در سند
- RTL و Material 3 Dark پیش‌فرض

فقط معماری/جای‌گذاری‌شده برای آینده (طبق سند، عمداً در MVP ساخته نشده):
- **AudioAnalyzer** (BPM/Mood/Energy Detection, TensorFlow Lite/ONNX): کدی نوشته نشده؛ لایه repository/recommendation از UI جداست تا بعداً بدون تغییر UI اضافه شود
- کارت‌های «مناسب شب» و «مناسب رانندگی» در Home همچنان **استاتیک** هستند — فیلتر واقعی به Energy/Mood Detection نیاز دارد که در بالا گفته شد هنوز نیست؛ ساختن این دو کارت با داده جعلی/حدسی به‌عمد انجام نشد
- **AI DJ, Voice Assistant, Android Auto, Widget, Backup/Sync**: هیچ‌کدام شروع نشده (طبق سند: «فعلاً نساز»)
- **تحلیل متن شعر فارسی**: شروع نشده
- تغییر ترتیب دستی آهنگ‌های داخل پلی‌لیست (drag & drop): جدول `position` برایش آماده است ولی UI بازچینی نوشته نشده

هرگز ساخته نشده (طبق دستور صریح سند): حساب کاربری، سرور، اشتراک، پرداخت، تبلیغات، AI ابری.

## DATABASE STRUCTURE (Room, version 2)

**tracks** — id, path (MediaStore URI، unique)، title، artist، album، genre؟، durationMs، albumArtUri؟، dateAdded، isFavorite

**listening_history** — id، trackId، startTime، listenDurationMs، completedPercentage، skipped، replayCount

**user_preference** — تک‌ردیفی (id=0)، favoriteArtists، favoriteGenres، favoriteEnergyLevel، preferredDurationMs، preferredTimeOfDay — توسط `TasteProfileWorker` هر روز بازسازی می‌شود

**playlists** — id، name، createdAt

**playlist_track_cross_ref** — کلید ترکیبی (playlistId, trackId)، position (برای حفظ ترتیب)

> نکته Migration: چون هنوز کاربر واقعی/نسخه منتشرشده‌ای وجود ندارد، از `fallbackToDestructiveMigration()` استفاده شده (v1→v2 داده‌ها را پاک می‌کند). قبل از هر انتشار عمومی باید با یک `Migration` واقعی جایگزین شود تا دیتای کاربر پاک نشود.

## AI ROADMAP

1. **انجام‌شده**: Metadata scoring (Favorite/Completion/Skip/Replay) + WorkManager دوره‌ای برای پروفایل
2. بعدی: Audio embedding / BPM / Mood / Energy detection — hook برای TensorFlow Lite یا ONNX Runtime (یک ماژول جدید مثل `data/analysis/` بدون تغییر UI)
3. بعدی: با وجود Energy Detection، کارت‌های «شب»/«رانندگی» را از استاتیک به فیلتر واقعی تبدیل کن
4. بعدی: تحلیل شعر فارسی + تشخیص حس
5. در صورت اضافه‌شدن AI ابری: Consent UI + کنترل کاربر + حذف اطلاعات شخصی طبق سند (فعلاً هیچ کدی برایش نیست و هیچ مجوز اینترنتی هم در Manifest درخواست نشده)

## KNOWN LIMITATIONS

- بدون Gradle Wrapper jar در ریپو (محدودیت شبکه سندباکس) — در Android Studio باز کنید تا خودش حلش کند.
- بدون تست ساخت واقعی/APK — قبل از انتشار حتماً در Android Studio Build کنید.
- Album art از URI قدیمی `content://media/external/audio/albumart/{id}` خوانده می‌شود که در برخی دستگاه‌های Android 10+ ممکن است کار نکند؛ جایگزین پایدارتر `ContentResolver.loadThumbnail` (API 29+) است.
- منطق «Skip vs Completed» در `PlayerViewModel` یک heuristic ساده است، نه دقیق ۱۰۰٪.
- بدون تست واحد (Unit Test) یا UI Test.
- بدون DI framework (Hilt/Koin) — به‌عمد برای سادگی MVP.
- رشته‌های UI صفحات Playlist مستقیم در Kotlin نوشته شده‌اند نه در `strings.xml` — برای لوکالایز کامل باید استخراج شوند.
- `fallbackToDestructiveMigration()` یعنی تغییر schema آینده داده محلی کاربر را پاک می‌کند — قبل از انتشار باید Migration واقعی نوشته شود.

## Privacy & Security (طبق سند)

- بدون اینترنت permission در Manifest.
- هیچ فایل صوتی یا تاریخچه‌ای از دستگاه خارج نمی‌شود — همه‌چیز در Room محلی است، شامل پروفایل‌سازی دوره‌ای که هم کاملاً آفلاین اجرا می‌شود.
- هیچ حساب کاربری/سرور/تبلیغاتی وجود ندارد.
