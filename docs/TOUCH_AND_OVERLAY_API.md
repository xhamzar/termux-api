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

### Format argumen helper

Gunakan tipe argumen yang sesuai agar Android menerima nilainya dengan benar:

| Flag | Tipe | Contoh |
| --- | --- | --- |
| `--es` | string | `--es action tap`, `--es text "Selesai"` |
| `--ei` | integer 32-bit | `--ei x 540`, `--ei progress 75` |
| `--el` | integer 64-bit | `--el duration 1200` |
| `--ez` | boolean | `--ez simultaneous true` |

Nama action tidak membedakan huruf besar/kecil setelah diterima API, tetapi seluruh contoh
memakai huruf kecil. Gunakan `true` atau `false` untuk boolean. JSON pada `touches`, `macro`,
dan `buttons` sebaiknya diapit tanda kutip tunggal agar shell tidak mengubah isinya.

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

### Referensi action Touch

| Action | Parameter | Default / hasil |
| --- | --- | --- |
| `status` | Tidak ada | Melaporkan `enabled`; tidak mengirim gesture. |
| `tap` | `x`, `y` | Tap tetap selama 50 ms. Ini juga action default bila `action` tidak diberikan. |
| `multi_tap` | `touches`, opsional `simultaneous` | 1–10 titik; berurutan jika `simultaneous=false`. Alias: `multi-tap`. |
| `swipe` | `x1`, `y1`, `x2`, `y2`, opsional `duration` | Durasi default 500 ms. |
| `long_press` | `x`, `y`, opsional `duration` | Durasi default 1000 ms. Alias: `long-press`. |
| `macro` | `macro`, opsional `name` | Menjalankan langkah JSON secara berurutan. |

Semua koordinat wajib integer nol atau lebih. API tidak membatasi koordinat terhadap ukuran
layar; koordinat di luar layar dapat ditolak Android. Durasi gesture wajib 1–60000 ms.

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

Tambahkan `--ez simultaneous true` untuk mengirim hingga 10 tap diam sebagai satu gesture
multi-jari. Ini berguna untuk menekan beberapa titik bersamaan, tetapi bukan pinch/zoom karena
setiap titik tidak memiliki lintasan gerak:

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
milidetik dan maksimal 60000. `delay` wajib ada pada langkah `wait` dan boleh bernilai 0.
`name` hanya label opsional yang dikembalikan dalam output.

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

### Output JSON Touch

Output sukses disesuaikan dengan action:

| Action | Field hasil tambahan |
| --- | --- |
| `status` | `action`, `enabled`, dan `message` bila belum aktif |
| `tap` | `action`, `x`, `y` |
| `multi_tap` | `action`, `count`, `simultaneous` |
| `swipe` | `action`, `from_x`, `from_y`, `to_x`, `to_y`, `duration` |
| `long_press` | `action`, `x`, `y`, `duration` |
| `macro` | `action`, opsional `name`, `steps` |

Contoh sukses dan gagal:

```json
{"success":true,"action":"tap","x":540,"y":1200}
```

```json
{"success":false,"error":"Touch service is disabled. Enable Termux:API Touch Service in Android Settings > Accessibility"}
```

Touch API menunggu callback Android sebelum mengembalikan hasil. Gesture lain dari pengguna,
perubahan layar, atau kebijakan aplikasi target dapat menyebabkan gesture dibatalkan. Macro
berhenti pada langkah pertama yang gagal dan tidak melakukan rollback langkah sebelumnya.

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

### Referensi seluruh action Overlay

| Action | Parameter utama | Perlu overlay aktif | Fungsi |
| --- | --- | --- | --- |
| `status` | Tidak ada | Tidak | Membaca permission, window, media, dan style; action default bila dihilangkan. |
| `permission` | Tidak ada | Tidak | Membuka halaman **Display over other apps** bila izin belum aktif. |
| `start` | Parameter panel opsional | Tidak | Memulai foreground service dan menampilkan overlay. |
| `show` | Parameter panel opsional | Tidak | Membuat atau menampilkan kembali overlay. |
| `update` | Parameter panel opsional | Ya | Memperbarui teks, posisi, ukuran, progress, tombol, fokus, atau style. |
| `move` | `x`, `y` | Ya | Memindahkan overlay. |
| `resize` | `width`, `height` | Ya | Mengubah ukuran overlay. |
| `style` | Parameter style | Ya | Mengubah tampilan/perilaku input tanpa membuat ulang overlay. |
| `reset_style` | Tidak ada | Ya | Mengembalikan seluruh style dan input ke default. Alias: `reset-style`. |
| `hide` | Tidak ada | Ya | Menyembunyikan window, tetapi mempertahankan service. |
| `content` | `type`, `source`, opsi konten/panel | Ya | Menampilkan gambar, halaman HTTPS, atau video. |
| `clear_content` | Tidak ada | Ya | Melepas konten dan fokus tanpa menghentikan panel. Alias: `clear-content`. |
| `play` / `pause` | Tidak ada | Ya | Mengontrol video yang dimuat. |
| `seek` | `position_ms` | Ya | Memindahkan posisi video ke nilai nonnegatif. |
| `volume` | `volume` | Ya | Mengatur volume video 0–100. |
| `events` | Opsional `clear` | Tidak | Membaca event; default `clear=true`. |
| `clear_events` | Tidak ada | Tidak | Menghapus antrean event. Alias: `clear-events`. |
| `stop` | Tidak ada | Tidak | Menghapus overlay dan menghentikan service; aman dipanggil berulang. |

Koordinat dan ukuran overlay menggunakan piksel layar. Ikuti kolom **Perlu overlay aktif**
pada tabel di atas; `stop` tetap sukses ketika service sudah berhenti.

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

### Parameter umum panel

Parameter berikut dapat dipakai pada `start`, `show`, dan `update`. Action `content` juga
menerimanya selain parameter khusus konten.

| Parameter | Tipe / batas | Keterangan |
| --- | --- | --- |
| `text` | string, maksimal 4096 karakter | Teks utama; teks default dipakai bila kosong. |
| `x`, `y` | integer ≥ 0 | Posisi kiri atas dalam piksel; posisi dikunci agar tetap di layar. |
| `width`, `height` | integer 1–10000 | Ukuran window dalam piksel. |
| `progress` | `-1` atau 0–100 | `-1` menyembunyikan progress bar. |
| `buttons` | string JSON | Array maksimal 8 object `{id,label}`; `[]` menyembunyikan tombol. |
| `focusable` | boolean | Mengizinkan overlay menerima fokus/keyboard; memerlukan `touchable=true`. |
| parameter style | lihat tabel style | Dapat diterapkan bersamaan dengan update lain. |

Setiap `id` tombol harus unik dan panjangnya 1–64 karakter; `label` harus 1–48 karakter.
Jika tombol ditambahkan tanpa `height`, tinggi panel akan dinaikkan setidaknya 160 dp. Jika
konten ditambahkan tanpa `height`, tinggi panel akan dinaikkan setidaknya 320 dp.

### Tampilan, warna, dan transparansi

Gunakan action `style` untuk mengubah tampilan tanpa membuat ulang overlay. Opsi style juga
dapat dikirim bersama action `start`, `show`, `update`, atau `content`. Warna wajib memakai
format `#RRGGBB` atau `#AARRGGBB`; tanda `#` sebaiknya selalu diapit tanda kutip di shell.

```sh
"$API" Overlay --es action style \
  --es background_color '#101820' \
  --ei background_opacity 65 \
  --es text_color '#00FF88' \
  --es status_color '#B0BEC5' \
  --es border_color '#00FF88' \
  --es button_color '#355070' \
  --es button_text_color '#FFFFFF' \
  --ei text_size 18 \
  --ei corner_radius 20 \
  --ei border_width 2 \
  --ei padding 14 \
  --ei elevation 10 \
  --es text_align center \
  --ez show_status false
```

| Opsi | Nilai | Default | Keterangan |
| --- | --- | --- | --- |
| `background_color` | `#RRGGBB` / `#AARRGGBB` | `#FF202124` | Warna dasar. Alpha dari `#AARRGGBB` menjadi `background_opacity`; `#RRGGBB` berarti 100%. |
| `background_opacity` | 0–100 | 92 | Transparansi latar saja; teks dan media tetap terlihat. |
| `text_color` | `#RRGGBB` / `#AARRGGBB` | `#FFFFFFFF` | Warna teks utama. |
| `status_color` | `#RRGGBB` / `#AARRGGBB` | `#FFCCCCCC` | Warna teks status/tap di bagian bawah. |
| `border_color` | `#RRGGBB` / `#AARRGGBB` | `#DC78AAFF` | Warna border dan indikator progress. |
| `button_color` | `#RRGGBB` / `#AARRGGBB` | `#FF4F6B9A` | Warna latar tombol. |
| `button_text_color` | `#RRGGBB` / `#AARRGGBB` | `#FFFFFFFF` | Warna label tombol. |
| `opacity` | 1–100 | 100 | Transparansi seluruh overlay, termasuk teks dan media. |
| `text_size` | 8–72 | 16 | Ukuran teks utama dalam `sp`. |
| `corner_radius` | 0–100 | 12 | Radius sudut dalam `dp`. |
| `border_width` | 0–16 | 1 | Tebal border dalam `dp`; 0 menghilangkan border. |
| `padding` | 0–64 | 12 | Jarak isi dari tepi panel dalam `dp`. |
| `elevation` | 0–32 | 8 | Elevasi/bayangan panel dalam `dp`. |
| `text_align` | `start`, `center`, `end` | `start` | Perataan teks utama. |
| `show_status` | boolean | `true` | Menampilkan atau menyembunyikan baris status bawah. |
| `draggable` | boolean | `true` | Mengizinkan panel dipindahkan dengan drag. |
| `touchable` | boolean | `true` | Jika `false`, sentuhan diteruskan ke aplikasi di bawah overlay. |
| `focusable` | boolean | `false` | Mengizinkan fokus input; dilaporkan sebagai field status tingkat atas. |

Mode HUD transparan cocok untuk menampilkan status tanpa menghalangi aplikasi lain:

```sh
"$API" Overlay --es action style \
  --ei background_opacity 0 --ei border_width 0 \
  --ez show_status false --ez draggable false --ez touchable false
```

Saat `touchable=false`, tombol, tap, drag, dan input WebView tidak dapat digunakan. Overlay
tetap bisa dikontrol dari Termux. Mode ini juga otomatis menonaktifkan `focusable` agar panel
tidak mengambil fokus input secara tidak terlihat.

Kembalikan seluruh style dan perilaku input ke nilai awal dengan:

```sh
"$API" Overlay --es action reset_style
```

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
dibaca. Event tetap dapat dibaca setelah `stop`, tetapi akan dibersihkan saat service baru
dibuat kembali.

Setiap event berisi `type`, `id`, waktu Unix dalam milidetik (`timestamp`), serta posisi
overlay (`x`, `y`) ketika event dibuat.

| `type` | `id` umum | Arti |
| --- | --- | --- |
| `tap` | `overlay` | Panel diketuk. |
| `move` | `overlay` | Drag panel selesai. |
| `button` | ID dari definisi tombol | Tombol dipilih. |
| `image_ready` / `image_error` | `image` / `decode` | Hasil decode gambar. |
| `web_loaded` / `web_error` | `page` / kode error | Hasil pemuatan halaman utama. |
| `video_ready` / `video_complete` | `video` | Video siap atau selesai. |
| `video_paused` | `audio_focus` | Autoplay/play gagal atau berhenti karena audio focus. |
| `video_error` | kode media atau `source` | Video gagal dipersiapkan/diputar. |

Contoh membaca event tombol tanpa menghapus antrean:

```sh
"$API" Overlay --es action events --ez clear false | jq '.events'
```

Contoh bagian `events` pada output:

```json
{"events":[{"type":"button","id":"cancel","timestamp":1770000000000,"x":40,"y":180}]}
```

## 6. Konten gambar, web, dan video

Mulai overlay terlebih dahulu sebelum memakai action `content`.

| `type` | `source` | Opsi khusus | Batasan |
| --- | --- | --- | --- |
| `image` | Path file absolut | Tidak ada | File harus terbaca, maksimal 25 MiB; decode dibatasi sekitar 2048 px per sisi. |
| `web` | URL HTTPS | `javascript`, `focusable` | JavaScript/DOM storage default nonaktif; akses file/content dan mixed content diblokir. |
| `video` | Path file absolut atau URL HTTPS | `autoplay`, `focusable` | `autoplay=true` secara default; format tergantung dukungan `MediaPlayer` perangkat. |

`source` maksimal 4096 karakter. URL harus mempunyai host, tidak boleh menyertakan user-info,
dan hanya boleh memakai HTTPS. File lokal harus berupa file reguler yang dapat dibaca oleh
proses Termux:API; path relatif tidak diterima.

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
`duration_ms`, `volume`, `javascript_enabled`, `focusable`, serta object `style` selain status
window biasa.

Nilai `playback_state` yang mungkin adalah `none`, `loading`, `preparing`, `ready`, `playing`,
`paused`, `completed`, dan `error`. Pemuatan gambar/web memakai sebagian state yang sama agar
dapat dipantau melalui satu field.

## 7. Output JSON Overlay

Action Overlay yang sukses mengembalikan snapshot terbaru. `status` dapat dipanggil kapan pun,
termasuk ketika permission belum diberikan atau service belum aktif.

| Field | Arti |
| --- | --- |
| `success`, opsional `error` | Hasil command dan alasan kegagalan. |
| `permission_granted` | Status permission **Display over other apps**. |
| `running`, `visible` | Status foreground service dan visibilitas window. |
| `text`, `x`, `y`, `width`, `height` | Isi, posisi, dan ukuran panel saat ini. |
| `tap_count` | Jumlah tap panel sejak service dibuat. |
| `progress` | Nilai progress; `-1` berarti tersembunyi. |
| `button_count`, `event_count` | Jumlah tombol dan event yang masih mengantre. |
| `content_type`, `content_source` | `none`, `image`, `web`, atau `video` beserta sumbernya. |
| `playback_state` | Status pemuatan/pemutaran konten. |
| `position_ms`, `duration_ms`, `volume` | Status media video. |
| `javascript_enabled`, `focusable` | Status WebView/keyboard saat ini. |
| `style` | Object berisi semua nilai style aktif. Warna dilaporkan sebagai `#AARRGGBB`. |
| `events` | Hanya ditambahkan oleh action `events`. |

Contoh snapshot ringkas dari `status`:

```json
{
  "success": true,
  "permission_granted": true,
  "running": true,
  "visible": true,
  "text": "Mengunduh",
  "x": 40,
  "y": 180,
  "width": 700,
  "height": 260,
  "tap_count": 0,
  "progress": 35,
  "button_count": 2,
  "event_count": 0,
  "content_type": "none",
  "content_source": "",
  "playback_state": "none",
  "position_ms": 0,
  "duration_ms": 0,
  "volume": 100,
  "javascript_enabled": false,
  "focusable": false,
  "style": {
    "background_color": "#FF101820",
    "background_opacity": 65,
    "text_color": "#FF00FF88",
    "status_color": "#FFCCCCCC",
    "border_color": "#FF00FF88",
    "button_color": "#FF355070",
    "button_text_color": "#FFFFFFFF",
    "opacity": 100,
    "text_size": 18,
    "corner_radius": 20,
    "border_width": 2,
    "padding": 12,
    "elevation": 8,
    "text_align": "center",
    "draggable": true,
    "touchable": true,
    "show_status": true
  }
}
```

Kegagalan validasi selalu mempunyai `success:false` dan `error`. Beberapa kegagalan yang
terjadi sebelum command dikirim ke service hanya menyertakan `permission_granted`; jangan
mengandalkan seluruh field snapshot pada respons gagal.

## 8. Contoh skrip lengkap

Contoh ini membuat panel status download, menampilkan tombol, kemudian membersihkannya.

```sh
#!/data/data/com.termux/files/usr/bin/sh
set -eu

API="$PREFIX/libexec/termux-api"

"$API" Overlay --es action start --es text "Memulai tugas" --ei x 24 --ei y 120 \
  --es background_color '#101820' --ei background_opacity 85 \
  --es text_color '#00FF88' --es border_color '#00FF88' \
  --es button_color '#355070' --es button_text_color '#FFFFFF' \
  --ei corner_radius 18 --ei border_width 2
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

Contoh polling satu event tombol dengan `jq`:

```sh
event_id=$("$API" Overlay --es action events | \
  jq -r 'first(.events[]? | select(.type == "button") | .id) // empty')

case "$event_id" in
  cancel) echo "Pengguna meminta pembatalan" ;;
  details) echo "Tampilkan detail" ;;
esac
```

Untuk proses panjang, lakukan polling dengan jeda yang wajar agar tidak membuat loop CPU
yang sibuk. Karena pembacaan default menghapus semua event yang dikembalikan, gunakan satu
konsumen event atau tambahkan `--ez clear false` ketika beberapa proses hanya perlu mengamati.

## 9. Pengujian dan pemecahan masalah

Urutan smoke test pada perangkat:

```sh
"$API" Touch --es action status
"$API" Overlay --es action permission
"$API" Overlay --es action start --es text "Tes overlay" \
  --es background_color '#202124' --ei background_opacity 70 \
  --es text_color '#FFFFFF' --es border_color '#78AAFF'
"$API" Overlay --es action move --ei x 30 --ei y 120
"$API" Overlay --es action style --ez show_status false --ei corner_radius 24
"$API" Overlay --es action status
"$API" Overlay --es action events
"$API" Overlay --es action stop
```

| Gejala | Penyebab dan solusi |
| --- | --- |
| `Touch service is disabled` | Aktifkan **Termux:API Touch Service** di Android Accessibility settings. |
| Gesture Touch dibatalkan | Pastikan koordinat berada di layar, layar tidak mati/terkunci, dan tidak ada gesture lain yang bersaing. |
| Permission overlay ditolak | Jalankan action `permission`, lalu aktifkan **Display over other apps** untuk Termux:API. |
| `Overlay service is not running` | Jalankan `Overlay --es action start` sebelum `update`, `content`, atau kontrol video. |
| Overlay tidak menerima keyboard | Gunakan `--ez focusable true` dan pastikan `touchable=true`; matikan lagi setelah input selesai. |
| Overlay menghalangi sentuhan | Gunakan HUD dengan `--ez touchable false`, atau panggil `reset_style`. |
| Gambar/video lokal gagal | Gunakan path absolut, cek file dapat dibaca, dan berikan izin storage yang diperlukan perangkat. |
| Web gagal dimuat | Gunakan URL HTTPS valid; `http://`, `file://`, dan `content://` sengaja ditolak. |
| Video tidak autoplay | Android mungkin menolak audio focus; cek event `video_paused` dan jalankan `play` kembali. |
| Perintah tidak ditemukan | Instal `termux-api` dan gunakan `$PREFIX/libexec/termux-api` seperti contoh. |
| Broadcast tidak diizinkan | Pastikan Termux dan Termux:API memakai signature key yang sama. |

## 10. Build, stop, dan uninstall

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
