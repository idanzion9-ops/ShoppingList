# 🛒 רשימת קניות

אפליקציית רשימת קניות בעברית עם הזנה קולית, ווידג'ט למסך הבית, זיכרון רשימות קודמות והמלצות.

## איך זה בנוי
- `docs/` – האפליקציה עצמה (HTML/JS). מתפרסמת ב-GitHub Pages:
  https://idanzion9-ops.github.io/ShoppingList/
- `android/` – מעטפת אנדרואיד קטנה שטוענת את האפליקציה + ווידג'ט קולי.
  GitHub Actions בונה ממנה APK ומפרסם אותו ב-Releases.

## עדכונים
- **שינוי באפליקציה (`docs/`)**: מעלים לגיטהאב → האפליקציה מתעדכנת לבד בפתיחה הבאה,
  או בלחיצה על "בדוק עדכון" בהגדרות. בכל עדכון מעלים את `APP_VERSION` ב-`docs/app.js`
  ואת `version` ב-`docs/version.json` (אותו מספר).
- **שינוי במעטפת (`android/`)** – נדיר: מעלים `versionCode` ב-`android/app/build.gradle`
  ואת `nativeVersion` ב-`docs/version.json`. באפליקציה יופיע כפתור להורדת העדכון,
  וההתקנה נעשית מעל הקיימת בלי למחוק ובלי לאבד נתונים.

## הורדת האפליקציה
https://github.com/idanzion9-ops/ShoppingList/releases/download/apk/ShoppingList.apk
