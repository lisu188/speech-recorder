# Speech Recorder

Dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Nagrywanie i VAD działają lokalnie jako foreground service. Opcjonalna transkrypcja OpenAI uruchamia się dopiero po zamknięciu klipu.

- wersja 1.4.1
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
- opcjonalna automatyczna transkrypcja przez OpenAI `gpt-transcribe`
- tytuł i podsumowanie rozmowy generowane przez `gpt-5.6-luna`
- długie WAV-y dzielone na 8-minutowe fragmenty tylko na potrzeby STT; oryginalne nagranie pozostaje jednym plikiem
- WorkManager 2.11.2 z wymaganiem aktywnego połączenia sieciowego i retry z backoffem
- klucz OpenAI szyfrowany kluczem przechowywanym w Android Keystore
- pliki TXT zapisywane przez Storage Access Framework po jednorazowym wskazaniu `Music/SpeechRecorder`
- wyszukiwanie po nazwie, podsumowaniu i pełnej transkrypcji

## Pliki

Przed transkrypcją nagranie ma techniczną nazwę `speech_YYYYMMDD_HHMMSS.wav`.

Po poprawnej transkrypcji aplikacja tworzy parę o wspólnej nazwie bazowej:

```text
2026-08-30_20-43_Umówienie_dentysty_na_jutro.wav
2026-08-30_20-43_Umówienie_dentysty_na_jutro.txt
```

Plik TXT zawiera tytuł, datę, czas nagrania, krótkie podsumowanie i pełną transkrypcję. Finalizacja najpierw zapisuje tymczasowy TXT, następnie zmienia nazwę WAV i na końcu nadaje finalną nazwę TXT. Jeśli finalizacja się nie powiedzie, aplikacja próbuje przywrócić pierwotną nazwę WAV i usuwa niedokończony TXT.

## Pierwsza konfiguracja transkrypcji

1. Nagraj co najmniej jeden klip, aby katalog `Music/SpeechRecorder` istniał.
2. Otwórz `Ustawienia` i zapisz własny klucz OpenAI API.
3. Wybierz `Music/SpeechRecorder` w systemowym selektorze folderu. Aplikacja akceptuje dokładnie ten katalog i utrwala uprawnienie odczytu/zapisu.
4. Włącz automatyczną transkrypcję.

Automatyczna kolejka działa tylko wtedy, gdy jednocześnie istnieją: zapisany klucz API, utrwalony dostęp do folderu i włączona opcja transkrypcji. Brak sieci nie wpływa na samo nagrywanie; WorkManager czeka na połączenie.

## OpenAI

`gpt-transcribe` wykonuje speech-to-text. Następnie `gpt-5.6-luna` z `reasoning.effort=none` i Structured Outputs generuje krótki tytuł do nazwy pliku oraz zwięzłe podsumowanie. Tytuł jest dodatkowo lokalnie czyszczony z niedozwolonych znaków i ograniczony do 60 znaków.

Żadne audio nie jest wysyłane, dopóki użytkownik nie skonfiguruje własnego klucza API i dostępu do folderu. Po konfiguracji można również zakolejkować istniejące nagrania bez odpowiadającego pliku TXT.

Bezpośrednie używanie osobistego klucza API w aplikacji mobilnej jest przeznaczone dla prywatnych buildów. Dla aplikacji dystrybuowanej innym użytkownikom należy zastąpić bezpośrednie wywołania OpenAI własnym backendem i nie przekazywać długoterminowego klucza API do klienta.

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

## Wersja 1.4.1

- Błąd kolejki transkrypcji nie usuwa poprawnie zapisanego WAV.
- Ukończone fragmenty STT, transkrypcja i metadane są zapisywane atomowo w prywatnym katalogu aplikacji. Ponowienie korzysta z zapisanych wyników. Awaria przed otrzymaniem lub utrwaleniem odpowiedzi API nadal może wymagać ponownego żądania.
- Długie zadania ustępują między etapami przed limitem czasu WorkManager; anulowanie odłącza aktywne połączenie HTTP.
- Błąd odczytu AudioRecord uruchamia ponowną inicjalizację mikrofonu.
- Lista nagrań, odczyt TXT, waveformy i wyszukiwanie brakujących transkrypcji w pamięci urządzenia działają poza głównym wątkiem. Przyciskiem odświeżania można wczytać nowo ukończone transkrypcje.
- Ustawienia pokazują liczbę oczekujących zadań, błędy kolejki oraz komunikaty o kluczu, uprawnieniach i braku środków API.
- CI uruchamia testy regresji, lint i kompilację obu wariantów. Testy HTTP korzystają wyłącznie z lokalnego serwera z odpowiedziami testowymi.

### Instalacja obok wcześniejszych APK

Wcześniejsze instalowalne APK 1.4.0 były podpisywane różnymi jednorazowymi certyfikatami debug. Zachowana kopia `speech-recorder-signing-backup.zip` zawiera osobny, stały klucz. Jego SHA-256: `afe1498136f756801c385653c7f34f1597a423da437398895f6a9d6c710d03a5`. Nie odpowiada on ani podpisom wcześniejszych APK, ani przypiętemu certyfikatowi historycznego wariantu release.

Wariant `standalone` ma identyfikator `pl.lisu188.speechrecorder.stable` i nazwę `Dyktafon 1.4`. Służy do instalacji obok poprzedniej aplikacji, bez jej usuwania. Ustawienia klucza API i dostęp do folderu trzeba skonfigurować w nowej aplikacji. Starsze pliki pozostają na urządzeniu; lista MediaStore nowej aplikacji pokazuje jej własne nagrania. Przed rozpoczęciem nagrywania zatrzymaj nasłuch w poprzedniej aplikacji. Kolejne aktualizacje standalone należy podpisywać tym samym zachowanym kluczem.

CI udostępnia niepodpisane APK obu wariantów. APK przeznaczone do instalacji musi być podpisane i zweryfikowane; samo powodzenie kompilacji nie oznacza instalowalnego pliku. Sekrety i kopia klucza pozostają poza repozytorium.

### Sprawdzenie na telefonie

1. Nadaj dostęp do mikrofonu, nagraj mowę i sprawdź odtwarzanie zapisanego WAV.
2. Sprawdź zakończenie klipu po 8 sekundach ciszy oraz zapis po zatrzymaniu nagrywania.
3. Włącz transkrypcję po zapisaniu klucza i wskazaniu folderu; sprawdź parę WAV/TXT o wspólnej nazwie.
4. Przerwij połączenie podczas transkrypcji i sprawdź wznowienie oraz komunikaty kolejki.
5. Sprawdź nasłuch z wygaszonym ekranem i po powrocie do aplikacji. Testy JVM nie potwierdzają zachowania rzeczywistego mikrofonu ani ograniczeń konkretnego telefonu.

Podstawy integracji: [transkrypcja OpenAI](https://developers.openai.com/api/docs/guides/speech-to-text), [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).
