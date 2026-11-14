---
name: Droneboard website
description: An ECM-sleeve landing page — one fog photograph, vast quiet space, the keyboard as the only sharp object.
colors:
  slate: "#2e3a44"
  slate-deep: "#20282f"
  fog: "#c9d1d3"
  mist: "#9aa8b1"
  ink: "#f2f2ee"
  red: "#b23a2e"
  red-lit: "#d0584b"
  rule: "rgba(201, 209, 211, 0.16)"
typography:
  display:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "max(26px, 3.22vmin)"
    fontWeight: 400
    lineHeight: 1.15
    letterSpacing: "0.005em"
  headline:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "clamp(1.6rem, 2.6vw, 2.3rem)"
    fontWeight: 400
    lineHeight: 1.15
    letterSpacing: "0.01em"
  title:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "1.15rem"
    fontWeight: 400
    lineHeight: 1.3
  body:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "1.0625rem"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "0.95rem"
    fontWeight: 400
    letterSpacing: "0.08em"
  wordmark:
    fontFamily: "PT Sans, Helvetica Neue, Arial, sans-serif"
    fontSize: "max(17px, 2.15vmin)"
    fontWeight: 400
    letterSpacing: "0.3em"
rounded:
  hairline: "2px"
  media: "0.6rem"
  device: "1.1rem"
  keyboard: "1.6vmin"
spacing:
  row: "0.85rem"
  track: "1.25rem"
  section: "clamp(4.5rem, 10vw, 8rem)"
  gutter: "clamp(1.25rem, 5vw, 3rem)"
components:
  link-install-primary:
    textColor: "{colors.ink}"
    typography: "{typography.title}"
  link-install-alt:
    textColor: "{colors.fog}"
  track-row:
    textColor: "{colors.fog}"
    padding: "1.25rem 0"
---

# Design System: Droneboard website

## Overview

**The Fog Sleeve.** The site is the front and back of an ECM record cover. The first viewport is one grainy photograph of fog over still water with small lowercase type and a single dark keyboard sitting sharp inside it; everything below is the back of the sleeve: slate ground, hairline rules, a track listing, liner-note credits. Calm is the point, but the offer is never hidden: the install line sits directly under the keyboard.

The world refuses the app-landing template (headline beside a phone mockup over a row of feature cards). There are no cards, no gradients as decoration, no icon tiles. Lowercase display type, tracked labels, and hairlines carry the structure.

## Colors

### Primary
- **Held-Note Red** (#b23a2e, lit #d0584b): the only accent. Install underline, the swipe trace, focus rings, text selection, the active-language underline and the privacy check's left rule. Never a fill for large areas, never body text.

### Neutral
- **Lake Slate** (#2e3a44): theme colour and brand slate; the photograph's mid-water tone.
- **Night Water** (#20282f): the page ground below the first viewport; footer and panels.
- **Fog** (#c9d1d3): body text on slate.
- **Mist** (#9aa8b1): secondary text, ledes, captions, track numbers, table heads.
- **Sleeve Ink** (#f2f2ee): headings, display type, links.
- **Hairline** (rgba(201,209,211,0.16)): every rule and divider.

### Named Rules
- **The One Red Rule.** Red marks the one action or the one moving line on screen. If two red things compete in a viewport, one of them is wrong.
- **The Photograph Owns the Light.** The first viewport's colour comes from the fog plate (assets/plates/fog-photo.png, shipped as img/fog-photo.webp). A bottom wash (rgba(92,106,114,0→0.2), stronger on phones) only restores the approved tone and legibility; it is not a gradient motif.

## Typography

One family, PT Sans (ParaType, OFL), self-hosted in latin and latin-ext subsets for SK/CS/DE/ES diacritics. Display voice is set lowercase with near-zero tracking; the wordmark is lowercase tracked wide (0.3em).

### Hierarchy
- **Display** (400, max(26px, 3.22vmin), 1.15): the hero headline only, lowercase, balanced.
- **Headline** (400, clamp(1.6rem, 2.6vw, 2.3rem), 1.15): section heads, lowercase.
- **Title** (400, 1.15rem, 1.3): track titles and the install line.
- **Body** (400, 1.0625rem, 1.6): copy, max ~62ch.
- **Label** (400, 0.95rem, 0.08em, lowercase): sleeve-side markers (“side a — today”) and table heads.

### Named Rules
- **Tabular Numerals Rule.** Track numbers, step numbers, stats and dates use tabular figures.

## Layout

The hero scales on `--u: min(1vw, 1svh)`, the smaller side of the viewport, like the square sleeve it was comped on; the stack (headline, subcopy, keyboard, install) is centred and starts at 57% of the viewport height. Below, content sits in a 70rem column with `clamp(1.25rem, 5vw, 3rem)` gutters and `clamp(4.5rem, 10vw, 8rem)` vertical rhythm between panels, each panel separated by one hairline. Two-column panels (privacy + numbers, steps + routes) collapse to one column under 760px. On phones the stack anchors to the bottom of the photograph within thumb reach and the section nav hides (languages stay).

One full-bleed photographic band (img/held-note.webp: fog with a red line low over the water) breaks the panels once, carrying the name's origin as its caption.

## Elevation & Depth

Flat. Depth exists only on objects that are physically objects: the illustrated keyboard (soft ambient shadow, inset hairline) and the phone screenshots (soft drop shadow). Everything else is tonal: hairlines on slate.

## Shapes

Hairline rules and square edges for structure. Rounded corners only on device-like objects: keyboard (1.6vmin), its keys (0.45em), screenshots (1.1rem), media in nostr posts (0.6rem).

## Components

- **Illustrated keyboard.** A dark phone keyboard drawn in HTML (keys as flex rows) with a canvas overlay. Signature motion: one swipe trace for “ďakujem” draws as a continuous line in ~1.7s with an exponential ease-out, the tail fading like a decaying note; the suggestion strip dims while the finger moves and brightens when the word lands. Replays on hover or tap. Reduced motion shows the finished trace.
- **Install line.** “Get it on Zapstore” in ink with a 2px muted-red underline that thickens on hover; APK and Obtainium as plain underlined links beside it. Repeated in the install section with the steps.
- **Track listing.** Ordered list, each row: tabular number (a1…/b1…), lowercase title, description; hairline between rows. Side B lists what is coming, titles only.
- **Stats table.** Real `<table>`, tabular numerals right-aligned, Droneboard rows in ink, comparison row in fog, caption below.
- **Nostr feed.** The shared nostr-live widget (vendored website/nostr-live.js), themed only through its `--nl-*` properties with these tokens; four newest posts, lazy-loaded, signatures verified, page language passed through `data-lang`.

## Do's and Don'ts

- Do keep the first viewport to the photograph, small lowercase type and the keyboard.
- Do keep red to one moving line or one action per viewport.
- Do set every number in tabular figures.
- Don't add cards, icon tiles, gradient text or glass.
- Don't add kickers/eyebrows above headings; the sleeve-side labels mark lists, not headings.
- Don't claim languages or features that are not released; “coming” lives on side B.
