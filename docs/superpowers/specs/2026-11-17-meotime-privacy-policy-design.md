# Meotime privacy policy page — design

Date: 2026-11-17
Status: awaiting approval

## Intent

Play Console requires a publicly reachable privacy policy URL before Meotime can be
published. The page must be accurate for what the app actually does today: an on-device
screen-time monitor with no account, no network access, and no third-party SDKs.

Audience: a Play reviewer checking the policy against the Data safety form, and a
prospective user deciding whether to grant usage access. Both need plain statements, not
legal padding.

## Goals

- A hosted-ready static page at `docs/privacy/index.html`, English by default.
- An Indonesian translation at `docs/privacy/id/index.html`, reachable from every page.
- Every claim traceable to the code: permissions from `AndroidManifest.xml`, stored data
  from the Room entities, absence of network from the missing `INTERNET` permission and
  the dependency list.
- Disclose the one non-obvious behaviour: `android:allowBackup="true"` means Android Auto
  Backup may copy the local database to the user's own Google Drive.
- Look like the app: warm cream/espresso/orange, quiet typography, generous whitespace.
- Readable and operable without JavaScript, at 320px and up, keyboard and screen-reader
  friendly.

## Non-goals

- Terms of service, cookie policy, EULA, or an in-app policy screen.
- A docs site, blog, landing page, or navigation beyond the two policy pages.
- Hosting setup: no CI workflow, DNS, or GitHub Pages config. The page is written so a
  Pages/Netlify/static-bucket publish works with no changes.
- Legal review. The wording is written to be accurate and readable, not to be a
  jurisdiction-specific compliance artifact.

## Anti-goals

- Publishing a policy that claims more privacy than the code delivers, or that omits the
  backup caveat.
- Inventing data flows that don't exist (accounts, analytics, ads, crash reporting) just
  because policies usually mention them.
- A generic template look, external fonts, trackers, CDNs, or a cookie banner this site
  has no use for.

## Constraints

- Publisher: Septa Alfauzan, independent developer. Contact: `alfauzansepta@gmail.com`.
- Effective date: 17 November 2026.
- App identity: Meotime, `com.septaalfauzan.meotime`, Android primary, iOS shell.
- No build step. Files are committed as-is and served statically.

## Approaches

**A. Two static pages + one shared stylesheet — chosen.** `index.html` (EN) and
`id/index.html` (ID), `style.css` beside them, `hreflang` alternates and a visible
language switch. Works with JavaScript disabled, gives each language its own URL,
keeps translations from drifting into one 40KB document, and keeps CSS in one place.

**B. One page, both languages in the DOM, small inline JS toggle.** Instant switching
and one file, but the policy then depends on script execution to be readable, doubles
every document in the DOM, and needs `localStorage` — a data-storing mechanism on a page
that exists to promise the app stores nothing beyond the device.

**C. One page, CSS-only toggle (`:target` or hidden radios).** No JS, but the switch
becomes a fragment hack with poor focus and screen-reader behaviour, and both languages
still ship in one document.

**D. Static site generator.** Adds toolchain and hosting config for two pages and one
stylesheet.

B, C, and D were rejected for the reasons above. A carries one cost — duplicated prose
across two files — mitigated by keeping the two versions section-for-section identical
and updating both in the same commit.

## Decisions

User decided:

- Publisher is an independent project; contact is `alfauzansepta@gmail.com`.
- English and Indonesian, English as default.

My calls (correctable):

- Two-file layout with shared CSS, no JavaScript anywhere.
- Warm palette lifted from `presentation/theme/Theme.kt` (cream `#FDF3E3`, espresso
  `#3B2620`, orange `#FF8F45`), with a `prefers-color-scheme: dark` counterpart using
  the dark scheme tokens. System font stack; no webfonts.
- Publisher line: "Meotime is an independent project developed by Septa Alfauzan."
  Not a company, no address — the email is the contact channel.
- Language switch is two plain links labelled `English` / `Bahasa Indonesia`, with the
  current language marked `aria-current="page"`.
- Relative links only (`./`, `../`), no canonical or `og:url`, because the host is not
  chosen yet.
- No dates beyond the effective date; no "last updated" history table.

## Page content

Both languages carry identical sections, in this order:

1. **Header** — app name, "Privacy Policy", effective date, language switch.
2. **The short version** — no account, no cloud, no ads, no tracking; usage data stays
   on the device.
3. **Who we are** — independent project, contact email, what the policy covers.
4. **What Meotime stores on your device** — monitored app package name and display name,
   daily limit, enabled flag; per-day and per-hour usage totals in seconds for those
   apps only; small sync-state bookkeeping. Note that only chosen apps are measured, not
   whole-device screen time.
5. **Permissions and why each exists** — usage access, display over other apps,
   notifications, foreground service, boot completed, installed-app visibility. Each as
   a term/description pair, stating it is never used to collect or transmit.
6. **Android backup** — `allowBackup` is on, so the local database may be included in the
   user's own Google backup and restored on a new device; Google handles that backup, we
   never see it; can be turned off in device settings.
7. **What Meotime does not do** — no account, no INTERNET permission, no analytics, no
   ads, no crash reporting, no selling or sharing, no third parties.
8. **Your choices and deletion** — stop monitoring or remove an app (removes its limit
   and usage rows), uninstall to erase all app data, deny permissions to stop collection.
9. **Retention** — kept until you remove the app or uninstall; no server-side copy.
10. **Children** — self-control tool, not parental control; not directed at children
    under 13; no personal data collected from anyone.
11. **Security** — Android app sandbox, data never leaves the device; no transmission to
    protect.
12. **Changes** — policy may change with app updates; date at the top changes; material
    changes noted in release notes.
13. **Contact** — the email, and "this page itself uses no cookies, scripts, or
    third-party resources".

## Files

```
docs/privacy/index.html      English (default)
docs/privacy/id/index.html   Indonesian
docs/privacy/style.css       shared, ~200 lines
```

## Visual and accessibility requirements

- Semantic structure: one `h1`, `h2` per section, `<dl>` for permission pairs, real
  `<a>`/`<ul>`, `<html lang="en">` / `lang="id"`.
- CSS custom properties for the warm tokens; spacing on a 4px scale (16/24/32/48);
  max text measure ~68ch; 320px–1440px checked.
- Contrast ≥ 4.5:1 for body text in both schemes; focus-visible outline using the
  orange accent.
- Skip link to main content, `prefers-reduced-motion` respected (no animation anyway),
  print styles that drop color.
- No color-only meaning; links underlined, not just colored.

## Verification

- Both files open and render offline; `grep -R "http"` finds no external resource.
- Language links round-trip and each page's `lang` attribute is correct.
- Chrome Lighthouse accessibility ≥ 95, or axe DevTools reports no violations.
- At 320/768/1440px: no horizontal scroll, readable line length, switch still reachable.
- Every permission named on the page appears in `AndroidManifest.xml`; every stored
  field named appears in the Room entities; no claim contradicts the Play listing copy in
  `docs/play-store/listing-en.md`.
