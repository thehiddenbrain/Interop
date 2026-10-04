# Design document sources

`design.html` (one folder up) is generated. Edit the fragments here and rebuild:

    python3 assemble_v2.py        # prints "tag errors 0 unclosed []" when the HTML is well formed

- `v2part1.html`, `v2part2.html`, `v2part3.html`: the document body, in order.
- `part1.html`: only its `<style>` block and Figure 1 are used.
- `part3.html`: only the id-grid figure is used (captions are rewritten by the script).
- `artifact_v2.html`: the assembled fragment that is published as the claude.ai artifact
  (`https://claude.ai/artifact/A6PfmrLch1RronSwsDQKRp`); republish it to that URL after a change.
