"""The dictation subpage: dictation/index.html (en) and <lang>/dictation/index.html.

Called from build.py, which passes in the shared copy (T) for the header and footer. The strings
here are written by us and may contain HTML.
"""
from html import escape

OSTT = "https://zapstore.dev/apps/app.offlinespeechtotext"

D = {}

D["en"] = dict(
    title="Dictation in Droneboard: Offline Speech to Text",
    desc="How dictation works in Droneboard: the keyboard records, the free app Offline Speech to Text transcribes on the phone, and nothing goes online. How to set it up, and a tip for people who speak several languages.",
    h1="dictation that stays on the phone.",
    sub="Droneboard types what you say with the help of Offline Speech to Text, a free app that turns speech into text on your phone. Nothing goes online.",
    more="Offline Speech to Text is a free app you install separately. <a href=\"{href}\">More about dictation</a>",
    ostt_h="offline speech to text",
    ostt_p="Offline Speech to Text is a free, open-source Android app. It runs speech recognition models on the phone itself and doesn’t need internet. Droneboard has no speech engine of its own; it hands the audio to this app.",
    how_h="how it works",
    how=[
        "Tap the mic at the right end of the suggestion strip and start talking. Tap it again when you’re done.",
        "The keyboard records the audio itself. With a Bluetooth headset, LE Audio earbuds or hearing aids connected, it uses their microphone, and if the headset stays silent it switches to the phone’s microphone.",
        "Long dictation is split into pieces under a minute each, so you can keep talking past the one-minute mark.",
        "Offline Speech to Text transcribes each piece on the phone, and the text appears in the field you were typing in.",
    ],
    stays="The audio never leaves the phone. Droneboard has no <code>INTERNET</code> permission, and Offline Speech to Text transcribes on the device.",
    setup_h="set it up",
    steps=[
        f"Install <a href=\"{OSTT}\">Offline Speech to Text from Zapstore</a>.",
        "Open it once and download a speech model.",
        "Switch to Droneboard, tap the mic in the suggestion strip and talk.",
    ],
    cta="Offline Speech to Text on Zapstore",
    route="Don’t have Droneboard yet? The <a href=\"{home}#install\">main page</a> shows how to install it.",
    tip_h="if you speak several languages",
    tip=[
        "In Offline Speech to Text, choose the model <strong>Cadence V2</strong>, set the language to auto-detect, and in the list of languages you speak, keep only the ones you want to dictate in.",
        "The default model, Parakeet, also detects the language on its own, but you can’t limit it. It may take Slovak for Czech or Polish and then keep writing in that language. Cadence V2 only picks from the languages you chose.",
    ],
    back="back to the main page",
)

D["sk"] = dict(
    title="Diktovanie v Droneboarde: Offline Speech to Text",
    desc="Ako funguje diktovanie v Droneboarde: klávesnica nahráva, bezplatná aplikácia Offline Speech to Text prepisuje priamo v telefóne a nič nejde na internet. Postup inštalácie a tip pre tých, čo hovoria viacerými jazykmi.",
    h1="diktovanie, ktoré ostáva v telefóne.",
    sub="Droneboard píše, čo hovoríte, s pomocou Offline Speech to Text. Je to bezplatná aplikácia, ktorá prepisuje reč v telefóne. Na internet nejde nič.",
    more="Offline Speech to Text je bezplatná aplikácia, ktorú si nainštalujete zvlášť. <a href=\"{href}\">Viac o diktovaní</a>",
    ostt_h="offline speech to text",
    ostt_p="Offline Speech to Text je bezplatná open-source aplikácia pre Android. Modely na rozpoznávanie reči spúšťa priamo v telefóne a internet nepotrebuje. Droneboard vlastný rečový engine nemá, zvuk odovzdá tejto aplikácii.",
    how_h="ako to funguje",
    how=[
        "Ťuknite na mikrofón na pravom konci pruhu s návrhmi a hovorte. Keď skončíte, ťuknite naň znova.",
        "Zvuk nahráva samotná klávesnica. Ak máte pripojené Bluetooth slúchadlá, LE Audio slúchadlá alebo načúvacie pomôcky, použije ich mikrofón. Keď zo slúchadiel nič nejde, prepne na mikrofón v telefóne.",
        "Dlhé diktovanie rozdelí na kúsky kratšie ako minúta, takže môžete hovoriť aj dlhšie.",
        "Offline Speech to Text každý kúsok prepíše v telefóne a text sa objaví v poli, kde ste písali.",
    ],
    stays="Zvuk z telefónu neodchádza. Droneboard nemá oprávnenie <code>INTERNET</code> a Offline Speech to Text prepisuje priamo v zariadení.",
    setup_h="inštalácia",
    steps=[
        f"Nainštalujte si <a href=\"{OSTT}\">Offline Speech to Text zo Zapstore</a>.",
        "Raz ju otvorte a stiahnite si model reči.",
        "Prepnite na Droneboard, ťuknite na mikrofón v pruhu s návrhmi a hovorte.",
    ],
    cta="Offline Speech to Text na Zapstore",
    route="Droneboard ešte nemáte? Návod na inštaláciu je na <a href=\"{home}#install\">hlavnej stránke</a>.",
    tip_h="ak hovoríte viacerými jazykmi",
    tip=[
        "V Offline Speech to Text si vyberte model <strong>Cadence V2</strong>, jazyk nastavte na automatické rozpoznanie a v zozname jazykov, ktorými hovoríte, nechajte len tie, v ktorých chcete diktovať.",
        "Predvolený model Parakeet tiež rozpozná jazyk sám, ale obmedziť sa nedá. Slovenčinu si potom môže pomýliť s češtinou alebo poľštinou a v tom jazyku už pokračuje. Cadence V2 vyberá len z jazykov, ktoré ste zvolili.",
    ],
    back="späť na hlavnú stránku",
)

D["cs"] = dict(
    title="Diktování v Droneboardu: Offline Speech to Text",
    desc="Jak funguje diktování v Droneboardu: klávesnice nahrává, bezplatná aplikace Offline Speech to Text přepisuje přímo v telefonu a nic nejde na internet. Postup instalace a tip pro ty, kdo mluví více jazyky.",
    h1="diktování, které zůstává v telefonu.",
    sub="Droneboard píše, co říkáte, s pomocí Offline Speech to Text. Je to bezplatná aplikace, která přepisuje řeč v telefonu. Na internet nejde nic.",
    more="Offline Speech to Text je bezplatná aplikace, kterou si nainstalujete zvlášť. <a href=\"{href}\">Víc o diktování</a>",
    ostt_h="offline speech to text",
    ostt_p="Offline Speech to Text je bezplatná open-source aplikace pro Android. Modely pro rozpoznávání řeči spouští přímo v telefonu a internet nepotřebuje. Droneboard vlastní řečový engine nemá, zvuk předá této aplikaci.",
    how_h="jak to funguje",
    how=[
        "Klepněte na mikrofon na pravém konci pruhu s návrhy a mluvte. Až skončíte, klepněte na něj znovu.",
        "Zvuk nahrává sama klávesnice. Když máte připojená Bluetooth sluchátka, LE Audio sluchátka nebo naslouchadla, použije jejich mikrofon. Pokud ze sluchátek nic nejde, přepne na mikrofon v telefonu.",
        "Dlouhé diktování rozdělí na kousky kratší než minuta, takže můžete mluvit i déle.",
        "Offline Speech to Text každý kousek přepíše v telefonu a text se objeví v poli, kde jste psali.",
    ],
    stays="Zvuk z telefonu neodchází. Droneboard nemá oprávnění <code>INTERNET</code> a Offline Speech to Text přepisuje přímo v zařízení.",
    setup_h="instalace",
    steps=[
        f"Nainstalujte si <a href=\"{OSTT}\">Offline Speech to Text ze Zapstore</a>.",
        "Jednou ji otevřete a stáhněte si model řeči.",
        "Přepněte na Droneboard, klepněte na mikrofon v pruhu s návrhy a mluvte.",
    ],
    cta="Offline Speech to Text na Zapstore",
    route="Droneboard ještě nemáte? Návod k instalaci je na <a href=\"{home}#install\">hlavní stránce</a>.",
    tip_h="když mluvíte více jazyky",
    tip=[
        "V Offline Speech to Text zvolte model <strong>Cadence V2</strong>, jazyk nastavte na automatické rozpoznání a v seznamu jazyků, kterými mluvíte, nechte jen ty, ve kterých chcete diktovat.",
        "Výchozí model Parakeet jazyk také rozpozná sám, ale omezit ho nejde. Slovenštinu si pak může splést s češtinou nebo polštinou a v tom jazyce už pokračuje. Cadence V2 vybírá jen z jazyků, které jste zvolili.",
    ],
    back="zpět na hlavní stránku",
)

D["de"] = dict(
    title="Diktieren mit Droneboard: Offline Speech to Text",
    desc="So funktioniert das Diktat in Droneboard: Die Tastatur nimmt auf, die kostenlose App Offline Speech to Text schreibt direkt auf dem Smartphone mit, und nichts geht ins Internet. Einrichtung und ein Tipp für alle, die mehrere Sprachen sprechen.",
    h1="diktieren, und alles bleibt auf dem smartphone.",
    sub="Droneboard schreibt, was Sie sagen, mit Hilfe von Offline Speech to Text. Die kostenlose App wandelt Sprache auf dem Smartphone in Text um. Nichts geht ins Internet.",
    more="Offline Speech to Text ist eine kostenlose App, die Sie separat installieren. <a href=\"{href}\">Mehr zum Diktieren</a>",
    ostt_h="offline speech to text",
    ostt_p="Offline Speech to Text ist eine kostenlose Open-Source-App für Android. Sie führt Spracherkennungsmodelle direkt auf dem Smartphone aus und braucht kein Internet. Droneboard hat keine eigene Spracherkennung, sondern gibt den Ton an diese App weiter.",
    how_h="so funktioniert es",
    how=[
        "Tippen Sie auf das Mikrofon am rechten Ende der Vorschlagsleiste und sprechen Sie. Wenn Sie fertig sind, tippen Sie noch einmal darauf.",
        "Die Tastatur nimmt den Ton selbst auf. Sind ein Bluetooth-Headset, LE-Audio-Kopfhörer oder Hörgeräte verbunden, nutzt sie deren Mikrofon. Bleibt das Headset stumm, wechselt sie zum Mikrofon des Smartphones.",
        "Lange Diktate teilt sie in Stücke unter einer Minute, Sie können also auch länger am Stück sprechen.",
        "Offline Speech to Text schreibt jedes Stück auf dem Smartphone mit, und der Text erscheint in dem Feld, in dem Sie getippt haben.",
    ],
    stays="Der Ton verlässt das Smartphone nicht. Droneboard hat keine <code>INTERNET</code>-Berechtigung, und Offline Speech to Text arbeitet direkt auf dem Gerät.",
    setup_h="einrichten",
    steps=[
        f"Installieren Sie <a href=\"{OSTT}\">Offline Speech to Text über Zapstore</a>.",
        "Öffnen Sie die App einmal und laden Sie ein Sprachmodell herunter.",
        "Wechseln Sie zu Droneboard, tippen Sie auf das Mikrofon in der Vorschlagsleiste und sprechen Sie.",
    ],
    cta="Offline Speech to Text bei Zapstore",
    route="Droneboard noch nicht installiert? Die Anleitung steht auf der <a href=\"{home}#install\">Startseite</a>.",
    tip_h="wenn Sie mehrere Sprachen sprechen",
    tip=[
        "Wählen Sie in Offline Speech to Text das Modell <strong>Cadence V2</strong>, stellen Sie die Sprache auf automatische Erkennung und lassen Sie in der Liste der Sprachen, die Sie sprechen, nur die stehen, in denen Sie diktieren wollen.",
        "Das voreingestellte Modell Parakeet erkennt die Sprache zwar auch selbst, lässt sich aber nicht einschränken. Slowakisch hält es dann manchmal für Tschechisch oder Polnisch und schreibt in dieser Sprache weiter. Cadence V2 wählt nur unter den Sprachen, die Sie ausgewählt haben.",
    ],
    back="zurück zur Startseite",
)

D["es"] = dict(
    title="Dictado en Droneboard: Offline Speech to Text",
    desc="Cómo funciona el dictado en Droneboard: el teclado graba, la aplicación gratuita Offline Speech to Text transcribe en el teléfono y nada sale a internet. Cómo instalarla y un consejo si hablas varios idiomas.",
    h1="dictado que no sale del teléfono.",
    sub="Droneboard escribe lo que dices con ayuda de Offline Speech to Text, una aplicación gratuita que transcribe la voz en tu teléfono. Nada sale a internet.",
    more="Offline Speech to Text es una aplicación gratuita que se instala aparte. <a href=\"{href}\">Más sobre el dictado</a>",
    ostt_h="offline speech to text",
    ostt_p="Offline Speech to Text es una aplicación gratuita y de código abierto para Android. Ejecuta los modelos de reconocimiento de voz en el propio teléfono y no necesita internet. Droneboard no tiene motor de voz propio: le pasa el audio a esta aplicación.",
    how_h="cómo funciona",
    how=[
        "Toca el micrófono en el extremo derecho de la barra de sugerencias y habla. Cuando termines, vuelve a tocarlo.",
        "El teclado graba el audio por su cuenta. Si tienes conectados unos auriculares Bluetooth, auriculares LE Audio o audífonos, usa su micrófono. Si de los auriculares no llega sonido, pasa al micrófono del teléfono.",
        "Los dictados largos se dividen en trozos de menos de un minuto, así que puedes hablar más de un minuto seguido.",
        "Offline Speech to Text transcribe cada trozo en el teléfono y el texto aparece en el campo donde estabas escribiendo.",
    ],
    stays="El audio no sale del teléfono. Droneboard no tiene permiso <code>INTERNET</code> y Offline Speech to Text transcribe en el propio dispositivo.",
    setup_h="instalación",
    steps=[
        f"Instala <a href=\"{OSTT}\">Offline Speech to Text desde Zapstore</a>.",
        "Ábrela una vez y descarga un modelo de voz.",
        "Cambia a Droneboard, toca el micrófono de la barra de sugerencias y habla.",
    ],
    cta="Offline Speech to Text en Zapstore",
    route="¿Aún no tienes Droneboard? En la <a href=\"{home}#install\">página principal</a> se explica cómo instalarlo.",
    tip_h="si hablas varios idiomas",
    tip=[
        "En Offline Speech to Text elige el modelo <strong>Cadence V2</strong>, pon el idioma en detección automática y, en la lista de idiomas que hablas, deja solo aquellos en los que quieres dictar.",
        "El modelo predeterminado, Parakeet, también detecta el idioma solo, pero no se puede limitar. Puede tomar el eslovaco por checo o polaco y seguir escribiendo en ese idioma. Cadence V2 elige solo entre los idiomas que hayas marcado.",
    ],
    back="volver a la página principal",
)

PATH = "dictation/"


def feature_note(code):
    """The sentence and link added to the dictation feature on the main page ("" without a page)."""
    return " " + D[code]["more"].format(href=PATH) if code in D else ""


def page(code, t, langs, site):
    """One dictation page; [t] is the main page's copy for the shared header and footer."""
    d = D[code]
    e = escape
    langs = [(c, name) for c, name in langs if c in D]  # only languages that have this page
    # from <lang>/dictation/ (or dictation/ for en) back to the site root
    root = "../" if code == "en" else "../../"
    home = root + ("" if code == "en" else code + "/")
    url = lambda c: site + ("" if c == "en" else c + "/") + PATH
    self_url = url(code)
    href = lambda c: root + ("" if c == "en" else c + "/") + PATH
    switcher = "".join(
        f'<li><a href="{href(c)}" hreflang="{c}" lang="{c}" title="{e(name)}"' + (' aria-current="page"' if c == code else "") + f">{c}</a></li>"
        for c, name in langs
    )
    foot_langs = "".join(
        f'<li><a href="{href(c)}" hreflang="{c}" lang="{c}"' + (' aria-current="page"' if c == code else "") + f">{e(name)}</a></li>"
        for c, name in langs
    )
    alternates = "\n".join(f'<link rel="alternate" hreflang="{c}" href="{url(c)}">' for c, _ in langs)
    alternates += f'\n<link rel="alternate" hreflang="x-default" href="{url("en")}">'
    nav = t["nav"]
    how = "".join(f"<li><span>{h}</span></li>" for h in d["how"])
    steps = "".join(f"<li><span>{s}</span></li>" for s in d["steps"])
    tip = "".join(f"<p>{p}</p>" for p in d["tip"])

    return f"""<!doctype html>
<html lang="{code}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{e(d['title'])}</title>
<meta name="description" content="{e(d['desc'])}">
<link rel="canonical" href="{self_url}">
{alternates}
<meta property="og:type" content="article">
<meta property="og:title" content="{e(d['title'])}">
<meta property="og:description" content="{e(d['desc'])}">
<meta property="og:url" content="{self_url}">
<meta property="og:image" content="{site}img/og.jpg">
<meta property="og:locale" content="{t['locale']}">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#2e3a44">
<link rel="icon" href="{root}img/icon-512.png">
<link rel="apple-touch-icon" href="{root}img/apple-touch-icon.png">
<link rel="preload" href="{root}fonts/ptsans-400-latin.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{root}style.css">
</head>
<body>
<a class="skip" href="#how">{e(t['skip'])}</a>

<header class="sleeve subpage" id="top">
  <picture><source type="image/webp" srcset="{root}img/fog-photo.webp"><img class="fog" src="{root}img/fog-photo.png" alt="" width="1600" height="1600" fetchpriority="high"></picture>
  <div class="masthead">
    <a class="wordmark" href="{home}">droneboard</a>
    <nav class="topnav" aria-label="Droneboard">
      <ul class="sections">
        <li><a href="{home}#features">{nav['features']}</a></li>
        <li><a href="{home}#privacy">{nav['privacy']}</a></li>
        <li><a href="{home}#install">{nav['install']}</a></li>
        <li><a href="{home}#nostr">{nav['nostr']}</a></li>
      </ul>
      <ul class="langs" aria-label="{e(t['langlabel'])}">{switcher}</ul>
    </nav>
  </div>
  <div class="stack">
    <h1>{e(d['h1'])}</h1>
    <p class="sub">{d['sub']}</p>
  </div>
</header>

<main>
<section class="panel" id="how" aria-labelledby="ostt-h">
  <div class="two">
    <div>
      <h2 id="ostt-h">{e(d['ostt_h'])}</h2>
      <p class="lede">{d['ostt_p']}</p>
    </div>
    <div>
      <h2 id="how-h">{e(d['how_h'])}</h2>
      <ol class="steps">{how}</ol>
      <p class="stays">{d['stays']}</p>
    </div>
  </div>
</section>

<section class="panel" id="setup" aria-labelledby="setup-h">
  <h2 id="setup-h">{e(d['setup_h'])}</h2>
  <div class="two">
    <ol class="steps">{steps}</ol>
    <div class="routes">
      <a class="primary" href="{OSTT}">{e(d['cta'])}</a>
      <p class="route"><span class="what">{d['route'].format(home=home)}</span></p>
    </div>
  </div>
</section>

<section class="panel" id="languages" aria-labelledby="tip-h">
  <div class="tip">
    <h2 id="tip-h">{e(d['tip_h'])}</h2>
    {tip}
  </div>
  <p class="inst-note"><a href="{home}">{e(d['back'])}</a> · <a href="{OSTT}">Offline Speech to Text · Zapstore</a></p>
</section>
</main>

<footer class="foot">
  <span>{t['runout']}</span>
  <ul aria-label="{e(t['langlabel'])}">{foot_langs}</ul>
</footer>
</body>
</html>
"""


def write_pages(root, T, langs, site):
    for code, _ in langs:
        if code not in D:
            continue
        out = root / (PATH + "index.html" if code == "en" else f"{code}/{PATH}index.html")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page(code, T[code], langs, site), encoding="utf-8")
        print("wrote", out.relative_to(root))
