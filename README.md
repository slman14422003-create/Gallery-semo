# الاستوديو · Gallery Studio

تطبيق أندرويد للصور والفيديو بنفس الهوية البصرية لتطبيق «مدير الملفات»: واجهة ناعمة وسلسة، شريط سفلي عائم، ثيمات ألوان، وضع ليلي، عربي/إنجليزي (RTL كامل). بدون إعلانات وبدون أي اتصال بالإنترنت سوى فحص التحديثات من GitHub.

## المزايا
- **الصور:** شبكة مجمّعة بالتاريخ، قرصة لتغيير الأعمدة، تحديد متعدد، مفضلة، ألبومات ذكية، بحث.
- **العارض:** تكبير سلس، GIF/WebP متحرك، HEIC/AVIF (حسب نظام الجهاز)، تفاصيل EXIF والترميز.
- **المشغّل المتقدم:** محرّكان — ExoPlayer (سريع، تسريع عتاد) وlibVLC (يشغّل تقريبًا كل الصيغ: MKV, AVI, WMV, FLV, RMVB, TS, MPEG, 3GP, HEVC, VP9, AV1, DTS, AC3, EAC3, FLAC…). يبدّل تلقائيًا عند عدم الدعم، أو يدويًا بالضغط على اسم المحرّك. إيماءات كاملة، سرعات، مسارات صوت/ترجمة (SRT, VTT, ASS/SSA, TTML), قفل، نافذة عائمة (PiP)، استئناف.
- **محرّر الصور (بيتا 3) ومحرّر الفيديو (بيتا):** يحفظان نسخة جديدة دائمًا (`Pictures/Edited` و`Movies/Edited`) ولا يمسّان الأصل. محرّر الصور فيه زر "تحسين AI" محلي بالكامل (موازنة ألوان، تباين، حدّة، تقليل ضجيج) يحلّل الصورة على الجهاز نفسه بدون أي اتصال إنترنت أو نموذج يتحمّل.

## البناء
GitHub Actions يبني تلقائيًا عند الدفع (`.github/workflows/build.yml`) وينشر Release موقّعًا. للتوقيع الدائم شغّل مرة واحدة سير العمل **Generate keystore** وأضف الأسرار كما في `SIGNING.md`. محليًا: `./gradlew assembleRelease` (JDK 17 وAndroid SDK 36).

> حجم الـAPK أكبر (~60–90 MB) بسبب libVLC (arm64-v8a وarmeabi-v7a فقط). إن أردت APK أصغر احذف السطر `libvlc-all` من `app/build.gradle` وملف `VlcEngine.java` وأزل استدعاءه من `PlayerActivity.startEngine`.

## هيكل الكود
`app/src/main/java/com/gallery/app/`
- هوية وواجهة مشتركة: `Ui`, `Appearance`, `Lang`, `Dlg`, `BaseActivity`, `NavBar`, `SpinnerView`, `ThemeActivity`
- المكتبة: `Media`, `MediaRepo`, `Library`, `Album`
- الصور: `MainActivity`, `MediaGrid`, `Tile`, `AlbumsView`, `AlbumActivity`, `ViewerActivity`, `PhotoView`, `InfoSheet`
- المشغّل: `PlayerActivity`, `PlayerEngine`, `ExoEngine`, `VlcEngine`, `AspectFrame`
- المحرّرات: `ImageEditorActivity`, `EditorView`, `EditState`, `Renderer`, `AutoEnhance` (تحسين AI محلي بدون نت), `VideoEditorActivity`, `TrimBar`, `MediaSaver`
- فتح من تطبيقات أخرى: `OpenActivity` · تحديثات: `Updater`, `UpdateActivity`, `Net`
