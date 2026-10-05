#!/usr/bin/env node
// Renders a .drawio file to PNG with mxGraph (the open-source engine draw.io is built on) in headless Chromium,
// and reports every label whose text does not fit inside its box.
//
// Prerequisites, in any scratch folder:  npm install mxgraph@4.2.2   (plus Playwright with a Chromium)
// Usage (from that folder):  node <this file> <in.drawio> <out.png> [scale, default 1; 2 for a sharp preview]
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const [inFile, outFile, scaleArg] = process.argv.slice(2);
  const scale = Number(scaleArg) || 1;
  if (!inFile || !outFile) {
    console.error('usage: node render_drawio_preview.js <in.drawio> <out.png> [scale]');
    process.exit(2);
  }
  const mxDir = path.dirname(require.resolve('mxgraph/javascript/mxClient.js', { paths: [process.cwd()] }));
  const xml = fs.readFileSync(inFile, 'utf8');
  const model = xml.match(/pageWidth="(\d+)"[^>]*pageHeight="(\d+)"/);
  const width = model ? Number(model[1]) + 20 : 1940;
  const height = model ? Number(model[2]) : 1040;

  const page = path.join(path.dirname(path.resolve(outFile)), '.render-preview.html');
  fs.writeFileSync(page, `<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;background:#fff}#g{position:relative;width:${width}px;height:${height}px;overflow:hidden}</style>
<script>var mxBasePath='file://${mxDir}/src';var mxImageBasePath=mxBasePath+'/images';var mxLoadResources=false;var mxLoadStylesheets=false;</script>
<script src="file://${mxDir}/mxClient.js"></script></head><body><div id="g"></div></body></html>`);

  const browser = await chromium.launch();
  try {
    const tab = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
    await tab.goto('file://' + page);
    const report = await tab.evaluate((source) => {
      const graph = new mxGraph(document.getElementById('g'));
      graph.setHtmlLabels(true);
      graph.setEnabled(false);
      const doc = mxUtils.parseXml(source);
      new mxCodec(doc).decode(doc.getElementsByTagName('mxGraphModel')[0], graph.getModel());
      const problems = [];
      const cells = graph.getModel().cells;
      for (const id in cells) {
        const state = cells[id].vertex ? graph.view.getState(cells[id]) : null;
        const divs = state && state.text && state.text.node ? state.text.node.getElementsByTagName('div') : [];
        if (!divs.length) continue;
        const r = divs[divs.length - 1].getBoundingClientRect();   // the innermost div holds the label text
        if (r.left < state.x - 1 || r.top < state.y - 1 || r.right > state.x + state.width + 1 || r.bottom > state.y + state.height + 1) {
          problems.push(`${id}: text ${Math.round(r.width)}x${Math.round(r.height)} does not fit its box ${Math.round(state.width)}x${Math.round(state.height)}`);
        }
      }
      return { count: Object.keys(cells).length, problems };
    }, xml);
    await tab.screenshot({ path: outFile, clip: { x: 0, y: 0, width, height } });
    console.log(`rendered ${report.count} cells to ${outFile}`);
    console.log(report.problems.length ? report.problems.join('\n') : 'every label fits its box');
  } finally {
    await browser.close();
    fs.unlinkSync(page);
  }
})();
