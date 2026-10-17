# MANHWA: ملف التسليم الكامل (عشان تكمّل في أي مكان)

> آخر تحديث: 17 أكتوبر 2026.
> الملف ده مكتوب لك ولأي مساعد ذكاء اصطناعي (أو مبرمج) هيكمّل الشغل بعدي. اديله الملف ده كله في أول رسالة.

> **تحديث 17 أكتوبر (بالليل): الملف ده هو التسليم النهائي لشات جديد.** اقرا الأقسام 2 و2.1 و3 الأول، وبعدين 4 (من ط لـ م) و8.
>
> **تحديث 17 أكتوبر (بعد الظهر):** الشغل كمل في شات تاني على فرع `claude/new-session-hidbs8` (البنايات من 64 لـ 80). آخر حاجة في `main` وقت التحديث ده: `2b1b161c4` (البناء 79). البناء 80 (`e94f6cc8b`) كان لسه شغال.
> وفي skills اتضافت للمشروع في `.claude/skills/`: find-skills، وfrontend-design، وskill-creator، وvercel-composition-patterns، وvercel-react-best-practices، وvercel-react-native-skills.

---

## 1. معلومات أساسية

| البند | القيمة |
|---|---|
| المستودع | `github.com/tiktok7daniel-stack/MANHWA` (الاسم القديم `manhwa` بيحوّل عليه) |
| فرع الشغل | `claude/fix-live-crash-and-batch-loading` |
| الفرع الأساسي | `main` |
| آخر بناء اتسلّم للمستخدم قبل البيلد النهائي | **البناء 86**، commit `f26ef7f5a` (اتثبّت على موبايله) |
| البيلد النهائي | **البناء 89**، commit `c50c347e4` (workflow_dispatch). نتيجته في القسم 8 |
| الفرع التاني | `claude/new-session-hidbs8` (شات تاني بيشتغل عليه وبيعمل fast-forward لـ main كمان) |
| أصل التطبيق | Mihon / TachiyomiAT (Kotlin + Jetpack Compose + Voyager + Metro DI) |

**لينك الـ APK:** في القسم 8 (البيلد النهائي).

**تحميل الـ APK:** من Actions تفتح الـ run، وبعدين Artifacts، وبعدين `MANHWA-build-N`، وجوه الـ zip تلاقي `app-arm64-v8a-debug.apk`.

---

## 2. قواعد الشغل (لازم تتطبق دايماً)

1. الرد على المستخدم يكون **بالعربي المصري**، وببساطة.
2. بعد كل بناء ناجح يتبعت **لينك البناء**، ويكون لينك الـ artifact بتاع arm64.
3. **ممنوع حذف أي ميزة موجودة.** كل المميزات القديمة تفضل شغالة.
4. **ممنوع الإيموجي النصي.** الأيقونات تكون Material Symbols بس (`mihon.icons.materialsymbols.*`).
   - لو محتاج أيقونة مش موجودة: حمّل الـ SVG من `google/material-design-icons` (`symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg`)، وحطه في `icons/material-symbols/src/main/valkyrieResources/rounded/`.
5. التصميم بستايل **ManhwaKit**: زجاجي ومتوهج.
   - الأدوات: `glass()` و`ManhwaKit.NeonButton` و`GhostButton` و`TopBar` و`GlowIcon`.
   - الألوان: Ember `#FF6A2B`، وMint `#3DDC97`، وAmber `#FFB020`.
6. **ممنوع استخدام مفاتيح API منشورة أو مسروقة.**
7. **ممنوع إنشاء حسابات وهمية.**
8. **ممنوع تخطي CAPTCHA أو أي أنظمة حماية أو شروط خدمة.**
9. **ممنوع ادعاء** إن البناء نجح أو إن الدمج حصل أو إن الـ APK جاهز **قبل التحقق الفعلي**.
10. لو في خدمة أو صلاحية مش متاحة، **قول العائق بوضوح** وما تختلقش نتيجة.
11. **ممنوع Mock بدل النظام الحقيقي** إلا جوه الاختبارات.
12. **ممنوع حذف بيانات المستخدم.** أي تغيير في التخزين يكون بقيم افتراضية متوافقة مع القديم.
13. **توكن Hugging Face** اللي اتكتب في الشات قبل كده لازم يتلغي ومايتستخدمش أبداً. ما تحطوش في أي ملف.

---

## 2.1 قاعدة البناء (مهمة)
ما يتعملش بناء بعد كل طلب. التعديلات تتسجّل في `docs/PENDING_CHANGES.md`، وتترفع ومعاها `[skip ci]`. البناء
يتعمل بس لما المستخدم يقول "اعمل بيلد نهائي". التفاصيل في الملف ده.

## 3. طريقة البناء والدمج

1. التعديل يتعمل على فرع `claude/fix-live-crash-and-batch-loading`، وبعدين commit وpush.
2. أي push على فرع `claude/**` بيشغّل الـ workflow `.github/workflows/build_branch.yml`:
   - `assembleDebug`
   - رفع الـ APK كـ artifact اسمه `MANHWA-build-N`
   - `:app:testDebugUnitTest`
   - الـ concurrency بيلغي أي بناء قديم شغال على نفس الفرع، **فـ push جديد بيلغي البناء اللي شغال.**
3. لما البناء يخلص **أخضر** (البناء والاختبارات)، الدمج في main بيكون fast-forward:
   ```bash
   git fetch -q origin main && git merge-base --is-ancestor origin/main <sha> && git push origin <sha>:main
   ```
4. لو البناء فشل، دوّر في السجل على أخطاء الكومبايل:
   ```bash
   grep -E '^e: | e: ' build.log
   ```
5. لو البناء علّق أكتر من 40 دقيقة في خطوة Build، الغالب إن الـ runner هو اللي علّق مش الكود: الغيه وأعد تشغيله.

**أخطاء متكررة لازم تاخد بالك منها:**
- `import androidx.compose.ui.unit.dp` ناقص.
- دالة `suspend` اتنادت من دالة عادية.
- اسم اختبار فيه `:` (ده بيكسر الكومبايل).
- ترتيب الـ imports مش مهم للـ CI.

**النصوص (Moko):** بتتضاف في الملفين دول، والوصول ليها بيكون `ATMR.strings.<key>`:
- `i18n-at/src/commonMain/moko-resources/base/strings.xml` (الإنجليزي)
- `i18n-at/src/commonMain/moko-resources/ar/strings.xml` (العربي)

---

## 4. اللي اتعمل (مرتب من الأقدم للأحدث)

### أ) فريق الترجمة والمفاتيح
- **فريق مفاتيح API** بيشتغلوا مع بعض:
  - الملفات: `translation/team/*`، و`AiRouter`، و`TeamTranslator`، و`orchestra/*`.
  - **ModelHealthManager:** بيصنّف الأخطاء (MaxTokens، وModelInvalid، وRateLimited، وAuthFailed، وTemporary)، وبيصلّح الموديل الميت أو حد التوكنز لوحده.
- **الموديلات اللي على الموبايل** (MediaPipe LlmInference) كأعضاء أساسيين في الفريق:
  - `local/PhoneEnsemble.kt`، و`LocalAiEngine.kt`، و`LocalAiTranslator.kt`، و`LocalModelsScreen.kt`.
  - **الأدوار:** موديل أساسي، ومساعدين حسب القوة، وحكّام بيصوّتوا.
  - **التحكم:** مفتاح تشغيل لكل موديل، واختيار العدد (1 أو 2 أو 3 أو الكل).
  - **الذاكرة:** الحد 45% من RAM.
- **منع التعليق:**
  - مهلات لكل طلب، ونبضات حياة، ووقت راحة 20 دقيقة للموديل البطيء (`markSlow`).
  - مراقب (Watchdog) بيعتبر الترجمة واقفة بعد 180 ثانية.
- **OpenRouter:** بيفضّل الموديلات المجانية (`:free`).

### ب) "بيترجم دلوقتي"
- الملف: `translation/queue/TranslatingNow.kt`.
- زرار في شريط صفحة العمل.
- كرة زجاجية في الشاشة الرئيسية جنب الجرس.
- شاشة الطابور: تفتح منها العمل، أو توقف ترجمة فصل، أو توقف الكل مؤقتاً أو تكمّله أو تمسح الطابور.

### ج) "الجديد في التحديث"
- الملف: `translation/whatsnew/WhatsNew.kt`.
- `latest` = آخر تحديث بس، وده اللي بيظهر في النافذة اللي بتطلع.
- `earlier` = التحديثات القديمة، وبتظهر في الإعدادات.
- **عند كل تحديث:** حط المميزات الجديدة في `latest`، وانقل القديمة لأول `earlier`.

### د) مراجعة شاملة وإصلاحات
- **كتابة ذرية للملفات** (`SafeFiles.kt`: `writeTextAtomic` و`readTextAtomic`).
- **أقفال** على المخازن المشتركة: TeamStore، وSeriesBrain، وLessonStore، وغيرهم.
- **مكتبة الترجمة:**
  - التصدير بـ `VACUUM INTO`.
  - الاستيراد جوه transaction.
- **ChapterTranslator:**
  - طابور من غير تكرار.
  - cache بحدود (24MB).
  - ترتيب طبيعي للصفحات.
- **إصلاحات في القارئ اللايف وفي بداية تشغيل التطبيق.**
- **شغل تقيل اتنقل من الـ main thread:** BrainButton، وStudySheet، وCharacterGallery.

### هـ) OCR مزدوج (PaddleOCR + ML Kit)
- **الملفات:** `translation/ocr/*`:
  - `PaddleOcrCore`: DBNet + CTC.
  - `BubbleFinder`.
  - `OcrFusion`.
  - `OcrModels`: تحميل الموديلات، ومعاها روابط بديلة وفحص sha.
  - `PaddleOcrEngine`.
  - `OcrModelsCard`.
- **تشغيل الموديلات:** ONNX Runtime Android 1.22.0.
- **الإعداد:** `translationOcrEngine`:
  - `DUAL`: الافتراضي.
  - `PADDLE`.
  - `MLKIT`.
- **السرعة:** في الوضع المزدوج، PaddleOCR بيقرا بس الأماكن اللي ML Kit ما كانش متأكد منها.
- **تحميل الموديلات:** بيحصل لوحده على الواي فاي بس.
- **روابط الموديل الكوري:** ما اتأكدتش منها.

### و) التحميل على أي شبكة (البناء 58 والبناء 61)
- **الإعدادات الافتراضية:** `downloadOnlyOverWifi` و`loadAheadOnlyOverWifi` بقوا `false`.
  - التحويل ده بيحصل مرة واحدة بس في `App.kt` (المفتاح `manhwa_any_network_downloads`).
- **`SmartDns`** (في `core/common/.../network/SmartDns.kt`):
  - بيستخدم DNS الشبكة الأول.
  - لو الشبكة خبّت الموقع (مفيش رد، أو رد بـ 0.0.0.0 أو 127.x)، بيسأل DoH من Cloudflare، ولو ما نفعش Google.
  - بيعيد الطلب مرة واحدة لو كان GET/HEAD والموقع ما اتوصلّوش.
  - لما الشبكة تتغير، بيمسح الاتصالات القديمة (`evictAll`).
  - القواعد اللي من غير Android في `NetworkRules`، ومتختبرة.
- **ما فيش تخطي لأي حجب:** لو الموقع محجوب بالـ IP نفسه، الحل VPN.
- **`DownloadWorker`:** لو النت وقع، بيرجع يكمّل لوحده أول ما النت يرجع (`DownloadResumeWorker`).
- **`Downloader`:**
  - الفصل اللي يفشل بيتعاد لحد 3 مرات (بعد 5 ثواني، وبعدين 15، وبعدين 30)، وبيستنى النت يرجع الأول.
  - الصفحات اللي اتحملت ما بتتحملش تاني.
  - الصورة الواحدة بتتحاول لحد 5 مرات.
  - `callTimeout` بقى 5 دقايق.

### ز) إلغاء تحميلات العمل
- في القائمة اللي بتطلع من زرار التحميل في شريط صفحة العمل اتضاف `DownloadAction.CANCEL_QUEUED` باسم "إلغاء تحميلات العمل ده".
- بيظهر بس لو في فصول مستنية تتحمل.
- بيلغي كمان الترجمة اللي كانت هتبدأ بعد التحميل (`translateAfterDownload`).

### ح) الاندماج الذكي للفصول (البناء 61، والبناء 63 للاختيار اليدوي)
**الفكرة:** عمل عربي واقف عند فصل 25، وعمل إنجليزي وصل لفصل 100. الاتنين بيبقوا قائمة واحدة.

**الملفات:**
- `translation/unified/ChapterFusion.kt`: المنطق والتخزين، ومحفوظ في `filesDir/chapter_fusions.json`.
- `translation/unified/FusionUi.kt`: البانر، والشريط، وشاشة الاختيار، وصفوف التكملة.
- `FusionRules`: قواعد من غير Android، ومتختبرة في `app/src/test/.../FusionTest.kt`.

**التدفق:**
1. لما تفتح عمل في المكتبة أو من مصدر عربي، التطبيق بيدوّر في المصادر اللي بلغة تانية:
   - لحد 8 مصادر، والمصادر اللي في الرئيسية أو المثبّتة الأول، والإنجليزي الأول.
   - البحث بالعنوان، وبالأسماء الإنجليزية اللي في الوصف (`titlesOf`).
   - المطابقة صارمة عن طريق `WorkIdentity.same`.
2. لو لقى فصول أكتر، بيطلع **بانر** فيه "الترجمة متوقفة عند X، والتكملة لحد Y في مصدر Z".
3. **الاختيار (البناء 63):**
   - "نعم" بتفتح **شاشة اختيار** (`FusionPicker`)، ونفس الشاشة بتفتح من "دوّر على تكملة" ومن "غيّر المصدر".
   - في الشاشة لستة نتايج (`searchCandidates`، لحد 3 نتايج من كل مصدر)، وكل نتيجة فيها الغلاف، والاسم، والمصدر واللغة، وآخر فصل.
   - على كل نتيجة شارة "نفس الاسم" أو "اسم قريب، اتأكد منه".
   - في خانة بحث لاسم تاني.
   - **الدمج ما بيحصلش إلا لما المستخدم يختار.**
4. **بعد الدمج:**
   - فوق الفصول: شريط "وضع الاندماج الذكي" فيه "غيّر المصدر" و"إلغاء الدمج".
   - **فصول التكملة:** هي الفصول اللي رقمها أكبر من آخر فصل عربي (`fusionContinuationItems`)، وعليها شارة "تكملة EN" وزرار تحميل (تقدر تلغيه) وزرار ترجمة.
   - **الفصول العربية:** عليها شارة "عربي جاهز" (باراميتر `badge` في `MangaChapterListItem`).
   - لما يطلع فصل عربي جديد، بياخد مكان النسخة الإنجليزية لوحده.
5. **القارئ:**
   - `ReaderViewModel.loadChapterList` بيخبّي الفصول الإنجليزية المكررة اللي ليها نسخة عربية.
   - `ReaderActivity.onChaptersEdge` بيتنادى من `WebtoonViewer` و`PagerViewer`:
     - في آخر فصل عربي بيفتح أول فصل إنجليزي.
     - في أول فصل إنجليزي ولو رجعت لورا، بيفتح آخر فصل عربي.
   - التنقل ده بيقفل القارئ ويفتحه تاني.
   - التكملة الإنجليزية بتشغّل الترجمة اللايف لوحدها.
6. **`LiveTranslator`:** الفصل اللي لغته هي نفسها لغة الترجمة (العربي) ما بيتترجمش، إلا بزرار "ترجم الآن".

**الحدود:**
- التحديث التلقائي للمكتبة ما بيجيبش فصول التكملة الجديدة. الجديد بيظهر لما تفتح صفحة العمل بس.
- الفصول الناقصة في نص القائمة العربية ما بتتكمّلش من الإنجليزي. التكملة بتبدأ بعد آخر فصل عربي بس.

---

### ط) الترجمة في الخلفية حتى والتطبيق مقفول
- **`TranslationJob`** (WorkManager، foreground من نوع dataSync):
  - لو اتقفل بيبدأ تاني بمهلة بتزيد (`RESTART_BASE_S = 10`، و`MAX_BACKOFF_STEPS = 6`).
  - `keepResuming`: شغل دوري كل 15 دقيقة (`TranslationResumeJob`) بيرجّع الطابور لو Android قفله.
- **`ChapterCheckpoint`** (`translation/ChapterCheckpoint.kt`):
  - تقدم كل فصل محفوظ على الديسك في `filesDir/translation_checkpoints`: الصفحات اللي اتقرت (`.read.json`)، والصفحات اللي اتترجمت (`.done.json`).
  - الفصل بيكمّل من مكانه بدل ما يبدأ من الأول.
  - بيتمسح لما الفصل يتحفظ أو يتشال من الطابور.
  - `prune` بيمسح أي حاجة عدّى عليها 7 أيام.
  - `CheckpointRules.applyDone` متختبر.
- **`TranslationManager.restorePending()`** بيتنادى في `App.kt` مع كل تشغيل للتطبيق.
- **نصايح لموبايل المستخدم** (RedMagic 10S Pro، Android 16، RedMagicOS 11): Auto-start، وSecondary launch، وBackground running كلهم Allowed، والبطارية Unrestricted.

### ي) مشكلة "App not installed" (اتحلّت)
- الـ APK كان سليم: خطوة "Check the APKs can be installed" في الـ CI بتفحص apksigner، وzipalign -P 16، وaapt2، والمكتبات الأصلية.
- المساحة كانت كفاية.
- **السبب الحقيقي:** الموبايل ما كانش بيعرض موافقة "مصادر غير معروفة" وPlay Protect. المستخدم صلّحها وبقى بيثبّت عادي.
- **التوقيع ثابت** (`signing/manhwa-dev.jks` عن طريق `keystore.properties`)، فالتحديث فوق النسخة القديمة شغال.
  - versionCode 38، وapplicationId `app.danghwa` (والـ debug بيزود `.dev`).

### ك) الدمج أسرع + زرار البحث (التعديلين 1 و2 في PENDING_CHANGES)
- `ChapterFusion.findOffers` بيستخدم نتيجة `UnifiedSources.discover` (كارت "Also on") ويجيب الفصول بس.
- `UnifiedSources.discover` عليه Mutex لكل عمل، فالبحث بيحصل مرة واحدة.
- 6 مصادر في نفس الوقت، ومهلة البحث 12 ثانية، ومهلة الفصول 15 ثانية.
- النتايج بتظهر أول بأول (`onFound`).
- آخر عروض محفوظة في `fusion_offers.json`.
- **`FusionRules.sameName`:** بيعرف الأسماء لو الكلمات بترتيب مختلف، بس بيرفض لو رقم الجزء/الموسم مختلف.
- **`FusionUi.ManualSearch`:** زرار بحث (Search IconButton)، والصف كله بيتداس، وسطر "بدوّر على: …".

### ل) مساعدين السرعة على الموبايل + تنضيف الموديلات (التعديل 3 في PENDING_CHANGES)
- **`ModelCatalog.models`** بقت 3 بس: Gemma 3n E4B، وGemma 3n E2B، وGemma 3 1B (المسودة).
  - **`retired`:** Phi-4 mini وQwen 2.5 3B وQwen 2.5 1.5B. بيظهروا في الشاشة بس لو فيه نسخة متحملة، ومعاهم زرار مسح ومن غير زرار تحميل.
  - **`known`:** = models + retired. ده اللي بيستخدمه PhoneEnsemble والشاشة، عشان الإعداد القديم ما يبوظش.
- **ML Kit بيعمل المسودة الأول بقى الافتراضي:**
  - `translationLocalTuning` الافتراضي بقى `"AUTO"`، و`Tuning.named` بيرجّع EDIT لأي قيمة غير `"FAST"`.
  - موديل ML Kit بيتحمّل على أي شبكة (اتشال `requireWifi`).
- **NPU** (`NpuSupport`، و`NpuRules`، و`LocalAiEngine.create`):
  - بيدوّر على `LlmInference.Backend.valueOf("NPU")` بالاسم عن طريق reflection، فالكود بيتبني في الحالتين.
  - بيشتغل بس على Qualcomm.
  - **أول مرة:**
    1. يسجّل "failed" بـ `commit()` قبل التجربة، فلو الموبايل وقع ما يجربش تاني.
    2. يطلب إجابة صغيرة يتأكد بيها.
    3. لو نجح يسجّل "works" في `manhwa_models` (`npu:<file>`).
  - **مش متأكد** إن MediaPipe 0.10.27 فيه NPU، لأن dl.google.com مقفول من بيئة التطوير.
- **قواعد البرومبت بتتقري مرة** (`generate(..., prefix = head)`):
  - كل Slot بيحتفظ بجلسة قرت الـ head.
  - كل طلب بيعمل `cloneSession` (reflection) ويضيف الباقي.
  - لو فيه أي خطأ، بيرجع للبرومبت كله ويعمل `dropPrefix`.
  - الـ head = أول سطر + قاعدة العربي + تعليمات المستخدم. القاموس والشخصيات والسياق بقوا بعد الـ head.
- **كارت "السرعة على موبايلك"** في `LocalModelsScreen`: الـ backend الحالي، ونصايح وضع الأداء.
- **الاختبارات:** `PhoneSpeedTest` (جديد)، و`PhoneCascadeTest` (اتحدّث).

### م) Skills في `.claude/skills/`
- الموجودة من قبل: find-skills، وfrontend-design، وskill-creator، وvercel-*.
- ضفت: ponytail، وponytail-review، وponytail-audit، وchangelog-generator، وdiagram-design (والمصادر في `SOURCES.md`).
- **ما اتضافش Scrapling** ولا أي حاجة بتتخطى الحماية أو الـ CAPTCHA، لأن ده ممنوع.
- skills.sh مقفول من البروكسي.

## 5. ملفات مهمة (خريطة سريعة)

**ملحوظة:** المسارات اللي في الجدول تحت `app/src/main/java/eu/kanade/`، ما عدا الصفوف اللي فيها مسار كامل.

| الغرض | الملف |
|---|---|
| تفضيلات الترجمة | `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt` |
| حقن الاعتماديات | `app/src/main/java/mihon/app/di/AppGraph.kt` (للوصول: `context.appGraph.<x>`) |
| صفحة العمل | `presentation/manga/MangaScreen.kt`، و`tachiyomi/ui/manga/MangaViewModel.kt` |
| القارئ | `tachiyomi/ui/reader/ReaderActivity.kt`، و`ReaderViewModel.kt`، و`viewer/*` |
| الترجمة اللايف | `translation/live/LiveTranslator.kt`، و`PageOcr.kt` |
| التحميل | `tachiyomi/data/download/Downloader.kt`، و`DownloadWorker.kt` |
| الشبكة | `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`، و`SmartDns.kt` |
| المصادر الموحدة | `translation/unified/UnifiedSources.kt` ("موجود كمان في...") |
| الاختبارات | `app/src/test/java/eu/kanade/manhwa/*Test.kt` (JUnit5 + kotest) |
| موديلات الموبايل | `translation/local/ModelCatalog.kt`، و`LocalAiEngine.kt`، و`LocalAiTranslator.kt`، و`PhoneEnsemble.kt`، و`LocalModelsScreen.kt` |
| الترجمة في الخلفية | `translation/TranslationJob.kt`، و`TranslationManager.kt`، و`ChapterTranslator.kt`، و`ChapterCheckpoint.kt` |
| الدمج | `translation/unified/ChapterFusion.kt`، و`FusionUi.kt`، و`UnifiedSources.kt` |
| قائمة البناء الجاي | `docs/PENDING_CHANGES.md` |
| الـ CI | `.github/workflows/build_branch.yml` |

---

## 6. اللي لسه ممكن يتعمل (أفكار مقترحة)

- [x] البناء 63 نجح واتدمج في main.
- [ ] تجربة الاندماج على تلفون حقيقي بعمل عربي متوقف.
- [ ] تحديث فصول التكملة أثناء تحديث المكتبة التلقائي.
- [ ] ترجمة مسبقة لعدد معيّن من فصول التكملة مرة واحدة (زي زرار "ترجم الفصول").
- [ ] التأكد من روابط موديل PaddleOCR الكوري.
- [ ] تجربة NPU وcloneSession على الموبايل، وبص في الـ logcat على: "can't run … on this NPU" و"The kept rules could not be reused".
- [ ] لو ML Kit بيخلّي الجودة أقل: اختبار السرعة في شاشة الموديلات يقدر يرجّعها لـ "FAST".
- [ ] **أكبر نقطة ضعف حالياً:** مفاتيح الـ API قليلة. كل ما زادت المفاتيح الرسمية المجانية، كل ما الترجمة بقت أسرع.

---

## 7. ملاحظات صراحة

- **مفيش أي ميزة اتجربت على تلفون حقيقي.** التحقق كان بالبناء على GitHub واختبارات الوحدات. وموديلات PaddleOCR اتجربت لوحدها على الكمبيوتر.
- **من بيئة التطوير:**
  - Hugging Face وModelScope ما كانوش متاحين.
  - GitHub وMaven كانوا متاحين.
  - تحميل الـ artifacts مباشرة كان ممنوع.
  - سجلات البناء كانت بتتقري من GitHub API.
