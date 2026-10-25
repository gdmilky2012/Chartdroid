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
