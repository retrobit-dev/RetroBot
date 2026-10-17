# 03 — Runbook

## 0. Yang sudah dan belum diverifikasi

| Komponen | Status | Cara verifikasi |
| :--- | :--- | :--- |
| `mock/` | **hijau** — 21 test | `npm test` |
| `scripts/` | **hijau** — 34 file Kotlin diperiksa | `npm run check` |
| `android/` | belum dikompilasi | butuh JDK 17 + Android SDK 35 |

Lingkungan tempat repo ini dibuat tidak punya JDK atau Android SDK, jadi
build Gradle belum pernah dijalankan di sini. Kode Kotlin sudah ditulis
lengkap dan diperiksa secara statis, tetapi belum melewati `assembleDebug`.

Pemeriksaan statis yang ada:

| Skrip | Yang dicek |
| :--- | :--- |
| `scripts/check-kotlin.js` | Keseimbangan kurung, import yang tidak resolve, deklarasi ganda |
| `scripts/check-symbols.js` | Simbol proyek yang dipakai tanpa import |
| `scripts/check-text.js` | Karakter asing yang terselip di dokumentasi |

---

## 1. Menjalankan provider tiruan

```bash
cd mock
npm start          # http://localhost:8787/v1
```

Endpoint yang tersedia:

| Endpoint | Fungsi |
| :--- | :--- |
| `GET /health` | liveness |
| `GET /v1/models` | daftar model tiruan |
| `POST /v1/chat/completions` | chat, streaming dan non-streaming |

Uji manual:

```bash
curl -s http://localhost:8787/v1/models | head -c 300; echo

curl -s http://localhost:8787/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"mock-concise","messages":[{"role":"user","content":"halo"}]}' | head -c 400; echo

curl -N -s http://localhost:8787/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"mock-detailed","stream":true,"messages":[{"role":"user","content":"halo"}]}' | head -5
```

## 2. Menjalankan aplikasi

### Prasyarat

- JDK 17
- Android Studio (Ladybug atau lebih baru) atau Android SDK 35
- Emulator atau perangkat fisik

### Langkah

```bash
cd android

# Sekali saja, kalau folder wrapper belum ada:
gradle wrapper --gradle-version 8.9

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest     # unit test Kotlin
./gradlew :app:installDebug
```

Alternatif: buka folder `android/` di Android Studio, tunggu sync, lalu Run.

### Konfigurasi untuk emulator

`localhost` di emulator merujuk ke emulator itu sendiri. Untuk menjangkau
mesin pengembangan, gunakan `10.0.2.2` (emulator bawaan) atau `10.0.3.2`
(Genymotion). Kedua host ini sudah diizinkan cleartext di
`res/xml/network_security_config.xml`.

### Uji manual dengan model tiruan

1. Jalankan `npm start` di `mock/`.
2. Di aplikasi: tab **Providers** → **+** → pilih **Custom
   (OpenAI-compatible)** → base URL `http://10.0.2.2:8787/v1` → key dikosongkan
   → **Add**.
3. Tekan **Sync models** → harus muncul "4 models available".
4. Tab **Battle** → pilih `mock-concise` untuk model A, `mock-detailed` untuk
   model B → tulis pertanyaan → **Battle**.
5. Dua kolom terisi berdampingan. Pilih yang lebih bagus → identitas
   terungkap.
6. Tab **Ranks** → leaderboard sudah punya satu baris.

### Dengan API key sungguhan

1. Providers → **+** → pilih provider → tempel key → **Add**.
2. **Sync models** → model-model provider itu muncul.
3. Battle seperti di atas.

## 3. Menjalankan semua test

```bash
npm run check      # statis + test
npm test           # hanya test
```

`npm test` menjalankan 21 test: 7 untuk provider tiruan dan 14 untuk alur
arena (battle → vote → Elo → leaderboard).

---

## 4. Memperbaiki masalah

| Gejala | Penyebab | Solusi |
| :--- | :--- | :--- |
| `Cleartext HTTP traffic not permitted` | host tidak ada di network security config | tambahkan host, atau pakai HTTPS |
| `Could not resolve host: 10.0.2.2` | emulator memakai konfigurasi jaringan lain | cek `adb reverse --list` |
| Sync models gagal dengan 401 | key salah atau kedaluwarsa | buat key baru di halaman provider |
| Sync models mengembalikan 0 model | provider butuh endpoint model yang berbeda | cek base URL; beberapa provider memakai `/v1beta` |
| Battle menampilkan "Could not reach the provider" | akun provider dihapus atau key dikosongkan | cek tab Providers |
| Kolom battle kosong tapi tidak ada error | streaming tidak berakhir | `maxTokens` mungkin terlalu kecil; naikkan di pengaturan battle |
| Leaderboard kosong | belum ada vote | cast satu battle dulu |
| Keystore "unavailable" | keystore emulator rusak | jalankan di perangkat nyata, atau reset data aplikasi |
| Gradle gagal sync | versi plugin tidak cocok | pastikan JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21 |
| Room KSP error | KSP dan Room harus sepadan | Room 2.8.5 + KSP 2.0.21-1.0.28 |

## 5. Catatan versi

Stack yang dipakai:

| Komponen | Versi |
| :--- | --- |
| AGP | 8.7.3 |
| Gradle | 8.9 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Room | 2.8.5 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| OkHttp | 4.12.0 |

Room 2.8.5 dipilih karena versi 2.6.x gagal di bawah KSP2 pada fungsi DAO
yang suspend. Kalau kamu naikkan ke stack yang lebih baru (AGP 9 + Kotlin
2.4), KSP dan Room harus ikut naik bersama, dan DSL-nya berubah — baca
`docs/01-architecture.md` bagian batasan.
