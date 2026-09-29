# Speech Recorder

Dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Nagrywanie i VAD działają lokalnie jako foreground service. Zakończone klipy są zapisywane do folderu wybranego w OneDrive przez systemowy Storage Access Framework.

## Wersja 1.6.0

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
- częściowy wake lock podczas aktywnego nasłuchu
- opcja bezpośredniego wyłączenia optymalizacji baterii dla aplikacji
- nasłuch nie zatrzymuje się po usunięciu aplikacji z listy ostatnich
- automatyczne wznowienie przy pierwszym otwarciu aplikacji po restarcie telefonu
- trwały lokalny bufor awaryjny w prywatnym katalogu aplikacji
- zapis nagrań do wybranego folderu OneDrive
- podczas aktywnego klipu poprawne 15-sekundowe WAV-y bezpieczeństwa są natychmiast przekazywane do providera OneDrive
- po poprawnym zapisie pełnego WAV techniczne fragmenty live są usuwane
- WAV-y starsze niż 30 dni są automatycznie kompresowane lossless do `.wav.zip` na OneDrive
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

Aktywny klip jest zapisywany w trwałym prywatnym katalogu aplikacji. Równolegle aplikacja zamyka co 15 sekund poprawny techniczny fragment WAV i natychmiast przekazuje go do providera OneDrive w dedykowanej szeregowej kolejce I/O. WorkManager służy jako fallback po błędzie oraz do odzyskiwania plików po restarcie procesu. Fragmenty mają prefiks `__sr_live_` i są plikami awaryjnymi, a nie pozycjami biblioteki.

Po zamknięciu rozmowy WorkManager zapisuje pełny WAV w OneDrive. Dopiero po poprawnym przesłaniu pełnego pliku aplikacja usuwa odpowiadające mu techniczne fragmenty live i lokalną kopię. Dzięki temu awaria urządzenia podczas długiego klipu ogranicza ilość audio, które aplikacja nie zdążyła jeszcze przekazać providerowi, do około 15 sekund. Faktyczny moment wysłania danych do serwerów Microsoft zależy od synchronizacji providera OneDrive.

Jeżeli OneDrive jest niedostępny, uprawnienie do folderu wygasło albo provider zwróci błąd, lokalna kopia pozostaje na urządzeniu i zapis jest ponawiany. Po ponownym otwarciu aplikacji osierocone nagrania są odzyskiwane, naprawiany jest nagłówek WAV i zadanie jest ponownie kolejkowane.

Po wybraniu folderu OneDrive aplikacja próbuje również przenieść wcześniejsze pliki WAV zapisane w `Music/SpeechRecorder`. Plik źródłowy jest kasowany dopiero po poprawnym skopiowaniu.

## Kompresja starszych nagrań

Raz dziennie WorkManager przegląda WAV-y w wybranym folderze OneDrive. Pliki mające co najmniej 30 dni są strumieniowo pakowane do ZIP/DEFLATE z najwyższym poziomem kompresji. Archiwum ma nazwę `<oryginalny.wav>.zip` i zawiera dokładnie oryginalny WAV bez zmiany próbek audio. Źródłowy WAV jest usuwany dopiero po poprawnym zapisaniu całego archiwum. Jedno uruchomienie archiwizuje maksymalnie osiem plików, aby nie blokować telefonu długą pracą.

Archiwa `.wav.zip` pozostają na OneDrive i są celowo pomijane przez ekran **Nagrania**. Do odsłuchu starszego archiwum należy rozpakować WAV w OneDrive lub innym menedżerze plików.

## Powiadomienie foreground service

Android wymaga powiadomienia dla długotrwałego foreground service korzystającego z mikrofonu. Aplikacja nie deklaruje jednak `POST_NOTIFICATIONS`.

Na Androidzie 13 i nowszym oznacza to, że informacja o usłudze nie jest wyświetlana jako zwykłe stałe powiadomienie w panelu powiadomień. System nadal pokazuje aplikację w systemowym widoku aktywnych aplikacji / Task Manager i może pozwolić użytkownikowi ją zatrzymać.

Na Androidzie 12 i starszym system nadal może wyświetlać wymagane powiadomienie foreground service. Tego nie można legalnie usunąć bez rezygnacji z ciągłego dostępu do mikrofonu w tle.

Kanał techniczny jest cichy, bez dźwięku, wibracji i badge'a.

## Tryb always-on i restart telefonu

Po ręcznym uruchomieniu nasłuchu aplikacja utrzymuje mikrofon jako `START_STICKY` foreground service i trzyma częściowy wake lock. Sticky foreground service może zostać odtworzony przez system po ubiciu procesu; ograniczenia Androida dotyczące uruchamiania FGS z tła nie blokują restartu już rozpoczętej sticky foreground service.

W ustawieniach aplikacji jest przycisk **WYŁĄCZ OPTYMALIZACJĘ BATERII**. Dla możliwie ciągłej pracy należy zatwierdzić systemowy wyjątek.

Android 14+ nie pozwala uruchomić foreground service typu `microphone` bezpośrednio z `BOOT_COMPLETED`. Po reboocie aplikacja zapamiętuje poprzedni stan. Przy pierwszym ręcznym otwarciu Dyktafonu nasłuch uruchomi się automatycznie, bez naciskania **ROZPOCZNIJ**.

Nie istnieje zwykły mechanizm aplikacji, który pozwala zagwarantować nagrywanie po **Force stop**. Force stop blokuje usługę i odbiorniki aplikacji do czasu ponownego uruchomienia jej przez użytkownika. Odebranie uprawnienia mikrofonu albo ustawienie aplikacji jako systemowo **Restricted** również może przerwać działanie.

## Prywatność

Wersja 1.6.0 nie zawiera integracji OpenAI. Usunięto:

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
2. Nagraj mowę przez ponad 30 sekund i sprawdź pojawianie się technicznych plików `__sr_live_...partXXXX.wav` w OneDrive.
3. Zakończ klip i sprawdź pojawienie się pełnego WAV oraz usunięcie odpowiadających mu fragmentów live.
4. Sprawdź zakończenie klipu po 8 sekundach ciszy.
5. Wyłącz sieć podczas klipu, a następnie przywróć połączenie i sprawdź, czy nagranie nie ginie.
6. Przerwij proces aplikacji podczas aktywnego klipu i sprawdź odzyskanie WAV po kolejnym uruchomieniu.
7. Wyłącz optymalizację baterii w ustawieniach Dyktafonu i sprawdź nasłuch przez kilka godzin z wygaszonym ekranem.
8. Usuń aplikację z listy ostatnich i sprawdź, że nasłuch nadal działa.
9. Ubij proces poleceniem ADB bez Force stop i sprawdź odtworzenie sticky service.
10. Zrestartuj telefon, otwórz Dyktafon i sprawdź automatyczne wznowienie bez naciskania ROZPOCZNIJ.
11. W testowym folderze umieść WAV starszy niż 30 dni, uruchom aplikację i sprawdź utworzenie `.wav.zip` oraz usunięcie źródłowego WAV dopiero po sukcesie.
12. Na Androidzie 13+ sprawdź brak stałego wpisu Dyktafonu w zwykłej liście powiadomień oraz obecność usługi w systemowym widoku aktywnych aplikacji.

Dokumentacja platformy:

- Android foreground service notifications: https://developer.android.com/develop/ui/compose/notifications
- Android 13 notification permission and foreground services: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Android Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
