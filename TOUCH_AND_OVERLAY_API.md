esac
```

Untuk proses panjang, lakukan polling dengan jeda yang wajar agar tidak membuat loop CPU
yang sibuk. Karena pembacaan default menghapus semua event yang dikembalikan, gunakan satu
konsumen event atau tambahkan `--ez clear false` ketika beberapa proses hanya perlu mengamati.

## 8. Pengujian dan pemecahan masalah
## 9. Pengujian dan pemecahan masalah

Urutan smoke test pada perangkat:

```sh
"$API" Touch --es action status
"$API" Overlay --es action permission
"$API" Overlay --es action start --es text "Tes overlay"
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

## 9. Build, stop, dan uninstall
## 10. Build, stop, dan uninstall

Build debug dari root repository:
