# Rally Overlay

## إصدار نسخة جديدة

تتطلب عملية النشر توقيع الإصدار بمفتاح ثابت. أضف الأسرار الأربعة الموضحة أدناه في GitHub قبل إصدار أول نسخة.

يمكنك إصدار نسخة جديدة بإحدى الطريقتين:

- من الطرفية: `git tag v3.0.1 && git push --tags` (استبدل رقم الإصدار).
- من GitHub: Actions → Rally Overlay Release → Run workflow، ثم أدخل رقم الإصدار بصيغة `x.y.z`.

مع كل إصدار ناجح، يزيد workflow قيمة `versionCode` تلقائياً ويحدّث `versionName` في `android-rally/version.properties`. ينشئ الإصدار ملفي `rally-overlay.apk` و`version.json` داخل GitHub Release. ملاحظات الإصدار تُجمع من آخر 10 commits.

## أسرار توقيع Android

من المستودع افتح Settings → Secrets and variables → Actions → New repository secret، وأضف:

- `ANDROID_KEYSTORE_BASE64`: محتوى ملف keystore بعد ترميزه Base64.
- `ANDROID_KEYSTORE_PASSWORD`: كلمة مرور المخزن.
- `ANDROID_KEY_ALIAS`: اسم المفتاح (alias).
- `ANDROID_KEY_PASSWORD`: كلمة مرور المفتاح.

لا ترفع ملف keystore إلى Git أو إلى أي artifact، ولا تضع كلمات المرور في الملفات أو سجلات البناء. البناء المحلي يستخدم توقيع debug تلقائياً إذا لم تكن متغيرات توقيع الإصدار موجودة.

## التحديث داخل التطبيق

تتحقق الشاشة الرئيسية من GitHub Releases عند فتحها، وبحد أقصى مرة كل 6 ساعات، مع زر للتحقق اليدوي. عند توفر إصدار أحدث، يعرض التطبيق ملاحظات الإصدار والتنزيل والتقدم. قبل فتح مُثبّت Android يتحقق من صلاحية APK وحجمه واسم الحزمة وتطابق توقيع التطبيق المثبت. يتطلب Android موافقة المستخدم على التثبيت من هذا المصدر.
