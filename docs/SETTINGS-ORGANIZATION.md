# Settings organization, 0.3.17

Layout owns the master switch, style, overall scale, outline structure, cell priorities and outside order.
Components owns one canonical editor per component; Wi-Fi and network type share one editor.
Overall appearance owns the global state-colour switch and custom font. Battery outline owns the
low threshold, shared low/critical palette, paused greyscale and charging animation. Bolt has its own palette.
More groups app interface, updates/project and diagnostics, including restart. Existing preference keys remain unchanged.

The component directory uses independent lazy items with stable keys and content types; rows read only the style.
Slider definitions are cached. Slider thumbs update locally; expensive persistence/reload work is coalesced to
80 ms intervals. Leaving the editor or stopping the activity flushes the final value. Each slider has an isolated identity.
Blur and non-lazy editor scrolling are retained. CI verifies functional regressions and queue delivery;
on-device frame time measurements remain necessary to quantify scrolling improvement.
