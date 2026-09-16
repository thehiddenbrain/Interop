# Browser tests for the workbench UI

`test_ui.py` drives a running workbench through Chromium and walks every screen against the built-in
demo server: adds the demo environment, tests the connection, obtains a token, searches a demo member by
member id and by name, opens the patient, loads coverage / claims / a clinical class, shows the prior
authorizations, runs the conformance suite (discovery, smart, security, patient, coverage, eob,
priorauth, paging, errors groups) and waits for it to finish, opens a history entry and the settings tab.
Screenshots land in `ui-tests/screenshots/` (git-ignored). It is not part of `mvn test`.

```bash
pip install playwright
playwright install chromium            # or set PAW_CHROME=/path/to/chrome
./run.sh &                              # dev profile: demo server on
PAW_URL=http://localhost:8090 python3 ui-tests/test_ui.py
```
