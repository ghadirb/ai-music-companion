# سیاست حریم خصوصی — AI Music Companion
# Privacy Policy — AI Music Companion

آخرین به‌روزرسانی / Last updated: 2026-09-20 · تماس / Contact: **[ایمیل پشتیبانی را پیش از انتشار اینجا وارد کنید / insert support e-mail before publishing]**

## خلاصه / Summary
برنامه به‌صورت پیش‌فرض **همه‌چیز را روی دستگاه شما** نگه می‌دارد و **هیچ حساب کاربری، تبلیغ یا ابزار ردیابی** ندارد. ارسال هر داده‌ای به سرور فقط برای قابلیت‌های اختیاریِ زیر و پس از اقدام یا رضایت شماست.
By default everything stays **on your device**. There are **no accounts, ads or trackers**. Data is sent to a server only for the optional features below, after your action/consent.

## داده‌های محلی (هرگز ارسال نمی‌شوند) / Local data (never uploaded)
فایل‌های موسیقی، متادیتا، کتابخانه، تاریخچهٔ شنیدن، علاقه‌مندی‌ها، پلی‌لیست‌ها، پروفایل سلیقه، آمار، متن `.lrc`، نتایج تحلیل صوتی و صف پخش. با حذف برنامه پاک می‌شوند. بکاپ فقط در محلی که خودتان انتخاب می‌کنید ذخیره می‌شود و شامل فایل صوتی، توکن یا خرید نیست.
Music files, metadata, library, listening history, favourites, playlists, taste profile, statistics, `.lrc` lyrics, audio-analysis results and the play queue. Removed when you uninstall. Backups go only where you choose and contain no audio, tokens or purchases.

## داده‌هایی که ممکن است به سرور بروند / Data that may be sent
| چه داده‌ای / What | چرا / Why | چه زمانی / When | به کجا / Where | نگهداری / Retention |
|---|---|---|---|---|
| شناسهٔ تصادفی نصب + نشست ناشناس، آدرس IP (فقط برای محدودسازی) / Random install ID + anonymous session; IP (rate-limiting only) | احراز نشست، جلوگیری از سوءاستفاده / session auth, abuse prevention | هنگام اولین استفاده از قابلیت ابری یا خرید / on first cloud/purchase use | Cloudflare Worker | IP فقط به‌صورت هش‌شده و شمارنده؛ شمارنده‌ها حداکثر ≈۲۵ ساعت / IP only as a salted hash inside counters; counters ≤ ~25 h |
| توصیف کوتاه حداکثر ۸ آهنگ (نام، خواننده، آلبوم، سبک، حال‌وهوا، BPM) / Short descriptors of ≤8 tracks (title, artist, album, genre, mood, BPM) | «بهبود آهنگ‌های مشابه با AI» / "Improve similar tracks with AI" | فقط با فعال‌بودن اجازهٔ AI و زدن دکمه / only with AI consent, when you tap the button | Worker → ارائه‌دهندهٔ AI (GapGPT) | ذخیره نمی‌شود؛ نگهداری نزد ارائه‌دهنده طبق شرایط او / not stored by us; provider retention per its terms |
| متن درخواست AI DJ (حداکثر ۳۰۰ نویسه) / AI DJ request text (≤300 chars) | تبدیل به ساختار پلی‌لیست / convert to a playlist intent | فقط با پرمیوم + اجازهٔ AI، هنگام زدن «ساخت» / Premium + consent, when you tap create | Worker → ارائه‌دهندهٔ AI | ذخیره نمی‌شود / not stored by us. **نام آهنگ‌ها، فایل‌ها و تاریخچه هرگز ارسال نمی‌شوند / no song names, files or history are sent** |
| SKU، توکن خرید مایکت، payload یک‌بارمصرف / SKU, Myket purchase token, one-time payload | تأیید خرید، فعال‌سازی و بازیابی پرمیوم / verify purchase, unlock & restore Premium | هنگام خرید یا «بازیابی خرید» / on purchase or Restore | Worker → مایکت (Myket) | رکورد حق‌دسترسی (SKU، شناسهٔ خرید، زمان) تا زمانی که پرمیوم معتبر است، برای بازیابی و جلوگیری از تقلب / entitlement record kept while Premium is valid, for restore & fraud prevention |

گزارش‌های Worker فقط شناسهٔ درخواست و نوع رویداد دارند؛ هیچ توکن، متن درخواست، IP یا کلیدی ثبت نمی‌شود. / Worker logs contain only a request id and event name.
گزارش خطا محلی است و فقط با اشتراک‌گذاری خودتان خارج می‌شود (مسیرها و آدرس آهنگ‌ها حذف می‌شوند). / Crash reports are local and leave the device only if you share them (paths/URIs are redacted).

## مجوزها / Permissions
دسترسی به فایل‌های صوتی (ساخت کتابخانه)، اعلان‌ها (کنترل پخش)، اینترنت (فقط قابلیت‌های اختیاری بالا). برای خواندن فایل‌های هم‌نام `.lrc`، برنامه **هیچ دسترسی گسترده‌ای به حافظه نمی‌خواهد**: شما پوشهٔ موسیقی را یک‌بار با انتخابگر پوشهٔ سیستم انتخاب می‌کنید و فقط همان پوشه (فقط فایل‌های `.lrc`) خوانده می‌شود؛ این دسترسی روی دستگاه می‌ماند و هر زمان از تنظیمات سیستم یا برنامه قابل لغو است. انتخاب فایل/پوشهٔ LRC از انتخابگر سیستم انجام می‌شود و فقط همان مورد خوانده می‌شود.
Audio access (library), notifications (playback controls), internet (only the optional features above). The app does **not** request broad storage access: to read same-name `.lrc` files you pick your music folder once with the system folder picker; only `.lrc` files inside that folder are read, on the device, and the grant is revocable at any time. LRC file/folder selection uses the system picker and only that item is read.

## انتخاب‌های شما / Your choices
قابلیت‌های ابری پیش‌فرض خاموش‌اند و از «تنظیمات» قابل لغو هستند. برای حذف رکورد حق‌دسترسی سروری با ایمیل بالا تماس بگیرید. کودکان: برنامه اطلاعات شخصی نمی‌گیرد. / Cloud features are off by default and revocable in Settings. To delete a server-side entitlement record, contact us. Children: the app collects no personal data.

## تغییرات / Changes
تغییرات مهم این سیاست پیش از اعمال در برنامه اعلام می‌شود. / Material changes will be announced in the app before taking effect.
