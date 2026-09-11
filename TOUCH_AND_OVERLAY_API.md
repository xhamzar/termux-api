  --ei padding 14 \
  --ei elevation 10 \
  --es text_align center \
  --ez show_status false
```

| Opsi | Nilai | Keterangan |
| --- | --- | --- |
| `background_color` | `#RRGGBB` / `#AARRGGBB` | Warna dasar panel. Alpha dari `#AARRGGBB` menjadi nilai awal `background_opacity`. |
| `background_opacity` | 0–100 | Transparansi latar saja; teks dan media tetap terlihat. |
| `text_color` | `#RRGGBB` / `#AARRGGBB` | Warna teks utama. |
| `status_color` | `#RRGGBB` / `#AARRGGBB` | Warna teks status/tap di bagian bawah. |
| `border_color` | `#RRGGBB` / `#AARRGGBB` | Warna border dan indikator progress. |
| `button_color` | `#RRGGBB` / `#AARRGGBB` | Warna latar tombol yang dibuat melalui `buttons`. |
| `button_text_color` | `#RRGGBB` / `#AARRGGBB` | Warna label tombol. |
| `opacity` | 1–100 | Transparansi seluruh overlay, termasuk teks dan media. |
| `text_size` | 8–72 | Ukuran teks utama dalam `sp`. |
| `corner_radius` | 0–100 | Radius sudut dalam `dp`. |
| `border_width` | 0–16 | Tebal border dalam `dp`; gunakan 0 untuk tanpa border. |
| `padding` | 0–64 | Jarak isi dari tepi panel dalam `dp`. |
| `elevation` | 0–32 | Elevasi/bayangan panel dalam `dp`. |
| `text_align` | `start`, `center`, `end` | Perataan teks utama. |
| `show_status` | boolean | Menampilkan atau menyembunyikan baris status bawah. |
| `draggable` | boolean | Mengizinkan panel dipindahkan dengan gestur drag. |
| `touchable` | boolean | Jika `false`, semua sentuhan diteruskan ke aplikasi di bawah overlay. |

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
```

`status` melaporkan `content_type`, `content_source`, `playback_state`, `position_ms`,
`duration_ms`, `volume`, `javascript_enabled`, dan `focusable` selain status window biasa.
`duration_ms`, `volume`, `javascript_enabled`, `focusable`, serta object `style` selain status
window biasa.

## 7. Contoh skrip lengkap
