# Browser tests for the test UI

`test_ui.py` drives the running service through Chromium and exercises every screen of the
test console at `/ui/`: sample loading, validate, preview, generate, validation errors,
service-line add/remove, JSON mode, the bundles tab and the settings tab. It is not part of
`mvn test` because it needs a browser; run it after starting the service.

```bash
pip install playwright
playwright install chromium
./run.sh &                                   # or run.cmd on Windows
CMS1500_URL=http://localhost:8080 python3 ui-tests/test_ui.py
```

Set `CMS1500_OUTPUT_DIR` to the service's output folder to also check the files on disk.
Screenshots of each screen are written to `ui-tests/screenshots/` (git-ignored).
