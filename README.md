# MD-INSTAL Narzędzia (wersja natywna 3.0)

Kotlin + Jetpack Compose. `applicationId` i klucz podpisu bez zmian, więc nowa wersja
aktualizuje starą bez odinstalowywania.

## Jak podmienić w repozytorium
1. W repo usuń: `app/src/main/java/` (stare pliki Kotlin), `app/src/main/assets/`,
   stary `AndroidManifest.xml`, stare `build.gradle.kts`, `settings.gradle.kts`, `.github/workflows/build.yml`.
2. ZACHOWAJ: `release.jks` oraz `app/src/main/res/mipmap-*` (ikony aplikacji).
3. Wgraj zawartość tej paczki na wierzch.
4. Push na `main` uruchamia build (GitHub Actions → Artifacts → MD-INSTAL-apk).

## Klucz podpisu
Aktualizacje w aplikacji: Ustawienia → Sprawdź aktualizacje (oraz automatycznie przy starcie, co 6 h).
Źródło: GitHub Releases repo kolczan07/MD-INSTAL (musi być publiczne). Workflow tworzy wydanie `v<versionName>`
z plikiem APK. Aby wypuścić update: podbij `versionName` i `versionCode` w app/build.gradle.kts i zrób push.

Hasła czytane są z sekretów `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(opcjonalnie `KEYSTORE_B64` = `base64 -w0 release.jks`). Gdy sekretów brak, użyte są dotychczasowe
wartości z `app/build.gradle.kts`. Po ustawieniu sekretów usuń wartości zapasowe z tego pliku.
