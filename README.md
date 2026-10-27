# ChartDroid

Editor chart (.chart / .mid, Clone Hero) untuk Android dengan tata letak mirip Moonscraper Chart Editor.

Fitur: Guitar/Co-op/Bass/Rhythm/Keys (5 lane + open), GHL (6 lane), Drums (4/5 lane, kick, cymbal/tom, accent, ghost,
kick 2x, roll, fill); sustain, HOPO/forced, tap; star power; BPM (anchor, tap BPM, +/-), birama, section, solo, lirik;
snap 1/1-1/192; undo/redo; salin/tempel/pindah/cerminkan; pilih chord/section; salin tingkat kesulitan;
audio multi-stem, kecepatan, offset, kalibrasi, waveform, metronome, clap; validator; impor/ekspor .mid;
buka folder lagu / ekspor folder Clone Hero (notes.chart + song.ini + audio); autosave; pengaturan.

Terinspirasi dari Moonscraper Chart Editor (c) Alexander Ong, BSD-3-Clause
(https://github.com/FireFox2000000/moonscraper-chart-editor). ChartDroid bukan produk resmi Moonscraper
dan memakai nama serta ikon sendiri.

## Pasang ke GH2 Deluxe (PS2) — eksperimental

File > "Pasang ke GH2 Deluxe (PS2)…" mengonversi chart 5-fret + audio yang sedang dimuat menjadi lagu Guitar Hero II dan
menyuntikkannya ke folder `GEN` hasil ekstrak ISO GH2 Deluxe Custom Edition (`MAIN.HDR` + `MAIN_0.ARK`).
Alur mengikuti Onyx Music Game Toolkit (mtolly/onyx): MIDI GH2 (PART GUITAR/RHYTHM|BASS/EVENTS), audio VGS (PS2 ADPCM),
edit `songs.dtb`, `coop_max_scores.dtb`, `store.dtb` (bonus, harga 0), `locale.dtb`. HDR dicadangkan ke `MAIN.HDR.bak`;
data ARK hanya ditambah di akhir. Belum ada: album art, berkas lipsync `.voc`, tier setlist. Setelah itu ISO dibangun ulang di PC.

Tambahan v0.6 (v0.6.1: DTB terenkripsi GH2 didukung): target bisa langsung **file ISO** PS2 (ISO9660 ditambal di HP: ARK diperpanjang/dipindah ke akhir image,
HDR dan ukuran volume diperbarui), dan **pasang banyak lagu** sekaligus dari folder gaya Clone Hero
(File > "Pasang BANYAK lagu ke GH2 (folder)…"). Buat salinan ISO sebelum memasang.

v0.6.2: pemasangan ISO memakai jurnal (isi asli yang ditimpa disimpan, rollback otomatis bila gagal/verifikasi gagal),
ukuran volume ISO diperbarui segera setelah ARK dipindah, verifikasi HDR/ARK/ISO setelah menulis, dan menu
File > "Periksa / pulihkan ISO GH2…". Kode native RB3 (src/main/cpp) belum diaktifkan di Gradle.

### v0.6.3 — impor GH2 lebih cepat
- Dekode audio per-stem paralel, campur PCM per potongan, enkoder VAG tanpa alokasi per blok.
- Banyak lagu: disiapkan paralel (maks 3), ditulis ke ARK berurutan.
- Pemasangan ISO berikutnya tidak menyalin ulang seluruh ARK (HDR dipindah ke ujung image).
- Pemasangan ke ISO: bila hanya berkas kecil yang berada setelah ARK, berkas itu dipindah ke ujung image (bukan menyalin ulang seluruh ARK), jadi ISO hanya bertambah sebesar lagu baru.
- v0.6.4: "Periksa ISO" kini menguji baca→tulis ulang DTB (harus IDENTIK) dan menyimpan salinan DTB asli ke folder gh2_dtb untuk analisis.
- v0.6.5: menu "Periksa ISO" punya UJI A/B (tulis ulang wadah ISO tanpa lagu) untuk mengisolasi layar hitam.
- v0.6.6: Periksa ISO menampilkan entri lagu pertama (teks) + penanda DX, dan menulis songs.dtb.txt untuk analisis.
