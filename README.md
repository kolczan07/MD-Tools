# MD-Tools (wersja natywna 3.0)

Kotlin + Jetpack Compose. `applicationId` i klucz podpisu bez zmian, więc nowa wersja
aktualizuje starą bez odinstalowywania.

## Jak podmienić w repozytorium
1. W repo usuń: `app/src/main/java/` (stare pliki Kotlin), `app/src/main/assets/`,
   stary `AndroidManifest.xml`, stare `build.gradle.kts`, `settings.gradle.kts`, `.github/workflows/build.yml`.
2. ZACHOWAJ: `release.jks`. Ikona aplikacji jest w paczce (`res/mipmap-*`); podmień ją na własną, jeśli chcesz.
3. Wgraj zawartość tej paczki na wierzch.
4. Push na `main` uruchamia build (GitHub Actions → Artifacts → MD-Tools-apk).

## Klucz podpisu (GitHub Secrets)
W repo nie ma keystore ani haseł. Dodaj w Settings → Secrets and variables → Actions:
- `KEYSTORE_B64`: `release.jks` zakodowany w base64 (Linux: `base64 -w0 release.jks`,
  PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`)
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Musi to być ten sam keystore co w starej aplikacji, inaczej Android nie potraktuje APK jako aktualizacji.
Workflow zatrzyma się z czytelnym błędem, jeśli któregoś sekretu brakuje.
