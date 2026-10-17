# RetroBot

Aplikasi Android **arena model** — meniru pengalaman arena.ai, tapi tanpa
memakai arena.ai sama sekali. Kamu membawa API key sendiri, aplikasinya
menjalankan dua model secara-blind, kamu memilih yang lebih bagus, dan
leaderboard Elo menyusun peringkat dari pilihanmu.

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  Model A     │   │  Model B     │   │  Model C     │
│  (buta)      │   │  (buta)      │   │              │
└──────┬───────┘   └──────┬───────┘   └──────────────┘
       │                  │
       └────────┬─────────┘
                ▼
        pilihanmu → rating Elo → leaderboard
```

## Yang membedakan dari aplikasi chat biasa

- **Battle buta.** Kamu memilih dua model, satu pertanyaan diberikan kepada
  keduanya, dan identitas model disembunyikan sampai kamu memilih. Sebuah
  lemparan koin menentukan model mana yang menduduki kolom kiri.
- **Rating Elo.** Setiap pilihan menambah rating model yang menang dan
  mengurangi yang kalah. Papan peringkat dibangun dari pilihanmu sendiri.
- **Key milikmu.** Semua API key disimpan terenkripsi di perangkat, di bawah
  kunci Android Keystore. Tidak ada server, tidak ada akun, tidak ada key
  yang terkirim ke mana pun selain provider yang kamu pilih.

## Struktur repositori

| Folder | Isi |
| :--- | :--- |
| `android/` | Aplikasi Kotlin + Jetpack Compose |
| `mock/` | Provider tiruan yang bicara dialek OpenAI — untuk menguji tanpa API key |
| `scripts/` | Pemeriksaan statis untuk kode Kotlin |
| `docs/` | Arsitektur, provider, dan runbook |

## Fitur

- **Battle** — dua model, satu prompt, streaming berdampingan, vote buta, dan
  pengungkapan identitas setelah vote.
- **Chat** — percakapan satu-lawan-satu dengan model pilihanmu.
- **Ranks** — leaderboard Elo dengan baris confidence dan catatan kapan
  sebuah peringkat belum bisa dipercaya.
- **Providers** — kelola akun provider: OpenRouter, OpenAI, Anthropic,
  Gemini, Groq, Together, Mistral, DeepSeek, Ollama lokal, atau endpoint
  OpenAI-compatible apa pun.

### Provider yang didukung

| Dialek | Provider |
| :--- | :--- |
| OpenAI-compatible | OpenRouter, OpenAI, Groq, Together, Mistral, DeepSeek, Ollama, dan endpoint kustom |
| Anthropic Messages | Anthropic |
| Gemini generateContent | Google Gemini |

Semua yang mendukung OpenAI-compatible berbagi satu adapter, jadi menambah
provider cukup menambah satu baris di `ProviderCatalog`.

## Mulai cepat

```bash
# 1. Jalankan provider tiruan (tanpa API key)
cd mock && npm start          # http://localhost:8787/v1
```

Di aplikasi: tab **Providers** → **Add a provider** → pilih
**Custom (OpenAI-compatible)**, base URL `http://10.0.2.2:8787/v1`, key
boleh dikosongkan → **Sync models** → kamu akan melihat `mock-concise`,
`mock-detailed`, `mock-hedged`, dan `mock-wrong`.

(`10.0.2.2` adalah mesin pengembang dilihat dari emulator Android.)

Lalu buka tab **Battle**, pilih dua model, kirim pertanyaan, dan vote.

Dengan API key sungguhan: pilih provider yang kamu pakai, tempel key-nya,
tekan **Sync models**, lalu battling seperti biasa.

## Test

```bash
npm run check      # pemeriksaan statis Kotlin + seluruh test
npm test           # hanya test
```

- `mock/test/provider.test.js` — provider tiruan: daftar model, streaming,
  dan penolakan model yang tidak dikenal.
- `mock/test/arena.test.js` — alur arena: battle → vote → Elo → leaderboard,
  dengan nilai yang dihitung tangan.
- `android/app/src/test/` — unit test Kotlin untuk rating Elo dan parser
  markdown.

`scripts/check-kotlin.js` dan `scripts/check-symbols.js` memeriksa 34 file
Kotlin untuk keseimbangan kurung, import yang tidak resolve, deklarasi
ganda, dan simbol proyek yang tidak terimport.

## Status dan batasan

- **Kode Android belum pernah dikompilasi.** Lingkungan tempat repo ini
  dibuat tidak punya JDK maupun Android SDK, jadi Kotlin-nya belum melewati
  `assembleDebug`. Sudah ditulis lengkap dan diperiksa secara statis, tapi
  siapkan diri memperbaiki satu-dua compile error di build pertama.
- **Leaderboard-nya lokal.** Rating berasal dari vote-mu sendiri, belum
  ada sinkronisasi antar perangkat. Arsitektur votenya sudah mendukung sinkronisasi
  nanti: log vote adalah sumber kebenaran, tabel rating hanyalah cache.
- **Model yang tersedia bergantung pada key-mu.** Tidak ada daftar model
  hardcoded; aplikasi mengambilnya dari provider.
- **Biaya asli.** Battle memakai key-mu, jadi dua model yang besar bisa
  mahal. Aplikasi menampilkan biaya bila provider melaporkannya (OpenRouter
  melaporkannya per permintaan).

## Lisensi

MIT.
