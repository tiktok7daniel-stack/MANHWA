# رسالة نقل للشات الجديد

**الريبو:** `tiktok7daniel-stack/MANHWA`، والفرع `claude/fix-live-crash-and-batch-loading`. اقرا `CLAUDE.md` و`docs/PENDING_CHANGES.md` الأول.

## اللي اتعمل لحد دلوقتي
- **آخر بيلد ناجح:** 155، ومدموج في main.
  - **فيه:**
    - 100 إطار مرسوم بالكود بدل إطارات الصور (`DrawnFrames.kt`: 10 أشكال في 10 ألوان).
    - 42 خط عربي (`res/font/ar_*`، ومتعرّفين في `TitleFonts.arabic`).
    - 20 تأثير و50 مكس و"تلقائي كل يوم" (`WorkEffects.kt` و`EffectChoice`).
    - الإيقاع الحقيقي للموسيقى (`MusicBeat`).
    - النظام كله للروايات.
    - شيل "اقرا معايا".
  - **اللينك:** https://github.com/tiktok7daniel-stack/MANHWA/releases/download/build-155/MANHWA-build-155-arm64.apk
- **بعد البيلد 155 (commit `6d7a90d0f`، لسه ما اتبناش):**
  - الإطار الافتراضي لأي مانهوا بقى العادي البسيط (`TitlePlate.forWork` بيرجّع NONE)، والمستخدم هو اللي بيحط الإطار الجديد بنفسه.
  - ورا كل غلاف تأثيرين تلقائي، عشوائي ومختلفين لكل مانهوا (`DAILY` هو الافتراضي في `rememberWorkEffects`). المكس بيتغير كل يوم الساعة 6 الصبح (`EffectChoice.CHANGE_HOUR`).
  - نماذج لـ 10 إطارات "طاقة" بلون الغلاف، معمولة في المتصفح بس:
    - الأسماء: system (نافذة النظام زي Solo Leveling)، halo، arcane، storm، inferno، frost، prism، void، lightwings، starcrown.
    - المكان: `tools/frame_proto/frames.html`.
    - التصوير: `node tools/frame_proto/shot.js <path>/frames.html#<group>,<time>,<colorIndex> out.png`، والـ group من 0 لـ 3. Playwright موجود في البيئة.
    - لسه ما اتشافتش ولا اتنقلت للتطبيق.

## المطلوب من المستخدم (لسه)
المستخدم شايف إن الـ 100 إطار مش مبهرين. عاوز إطارات **مبهرة جداً وكتير ومختلفة**، و**كل إطار ياخد لون المانهوا بتاعته** (لون الغلاف).

## الخطوات الجاية
1. صوّر النماذج العشرة وبُص عليها بعينك. حسّنها لحد ما تبقى فخمة بجد: طبقات نور، وحركة أكتر من نوع، وتفاصيل.
2. انقلها لـ Kotlin في ملف جديد (مثلاً `EnergyFrames.kt`):
   - كل الألوان تيجي من غلاف المانهوا (`LocalCoverPalette`: primary وsecondary وdeep).
   - ضيفها في `TitlePlate` بمفاتيح جديدة (مثلاً `E_SYSTEM`)، وقسم لوحده في `TitleLookDialog` اسمه "بلون المانهوا".
   - كتابة الـ JS قريبة من Kotlin: `'lighter'` = `BlendMode.Plus`، و`Path2D` = `Path`.
3. ضيف لون "تلقائي من الغلاف" للأشكال العشرة القديمة كمان: `FramePalette.AUTO` بيتبني من ألوان الغلاف.
4. **خفّف الأداء** (من مراجعة الكود):
   - الـ Paths الثابتة تتبني مرة واحدة.
   - المعاينات في شاشة الاختيار تبقى بوقت ثابت.
   - التأثيرات تبقى أقل لما يتحطوا فوق بعض.
   - النص في `AnimatedPlate` ما يتعادش في كل فريم.
5. اتأكد من الحاجات دي:
   - كل `ATMR.strings` اللي اتضافت موجودة في base وar.
   - مفيش تكرار في المفاتيح.
   - الـ `'` مكتوبة `\'`.
6. سجّل في `docs/PENDING_CHANGES.md`، وحدّث `WhatsNew.latest`، ونقّل القديم تحت `// Build 155`.
7. شغّل الـ kotlin-reviewer، وبعده بيلد (`build_branch.yml` workflow_dispatch على الفرع)، واتأكد منه على GitHub.
8. بعد البيلد: fast-forward لـ main، وسجّل "آخر بيلد نهائي"، وابعت لينك arm64.
9. رد على المستخدم بالمصري، وباختصار.
