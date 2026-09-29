# Speech Recorder

Dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Nagrywanie i VAD działają lokalnie jako foreground service. Zakończone klipy są zapisywane do folderu wybranego w OneDrive przez systemowy Storage Access Framework.

## Wersja 1.5.0

- Kotlin 2.4.10
- Android Gradle Plugin 9.3.2
- compileSdk / targetSdk 37
- minSdk 29
- JDK 17
- 16 kHz mono PCM WAV
- 5 s pre-buffer
- 8 s ciszy kończy klip
- dynamiczny próg szumu + energia + zero-crossing rate
- foreground service z `START_STICKY`
- trwały lokalny bufor awaryjny w prywatnym katalogu aplikacji
- zapis nagrań do wybranego folderu OneDrive
- przeglądarka nagrań czytająca bezpośrednio wybrany folder OneDrive
- odtwarzanie, mini-waveformy, udostępnianie i usuwanie nagrań
- migracja wcześniejszych WAV z `Music/SpeechRecorder` po skonfigurowaniu OneDrive
- brak OpenAI, transkrypcji, kluczy API i uprawnienia `INTERNET`

## Pierwsza konfiguracja

1. Zainstaluj aplikację OneDrive na telefonie i zaloguj się.
2. Otwórz **Ustawienia** w Dyktafonie.
3. Wybierz **WYBIERZ FOLDER W ONEDRIVE**.
4. W systemowym selektorze dokumentów przejdź do OneDrive i wybierz folder, np. `SpeechRecorder`.
5. Wróć do ekranu **Dyktafon** i wybierz **ROZPOCZNIJ**.

Aplikacja zapamiętuje persistowalne uprawnienie do wybranego drzewa dokumentów. Nie przechowuje hasła ani tokenu Microsoft. Dostęp do konta i synchronizację sieciową realizuje provider dokumentów OneDrive.

## Bezpieczeństwo zapisu

Aktywny klip jest zapisywany w trwałym prywatnym katalogu aplikacji. Po zamknięciu WAV zadanie WorkManager kopiuje go do wskazanego folderu OneDrive. Lokalny plik jest usuwany dopiero po poprawnym zapisaniu pełnej liczby bajtów do providera.

Jeżeli OneDrive jest niedostępny, uprawnienie do folderu wygasło albo provider zwróci błąd, lokalna kopia pozostaje na urządzeniu i zapis jest ponawiany. Po ponownym otwarciu aplikacji osierocone nagrania są odzyskiwane, naprawiany jest nagłówek WAV i zadanie jest ponownie kolejkowane.

Po wybraniu folderu OneDrive aplikacja próbuje również przenieść wcześniejsze pliki WAV zapisane w `Music/SpeechRecorder`. Plik źródłowy jest kasowany dopiero po poprawnym skopiowaniu.

## Powiadomienie foreground service

Android wymaga powiadomienia dla długotrwałego foreground service korzystającego z mikrofonu. Aplikacja nie deklaruje jednak `POST_NOTIFICATIONS`.

Na Androidzie 13 i nowszym oznacza to, że informacja o usłudze nie jest wyświetlana jako zwykłe stałe powiadomienie w panelu powiadomień. System nadal pokazuje aplikację w systemowym widoku aktywnych aplikacji / Task Manager i może pozwolić użytkownikowi ją zatrzymać.

Na Androidzie 12 i starszym system nadal może wyświetlać wymagane powiadomienie foreground service. Tego nie można legalnie usunąć bez rezygnacji z ciągłego dostępu do mikrofonu w tle.

Kanał techniczny jest cichy, bez dźwięku, wibracji i badge'a.

## Restart telefonu

Nowe wersje Androida ograniczają automatyczne uruchamianie mikrofonowego foreground service po restarcie. Wersja 1.5.0 nie publikuje osobnego powiadomienia z prośbą o wznowienie. Po restarcie należy otworzyć Dyktafon i wybrać **ROZPOCZNIJ**.

## Prywatność

Wersja 1.5.0 nie zawiera integracji OpenAI. Usunięto:

- klienta OpenAI,
- przechowywanie klucza API,
- automatyczną transkrypcję,
- generowanie tytułów i podsumowań,
- pliki checkpointów transkrypcji,
- dzielenie WAV na fragmenty do STT,
- uprawnienie `INTERNET`,
- uprawnienie `POST_NOTIFICATIONS`.

Analiza VAD odbywa się wyłącznie lokalnie. Przesłanie WAV do OneDrive jest wykonywane przez wybrany przez użytkownika systemowy provider dokumentów.

## Release signing

Wariant `release` zachowuje certyfikat SHA-256:

`c311a44e405ccfab2b822d5295c45e4dbbc6972516c3695dadb146b6149ec2b6`

Wariant `standalone` (`pl.lisu188.speechrecorder.stable`) zachowuje certyfikat SHA-256:

`afe1498136f756801c385653c7f34f1597a423da437398895f6a9d6c710d03a5`

Do aktualizacji istniejącej instalacji trzeba użyć dokładnie tego samego klucza. Skrypt `scripts/sign-apk.sh` weryfikuje fingerprint przed wystawieniem APK.

## Sprawdzenie na telefonie

1. Wybierz folder OneDrive i uruchom nasłuch.
2. Nagraj mowę i sprawdź pojawienie się WAV w OneDrive.
3. Sprawdź zakończenie klipu po 8 sekundach ciszy.
4. Wyłącz sieć podczas klipu, a następnie przywróć połączenie i sprawdź, czy nagranie nie ginie.
5. Przerwij proces aplikacji podczas aktywnego klipu i sprawdź odzyskanie WAV po kolejnym uruchomieniu.
6. Sprawdź nasłuch z wygaszonym ekranem.
7. Na Androidzie 13+ sprawdź brak stałego wpisu Dyktafonu w zwykłej liście powiadomień oraz obecność usługi w systemowym widoku aktywnych aplikacji.

Dokumentacja platformy:

- Android foreground service notifications: https://developer.android.com/develop/ui/compose/notifications
- Android 13 notification permission and foreground services: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Android Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
