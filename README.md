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
- wyszukiwanie po nazwie, podsumowaniu i pełnej transkrypcji

## Pliki

Przed transkrypcją nagranie ma techniczną nazwę `speech_YYYYMMDD_HHMMSS.wav`.

Po poprawnej transkrypcji aplikacja tworzy parę o wspólnej nazwie bazowej:

```text
2026-08-30_20-43_Umówienie_dentysty_na_jutro.wav
2026-08-30_20-43_Umówienie_dentysty_na_jutro.txt
```

Plik TXT zawiera tytuł, datę, czas nagrania, krótkie podsumowanie i pełną transkrypcję. Finalizacja jest wykonywana w kolejności pozwalającej wycofać zmianę nazwy WAV, jeśli nie uda się utworzyć odpowiadającego mu TXT.

## OpenAI

Transkrypcja jest domyślnie włączona logicznie, ale żadne audio nie jest wysyłane, dopóki użytkownik nie zapisze własnego klucza OpenAI API w ekranie Ustawienia. Po zapisaniu klucza można również zakolejkować istniejące nagrania bez pliku TXT.

Bezpośrednie używanie osobistego klucza API w aplikacji mobilnej jest przeznaczone dla prywatnych buildów. Dla aplikacji dystrybuowanej innym użytkownikom należy zastąpić bezpośrednie wywołania OpenAI własnym backendem i nie przekazywać długoterminowego klucza API do klienta.

Android może nadal zatrzymać aplikację po Force stop, odebraniu uprawnienia mikrofonu albo w wyniku ograniczeń systemowych. Android 14+ nie pozwala uruchomić mikrofonowego foreground service bezpośrednio z `BOOT_COMPLETED`, dlatego po restarcie wymagane jest świadome tapnięcie powiadomienia przez użytkownika.
