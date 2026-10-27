# Project Handoff: Remote Camera & Lego Recognition

Questo documento fornisce una panoramica completa dello stato attuale del progetto Android **Remote Camera & Lego Recognition**, utile per passaggi di consegne, continuazione dello sviluppo o pubblicazione.

---

## 🏗️ Architettura del Progetto

Il progetto è strutturato come un'applicazione Android nativa in Kotlin (`minSdk 29`, `targetSdk 36`) suddivisa in un unico modulo principale (`:app`).

### Componenti Principali (`com.igino.remote_camera`)
1. **`MainActivity.kt`**: Il cuore dell'applicazione. Gestisce:
   - Connettività di rete multi-dispositivo tramite **Network Service Discovery (NSD)** e Socket TCP asincroni.
   - Ruoli **Master** (dispositivo che gestisce la fotocamera fisica, streaming video, scatto foto ad alta risoluzione e controlli zoom/risoluzione) e **Slave** (dispositivo di controllo remoto).
   - Integrazione con **CameraX** (`ImageAnalysis`, `ImageCapture`, `PreviewView`, controllo Zoom e Autofocus).
   - Integrazione con le API di **Brickognize** (`https://api.brickognize.com/`) e **Rebrickable** (chiave API: `b63b7096a3cdea9e37191e848c99a9ad`) per il riconoscimento dei mattoncini Lego (inclusi `part_cat_id` e nome della categoria).
   - Gestione delle palette cromatiche multiple (Strumenti, Colori base, Color Scheme & Sfumature HSL) e modalità di disegno/modifica foto (`DrawingView`).
2. **`DrawingView.kt`**: Vista personalizzata per disegnare sopra le foto catturate o caricate.
3. **`ColorSliderView.kt`**: Slider personalizzato con sfumature di colore per la selezione HSL.
4. **`activity_main.xml`**: Layout principale dell'interfaccia utente (anteprima fotocamera, controlli AF, slider risoluzione e zoom, pulsanti d'azione rapidi tra cui il riconoscimento Lego e la modifica foto).

---

## 🛠️ Ulteriori Modifiche e Correzioni Recenti

1. **Riconoscimento Lego Ottimizzato**:
   - Il pulsante di riconoscimento Lego è sempre visibile nella barra principale.
   - Quando viene premuto sullo Slave, non scarica file di foto grezze sul dispositivo slave ma richiede lo scatto al Master, il quale esegue l'analisi del mattoncino via API e restituisce solo il risultato testuale e il link dell'immagine ufficiale.
   - Il popup dei risultati mostra un confronto visivo affiancato (Foto scattata vs Immagine Rebrickable) insieme a tutti i dettagli tecnici: Nome, Codice Parte (`part_num`), Categoria, ID Categoria (`part_cat_id`), Anno di produzione e Affidabilità (%).
2. **Slider di Risoluzione e Zoom**:
   - Aggiunto lo slider per la risoluzione (*Bassa HD*, *Media Full HD*, *Alta Massima*) sotto lo switch AF.
   - Aggiunto lo slider per lo zoom ottico/digitale (`setZoomRatio`) sotto quello della risoluzione.
   - Sincronizzazione in tempo reale dei comandi tra Master e Slave.
3. **Stabilità e Crash Fix**:
   - Risolto il crash sul Master causato dal riciclo prematuro di bitmap attive durante lo scatto ad alta risoluzione.
   - Ripristinato correttamente il listener e la visibilità del pulsante di modifica foto (`editPhotoButton`).

---

## 📂 Struttura dei File Chiave
- `app/src/main/AndroidManifest.xml`: Configurazione permessi (Camera, Internet, Access Network State, ecc.).
- `app/src/main/java/com/igino/remote_camera/MainActivity.kt`: Logica principale, networking e API.
- `app/src/main/res/layout/activity_main.xml`: UI principale.
- `README.md`: Documentazione pubblica del progetto.
- `.gitignore`: Esclusione di file di build, cache e file locali dell'IDE.

---

## 🚀 Prossimi Passi Consigliati
- Aggiunta di test automatici unitari e di UI (Instrumentation tests).
- Eventuale persistenza locale (Room DB) dello storico dei mattoncini Lego riconosciuti.
- Ottimizzazione della gestione degli errori di rete in ambienti con Wi-Fi instabile.
