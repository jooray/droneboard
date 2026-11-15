"""Cross-promotion block: Third Ear and I Hear You. Used by build.py (main page) and dictation.py (compact).

section(code, base, dict_href, compact=False) returns one <section> of HTML.
  base       prefix to the site root from the page ("" or "../")
  dict_href  link to the dictation page's #languages tip, as seen from the page
Copy is ours; no external requests, icons are self-hosted in img/.
"""
from html import escape as e

ZAPSTORE = {"te": "https://zapstore.dev/apps/app.thirdear", "ihy": "https://zapstore.dev/apps/app.ihearyou"}
SITES = {
    "te": "https://cypherpunk.today/third-ear/",
    "ihy": "https://cypherpunk.today/i-hear-you/",
}

C = {
    "en": dict(
        h="if you like Droneboard, you might also like…",
        te="A voice recorder with your own buttons: save, transcribe, copy, share or send to a White Noise group. It records from Bluetooth headsets and hearing aids, transcribes on the phone with Offline Speech to Text, and works in Android Auto.",
        ihy="An open-source Android remote for GN hearing aids (ReSound, Beltone, Jabra Enhance and sister brands), with no account or cloud. It can also record voice notes from the aids’ own microphones and transcribe them on the phone.",
        site="Website", zs="Zapstore",
        note='All three apps transcribe speech on the phone with Offline Speech to Text. <a href="{d}">How dictation works</a>.',
    ),
    "sk": dict(
        h="ak sa vám páči Droneboard, môže sa vám páčiť aj…",
        te="Diktafón s vlastnými tlačidlami: uložte, prepíšte, skopírujte, zdieľajte alebo pošlite do skupiny vo White Noise. Nahráva aj z Bluetooth slúchadiel a načúvacích prístrojov, prepisuje v telefóne cez Offline Speech to Text a funguje v Android Auto.",
        ihy="Open-source aplikácia pre Android na ovládanie načúvacích prístrojov GN (ReSound Nexia a CROS), bez účtu a offline. Cez mikrofóny prístrojov nahráte hlasové správy a prepíšete ich v telefóne.",
        site="Web", zs="Zapstore",
        note='Všetky tri aplikácie prepisujú reč v telefóne cez Offline Speech to Text. <a href="{d}">Ako funguje diktovanie</a>.',
    ),
    "cs": dict(
        h="pokud se vám líbí Droneboard, může se vám líbit i…",
        te="Diktafon s vlastními tlačítky: uložte, přepište, zkopírujte, sdílejte nebo pošlete do skupiny ve White Noise. Nahrává i ze sluchátek Bluetooth a naslouchadel, přepisuje v telefonu přes Offline Speech to Text a funguje v Android Auto.",
        ihy="Open-source aplikace pro Android k ovládání sluchadel GN (ReSound Nexia a CROS), bez účtu a offline. Přes mikrofony sluchadel nahrajete hlasové zprávy a přepíšete je v telefonu.",
        site="Web", zs="Zapstore",
        note='Všechny tři aplikace přepisují řeč v telefonu přes Offline Speech to Text. <a href="{d}">Jak funguje diktování</a>.',
    ),
    "de": dict(
        h="wenn Ihnen Droneboard gefällt, gefällt Ihnen vielleicht auch…",
        te="Ein Diktiergerät mit eigenen Tasten: speichern, transkribieren, kopieren, teilen oder an eine White-Noise-Gruppe senden. Es nimmt auch über Bluetooth-Headsets und Hörgeräte auf, transkribiert auf dem Telefon mit Offline Speech to Text und läuft in Android Auto.",
        ihy="Eine unabhängige Open-Source-App für Android, mit der Sie GN-Hörgeräte bedienen (ReSound Nexia und CROS), ohne Konto und offline. Über die Mikrofone der Hörgeräte nehmen Sie Sprachnachrichten auf und transkribieren sie auf dem Handy.",
        site="Website", zs="Zapstore",
        note='Alle drei Apps transkribieren Sprache auf dem Telefon mit Offline Speech to Text. <a href="{d}">So funktioniert das Diktieren</a>.',
    ),
    "es": dict(
        h="si te gusta Droneboard, quizá también te gusten…",
        te="Una grabadora de voz con tus propios botones: guardar, transcribir, copiar, compartir o enviar a un grupo de White Noise. Graba también desde auriculares Bluetooth y audífonos, transcribe en el teléfono con Offline Speech to Text y funciona en Android Auto.",
        ihy="Un mando independiente y de código abierto para audífonos GN (ReSound Nexia y CROS) en Android, sin cuenta y sin conexión. Graba notas de voz con los micrófonos de los audífonos y las transcribe en el teléfono.",
        site="Web", zs="Zapstore",
        note='Las tres apps transcriben voz en el teléfono con Offline Speech to Text. <a href="{d}">Cómo funciona el dictado</a>.',
    ),
}

APPS = [("te", "Third Ear", "third-ear-icon.png"), ("ihy", "I Hear You", "i-hear-you-icon.png")]


def section(code, base, dict_href, compact=False):
    c = C[code]
    rows = []
    for key, name, icon in APPS:
        url = SITES[key] + ("" if code == "en" else code + "/")
        rows.append(
            f'<li><img src="{base}img/{icon}" width="48" height="48" alt="" loading="lazy">'
            f'<div class="xp-text"><h3>{name}</h3><p>{e(c[key], quote=False)}</p></div>'
            f'<div class="xp-links"><a href="{url}" hreflang="{code}">{e(c["site"])}</a>'
            f'<a href="{ZAPSTORE[key]}">{c["zs"]}</a></div></li>'
        )
    cls = "panel xpromo" + (" compact" if compact else "")
    return (
        f'<section class="{cls}" id="more" aria-labelledby="more-h">\n'
        f'  <h2 id="more-h">{e(c["h"])}</h2>\n'
        f'  <ul class="xp-list">{"".join(rows)}</ul>\n'
        f'  <p class="xp-note">{c["note"].format(d=dict_href)}</p>\n'
        f"</section>"
    )
