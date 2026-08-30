# Speech Recorder

Lokalny dyktafon Android zapisujący WAV tylko wtedy, gdy wykryje mowę. Po uruchomieniu działa jako foreground service i nie wymaga połączenia z siecią.

- wersja 1.2.0
- Android 10+
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
