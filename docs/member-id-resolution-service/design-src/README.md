# Design document sources

`design.html` (one folder up) is generated. Edit the fragments here and rebuild:

    python3 assemble_v2.py        # prints "tag errors 0 unclosed []" when the HTML is well formed

- `v2part1.html`, `v2part2.html`, `v2part3.html`: the document body, in order.
- `part1.html`: only its `<style>` block and Figure 1 are used.
- `part3.html`: only the id-grid figure is used (captions are rewritten by the script).
- `artifact_v2.html`: the assembled fragment that is published as the claude.ai artifact
  (`https://claude.ai/artifact/A6PfmrLch1RronSwsDQKRp`); republish it to that URL after a change.
- `high_level_drawio.py`: writes `../member-id-resolution-service-high-level.drawio`, the one-page high-level diagram
  (uncompressed draw.io XML: imports into Lucidchart through File > Import > draw.io, opens in draw.io). Plain shapes
  on one layer with absolute coordinates, so the import stays clean. Run `python3 high_level_drawio.py`; it refuses to
  write when two boxes overlap or an id-shaped value slipped in. Once someone edits the diagram in Lucid or draw.io,
  that copy is the source; the script covers changes made here.
- `render_drawio_preview.js`: renders a .drawio file to PNG with mxGraph (the engine draw.io is built on) in headless
  Chromium and reports every label that does not fit its box. `../member-id-resolution-service-high-level.png` is its
  output at scale 2: `npm install mxgraph@4.2.2` in a scratch folder, then from there
  `node <path>/render_drawio_preview.js <in.drawio> <out.png> 2`.
