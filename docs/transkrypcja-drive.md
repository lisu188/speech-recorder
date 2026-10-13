# Masowa, lokalna transkrypcja Google Drive

`scripts/transcribe-drive.py` pobiera nagrania z Google Drive, rozpoznaje mowę
lokalnie przez `faster-whisper` i zapisuje tekst w folderze źródłowego WAV.
Nie wysyła audio do Descript, OpenAI ani innej usługi transkrypcji. Nie zmienia
plików nagrań ani ich uprawnień. Pobranie modelu z Hugging Face przesyła wyłącznie
żądania dotyczące modelu, bez nagrań.

## Instalacja

Wymagany jest Python 3.10 lub nowszy. Środowisko, modele, tokeny, audio,
checkpointy i raporty muszą znajdować się **poza repozytorium**. Przykład dla
Codex cloud:

```bash
python3 -m venv /workspace/.speech-recorder-transcription-venv
/workspace/.speech-recorder-transcription-venv/bin/python -m pip install \
  -r /workspace/speech-recorder/scripts/requirements-transcription.txt
```

Domyślny model to `large-v3`. Skrypt wybiera CUDA, jeśli jest dostępna i nadaje
się do inferencji; w przeciwnym razie używa CPU z `int8`. NVIDIA wymaga zgodnych
sterowników oraz bibliotek CUDA/cuDNN dla zainstalowanego CTranslate2. Na CPU
duży zbiór długich nagrań może przetwarzać się wiele godzin lub dni. Nie należy
uruchamiać wielu kopii modelu tylko dlatego, że dostępne jest wiele plików.

## OAuth 2.0

Włącz Google Drive API w swoim projekcie Google Cloud i utwórz klienta OAuth
typu **Desktop app**. Konto udzielające zgody musi widzieć folder z nagraniami.
Zakres `https://www.googleapis.com/auth/drive` pozwala odczytać istniejące pliki
i zachować treść wcześniejszych transkrypcji przed aktualizacją. Sam zakres
`drive.file` nie zapewnia dostępu do nagrań utworzonych przez inną aplikację.
Skrypt nie korzysta z endpointów usuwania plików lub zmiany uprawnień.

Pliki klienta OAuth i tokenu przechowuj w prywatnym katalogu poza checkoutem;
nie dodawaj ich do Git. Pierwszą zgodę przeglądarkową najwygodniej wykonać na
własnym komputerze, a następnie przekazać dane do bezpiecznych ustawień
środowiska. Token odświeżania pozwala kontynuować po wygaśnięciu tokenu dostępu.

Na komputerze z przeglądarką, po pobraniu JSON klienta do prywatnego katalogu:

```bash
TRANSCRIPTION_STATE="$HOME/.local/share/speech-recorder-transcription"
python3 scripts/transcribe-drive.py --authorize --dry-run \
  --credentials "$TRANSCRIPTION_STATE/oauth-client.json" \
  --token "$TRANSCRIPTION_STATE/oauth-token.json" \
  --state-dir "$TRANSCRIPTION_STATE"
```

Polecenie uruchamia oficjalny przepływ OAuth z lokalnym callbackiem i zapisuje
token z uprawnieniami pliku `0600`. Po udzieleniu zgody wykonuje także skan
Drive. W razie kilku folderów dodaj `--folder-id`. W cloud możesz wskazać już
przygotowany plik przez `--token`, bez ponownego otwierania przeglądarki.

Środowisko może przekazać trzy zmienne:

- `GDRIVE_OAUTH_CLIENT_ID` — identyfikator klienta OAuth;
- `GDRIVE_OAUTH_CLIENT_SECRET` — sekret klienta, ustawiany bezpiecznie;
- `GDRIVE_OAUTH_REFRESH_TOKEN` — token odświeżania, ustawiany bezpiecznie.

Nie wklejaj sekretów do poleceń, raportów, zgłoszeń ani czatu. Sekrety proxy
OAuth muszą mieć dozwoloną domenę `oauth2.googleapis.com`. Dla API wymagany
jest dostęp do `www.googleapis.com`. Pobieranie modeli wymaga `huggingface.co`
oraz domen pobierania wskazanych przez jego przekierowania. Operacje PR i CI
wymagają również `api.github.com`. Zachowaj weryfikację TLS.

## Skanowanie, próba i cały zbiór

Pracuj w `/workspace/speech-recorder`, używając Pythona z powyższego venv.
Najpierw wykonaj skan bez pobierania audio i bez zapisu na Drive:

```bash
/workspace/.speech-recorder-transcription-venv/bin/python scripts/transcribe-drive.py \
  --dry-run --retry-empty \
  --state-dir /workspace/speech-recorder-transcription-state \
  --report /workspace/speech-recorder-transcription-state/inventory.json
```

Nie zakładaj liczby WAV ani wcześniejszych dokumentów. Sprawdź faktyczny
raport, wybrany folder, duplikaty i pliki wymagające przeglądu. Jeśli znaleziono
wiele folderów, wskaż właściwy za pomocą `--folder-id`.

Kod zakończenia `0` oznacza udany skan `--dry-run` lub pełne wykonanie bez
pozostałych niekompletnych nagrań; `2` oznacza konflikty, wyniki puste,
przerwanie lub pracę pozostającą do wykonania, a `1` globalną blokadę, np.
brak OAuth lub modelu. Samo zakończenie pętli nie oznacza kompletności zbioru.

Następnie przetwórz kilka krótkich plików, zweryfikuj ich treść i sprawdź pliki
tekstowe w tym samym folderze Drive. Po poprawnej próbie kontynuuj cały zbiór:

```bash
/workspace/.speech-recorder-transcription-venv/bin/python scripts/transcribe-drive.py \
  --resume --retry-empty --limit 3 --model large-v3 \
  --state-dir /workspace/speech-recorder-transcription-state \
  --report /workspace/speech-recorder-transcription-state/pilot-report.json
```

Pliki kwalifikujące się do pracy są sortowane według rozmiaru WAV, więc próba
z limitem wybiera najkrótsze pliki PCM. Wyniki puste lub niepewne nie oznaczają
udanej próby. Pełne wykonanie:

```bash
/workspace/.speech-recorder-transcription-venv/bin/python scripts/transcribe-drive.py \
  --resume --retry-empty --model large-v3 \
  --state-dir /workspace/speech-recorder-transcription-state \
  --report /workspace/speech-recorder-transcription-state/full-report.json
```

`--limit N` ogranicza liczbę nagrań wybranych do przetwarzania. `--model`
może wskazywać inną nazwę modelu lub przygotowany lokalny katalog modelu.
Język domyślnie wykrywany jest automatycznie; rozpoznawany tekst nie jest
tłumaczony. Długie nagrania są dzielone na części, a ukończone części pozostają
w checkpointach, aby wznowienie nie wymagało ponownej transkrypcji całości.

## Wyniki i zachowanie danych

Dla `speech_20261012_183218.wav` wynik nazywa się
`speech_20261012_183218.transkrypcja.txt`. Nagłówek zawiera nazwę nagrania,
język, czas trwania i status; wypowiedzi mają znaczniki czasu. Skrypt nie
usuwa wulgaryzmów, nie interpretuje rozmów ani nie przypisuje tożsamości
rozmówcom. Detektor mowy i progi modelu ograniczają rozpoznawanie ciszy i
szumu; nie stanowią gwarancji poprawności każdej wypowiedzi. Wyniki puste
i błędne trzeba rozróżniać od poprawnie zakończonych transkrypcji.

Checkpoint wiąże postęp z identyfikatorem Drive i metadanymi źródła.
Zakończona transkrypcja jest sprawdzana na Drive także po wznowieniu, więc
przerwanie między wysłaniem tekstu a zapisem checkpointu nie powinno
tworzyć kolejnego dokumentu. Nie zmieniaj katalogu stanu między kolejnymi
uruchomieniami i nie uruchamiaj niezależnych autorów wyników na tym samym
zbiorze jednocześnie. Google Drive dopuszcza duplikaty nazw.

Puste wcześniejsze dokumenty, w tym wyniki Descript „brak rozpoznanej mowy”,
są ponawiane przez `--retry-empty`. Treść istniejącego wyniku musi zostać
zachowana przed zastąpieniem. Niejednoznaczne duplikaty lub dokumenty o
niepewnym statusie wymagają przeglądu; nie są automatycznie usuwane.
Techniczne fragmenty `__sr_live_*` są pomijane, gdy istnieje odpowiadający
im pełny WAV; fragmenty bez pełnego nagrania są raportowane osobno.

Usuwane są wyłącznie lokalne kopie audio należące do tego narzędzia, po
zweryfikowanym zapisie wyniku na Drive. Przy błędzie lub przerwaniu lokalne
dane pozostają do wznowienia. Raport i tekstowe checkpointy także zawierają
prywatną treść; zabezpiecz katalog stanu tak samo jak nagrania.

## Testy i raport końcowy

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-transcribe-drive.py
```

Testy jednostkowe nie wymagają konta Google ani pobrania modelu. Sprawdzają
dopasowanie nazw, stan transkrypcji, checkpointy i bezpieczną obsługę
duplikatów oraz błędów API. Prawdziwy test integracji wymaga skanu Drive,
lokalnej inferencji na wybranych nagraniach i odczytu przesłanych tekstów.

Raport końcowy powinien zawierać rzeczywisty stan Drive oraz osobne liczby
wyników udanych, pustych, pominiętych i błędnych. Brak OAuth lub dostępu
sieciowego oznacza nieznaną liczbę plików, a nie pusty zbiór. Nie uznawaj
testów jednostkowych ani syntetycznej ciszy za wykonanie transkrypcji konta.
