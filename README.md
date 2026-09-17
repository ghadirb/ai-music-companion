# AI Music Companion

اپلیکیشن اندروید پلیر موسیقی آفلاین هوشمند — Kotlin + Jetpack Compose + Media3 + Room.
بدون استریم، بدون سرور، بدون خروج داده از دستگاه. طبق سند پروژه («سند برنامه موسیقی آفلاین هوشمند اندروید.txt»).

## BUILD STATUS

- کد فاز MVP (پلیر پایه + ثبت رفتار + Recommendation ساده) نوشته و به این ریپو پوش شده است.
- **این پروژه در محیط ساخت این چت کامپایل/بیلد نشده** — سندباکس این محیط به `google()`/`mavenCentral()` و Android SDK دسترسی ندارد، پس نمی‌توان Gradle را همین‌جا اجرا کرد.
- برای ساخت واقعی: پروژه را با **Android Studio (Koala/2024.1 یا جدیدتر)** باز کنید؛ Studio به‌طور خودکار وابستگی‌ها را دانلود و Gradle Wrapper را همگام می‌کند (فایل `gradle-wrapper.jar` عمداً در این ریپو نیست چون قابل دانلود در سندباکس نبود).
- `minSdk = 26`, `compileSdk = targetSdk = 34`, Kotlin `1.9.24`, AGP `8.5.2`.

## FEATURE STATUS

پیاده‌سازی‌شده (واقعی، نه اسکلت خالی):
- اسکن کتابخانه از `MediaStore` (MP3/FLAC/WAV/M4A/OGG — هرچه MediaStore ایندکس کرده)
- پخش با Media3 ExoPlayer + `MediaSessionService` (کنترل از نوتیفیکیشن/صفحه قفل)
- صفحات: Home، Library، Artists، Albums، Favorites، Settings، Player (ناوبری Compose کامل با BottomBar)
- Room: جدول‌های Track / ListeningHistory / UserPreference طبق سند
- ثبت خودکار تاریخچه گوش‌دادن (شروع/پایان، درصد تکمیل، Skip، Replay) روی هر تغییر آهنگ
- Favorite (toggle از Library، نمایش در Favorites)
- Recommendation Engine ساده (فاز اول سند): امتیازدهی بر اساس Favorite + تعداد گوش کامل + Replay − Skip
- RTL و Material 3 Dark به‌صورت پیش‌فرض طبق سند

فقط معماری/جای‌گذاری‌شده برای آینده (طبق سند، عمداً در MVP ساخته نشده):
- **Playlist دستی**: صفحه Placeholder است؛ جدول‌های `Playlist`/`PlaylistTrackCrossRef` و UI ساخت/ویرایش هنوز اضافه نشده
- **AudioAnalyzer** (BPM/Mood/Energy Detection, TensorFlow Lite/ONNX): هیچ کدی نوشته نشده — طبق سند فقط باید معماری برایش باز باشد که هست (repository/recommendation جدا از UI است)
- **پیشنهاد بر اساس زمان/مکان (شب، رانندگی)**: کارت‌های Home فعلاً استاتیک هستند؛ فیلتر واقعی زمان/سنسور حرکت پیاده نشده
- **AI DJ, Voice Assistant, Android Auto, Widget, Backup/Sync**: هیچ‌کدام شروع نشده (طبق سند: «فعلاً نساز»)
- **تحلیل متن شعر فارسی**: شروع نشده

هرگز ساخته نشده (طبق دستور صریح سند): حساب کاربری، سرور، اشتراک، پرداخت، تبلیغات، AI ابری.

## DATABASE STRUCTURE (Room, version 1)

**tracks** — id, path (MediaStore URI، unique)، title، artist، album، genre؟، durationMs، albumArtUri؟، dateAdded، isFavorite

**listening_history** — id، trackId، startTime، listenDurationMs، completedPercentage، skipped، replayCount

**user_preference** — تک‌ردیفی (id=0)، favoriteArtists، favoriteGenres، favoriteEnergyLevel، preferredDurationMs، preferredTimeOfDay
(نکته: `RecommendationEngine.buildTasteProfile()` منطق ساخت این پروفایل را دارد اما فعلاً به‌صورت WorkManager دوره‌ای زمان‌بندی نشده — باید در `AiMusicApp` یا یک ماژول جدید wire شود.)

## AI ROADMAP

1. فعلی: Metadata-only scoring (Favorite/Completion/Skip/Replay)
2. بعدی: WorkManager job برای refresh دوره‌ای `UserPreferenceEntity`
3. بعدی: Audio embedding / BPM / Mood / Energy detection — hook برای TensorFlow Lite یا ONNX Runtime (معماری فعلی این را با یک لایه جدید مثل `data/analysis/` بدون تغییر در UI پشتیبانی می‌کند)
4. بعدی: تحلیل شعر فارسی + تشخیص حس
5. در صورت اضافه‌شدن AI ابری: باید Consent UI + کنترل کاربر + حذف اطلاعات شخصی طبق سند اضافه شود (فعلاً هیچ کدی برای این وجود ندارد و هیچ مجوز اینترنتی هم در Manifest درخواست نشده)

## KNOWN LIMITATIONS

- بدون Gradle Wrapper jar در ریپو (به دلیل محدودیت شبکه سندباکس ساخت) — در Android Studio باز کنید تا خودش حلش کند.
- بدون تست ساخت واقعی/APK — قبل از انتشار حتماً در Android Studio Build کنید و خطاهای احتمالی وابستگی/نسخه را برطرف کنید.
- Album art از URI قدیمی `content://media/external/audio/albumart/{id}` خوانده می‌شود که در برخی دستگاه‌های Android 10+ ممکن است کار نکند؛ جایگزین پایدارتر `MediaStore.Audio.Albums.getContentUri` + `ContentResolver.loadThumbnail` (API 29+) است — بهبود آینده.
- منطق «Skip vs Completed» در `PlayerViewModel` یک heuristic ساده است (بر پایه `MEDIA_ITEM_TRANSITION_REASON`)، نه دقیق ۱۰۰٪.
- بدون تست واحد (Unit Test) یا UI Test در این نسخه.
- بدون DI framework (Hilt/Koin) — به‌عمد برای سادگی MVP؛ اگر پروژه بزرگ‌تر شود توصیه می‌شود اضافه شود.

## Privacy & Security (طبق سند)

- بدون اینترنت permission در Manifest.
- هیچ فایل صوتی یا تاریخچه‌ای از دستگاه خارج نمی‌شود — همه‌چیز در Room محلی است.
- هیچ حساب کاربری/سرور/تبلیغاتی وجود ندارد.
