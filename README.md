# Remote Camera & Lego Recognition (Android)

Un'applicazione Android avanzata sviluppata in Kotlin che trasforma più smartphone in un sistema di telecamera remota collaborativa e riconoscimento intelligente di mattoncini **Lego**, basata su architettura **Master/Slave** tramite Wi-Fi (Network Service Discovery / Socket TCP).

---

## 🚀 Caratteristiche Principali

### 1. Architettura Multi-Dispositivo (Master / Slave)
- **Master (Fotocamera)**: Condivide il flusso video in tempo reale, gestisce i controlli avanzati della fotocamera e scatta foto ad alta risoluzione.
- **Slave (Telecomando / Controllo)**: Visualizza il live streaming dal Master, invia comandi remoti e gestisce le palette di disegno/editing.
- **Riconoscimento Automatico (NSD)**: Scoperta automatica dei dispositivi connessi sulla stessa rete Wi-Fi tramite Network Service Discovery (mDNS) e connessione TCP socket diretta.

### 2. Riconoscimento Intelligente Lego (`Brickognize` & `Rebrickable`)
- Scatta una foto a un mattoncino Lego direttamente dalla pagina principale.
- Utilizza le API di **Brickognize** (`https://api.brickognize.com/`) per identificare il pezzo.
- Interroga il database ufficiale **Rebrickable** (tramite chiave API dedicata) per estrarre informazioni dettagliate:
  - Nome del pezzo
  - Codice Parte (`part_num`)
  - Categoria e ID Categoria (`part_cat_id`)
  - Anno di produzione
  - Percentuale di affidabilità del riconoscimento
- Mostra un pratico popup con confronto visivo affiancato (Foto scattata vs Immagine ufficiale del database).

### 3. Controlli Avanzati della Fotocamera
- **Autofocus (AF)** e focus manuale tramite slider dedicato.
- **Selettore di Risoluzione**: Scegli tra *Bassa (HD)*, *Media (Full HD)* e *Alta (Massima risoluzione)*.
- **Slider dello Zoom**: Regolazione fluida dello zoom ottico/digitale supportato dal sensore.

### 4. Modifica Foto e Palette Cromatiche Multiple
- Disegna direttamente sulle foto catturate o caricate dalla galleria con spessore del pennello regolabile.
- **3 Palette di Colori Dedicate**:
  1. *Palette Strumenti* (Dimensioni e spessore pennello)
  2. *Palette Colori Base* (12 colori principali)
  3. *Palette Color Scheme & Sfumature* (Slider HSL per tonalità, saturazione e luminosità, sfumature generate e armonie cromatiche).

---

## 🛠️ Tecnologie Utilizzate
- **Linguaggio**: Kotlin
- **Framework UI**: Android Views, Material Design, Layouts Adattivi
- **Librerie Jetpack**: CameraX (`camera2`, `lifecycle`, `view`), Core KTX, Appcompat, ConstraintLayout
- **Networking**: Socket TCP asincroni (`java.net`), Network Service Discovery (NSD), HTTPURLConnection per chiamate REST alle API esterne (`Brickognize` e `Rebrickable`).

---

## 📝 Configurazione ed Esecuzione
1. Clora il repository o apri il progetto in **Android Studio**.
2. Sincronizza le dipendenze Gradle (`Sync Project with Gradle Files`).
3. Installa l'app su due o più dispositivi Android (minSdk 29).
4. Collega i dispositivi alla stessa rete Wi-Fi.
5. Avvia l'app: un dispositivo assumerà il ruolo di **Master** (inquadratura e scatto) e gli altri di **Slave** (telecomando e strumenti).
