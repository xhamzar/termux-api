# Panduan Touch dan Floating Overlay Termux:API

Dokumen ini menjelaskan penggunaan API `Touch` dan `Overlay` dari Termux, mulai dari
instalasi sampai penghentian layanan. Kedua API memakai IPC Termux:API yang sudah ada;
tidak membutuhkan root dan tidak menjalankan daemon permanen.

> **Peringatan keamanan:** Touch API menggunakan layanan Aksesibilitas sehingga dapat
> mengirim gestur ke layar. Aktifkan hanya pada perangkat dan skrip yang Anda percaya.

## 1. Prasyarat

1. Instal aplikasi Termux dan Termux:API dari sumber yang kompatibel.
2. Instal paket klien di Termux:

   ```sh
   pkg update
   pkg install termux-api
   ```

3. Untuk build lokal, Termux dan Termux:API harus ditandatangani dengan key yang sama.
   APK debug tidak dapat menggantikan Termux:API rilis yang bertanda tangan berbeda.

Semua contoh menggunakan helper internal berikut. Variabel ini hanya berlaku dalam sesi
shell saat ini; tambahkan ke skrip Anda bila diperlukan.

```sh
API="$PREFIX/libexec/termux-api"
```

Output setiap perintah berbentuk JSON. Tambahkan `| jq` bila paket `jq` telah diinstal agar
hasilnya lebih mudah dibaca.

## 2. Mengaktifkan Touch API

1. Jalankan pemeriksaan status:

   ```sh
   "$API" Touch --es action status
   ```

2. Buka **Android Settings → Accessibility → Installed apps / Downloaded apps →
   Termux:API Touch Service**, lalu aktifkan layanan tersebut.
3. Jalankan `status` kembali dan pastikan nilai `enabled` adalah `true`.

Android mewajibkan pengguna mengaktifkan Accessibility Service sendiri. Tidak ada command
Termux untuk mem-bypass atau mengaktifkan permission ini.

Semua koordinat Touch memakai piksel layar: titik `(0, 0)` adalah kiri atas layar. Periksa
resolusi perangkat, misalnya dengan `wm size`, sebelum memakai koordinat tetap.

## 3. Perintah Touch

### Tap

```sh
"$API" Touch --es action tap --ei x 540 --ei y 1200
```

### Tap banyak titik

Tap berikut dijalankan berurutan:

```sh
"$API" Touch --es action multi_tap \
  --es touches '[{"x":250,"y":800},{"x":800,"y":800}]'
```

Tambahkan `--ez simultaneous true` untuk mengirim hingga 10 titik sebagai satu gestur
bersamaan, misalnya pinch dua jari:

```sh
"$API" Touch --es action multi_tap --ez simultaneous true \
  --es touches '[{"x":350,"y":900},{"x":730,"y":900}]'
```

### Swipe

`duration` memakai milidetik dan harus berada pada rentang 1–60000.

```sh
"$API" Touch --es action swipe \
  --ei x1 540 --ei y1 1600 --ei x2 540 --ei y2 500 --el duration 500
```

### Tekan lama

```sh
"$API" Touch --es action long_press \
  --ei x 540 --ei y 1200 --el duration 1200
```

### Macro

Macro menerima JSON array. Action yang didukung adalah `tap`, `swipe`, `long_press`, dan
`wait`. Field `delay` pada `wait`, serta `duration` pada swipe/long press, memakai
milidetik dan maksimal 60000.

```sh
"$API" Touch --es action macro --es name "open-menu" --es macro '[
  {"action":"tap","x":540,"y":1800},
  {"action":"wait","delay":400},
  {"action":"swipe","x1":540,"y1":1600,"x2":540,"y2":650,"duration":450},
  {"action":"long_press","x":300,"y":900,"duration":900}
]'
```

Jika layanan Aksesibilitas belum aktif, koordinat negatif, atau Android membatalkan gestur,
API mengembalikan JSON dengan `success: false` dan pesan `error`.

## 4. Mengaktifkan Floating Overlay

Overlay adalah panel yang dapat digeser di atas aplikasi lain. Ia memakai foreground service
selama sedang aktif agar Android tidak segera menghentikannya.

1. Jalankan command ini untuk membuka halaman permission:

   ```sh
   "$API" Overlay --es action permission
   ```

2. Aktifkan **Display over other apps** untuk Termux:API.
3. Pastikan status permission:

   ```sh
   "$API" Overlay --es action status
   ```

Tidak diperlukan root. Bila permission belum diberikan, action `start` dan `show` akan
menampilkan error yang menjelaskan cara mengaktifkannya.

## 5. Perintah dasar Overlay

Koordinat dan ukuran overlay menggunakan piksel layar. Action `update`, `move`, `resize`,
`hide`, dan `stop` membutuhkan overlay yang telah dimulai.

```sh
# Mulai dan tampilkan panel.
"$API" Overlay --es action start --es text "Termux siap"

# Ganti teks atau tampilkan kembali panel yang tersembunyi.
"$API" Overlay --es action update --es text "Sedang bekerja"
"$API" Overlay --es action show --es text "Selesai"

# Pindahkan dan ubah ukuran.
"$API" Overlay --es action move --ei x 40 --ei y 180
"$API" Overlay --es action resize --ei width 700 --ei height 260

# Inspeksi, sembunyikan, atau hentikan.
"$API" Overlay --es action status
"$API" Overlay --es action hide
"$API" Overlay --es action stop
```

`hide` menyembunyikan UI tetapi mempertahankan service yang sudah dimulai. `stop` menghapus
window, menghentikan foreground service, dan melepaskan resource media/web.

### Progress dan tombol

`progress` bernilai 0–100; gunakan `-1` untuk menyembunyikannya. `buttons` adalah JSON
array maksimal delapan tombol dengan `id` unik dan `label`.

```sh
"$API" Overlay --es action update --es text "Mengunduh" --ei progress 35 \
  --es buttons '[{"id":"cancel","label":"Batal"},{"id":"details","label":"Detail"}]'
```

Tap panel, perpindahan panel, dan klik tombol masuk ke antrean event dalam memori:

```sh
"$API" Overlay --es action events
"$API" Overlay --es action events --ez clear false  # baca tanpa menghapus
"$API" Overlay --es action clear_events
```

Antrean menyimpan 100 event terbaru. Secara default `events` mengosongkan antrean setelah
dibaca.

## 6. Konten gambar, web, dan video

Mulai overlay terlebih dahulu sebelum memakai action `content`.

### Gambar lokal

Sumber gambar harus berupa file absolut yang dapat dibaca Termux:API dan maksimum 25 MiB.
Gambar besar di-decode secara tersampling untuk menekan pemakaian memori.

```sh
"$API" Overlay --es action content --es type image \
  --es source "$HOME/storage/shared/Pictures/status.png"
```

### Halaman web

Web hanya menerima URL HTTPS. JavaScript nonaktif secara default dan WebView tidak dapat
membuka file lokal atau `content://`. Aktifkan `focusable` hanya untuk formulir yang perlu
keyboard; pada mode ini overlay mendapat fokus input dari aplikasi di bawahnya.

```sh
"$API" Overlay --es action content --es type web \
  --es source "https://example.com/dashboard"

"$API" Overlay --es action content --es type web \
  --es source "https://example.com/form" \
  --ez javascript true --ez focusable true
```

### Video

Video dapat memakai file lokal absolut atau URL HTTPS. Playback berjalan asynchronous dan
akan pause bila overlay disembunyikan atau Android mengambil audio focus.

```sh
"$API" Overlay --es action content --es type video \
  --es source "$HOME/storage/shared/Movies/demo.mp4" --ez autoplay true

"$API" Overlay --es action pause
"$API" Overlay --es action seek --ei position_ms 30000
"$API" Overlay --es action volume --ei volume 50
"$API" Overlay --es action play
```

`volume` berada pada rentang 0–100. Gunakan `clear_content` untuk menghapus konten aktif
tanpa menghentikan panel:

```sh
"$API" Overlay --es action clear_content
```

`status` melaporkan `content_type`, `content_source`, `playback_state`, `position_ms`,
`duration_ms`, `volume`, `javascript_enabled`, dan `focusable` selain status window biasa.

## 7. Contoh skrip lengkap

Contoh ini membuat panel status download, menampilkan tombol, kemudian membersihkannya.

```sh
#!/data/data/com.termux/files/usr/bin/sh
set -eu

API="$PREFIX/libexec/termux-api"

"$API" Overlay --es action start --es text "Memulai tugas" --ei x 24 --ei y 120
"$API" Overlay --es action update --es text "Mengunduh" --ei progress 10 \
  --es buttons '[{"id":"cancel","label":"Batal"}]'

# Jalankan pekerjaan Anda di sini, lalu perbarui progress sesuai kebutuhan.
"$API" Overlay --es action update --es text "Memproses" --ei progress 75

"$API" Overlay --es action update --es text "Selesai" --ei progress 100 \
  --es buttons '[]'
sleep 2
"$API" Overlay --es action stop
```

Untuk memakai event tombol sebagai sinyal, panggil `events`, parse JSON hasilnya, dan
tentukan sendiri action aplikasi berdasarkan `id`. Tombol tidak pernah mengeksekusi shell
command secara otomatis.

## 8. Pengujian dan pemecahan masalah

Urutan smoke test pada perangkat:

```sh
"$API" Touch --es action status
"$API" Overlay --es action permission
"$API" Overlay --es action start --es text "Tes overlay"
"$API" Overlay --es action move --ei x 30 --ei y 120
"$API" Overlay --es action events
"$API" Overlay --es action stop
```

| Gejala | Penyebab dan solusi |
| --- | --- |
| `Touch service is disabled` | Aktifkan **Termux:API Touch Service** di Android Accessibility settings. |
| Permission overlay ditolak | Jalankan action `permission`, lalu aktifkan **Display over other apps** untuk Termux:API. |
| `Overlay service is not running` | Jalankan `Overlay --es action start` sebelum `update`, `content`, atau kontrol video. |
| Gambar/video lokal gagal | Gunakan path absolut, cek file dapat dibaca, dan berikan izin storage yang diperlukan perangkat. |
| Web gagal dimuat | Gunakan URL HTTPS valid; `http://`, `file://`, dan `content://` sengaja ditolak. |
| Perintah tidak ditemukan | Instal `termux-api` dan gunakan `$PREFIX/libexec/termux-api` seperti contoh. |
| Broadcast tidak diizinkan | Pastikan Termux dan Termux:API memakai signature key yang sama. |

## 9. Build, stop, dan uninstall

Build debug dari root repository:

```sh
./gradlew assembleDebug
```

APK berada di `app/build/outputs/apk/debug/`. Instal hanya ke lingkungan Termux yang memakai
signature key kompatibel.

Hentikan overlay dengan bersih dari Termux:

```sh
"$API" Overlay --es action stop
```

Sebelum uninstall, hentikan overlay dan nonaktifkan Touch Service pada Android Accessibility
settings. Dari komputer dengan ADB, uninstall Termux:API memakai:

```sh
adb uninstall com.termux.api
```

Uninstall menghapus data Termux:API. Jika kemudian mengganti sumber APK/signature Termux,
ikuti panduan instalasi Termux untuk menghapus atau memasang ulang plugin yang tidak
kompatibel.
