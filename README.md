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
- ناوبری کامل Compose با BottomBar + TopAppBar (عنوان و دکمه بازگشت پویا) برای همه صفحات سند: Home، Library، Artists (+ جزئیات خواننده)، Albums (+ جزئیات آلبوم)، Playlists (+ جزئیات پلی‌لیست)، Favorites، Settings، Player — همه‌جا فارسی (برچسب تب‌ها هم‌اکنون از `strings.xml` می‌آید؛ قبلاً هاردکد انگلیسی بود)
- Room: Track / ListeningHistory / UserPreference / Playlist / PlaylistTrackCrossRef (v3)
- ثبت خودکار تاریخچه گوش‌دادن (شروع/پایان، درصد تکمیل، Skip، Replay) روی هر تغییر آهنگ
- Favorite (toggle از Library و Favorites)
- Playlist دستی کامل: ساخت، حذف، نمایش تعداد آهنگ، افزودن آهنگ از Library، حذف آهنگ از پلی‌لیست، پخش از داخل پلی‌لیست
- Recommendation Engine (فاز اول سند): امتیازدهی بر اساس Favorite + گوش کامل + Replay − Skip → کارت «پیشنهاد امروز»
- «آهنگ‌های فراموش‌شده» واقعی → کارت «دوباره کشف کن» در Home
- پروفایل‌سازی خودکار دوره‌ای: `TasteProfileWorker` (WorkManager، هر ۲۴ ساعت، کاملاً آفلاین)
- RTL و Material 3 Dark پیش‌فرض
- **AudioAnalyzer (جدید)**: `analysis/AudioAnalyzer.kt` فایل صوتی را با `MediaExtractor`+`MediaCodec` روی خود دستگاه دیکد می‌کند (بدون TensorFlow Lite/ONNX — یک روش سبک DSP)، بلندی صدا (RMS) را برای امتیاز انرژی ۰..۱ و ضرب‌آهنگ (BPM) را با autocorrelation روی envelope انرژی تخمین می‌زند، و یک برچسب حس (calm/energetic/neutral) می‌سازد. `AudioAnalysisWorker` (WorkManager) این را روی آهنگ‌های تحلیل‌نشده به‌صورت دسته‌ای و کاملاً آفلاین اجرا می‌کند — بعد از هر اسکن و همچنین دوره‌ای.
- **کارت‌های «مناسب شب» و «مناسب رانندگی» اکنون واقعی هستند**: `MusicRepository.nightSuitableTracks()`/`drivingSuitableTracks()` بر اساس moodTag/energyLevel واقعیِ AudioAnalyzer فیلتر می‌کنند؛ تا وقتی تحلیل در پس‌زمینه هنوز کافی انجام نشده، پیام کوتاه «در حال تحلیل آهنگ‌ها…» نشان داده می‌شود به‌جای کارت خالی یا داده جعلی.
- **تحلیل متن شعر فارسی (best-effort)**: `analysis/LyricsAnalyzer.kt` فقط یک فایل sidecar محلی `.lrc` هم‌نام آهنگ را می‌خواند (اگر کاربر داشته باشد) و با یک فهرست کلیدواژه فارسی حس غم/شادی را تخمین می‌زند تا حس مرزی («neutral») صوتی را دقیق‌تر کند؛ هیچ مدل NLP یا سروری در کار نیست.

رفع‌شده در این نوبت (باگ‌های گزارش‌شده از تست واقعی روی دستگاه):
- **کرش هنگام اسکن/رفرش کتابخانه**: `MediaStore.Audio.Media.GENRE` روی برخی دستگاه‌ها (مثلاً Android 10 / API 29) اصلاً به‌عنوان ستون قابل‌کوئری وجود ندارد و کوئری با `IllegalArgumentException: Invalid column genre` کرش می‌کرد. حالا فقط API 30+ درخواست می‌شود و در صورت رد شدن توسط provider، به‌صورت خودکار بدون genre دوباره کوئری می‌زند.
- **پرش لحظه‌ای صفحه Home**: `hasLibrary` پیش‌فرض `true` بود، پس یک فریم کل کارت‌ها را نشان می‌داد و بلافاصله (وقتی `trackCount()` واقعی صفر برمی‌گشت) به پیام «کتابخانه خالی» می‌پرید. اکنون این مقدار تا پایان اولین بررسی `null` (نامشخص) است و هیچ‌چیز رندر نمی‌شود تا وضعیت واقعی معلوم شود.
- **برچسب تب‌های پایین/عنوان صفحه به انگلیسی** (Home/Library/Artists/…): این‌ها هاردکد بودند و از `strings.xml` نمی‌آمدند؛ اصلاح شد تا همه‌جا فارسی نمایش داده شود.

فقط معماری/جای‌گذاری‌شده برای آینده (طبق سند، عمداً در MVP ساخته نشده):
- کارت‌های «مناسب شب»/«مناسب رانندگی» تا وقتی AudioAnalysisWorker کتابخانه را تحلیل نکرده (چند دقیقه اول پس از اولین اسکن، بسته به تعداد آهنگ) خالی/در-حال-تحلیل هستند — این طبیعی است، نه باگ.
- **AI DJ, Voice Assistant, Android Auto, Widget, Backup/Sync**: هیچ‌کدام شروع نشده (طبق سند: «فعلاً نساز»)

هرگز ساخته نشده (طبق دستور صریح سند): حساب کاربری، سرور، اشتراک، پرداخت، تبلیغات، AI ابری.

## DATABASE STRUCTURE (Room, version 3)

**tracks** — id, path (MediaStore URI، unique)، title، artist، album، genre؟، durationMs، albumArtUri؟، dateAdded، isFavorite، **energyLevel؟ (Float 0..1)، bpm؟ (Int)، moodTag؟ (calm/energetic/neutral)، analyzed (Boolean)**

**listening_history** — id، trackId، startTime، listenDurationMs، completedPercentage، skipped، replayCount

**user_preference** — تک‌ردیفی (id=0)، favoriteArtists، favoriteGenres، favoriteEnergyLevel، preferredDurationMs، preferredTimeOfDay — توسط `TasteProfileWorker` هر روز بازسازی می‌شود

**playlists** — id، name، createdAt

**playlist_track_cross_ref** — کلید ترکیبی (playlistId, trackId)، position (برای حفظ ترتیب)

> Migration: v2→v3 یک `Migration` واقعی است (چهار `ALTER TABLE ADD COLUMN`) — داده کاربر پاک نمی‌شود. v1→v2 هنوز `fallbackToDestructiveMigrationFrom(1)` است چون v1 هرگز منتشر نشد.

## AI ROADMAP

1. **انجام‌شده**: Metadata scoring (Favorite/Completion/Skip/Replay) + WorkManager دوره‌ای برای پروفایل
2. **انجام‌شده**: Audio energy (RMS) + tempo (BPM از autocorrelation) به‌صورت DSP سبک روی خود دستگاه — بدون مدل ML؛ TensorFlow Lite/ONNX هنوز اضافه نشده و برای mood/genre classification دقیق‌تر در آینده باقی می‌ماند.
3. **انجام‌شده**: کارت‌های «شب»/«رانندگی» از استاتیک به فیلتر واقعی energyLevel/moodTag تبدیل شدند.
4. **شروع محدود**: تحلیل شعر فارسی فقط برای فایل‌های `.lrc` sidecar محلی (کلیدواژه‌محور، نه NLP) — نه استخراج از ID3/متادیتا، نه دانلود شعر.
5. بعدی: AI DJ (زبان طبیعی → فیلتر mood/energy)، Voice Assistant، Android Auto (نیاز به تبدیل PlaybackService به `MediaLibraryService`)، Widget صفحه اصلی، Backup/Restore محلی (JSON از پلی‌لیست‌ها/علاقه‌مندی‌ها/تنظیمات — بدون سرور/Sync ابری، طبق سند).
6. در صورت اضافه‌شدن AI ابری: Consent UI + کنترل کاربر + حذف اطلاعات شخصی طبق سند (فعلاً هیچ کدی برایش نیست و هیچ مجوز اینترنتی هم در Manifest درخواست نشده)

## KNOWN LIMITATIONS

- بدون Gradle Wrapper jar در ریپو در بعضی حالت‌ها (بسته به شبکه سندباکس ساخت) — در Android Studio باز کنید تا خودش حلش کند.
- بدون تست ساخت واقعی/APK در این نوبت هم — سندباکس این محیط به Android SDK/`google()`/`mavenCentral()` دسترسی ندارد؛ قبل از انتشار حتماً در Android Studio Build کنید.
- Album art از URI قدیمی `content://media/external/audio/albumart/{id}` خوانده می‌شود که در برخی دستگاه‌های Android 10+ ممکن است کار نکند.
- منطق «Skip vs Completed» در `PlayerViewModel` یک heuristic ساده است، نه دقیق ۱۰۰٪.
- **تخمین BPM/انرژی یک heuristic DSP است، نه یک مدل آموزش‌دیده** — روی آهنگ‌های کم‌ضرب (آمبینت، صدای تنها) ممکن است BPM را null برگرداند (عمدی، به‌جای حدس اشتباه)؛ فقط ۴۵ ثانیه اول هر فایل تحلیل می‌شود تا سریع و کم‌مصرف بماند.
- **`LyricsAnalyzer` روی Android 10+ (scoped storage) ممکن است هیچ‌وقت فایل `.lrc` را پیدا نکند** مگر دستگاه/نسخه اجازه دسترسی مستقیم به مسیر فایل را بدهد؛ این محدودیت شناخته‌شده و مستند است، نه باگ پنهان — راه‌حل کامل‌تر (SAF folder picker) هنوز ساخته نشده.
- بدون تست واحد (Unit Test) یا UI Test.
- بدون DI framework (Hilt/Koin) — به‌عمد برای سادگی MVP.
- رشته‌های UI صفحات Playlist مستقیم در Kotlin نوشته شده‌اند نه در `strings.xml` — برای لوکالایز کامل باید استخراج شوند.
- `fallbackToDestructiveMigrationFrom(1)` یعنی فقط ارتقاء از نسخه v1 (که هرگز منتشر نشد) داده را پاک می‌کند؛ v2→v3 امن است.

## Privacy & Security (طبق سند)

- بدون اینترنت permission در Manifest.
- هیچ فایل صوتی یا تاریخچه‌ای از دستگاه خارج نمی‌شود — همه‌چیز در Room محلی است، شامل پروفایل‌سازی دوره‌ای و تحلیل صوتی/شعر که هر دو کاملاً آفلاین اجرا می‌شوند.
- هیچ حساب کاربری/سرور/تبلیغاتی وجود ندارد.
