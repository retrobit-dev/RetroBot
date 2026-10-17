# 01 — Arsitektur

## Gambaran umum

```
┌──────────────────────────────────────────────┐
│  Android app (Kotlin + Compose)              │
│                                              │
│  Battle ──► BattleViewModel ──► BattleEngine │
│                    │                         │
│                    ├─► ArenaRepository       │
│                    │     ├─ ProviderAccountDao│
│                    │     ├─ ModelDao          │
│                    │     ├─ VoteDao           │
│                    │     └─ RatingDao         │
│                    │                         │
│                    └─► ChatProvider          │
│                          ├─ OpenAiCompatible │
│                          ├─ Anthropic        │
│                          └─ Gemini           │
│                                              │
│  KeyVault (Android Keystore, AES-GCM)        │
└──────────────────────────────────────────────┘
```

## Prinsip

### Kunci tidak pernah meninggalkan perangkat

API key dienkripsi dengan kunci AES-256-GCM yang dibuat di Android Keystore.
Yang tersimpan di database Room hanyalah ciphertext; hanya `KeyVault` yang
bisa mengubahnya kembali. Kunci induknya sendiri tidak pernah keluar dari
Keystore, jadi tidak bisa dicuri bersama dump database.

> Catatan: `androidx.security:security-crypto` sudah deprecated upstream
> (1.1.0-alpha07), jadi aplikasi ini berbicara langsung dengan Keystore lewat
> `javax.crypto`, bukan lewat pustaka itu.

### Log vote adalah sumber kebenaran

Tabel `votes` adalah data aslinya. Tabel `ratings` hanyalah cache hasil
perhitungan Elo di atas log itu. Konsekuensinya:

- Perhitungan rating bisa diubah tanpa migrasi database — cukup
  `recomputeRatings()`.
- Leaderboard bisa dibangun ulang dari nol kapan saja.
- Sinkronisasi ke server (nanti) hanya perlu mengirim log vote.

### Battle benar-benar buta

Pemilihan model dan penempatan model dipisahkan:

- `slotA` dan `slotB` adalah model yang **kamu** pilih, dan namanya selalu
  terlihat.
- Sebuah lemparan koin menentukan model mana yang mengisi kolom kiri dan
  mana yang mengisi kolom kanan.
- Kolom diberi label "Response A" dan "Response B" — bukan nama model.
- Nama model baru muncul setelah vote dicatat.

Dengan begitu, pilihanmu benar-benar blind: tidak ada bias dari
memperhatikan model mana yang wrote lebih cepat.

### Kegagalan satu sisi tidak menjatuhkan sisi lain

Kedua sisi battle berjalan sebagai dua coroutine terpisah dalam satu
`coroutineScope`. Kalau satu provider gagal, sisi itu melaporkan
`BattleEvent.Failed` dan sisi lainnya terus berjalan sampai selesai.

## Lapisan

| Paket | Tanggung jawab |
| :--- | :--- |
| `core/net` | Pembaca SSE yang bisa dibatalkan, error HTTP provider |
| `core/providers` | Antarmuka `ChatProvider` dan tiga adapter dialek |
| `core/arena` | Perhitungan Elo dan mesin battle |
| `core/security` | `KeyVault` (Keystore) dan `SettingsStore` |
| `data` | Entitas Room, DAO, dan `ArenaRepository` |
| `feature/battle` | Layar battle buta |
| `feature/chat` | Chat satu-lawan-satu |
| `feature/leaderboard` | Papan peringkat |
| `feature/keys` | Manajemen akun provider |
| `di` | `AppContainer` — DI manual, tanpa annotation processor |

## Alur satu battle

1. `BattleScreen` memanggil `BattleViewModel.start()`.
2. ViewModel mengundi lotre penempatan, lalu memanggil `BattleEngine.run()`.
3. `BattleEngine` membangun permintaan untuk kedua model dan menjalankan
   keduanya secara paralel.
4. Setiap potongan teks memicu `BattleEvent.Delta`, yang memperbarui state
   UI secara inkremental.
5. Model yang selesai lebih dulu tidak perlu menunggu yang lain.
6. Setelah keduanya selesai, kamu memilih. `vote()` menulis baris ke
   `votes` lalu memperbarui cache `ratings`.

## Batas iterasi

Battle tidak punya loop: satu prompt menghasilkan satu jawaban dari masing-masing
model. Batas iterasi ada di level vote — tidak ada voting yang otomatis.
Batas token ada di pengaturan battle (`maxTokens`), yang menjaga agar
battle dengan model mahal tidak menghabiskan kuota tanpa sengaja.

## Yang belum ada

- **Sinkronisasi leaderboard antar perangkat.** Rating bersifat lokal. Log
  vote sudah cukup untuk ditambahkan sinkronisasi nanti.
- **Battle lebih dari dua model.** Antarmukanya sudah dua kolom; menambah
  model ketiga berarti mengubah tata letak dan aturan vote.
- **Riwayat battle.** Vote disimpan, tapi belum ada layar untuk menelusurinya.
- **Streaming parsial yang bisa diinterrupt dengan rapi.** Panggilan HTTP
  sudah bisa dibatalkan; belum ada tombol "hentikan di tengah kalimat"
  yang menyimpan jawaban parsial.
