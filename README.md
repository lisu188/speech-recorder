# Speech Recorder

Lokalny dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Po uruchomieniu działa jako foreground service i nie wymaga połączenia z siecią.

- wersja 1.3.0
- Kotlin 2.4.10
- Android Gradle Plugin 9.3.2 z wbudowanym Kotlinem
- compileSdk / targetSdk 37 (Android 17)
- minSdk 29 (Android 10)
- Gradle 9.5.0 w CI
- JDK 17
- 16 kHz mono PCM WAV
- 5 s pre-buffer
- 8 s ciszy kończy klip
- dynamiczny próg szumu + energia + zero-crossing rate
- brak uprawnienia INTERNET i usług chmurowych
- nagrania w `Music/SpeechRecorder`
- foreground service z `START_STICKY`
- `stopWithTask=false`
- automatyczna ponowna inicjalizacja `AudioRecord` po błędzie
- własna ikona mikrofonu
- ekran główny z jednym przyciskiem start/stop, stanem i miernikiem wejścia
- informacja o ostatnio wykrytej mowie
- dolna nawigacja: Dyktafon / Nagrania / Ustawienia
- przeglądarka nagrań z wyszukiwaniem i sortowaniem
- odtwarzanie, mini-waveformy, udostępnianie i usuwanie nagrań
- osobny ekran ustawień systemowych i optymalizacji baterii

Android może nadal zatrzymać aplikację po Force stop, odebraniu uprawnienia mikrofonu albo w wyniku ograniczeń systemowych. Android 14+ nie pozwala uruchomić mikrofonowego foreground service bezpośrednio z `BOOT_COMPLETED`, dlatego po restarcie wymagane jest świadome tapnięcie powiadomienia przez użytkownika.

## Release signing

CI zawsze buduje `SpeechRecorder-Release-Unsigned`. Na pushu do `main` może dodatkowo utworzyć i zweryfikować `SpeechRecorder-Release-Signed`, jeśli w GitHub Actions są skonfigurowane cztery sekrety:

- `RELEASE_KEYSTORE_BASE64` — keystore PKCS12 zakodowany Base64 bez znaków nowej linii
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Oczekiwany certyfikat release ma SHA-256:

`c311a44e405ccfab2b822d5295c45e4dbbc6972516c3695dadb146b6149ec2b6`

Pipeline po podpisaniu sprawdza ten fingerprint i przerywa build, jeśli skonfigurowano inny klucz. Klucza release nie wolno dodawać do repozytorium. Po opublikowaniu pierwszego APK trzeba zachować dokładnie ten sam klucz dla wszystkich kolejnych aktualizacji tego `applicationId`; APK podpisane innym kluczem nie zainstaluje się jako aktualizacja istniejącej aplikacji.
