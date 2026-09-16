#!/usr/bin/env python3
"""End-to-end walk through the workbench UI against the built-in demo server (see README.md)."""
import os
import sys
import time

from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get("PAW_URL", "http://localhost:8090").rstrip("/")
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
os.makedirs(SHOTS, exist_ok=True)


def shot(page, name):
    page.screenshot(path=os.path.join(SHOTS, name + ".png"), full_page=True)


def main():
    failures = []
    with sync_playwright() as p:
        launch = {}
        if os.environ.get("PAW_CHROME"):
            launch["executable_path"] = os.environ["PAW_CHROME"]
        browser = p.chromium.launch(**launch)
        page = browser.new_page(viewport={"width": 1400, "height": 1000})
        page.on("pageerror", lambda e: failures.append("page error: %s" % e))

        page.goto(BASE + "/ui/", wait_until="networkidle")
        expect(page.locator("#health")).to_have_class("pill up", timeout=15000)

        # --- environments: add the demo environment and test it
        page.click('button[data-tab="environments"]')
        page.click("#btn-env-demo")
        expect(page.locator("#env-form")).to_be_visible(timeout=15000)
        expect(page.locator("#env-form-title")).to_contain_text("Demo (local)")
        page.click("#btn-env-test")
        expect(page.locator("#env-test-result")).to_contain_text("Connection OK", timeout=30000)
        shot(page, "01-environment-test")
        expect(page.locator("#token-pill")).to_contain_text("token ok", timeout=15000)

        # --- search by member id (demo patient identifiers come from the settings tab)
        page.click('button[data-tab="settings"]')
        expect(page.locator("#settings-demo table")).to_be_visible(timeout=15000)
        first_row = page.locator("#settings-demo table tbody tr").first
        member_value = first_row.locator("td").nth(2).inner_text().split(" ")[1]
        page.click('button[data-tab="search"]')
        page.fill('#search-form input[name="memberId"]', member_value)
        page.click('#search-form button[type="submit"]')
        expect(page.locator("#search-results tbody tr").first).to_be_visible(timeout=20000)
        shot(page, "02-search-member-id")

        # --- search by name + birth date
        page.click("#btn-search-clear")
        page.fill('#search-form input[name="name"]', "Example")
        page.click('#search-form button[type="submit"]')
        expect(page.locator("#search-queries")).to_be_visible(timeout=20000)
        shot(page, "03-search-name")

        # --- open the first member found by member id again
        page.click("#btn-search-clear")
        page.fill('#search-form input[name="memberId"]', member_value)
        page.click('#search-form button[type="submit"]')
        page.locator("#search-results tbody tr").first.locator("button").click()
        expect(page.locator("#patient-summary h2")).to_be_visible(timeout=20000)
        expect(page.locator("#patient-classes .card").first).to_be_visible(timeout=30000)
        shot(page, "04-patient-overview")
        page.locator('#patient-classes .card[data-key="coverage"]').click()
        expect(page.locator("#patient-table-wrap table")).to_be_visible(timeout=20000)
        page.locator('#patient-classes .card[data-key="claims"]').click()
        expect(page.locator("#patient-table-wrap table")).to_be_visible(timeout=20000)
        shot(page, "05-patient-claims")
        page.locator('#patient-classes .card[data-key="Condition"]').click()
        expect(page.locator("#patient-table-wrap table")).to_be_visible(timeout=20000)

        # --- prior authorizations
        page.click("#btn-patient-priorauth")
        expect(page.locator("#pa-list .pa-card").first).to_be_visible(timeout=20000)
        shot(page, "06-prior-auth")

        # --- conformance run
        page.click('button[data-tab="conformance"]')
        expect(page.locator("#conf-groups input").first).to_be_visible(timeout=15000)
        page.click("#btn-conf-none")
        for g in ["discovery", "smart", "security", "patient", "coverage", "eob", "priorauth", "paging", "errors"]:
            page.check('#conf-groups input[value="%s"]' % g)
        page.click('#conf-form button[type="submit"]')
        deadline = time.time() + 240
        while time.time() < deadline:
            title = page.locator("#conf-run-title").inner_text()
            if "DONE" in title or "FAILED" in title or "CANCELLED" in title:
                break
            time.sleep(2)
        else:
            failures.append("conformance run did not finish in time")
        shot(page, "07-conformance")
        summary = page.locator("#conf-summary").inner_text()
        print("conformance summary:", summary.replace("\n", " "))

        # --- history
        page.click('button[data-tab="history"]')
        expect(page.locator("#hist-table tbody tr.selectable").first).to_be_visible(timeout=15000)
        page.locator("#hist-table tbody tr.selectable").first.click()
        expect(page.locator("#hist-detail")).to_contain_text("Request headers", timeout=15000)
        shot(page, "08-history")

        browser.close()
    if failures:
        print("FAILURES:\n - " + "\n - ".join(failures))
        sys.exit(1)
    print("UI walk-through passed")


if __name__ == "__main__":
    main()
