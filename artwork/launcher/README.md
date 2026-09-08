# Cut & wind launcher

Original Nema thread-spool artwork, licensed with Nema under GPL-3.0-only.

- `nema-editable.svg` is the editable cubic-path source, with live transforms.
- `nema.svg` is its boolean-resolved, flattened export used by Android.
  Transparent even-odd cuts are holes, not background-colored paint.
- `ic_launcher_foreground.xml` uses that exact path in #111111;
  `ic_launcher_monochrome.xml` uses the same alpha silhouette in black.

Both Android vectors have 108dp dimensions and a 108-unit viewport. Keep
all artwork inside the 66-unit safe circle centered at (54,54); do not
rescale the adaptive foreground. The ordinary and round adaptive icons
share these layers and the existing white background. Android 13+ uses
the monochrome layer for themed icons; older Android ignores that layer.

The retained 48dp fallback vectors use the same path scaled 1.5 around
(54,54), matching the adaptive icon's central 72-unit crop, with square
and circular white backgrounds respectively. The in-app mark is separate.

To update the artwork, edit the cubic SVG, union the spool and strand and
subtract the winding cuts, apply transforms, and export a single even-odd
path. Synchronize that path across the flattened SVG and four Android
vectors. Preserve the transparent cuts and verify circular and rounded
square masks at 48, 32 and 24 pixels, including a contrasting themed fill.
Do not substitute a raster trace or change the approved adaptive scale.
