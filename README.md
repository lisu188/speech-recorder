# Speech Recorder

Dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Nagrywanie i VAD działają lokalnie jako foreground service. Opcjonalna transkrypcja OpenAI uruchamia się dopiero po zamknięciu klipu.

- wersja 1.4.0
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
