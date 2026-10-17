# 02 — Provider

Aplikasi ini tidak terhubung ke satu pun backend. Kamu menambahkan
"provider account" — satu key untuk satu provider — lalu model yang tersedia
ditarik langsung dari provider tersebut.

## Menambahkan provider

1. Tab **Providers** → tombol **+**.
2. Pilih provider dari daftar.
3. Tempel API key. Base URL terisi otomatis; ubah hanya kalau kamu memakai
   endpoint sendiri.
4. **Add**.
5. Tekan **Sync models** pada kartu provider.

Langkah kelima memanggil `GET {base}/models` dan menyimpan hasilnya. Model
yang tidak ada di daftar itu tidak bisa dipilih untuk battle.

## Provider bawaan

| Provider | Base URL | Key |
| :--- | :--- | :--- |
| OpenRouter | `https://openrouter.ai/api/v1` | `sk-or-v1-…` |
| OpenAI | `https://api.openai.com/v1` | `sk-…` |
| Anthropic | `https://api.anthropic.com` | `sk-ant-…` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | `AIza…` |
| Groq | `https://api.groq.com/openai/v1` | `gsk_…` |
| Together AI | `https://api.together.xyz/v1` | — |
| Mistral | `https://api.mistral.ai/v1` | — |
| DeepSeek | `https://api.deepseek.com/v1` | `sk-…` |
| Ollama (lokal) | `http://10.0.2.2:11434/v1` | tidak perlu |
| Custom | basis URL kamu | biasanya tidak perlu |

### Satu key, banyak provider

OpenRouter adalah cara paling hemat untuk mencoba banyak model: satu key
memberi akses ke ratusan model dari banyak pembuat, semuanya lewat dialek
OpenAI yang sama.

### Menjalankan model lokal

Ollama di mesin pengembangmu bisa dijalankan begini:

```bash
ollama serve
ollama pull llama3.2
```

Lalu tambahkan provider **Ollama (local)** dengan base URL
`http://10.0.2.2:11434/v1` (emulator) atau `http://<ip-mu>:11434/v1` (perangkat
nyata di jaringan yang sama). Tak ada key.

## Menambah provider baru

Semua yang bicara dialek OpenAI berbagi satu adapter, jadi provider
OpenAI-compatible baru cukup satu entri di
`core/providers/ProviderCatalog.kt`:

```kotlin
ProviderSpec(
    id = "my-provider",
    displayName = "My Provider",
    defaultBaseUrl = "https://api.example.com/v1",
    dialect = Dialect.OPENAI_COMPATIBLE,
    keyUrl = "https://example.com/keys",
    keyHint = "…",
)
```

Untuk dialek yang berbeda, tambahkan implementasi `ChatProvider` baru dan
satu cabang di `ProviderFactory.create()`.

## Detail dialek

### OpenAI-compatible

- Endpoint: `POST {base}/chat/completions`
- Header: `Authorization: Bearer <key>`
- Streaming: SSE, `data: {json}` per potongan, diakhiri `data: [DONE]`
- Frame usage dikirim terpisah bila `stream_options.include_usage` aktif
- `usage.cost` dilaporkan OpenRouter dalam dolar

### Anthropic

- Endpoint: `POST {base}/v1/messages`
- Header: `x-api-key: <key>` dan `anthropic-version: 2023-06-01`
- System prompt adalah field top-level, bukan sebuah message
- Message hanya boleh memakai role `user` dan `assistant`
- Token input datang di `message_start`, token output di `message_delta`
- `max_tokens` wajib ada; default-nya 4096

### Gemini

- Endpoint: `POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse`
- Header: `x-goog-api-key: <key>`
- Role dinamai `user` dan `model`, bukan `assistant`
- System prompt masuk ke `systemInstruction`
- Token usage dikirim di `usageMetadata` pada setiap potongan

## Model tiruan

`mock/` berisi provider tiruan yang bicara dialek OpenAI, dengan empat
model yang masing-masing punya karakter berbeda:

| Model | Karakter |
| :--- | :--- |
| `mock-concise` | Pendek dan berpoin |
| `mock-detailed` | Panjang dan tersusun |
| `mock-hedged` | Berhati-hati dan menunda |
| `mock-wrong` | Dulu salah, lalu mengoreksi diri |

Tambahkan sebagai provider **Custom (OpenAI-compatible)** dengan base URL
`http://10.0.2.2:8787/v1`, lalu **Sync models**. Battle antara dua model
tiruan memberi jawaban yang jelas berbeda, jadi alur vote bisa diuji tanpa
menggunakan uang sungguhan.

## Biaya

Battle memakai key-mu, jadi dua model yang mahal bisa berali dua kali biaya satu
percakapan. Aplikasi menampilkan biaya per sisi bila provider melaporkannya
— OpenRouter melaporkannya di `usage.cost`. Provider lain tidak melapor,
jadi biaya tidak bisa dihitung di sisi aplikasi tanpa mengetahui harga model.

Pengaturan `maxTokens` di layar battle adalah rem pengaman yang paling
berguna: nilai default 2048, dan model yang supporting lebih panjang tetap
akan dihentikan di situ.
