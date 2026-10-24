import json
import os

# Sesuaikan folder tempat menyimpan file makro .echo Anda
folder_path = "/sdcard/Download/macros/"

# FPS saat makro direkam (misal: 240, 360, dsb)
RECORD_FPS = 240.0
TARGET_FPS = 240.0  # Standar Vanilla TPS GD 2.2


def clean_and_convert():
    if not os.path.exists(folder_path):
        print("Folder tidak ditemukan!")
        return

    files = [f for f in os.listdir(folder_path) if f.endswith(".echo")]
    for filename in files:
        file_path = os.path.join(folder_path, filename)
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            if "inputs" in data:
                raw_inputs = data["inputs"]
                scale_factor = TARGET_FPS / RECORD_FPS
                converted_inputs = []

                # 1. Re-calculate timing
                for inp in raw_inputs:
                    old_frame = inp.get("frame", 0)
                    new_frame = int(round(old_frame * scale_factor))
                    converted_inputs.append(
                        {
                            "frame": new_frame,
                            "button": inp.get("button", 1),
                            "hold": bool(inp.get("hold", True)),
                            "player2": bool(inp.get("player2", False)),
                        }
                    )

                # 2. Filter & Hapus Tumpukan Input Ganda (Penyebab Bottleneck Memory)
                converted_inputs.sort(key=lambda x: x["frame"])
                clean_inputs = []
                last_hold_p1 = None
                last_hold_p2 = None

                for inp in converted_inputs:
                    p2 = inp["player2"]
                    hold = inp["hold"]

                    # Filter duplikasi hold/release beruntun di frame yang sama
                    if not p2:
                        if hold != last_hold_p1:
                            clean_inputs.append(inp)
                            last_hold_p1 = hold
                    else:
                        if hold != last_hold_p2:
                            clean_inputs.append(inp)
                            last_hold_p2 = hold

                data["inputs"] = clean_inputs
                data["fps"] = TARGET_FPS

                # Hapus metadata trajectory lama yang bikin lag
                if "trajectory" in data:
                    del data["trajectory"]

                with open(file_path, "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2)

                print(
                    f"Berhasil mengonversi & membersihkan: {filename} ({len(clean_inputs)} inputs)"
                )
        except Exception as e:
            print(f"Gagal mengonversi {filename}: {e}")


if __name__ == "__main__":
    clean_and_convert()

