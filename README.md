# Corsa BT — computer da corsa per Xiaomi Watch 2 + Strava su iPhone

App per Wear OS. Sull'orologio vedi passo, battito, zone, ripetute e fartlek;
in contemporanea l'orologio fa da **fascia cardio Bluetooth** per l'app Strava
sull'iPhone, che registra GPS e percorso.

<p align="center">
  <img src="docs/screenshots/menu.png" width="200" alt="Menu">
  <img src="docs/screenshots/corsa.png" width="200" alt="Schermata corsa">
  <img src="docs/screenshots/percorso.png" width="200" alt="Percorso">
  <img src="docs/screenshots/personalizza.png" width="200" alt="Personalizza">
</p>

## Installazione veloce (senza compilare)

1. Scarica `corsa-bt.apk` dall'ultima [Release](../../releases/latest).
2. Attiva il debug wireless sull'orologio e collegati con `adb` (vedi *Compilare e installare* più sotto).
3. `adb install -r corsa-bt.apk`

## Funzioni

| Modalità | Cosa fa |
|---|---|
| **Corsa libera** | Tempo, distanza, passo attuale/medio, battito con zona colorata. Obiettivo opzionale di passo (± tolleranza) o zona cardio con avviso a vibrazione. Giro automatico ogni km con riepilogo (passo, tempo, ♥ medio). |
| **Ripetute** | Riscaldamento (minuti o "fino a GIRO") → N × veloce (distanza o tempo) con passo/zona obiettivo → recupero (tempo o distanza) → defaticamento. Anello di avanzamento, conto alla rovescia, "partner virtuale" (secondi avanti/indietro rispetto al passo obiettivo), anteprima dello step successivo. |
| **Fartlek** | A tempo (N cicli veloce/lento) oppure libero: premi GIRO a ogni cambio di ritmo. |
| **Solo sensore cardio** | Solo battito a Strava, senza GPS. |
| **Storico** | Riepilogo di ogni allenamento: tempo in zona, giri/ripetute con passo e battito. |

Durante l'allenamento scorri orizzontalmente fra le pagine:
**Controlli** (Pausa / Giro / Fine) ← **Principale** → Percorso → Giro → Zona cardio → Totali (cadenza, stato GPS e iPhone).
In alto su ogni pagina: il tuo nome e l'ora.
Il **tasto fisico inferiore** fa GIRO, se l'orologio lo consente.

- **Principale**: tempo, km, 4 campi personalizzabili (di base passo attuale, battito,
  media del giro, media totale) e barra delle zone cardio.
- **Percorso**: traccia GPS disegnata dal vivo, nord in alto, zoom automatico e scala.
  Non c'è la mappa stradale sotto (l'orologio non ha connessione durante la corsa).

**Personalizza** (dal menu): colore dell'app, nome sì/no, scelta dei 4 campi della pagina principale.

Impostazioni: FC massima (zone Garmin 50/60/70/80/90 %), giro automatico, pausa automatica,
battito all'iPhone, schermo sempre acceso, vibrazione.

## Uso con Strava (iPhone)

1. Orologio: scegli l'allenamento → **AVVIA** (concedi i permessi la prima volta).
2. iPhone: Strava → **Registra** → pannello sensori → **Aggiungi un sensore** → l'orologio.
   Serve farlo solo la prima volta; poi Strava lo ricollega da solo.
3. Avvia l'attività su Strava. A fine corsa: **Fine** sull'orologio e salva su Strava.

Strava dal sensore prende **solo il battito**; percorso, distanza e passo li registra l'iPhone.
Le metriche sull'orologio usano il GPS dell'orologio, quindi possono differire di poco da Strava.

## Compilare e installare

Strumenti già installati: JDK 17 (Temurin) e Android SDK in `%LOCALAPPDATA%\Android\Sdk`.

```
gradlew.bat testDebugUnitTest   # test della logica di allenamento
gradlew.bat assembleDebug       # → app\build\outputs\apk\debug\app-debug.apk
```

Sull'orologio (una volta): *Impostazioni → Sistema → Info* → tocca **Numero build** 7 volte;
poi *Opzioni sviluppatore* → **Debug ADB** + **Debug wireless** → **Accoppia nuovo dispositivo**.
Orologio e PC sulla stessa rete Wi-Fi (va bene l'hotspot dell'iPhone):

```
adb pair IP:PORTA_ACCOPPIAMENTO CODICE
adb connect IP:PORTA_DEBUG_WIRELESS
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Problemi comuni

- *L'iPhone non vede l'orologio*: Impostazioni iOS → Privacy → Bluetooth → Strava attivo;
  sulla pagina Totali deve comparire "in attesa di iPhone".
- *"Bluetooth sensore non supportato"*: il chip non permette di trasmettere come periferica;
  le metriche sull'orologio funzionano comunque.
- *Battito "--"*: stringi il cinturino; il valore arriva dopo qualche secondo.
