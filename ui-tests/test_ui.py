#!/usr/bin/env python3
"""Browser test for the CMS-1500 Test Console.

Drives a RUNNING service through Chromium (Playwright) and checks every screen: loading the
sample, validate, preview, generate, validation errors, service-line add/remove, JSON mode,
the bundles tab, and the settings tab (change output folder, invalid save, reset).

    pip install playwright && playwright install chromium
    CMS1500_URL=http://localhost:8080 python3 ui-tests/test_ui.py [--headed] [--shots DIR]

Optional CMS1500_OUTPUT_DIR: when set, files on disk are checked as well.
Exit code 0 means every step passed.
"""
import argparse
import json
import os
import pathlib
import re
import sys

from playwright.sync_api import expect, sync_playwright

SAMPLE_CLAIM = "CLM-2026-000123"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--headed", action="store_true", help="show the browser")
    parser.add_argument("--shots", default="ui-tests/screenshots", help="screenshot folder")
    args = parser.parse_args()
    base = os.environ.get("CMS1500_URL", "http://localhost:8080").rstrip("/")
    output_dir = os.environ.get("CMS1500_OUTPUT_DIR")
    shots = pathlib.Path(args.shots)
    shots.mkdir(parents=True, exist_ok=True)
    steps = []

    def step(name):
        print(f"  ok  {name}")
        steps.append(name)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        page = browser.new_page(viewport={"width": 1500, "height": 1000})
        page.on("dialog", lambda d: d.accept())
        js_errors = []
        page.on("pageerror", lambda e: js_errors.append(str(e)))
        page.on("console", lambda m: js_errors.append(m.text)
                if m.type == "error" and "Failed to load resource" not in m.text else None)

        # ---- page and health
        page.goto(base + "/ui/")
        expect(page).to_have_title("CMS-1500 Test Console")
        expect(page.locator("#health")).to_have_text("service UP", timeout=15000)
        step("page loads and reports the service UP")

        # ---- sample -> form -> JSON identical to the sample file
        page.click("#btn-sample")
        expect(page.locator('[data-path="claimNumber"]')).to_have_value(SAMPLE_CLAIM)
        expect(page.locator(".line")).to_have_count(3)
        expect(page.locator('[data-path="serviceLines[1].modifiers[1]"]')).to_have_value("26")
        expect(page.locator('[data-path="insured.anotherHealthBenefitPlan"]')).to_have_value("true")
        sample = page.evaluate("fetch('/ui/samples/claim-full.json').then(r => r.json())")
        collected = page.evaluate("window.cms1500Console.collect()")
        assert collected == sample, "form does not round-trip the sample:\n" + json.dumps(
            {"missing": {k: v for k, v in sample.items() if collected.get(k) != v},
             "extra": {k: v for k, v in collected.items() if sample.get(k) != v}}, indent=1, default=str)
        step("load sample fills every field and reads back exactly as the sample JSON")

        # ---- validate
        page.click("#btn-validate")
        expect(page.locator("#messages .msg.ok")).to_contain_text("Valid")
        step("validate reports a valid claim")

        # ---- preview
        page.click("#btn-preview")
        expect(page.locator("#viewer-title")).to_contain_text("Form preview", timeout=20000)
        assert page.get_attribute("#viewer", "src").startswith("blob:"), "viewer did not receive a blob URL"
        preview = page.request.post(base + "/api/v1/claims/cms1500/preview", data=json.dumps(collected),
                                    headers={"Content-Type": "application/json"})
        assert preview.ok and preview.body()[:4] == b"%PDF", "preview endpoint did not return a PDF"
        page.screenshot(path=str(shots / "01-claim-preview.png"))
        step("preview renders the filled form into the viewer without saving")

        # ---- generate
        page.click("#btn-generate")
        expect(page.locator("#messages .msg.ok strong")).to_contain_text(f"GENERATED: {SAMPLE_CLAIM}.pdf", timeout=30000)
        expect(page.locator("#viewer-title")).to_contain_text(f"Bundle {SAMPLE_CLAIM}.pdf")
        expect(page.locator("#messages")).to_contain_text("Bundle path")
        listed = page.request.get(base + "/api/v1/claims").json()
        assert any(b["claimNumber"] == SAMPLE_CLAIM for b in listed), "bundle missing from the list API"
        if output_dir:
            assert (pathlib.Path(output_dir) / f"{SAMPLE_CLAIM}.pdf").exists(), "bundle file not on disk"
        page.screenshot(path=str(shots / "02-claim-generated.png"))
        step("generate writes the bundle, shows the receipt and loads the bundle in the viewer")

        # ---- validation errors
        page.fill('[data-path="billingProvider.npi"]', "1234567890")
        page.click("#btn-validate")
        expect(page.locator("#messages .msg.error")).to_contain_text("billingProvider.npi")
        expect(page.locator('[data-path="billingProvider.npi"]')).to_have_class(re.compile("invalid"))
        page.click('#messages a:has-text("billingProvider.npi")')
        assert page.evaluate("document.activeElement.dataset.path") == "billingProvider.npi", "error link did not focus the field"
        page.click("#btn-generate")
        expect(page.locator("#messages .msg.error strong")).to_contain_text("VALIDATION_ERROR (HTTP 400)")
        page.screenshot(path=str(shots / "03-validation-errors.png"))
        page.fill('[data-path="billingProvider.npi"]', "1234567893")
        step("validation errors are listed, the link focuses the field, generate is refused")

        # ---- service lines add / remove (item 28 is blanked so the service computes the new total)
        page.fill('[data-path="totalCharge"]', "")
        page.click("#btn-add-line")
        expect(page.locator(".line")).to_have_count(4)
        for path, value in [("dateFrom", "2026-07-19"), ("placeOfService", "11"), ("procedureCode", "99212"),
                            ("diagnosisPointers", "A"), ("charges", "50"), ("units", "1")]:
            page.fill(f'[data-path="serviceLines[3].{path}"]', value)
        page.click("#btn-validate")
        expect(page.locator("#messages .msg.ok")).to_contain_text("Valid")
        assert len(page.evaluate("window.cms1500Console.collect()")["serviceLines"]) == 4
        page.locator(".line").nth(3).locator(".btn-remove-line").click()
        expect(page.locator(".line")).to_have_count(3)
        assert len(page.evaluate("window.cms1500Console.collect()")["serviceLines"]) == 3
        step("service lines can be added, filled, validated and removed")

        # ---- JSON mode
        page.check('input[name="mode"][value="json"]')
        expect(page.locator("#json-text")).to_be_visible()
        text = page.input_value("#json-text")
        assert SAMPLE_CLAIM in text
        page.fill("#json-text", text.replace(SAMPLE_CLAIM, "CLM-UI-JSON"))
        page.click("#btn-json-apply")
        expect(page.locator("#json-status")).to_have_text("Applied to the form.")
        page.click("#btn-generate")
        expect(page.locator("#messages .msg.ok strong")).to_contain_text("GENERATED: CLM-UI-JSON.pdf", timeout=30000)
        expect(page.locator("#messages .msg.warn")).to_contain_text("no attachments found")
        page.screenshot(path=str(shots / "04-json-mode.png"))
        page.fill("#json-text", "{bad json")
        page.click("#btn-json-apply")
        expect(page.locator("#json-status")).to_contain_text("Invalid JSON")
        page.fill("#json-text", text)
        page.check('input[name="mode"][value="form"]')
        expect(page.locator("#claim-form")).to_be_visible()
        expect(page.locator('[data-path="claimNumber"]')).to_have_value(SAMPLE_CLAIM)
        step("JSON mode edits the request verbatim, rejects bad JSON, and round-trips back to the form")

        # ---- bundles tab
        page.click('.tab-btn[data-tab="bundles"]')
        expect(page.locator("#bundles-table tbody")).to_contain_text(SAMPLE_CLAIM)
        expect(page.locator("#bundles-table tbody")).to_contain_text("CLM-UI-JSON")
        page.locator("#bundles-table tbody tr", has_text=SAMPLE_CLAIM).locator(".btn-view").click()
        expect(page.locator("#bundle-viewer-title")).to_contain_text(f"Bundle {SAMPLE_CLAIM}.pdf", timeout=20000)
        assert page.get_attribute("#bundle-viewer", "src").startswith("blob:")
        page.fill("#att-claim", SAMPLE_CLAIM)
        page.click("#btn-att-lookup")
        expected = page.request.get(base + f"/api/v1/claims/{SAMPLE_CLAIM}/attachments").json()
        expect(page.locator("#attachments-table tbody tr")).to_have_count(len(expected))
        expect(page.locator("#att-status")).to_contain_text(f"{len(expected)} file(s)")
        if expected:
            expect(page.locator("#attachments-table tbody tr").first).to_contain_text(expected[0]["fileName"])
        page.screenshot(path=str(shots / "05-bundles.png"))
        step("bundles tab lists bundles, views one, and looks up attachments by claim number")

        # ---- settings tab
        page.click('.tab-btn[data-tab="settings"]')
        expect(page.locator("#s-outputRoot")).not_to_have_value("")
        original_output = page.input_value("#s-outputRoot")
        via_ui = original_output.rstrip("/\\") + os.sep + "via-ui"
        page.fill("#s-outputRoot", via_ui)
        page.click("#btn-settings-save")
        expect(page.locator("#settings-status")).to_have_text("Saved and applied.")
        expect(page.locator("#s-overridden")).to_have_text("overrides active")
        expect(page.locator("#s-output-status")).to_have_text("will be created")
        page.fill("#s-allowedExtensions", "pdf, bad ext")
        page.click("#btn-settings-save")
        expect(page.locator("#settings-errors")).to_contain_text("allowedExtensions[1]")
        expect(page.locator("#s-allowedExtensions")).to_have_class(re.compile("invalid"))
        page.click("#btn-settings-reload")
        expect(page.locator("#s-allowedExtensions")).not_to_have_value(re.compile("bad"))
        page.screenshot(path=str(shots / "06-settings.png"))
        page.click('.tab-btn[data-tab="claim"]')
        page.click("#btn-generate")
        expect(page.locator("#messages .msg.ok strong")).to_contain_text(f"GENERATED: {SAMPLE_CLAIM}.pdf", timeout=30000)
        expect(page.locator("#messages")).to_contain_text("via-ui")
        if output_dir:
            assert (pathlib.Path(output_dir) / "via-ui" / f"{SAMPLE_CLAIM}.pdf").exists(), "bundle not in the new folder"
        page.click('.tab-btn[data-tab="settings"]')
        page.click("#btn-settings-reset")
        expect(page.locator("#settings-status")).to_have_text("Reset to application.yaml values.")
        expect(page.locator("#s-outputRoot")).to_have_value(original_output)
        expect(page.locator("#s-overridden")).to_have_text("application.yaml values")
        step("settings: a new output folder takes effect at once, an invalid save is refused, reset restores")

        assert not js_errors, "browser errors: " + "\n".join(js_errors)
        step("no JavaScript errors in the browser")
        browser.close()

    print(f"\nUI tests passed: {len(steps)} steps; screenshots in {shots}/")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"\nUI TEST FAILED: {e}", file=sys.stderr)
        sys.exit(1)
