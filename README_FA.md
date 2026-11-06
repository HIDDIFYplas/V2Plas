# V2 Plas — دریافت کم‌مصرف کانفیگ رایگان

این بسته برای **مخزن آزمایشی `V2-Plas-test`** آماده شده است. سورس کامل v2rayNG داخل ZIP نیست؛ Workflow نسخهٔ upstream را از revision ثابت دریافت و سپس overlay را اعمال می‌کند. مخزن اصلی `V2Plas` نباید تا پایان تست تغییر کند.

## معماری نهایی دریافت داده

1. مدیر کانفیگ را در پنل ثبت/ویرایش/حذف می‌کند. Worker اطلاعات D1 را می‌خواند و یک Snapshot استاتیک شامل `public/manifest.json` و `public/configs.json` تولید می‌کند.
2. Worker فقط وقتی محتوای واقعی Snapshot تغییر کرده باشد، یک commit در GitHub می‌سازد. اگر داده تغییر نکرده باشد، commit جدید و در نتیجه Pages deployment جدید ساخته نمی‌شود.
3. Cloudflare Pages پوشهٔ `public/` همان مخزن/شاخه را به‌عنوان فایل استاتیک منتشر می‌کند. اپ اندروید فقط به دامنهٔ `pages.dev` یا دامنهٔ استاتیک متصل به Pages درخواست می‌فرستد؛ اپ هیچ درخواست عمومی به Worker، D1 یا پنل ارسال نمی‌کند.
4. اپ در زمان بازشدن و با دکمهٔ بروزرسانی، حداکثر هر ۱۵ دقیقه (پس از خطا هر ۵ دقیقه، و در بروزرسانی دستی حداقل هر ۳۰ ثانیه) فقط `manifest.json` را بررسی می‌کند. درخواست دارای `If-None-Match` است. اگر پاسخ 304 باشد یا `version` با نسخهٔ ذخیره‌شده برابر باشد، `configs.json` اصلاً دانلود نمی‌شود.
5. فقط وقتی نسخهٔ manifest عوض شده باشد، اپ `configs.json` را یک‌بار دریافت می‌کند. اندازه، SHA-256، قالب داده و تطابق نسخه پیش از جایگزینی cache بررسی می‌شوند. فایل ناقص/خراب cache قبلی را حذف یا جایگزین نمی‌کند. سقف حجم payload در Worker و اندروید یکسان و برابر ۲ مگابایت است.
6. دکمهٔ «بارگذاری مجدد فهرست» فقط cache محلی MMKV را می‌خواند. انتخاب کانفیگ و تلاش اتصال نیز فقط از cache استفاده می‌کنند و درخواست شبکه ندارند.
7. اعلان برنامه نیز از همان دامنهٔ استاتیک Pages خوانده می‌شود، در گوشی cache می‌شود و حداکثر هر ۱۲ ساعت یک‌بار بررسی می‌شود؛ URL دارای query تصادفی برای دور زدن cache نیست و ETag پشتیبانی می‌شود.

### نکتهٔ مهم دربارهٔ «درخواست صفر»

بدون Push/FCM یا یک سازوکار اعلان سمت سیستم، برنامه نمی‌تواند از تغییر پنل باخبر شود مگر اینکه گهگاه یک manifest کوچک را بررسی کند. بنابراین تضمین فنی درست این است: **حداکثر یک بررسی manifest در هر ۱۵ دقیقه (۵ دقیقه پس از خطا)؛ بدون دانلود فایل کانفیگ در صورت ثابت‌ماندن نسخه؛ و بدون درخواست در اتصال.** درخواست‌های فایل استاتیک Pages رایگان و نامحدودند و Worker اصلاً صدا زده نمی‌شود. این روش درخواست‌ها را به حداقل می‌رساند، اما نمی‌توان تعداد درخواست‌های manifest را مطلقاً صفر کرد و هم‌زمان تغییرات پنل را خودکار تشخیص داد.

## تنظیمات ضروری Cloudflare Pages و GitHub

### ۱) ساخت Pages برای فایل‌های استاتیک

در Cloudflare داشبورد، یک Pages project متصل به همان GitHub repository و branch بساز که Worker فایل‌های Snapshot را در آن منتشر می‌کند (`PUBLIC_FEED_GITHUB_REPO` و `PUBLIC_FEED_GITHUB_BRANCH`). تنظیمات پروژه:

- Framework preset: `None`
- Build command: خالی / بدون دستور build
- Build output directory: `public`
- دامنهٔ عمومی پروژه معمولاً به شکل `https://<project-name>.pages.dev` است.
- در `Settings → Builds → Build watch paths`، بخش Include را روی `public/*` بگذار و Exclude را خالی نگه دار. در نتیجه تغییر فایل‌های Android/README باعث Build بی‌دلیل Pages نمی‌شود؛ فقط تغییر فایل‌های `public/` انتشار جدید را فعال می‌کند.

پوشهٔ `public/` این بسته شامل Snapshot اولیه و فایل `_headers` است. Worker فایل‌های `manifest.json` و `configs.json` را پس از تغییر داده جایگزین می‌کند. فایل `announcement.json` نیز از همین Pages سرو می‌شود؛ برای تغییر اعلان، نسخهٔ `public/announcement.json` را در مخزن مقصد ویرایش کن. فایل `_headers` باید در همان پوشه باقی بماند.

در حالت Pages Static Assets و بدون اجرای Pages Functions، درخواست‌های فایل‌های استاتیک در پلن‌های Cloudflare Pages رایگان و نامحدود هستند؛ درخواست‌هایی که وارد Functions شوند مشمول سهمیهٔ Workers خواهند بود. پس مطمئن شو مسیرهای `manifest.json`, `configs.json` و `announcement.json` هیچ Function/Worker را اجرا نمی‌کنند. <https://developers.cloudflare.com/pages/functions/pricing/> <https://developers.cloudflare.com/pages/configuration/build-watch-paths/>

### ۲) تعریف URL در مخزن آزمایشی

در GitHub مخزن `V2-Plas-test` به مسیر `Settings → Secrets and variables → Actions → Variables` برو و یک Repository variable با نام زیر بساز:

`V2PLAS_FEED_BASE_URL`

مقدار آن فقط دامنهٔ پایهٔ Pages باشد؛ نمونهٔ ساختار:

`https://<project-name>.pages.dev`

جای `<project-name>` دامنهٔ واقعی Pages خودت را قرار بده؛ مقدار نمونه را عیناً وارد نکن. این URL محرمانه نیست و باید به‌صورت Variable ذخیره شود، نه Secret. اگر مقدار خالی بماند، Workflow هشدار می‌دهد و برنامه عمداً هیچ درخواست feed نمی‌فرستد و فقط cache محلی را نمایش می‌دهد.

### ۳) متغیرها و Secretهای Worker

در Worker که پنل D1 را مدیریت می‌کند، این موارد باید تنظیم باشند:

- `PUBLIC_FEED_GITHUB_REPO`: مخزن مقصد انتشار Snapshot، با قالب `owner/repository`
- `PUBLIC_FEED_GITHUB_BRANCH`: شاخهٔ مقصد، معمولاً `main`
- `PUBLIC_FEED_GITHUB_TOKEN`: Fine-grained GitHub token با حداقل دسترسی `Contents: Read and write` فقط روی همان مخزن مقصد
- `DB`: binding دیتابیس D1 موجود

Token را داخل سورس یا فایل ZIP قرار نده. فقط در بخش Secrets/Variables محیط Worker ذخیره شود. Worker با D1 lease انتشارهای هم‌زمان را serial می‌کند تا کلیک‌های تکراری/هم‌زمان باعث commit و deployment تکراری نشوند.

### ۴) Cron Worker

زمان‌بندی Worker را روی اجرای هر ۶ ساعت تنظیم کن:

`0 */6 * * *`

Worker منابع اشتراک را ترتیبی بررسی می‌کند و Snapshot را در پایان چرخه منتشر می‌کند. مقدار refresh کمتر از ۳۶۰ دقیقه در تنظیمات عمومی Feed اعمال نمی‌شود.

## مسیرهای فعلی پنل و اپ

- پنل مدیریت و D1 در Worker باقی می‌مانند.
- endpoint قدیمی `/api/public/configs` برای سازگاری/عیب‌یابی باقی است، اما نسخهٔ جدید اپ اندروید از آن استفاده نمی‌کند.
- درخواست‌های static Pages سهمیهٔ Worker را مصرف نمی‌کنند؛ استفاده از Pages Functions یا route کردن این فایل‌ها به Worker، این مزیت را از بین می‌برد. مستندات Cloudflare همچنین می‌گوید Pages به‌صورت پیش‌فرض برای تغییر هر فایل در مخزن build می‌شود، بنابراین Build watch paths روی `public/*` تنظیم می‌شود تا Buildهای نامرتبط حذف شوند. <https://developers.cloudflare.com/pages/functions/pricing/> <https://developers.cloudflare.com/pages/configuration/build-watch-paths/>
- هیچ شناسهٔ دستگاه، IP، حجم مصرف یا آمار استفاده از اپ به Worker ارسال نمی‌شود.
- سهمیهٔ محلی رایگان فقط برای پروفایل رایگان اعمال می‌شود؛ کانفیگ‌های شخصی همچنان بدون تبلیغ و سهمیهٔ رایگان باقی می‌مانند.

## فایل‌هایی که باید در مخزن آزمایشی بارگذاری شوند

فایل‌ها را از ZIP استخراج کن و ساختار پوشه‌ها را حفظ کن. ZIP را به‌صورت یک فایل واحد در GitHub بارگذاری نکن.

- `overlay/V2PlasApp.kt`
- `overlay/FreeConfigRepository.kt`
- `overlay/FeedEndpoint.kt`
- `overlay/RemoteAnnouncement.kt`
- `overlay/FreeQuotaServiceMonitor.kt`
- `overlay/patch.py`
- `overlay/AboutActivity.kt`
- `tests/validate_ui_patch.py`
- `.github/workflows/build-v2plas.yml`
- `README_FA.md`
- `announcement.json`
- `worker/V2Plas-worker-pages.js` — نسخهٔ به‌روزشدهٔ Worker برای Deploy در Cloudflare
- `public/manifest.json`
- `public/configs.json`
- `public/announcement.json`
- `public/_headers`
- `UPLOAD_PATHS.txt`

پس از Deploy نسخهٔ Worker و ساخت Pages، مقدار `V2PLAS_FEED_BASE_URL` را تنظیم کن و Workflow را اجرا کن. تست ایستا باید پیش از Build همهٔ checkها را PASS کند.

## تست ایستا

در ریشهٔ مخزن:

```bash
python3 tests/validate_ui_patch.py
```

این تست ۷۷ قرارداد ایستا را بررسی می‌کند. جایگزین کامپایل واقعی Kotlin/Gradle، انتشار Cloudflare Pages، Deploy Worker یا آزمون اتصال VPN روی گوشی نیست.

## محدودیت‌ها و کنترل کیفیت

- هیچ معماری نمی‌تواند بدون Push، کشف تغییرات سرور را با صفر درخواست تضمین کند. این پیاده‌سازی فقط manifest کوچک را هر ۱۵ دقیقه بررسی می‌کند و محتوای کانفیگ را صرفاً در صورت تغییر نسخه می‌گیرد.
- سهمیه و تعداد درخواست‌های سرویس‌ها وابسته به پلن و شرایط فعلی ارائه‌دهندگان است؛ این طراحی مصرف را پایین می‌آورد، اما تضمین «بدون هیچ محدودیت» نمی‌دهد.
- محدودیت حجم/زمان اپ سمت دستگاه است و جایگزین کنترل سمت ارائه‌دهندهٔ کانفیگ نیست. آمار `TrafficStats` نیز باید روی گوشی واقعی با ترافیک تونل سنجیده شود.
- پس از Build، ابتدا APK را روی گوشی آزمایشی نصب کن و این موارد را بررسی کن: دریافت اولیه، بازکردن چندبارهٔ برنامه در کمتر از ۱۵ دقیقه، زدن Refresh چندباره، اتصال/قطع/اتصال مجدد رایگان، تغییر کانفیگ در پنل، و حفظ cache هنگام قطع اینترنت.
- تا وقتی تست‌های گوشی و امضای Release تأیید نشده‌اند، فایل را به کاربران نده و مخزن اصلی را تغییر نده.
