#!/usr/bin/env bash
# Ganti package com.nfsu2.androidevolved -> com.zafar.nfsu2remix di SELURUH proyek.
# Jalankan dari akar repo (folder yang berisi folder android/), lalu build ulang library native.
set -euo pipefail

OLD="com.nfsu2.androidevolved";  NEW="com.zafar.nfsu2remix"
OLD_J="Java_com_nfsu2_androidevolved_"; NEW_J="Java_com_zafar_nfsu2remix_"
JAVA="android/app/src/main/java"
CPP="android/app/src/main/cpp"

[ -d android/app ] || { echo "Jalankan dari akar repo (harus ada android/app)"; exit 1; }

# 1. Nama fungsi JNI di kode native (inilah yang harus cocok dengan package Java)
sed -i "s/${OLD_J}/${NEW_J}/g" "$CPP"/*.cpp "$CPP"/*.h 2>/dev/null || true

# 2. Pindahkan file Java ke folder baru (kalau belum)
SRC_FILE=$(find "$JAVA" -name LauncherActivity.java | head -1)
[ -n "$SRC_FILE" ] || { echo "LauncherActivity.java tidak ditemukan"; exit 1; }
SRC_DIR=$(dirname "$SRC_FILE")
DST_DIR="$JAVA/com/zafar/nfsu2remix"
if [ "$SRC_DIR" != "$DST_DIR" ]; then
  mkdir -p "$DST_DIR"
  mv "$SRC_DIR"/*.java "$DST_DIR"/
  # buang folder kosong sisa package lama
  find "$JAVA" -type d -empty -delete
fi

# 3. Ganti deklarasi package, import, dan semua penyebutan nama lama di Java + Gradle + manifest
grep -rl --include='*.java' --include='*.gradle' --include='*.xml' "$OLD" android/app 2>/dev/null \
  | xargs -r sed -i "s/${OLD//./\\.}/${NEW}/g"

# 4. Cek sisa
echo "--- sisa penyebutan nama lama (harus kosong, kecuali docs/) ---"
grep -rn "nfsu2.androidevolved\|nfsu2_androidevolved" android app 2>/dev/null | grep -v '^docs/' || echo "(kosong)"
echo "Selesai. Sekarang build native: lihat langkah di chat."
