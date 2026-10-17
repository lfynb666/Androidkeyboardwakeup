from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "controller" / "src" / "main" / "res"
SAFE_ZONE_RATIO = 72 / 108


for state in ("grey", "red", "green"):
    source_path = RES / "drawable-nodpi" / f"state_{state}.png"
    output_path = RES / "drawable-nodpi" / f"launcher_foreground_{state}.png"

    with Image.open(source_path) as source:
        source = source.convert("RGBA")
        width, height = source.size
        fitted_size = round(min(width, height) * SAFE_ZONE_RATIO)
        fitted = source.resize((fitted_size, fitted_size), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", source.size, (0, 0, 0, 0))
        offset = ((width - fitted_size) // 2, (height - fitted_size) // 2)
        canvas.alpha_composite(fitted, offset)
        canvas.save(output_path, optimize=True)

    print(f"{output_path.name}: {width}x{height} -> {fitted_size}x{fitted_size}, ratio={SAFE_ZONE_RATIO:.4f}")
