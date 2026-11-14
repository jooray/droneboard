#!/usr/bin/env python3
"""Generates the static pages: index.html (en) and sk/ cs/ de/ es/ index.html.

Standard library only. Run from anywhere:  python3 website/build.py
The strings below are written by us and may contain HTML; nothing here comes from the network.
The fog photograph is the approved plate at assets/plates/fog-photo.png; this script converts it
into website/img/fog-photo.webp when that file is missing or older.
"""
import shutil
import subprocess
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PLATE = ROOT.parent / "assets" / "plates" / "fog-photo.png"
SITE = "https://jooray.github.io/droneboard/"
NPUB = "npub13twdjamspmt5gzm3kczydmntnw5mxp8dr788ucxnqy40gvl3gm2qxwypc9"
# nprofile with relay hints wss://nostr.cypherpunk.today and wss://relay.primal.net (nak encode nprofile)
NPROFILE = "nprofile1qqsg4hxewacqa46ypdcmvpzxae4eh2dnqnk3lrn7vrfsz2h5x0c5d4qpr3mhxue69uhkummnw3ezucmewp5x2unsw4hxktn5dajxz7gpzemhxue69uhhyetvv9ujuurjd9kkzmpwdejhgu62ll5"
HEX = "8adcd977700ed7440b71b60446ee6b9ba9b304ed1f8e7e60d3012af433f146d4"
ZAPSTORE_APP = "https://zapstore.dev/apps/today.cypherpunk.droneboard"
ZAPSTORE = "https://zapstore.dev"
OSTT = "https://zapstore.dev/apps/app.offlinespeechtotext"
REPO = "https://github.com/jooray/droneboard"
APK = "https://github.com/jooray/droneboard/releases/latest"
OBTAINIUM = "obtainium://add/https://github.com/jooray/droneboard"
OBTAINIUM_SITE = "https://obtainium.imranr.dev"
LANGS = [("en", "English"), ("sk", "Slovenčina"), ("cs", "Čeština"), ("de", "Deutsch"), ("es", "Español")]

T = {}

T["en"] = dict(
    locale="en_US",
    title="Droneboard: a multilingual keyboard for Android that works offline",
    desc="Droneboard is an open-source Android keyboard that feels like Gboard and never goes online: swipe across languages without switching, next-word predictions from a small neural network on the phone, offline dictation. No INTERNET permission.",
    skip="Skip to content",
    nav=dict(features="features", privacy="privacy", install="install", nostr="nostr"),
    langlabel="Language",
    h1="a keyboard that plays along. offline.",
    sub="Swipe in several languages at once without switching, get next-word predictions from a small neural network on your phone, dictate offline. No INTERNET permission.",
    strip=("ďakujem", "thanks", "diky"),
    kb_label="Illustration: a swipe across the keyboard spelling ďakujem, with the suggestions ďakujem, thanks and diky in the strip.",
    cta="Get it on Zapstore",
    apk="APK on GitHub",
    obt="Obtainium",
    cue="more features & details",
    feat_h="what it does today",
    feat_p="The keyboard you already know, with the parts that usually live in someone’s cloud rebuilt to stay on your phone. It is a beta: good enough for daily use if you don’t mind reporting the odd bug.",
    side_a="side a — today",
    feats=[
        ("multilingual swipe",
         "Swipe in several languages in the same sentence without touching a language key. Today that means Czech, Slovak and English; Spanish and German are next. FUTO Swipe reads the gesture; our decoder adds the accents and lets the previous word choose between candidates."),
        ("the next word, predicted on the phone",
         "A small neural network guesses your next word from what you have written so far. It runs on the phone; there is no server to send anything to."),
        ("dictation without leaving the keyboard",
         "Tap the mic at the end of the suggestion strip and talk. The keyboard records the audio itself, from the phone or a Bluetooth headset, LE Audio buds or hearing aids, and Offline Speech to Text turns it into text on the device. Long dictation keeps going past the one-minute mark."),
        ("feels like Gboard",
         "Material You colours, the same long-press characters, the word you typed in the middle of the strip, a number row in password fields, and no silent autocorrection."),
        ("emoji by any part of a word",
         "Type “ocket” in emoji search and the rocket shows up. A matching emoji also appears in the strip while you write."),
        ("the space bar as a trackpad",
         "Hold the space bar and slide to move the cursor, or set it to switch languages instead."),
        ("HeliBoard underneath",
         "Clipboard history, one-handed and split layouts, themes, custom layouts and physical keyboards all come from HeliBoard."),
    ],
    side_b="side b — coming",
    coming=[
        "Spanish and German, with their own swipe dictionaries and predictions",
        "A “fix sentence” button backed by a small model on the phone",
    ],
    shots=[
        ("shot-swipe", 1080, 884, "Droneboard in dark theme with English–Slovak on the space bar; after swiping “keyboard” the strip offers keynotes, keyboards and keypad, with the mic on the right.", "After a swipe: suggestions in the strip, the mic on the right."),
        ("shot-emoji-search", 1080, 1239, "Emoji search for the Slovak word “raketa”, showing rackets, astronauts and a rocket above the keyboard.", "Emoji search understands Slovak too: <span lang=\"sk\">“raketa”</span>."),
    ],
    origin="Why “drone”? A swipe across the keys feels like sliding your hand along a synthesizer and letting the note hang, the way drone ambient does.",
    priv_h="nothing leaves your phone",
    priv_p="Droneboard can’t reach the internet because Android never gave it permission to.",
    priv=[
        "No <code>INTERNET</code> permission, no analytics, no crash reports.",
        "It downloads nothing. Extra dictionaries are imported from a file.",
        "Dictation audio goes to Offline Speech to Text on the same phone and nowhere else.",
        "Contacts in suggestions are optional and off by default.",
    ],
    check="<strong>Check it yourself:</strong> Settings → Apps → Droneboard → Permissions. Network access isn’t on the list.",
    num_h="how good is the swipe",
    num_cols=("test", "top-1", "top-3"),
    num_rows=[
        ("Czech gesture set, Google’s gesture library (as in HeliBoard)", "82.4", "96.3", False),
        ("Czech gesture set, Droneboard with its own Czech dictionary", "78.2", "92.0", True),
        ("A real phone, 106 prompted Slovak and English words", "82.1", "92.5", True),
    ],
    num_cap="Top-1: the right word comes first. Top-3: it is among the first three. Measured on the open “Sink-or-Swipe” gesture set and one person’s recordings; a direction, not a promise.",
    inst_h="install",
    inst_p="Zapstore is the easiest way: signed releases and updates from one app, no account.",
    steps=[
        f"Install Zapstore from <a href=\"{ZAPSTORE}\">zapstore.dev</a>.",
        f"Open <a href=\"{ZAPSTORE_APP}\">Droneboard on Zapstore</a> and install it.",
        "Open Droneboard and follow the prompts to switch it on.",
        f"For dictation, also install <a href=\"{OSTT}\">Offline Speech to Text</a>.",
    ],
    routes=[
        ("apk", "The signed APK from the latest GitHub release."),
        ("obt", f"Track GitHub releases in <a href=\"{OBTAINIUM_SITE}\">Obtainium</a>; the link adds the repository directly."),
    ],
    src="Build it yourself from the <a href=\"" + REPO + "\">source code</a>. GPL-3.0.",
    news_h="news on nostr",
    news_p="Droneboard posts on nostr, an open network that no company owns. Your browser loads the newest posts straight from the relays; this page has no server of its own and sets no cookies.",
    n_profile="Profile on njump.me",
    n_zap="Zaps",
    credits_h="credits",
    credits=[
        ("keyboard", "A fork of <a href=\"https://github.com/HeliBorg/HeliBoard\">HeliBoard</a>, which builds on OpenBoard and AOSP LatinIME. GPL-3.0; some files Apache-2.0 or CC-BY-SA-4.0."),
        ("swipe", "Swipe typing powered by FUTO Swipe (<a href=\"https://gitlab.futo.org/keyboard/swipe-library\">swipe-library</a>, GPL-3.0). The model weights are under the FUTO Model Weights License."),
        ("headset recording", "Bluetooth and LE Audio recording comes from <a href=\"https://github.com/jooray/third-ear\">Third Ear</a> by the same author."),
        ("dictation", f"<a href=\"{OSTT}\">Offline Speech to Text</a>, installed separately."),
        ("word lists", "FineWeb-2 (ODC-By), filtered through Hunspell dictionaries."),
        ("emoji data", "Unicode CLDR annotations and Signal’s emoji search data, via aosp-dictionaries."),
        ("type", "PT Sans by ParaType, SIL Open Font License."),
    ],
    runout="Droneboard 0.1 beta · GPL-3.0 · built with AI assistance; contributions welcome",
)

T["sk"] = dict(
    locale="sk_SK",
    title="Droneboard: viacjazyčná klávesnica pre Android, ktorá funguje offline",
    desc="Droneboard je open-source klávesnica pre Android, ktorá sa správa ako Gboard a nikdy nejde na internet: písanie potiahnutím vo viacerých jazykoch bez prepínania, návrhy ďalšieho slova z malej neurónovej siete v telefóne, diktovanie offline. Bez oprávnenia INTERNET.",
    skip="Preskočiť na obsah",
    nav=dict(features="funkcie", privacy="súkromie", install="inštalácia", nostr="nostr"),
    langlabel="Jazyk",
    h1="klávesnica, ktorá hrá s vami. offline.",
    sub="Píšte potiahnutím vo viacerých jazykoch naraz bez prepínania. Ďalšie slovo vám navrhne malá neurónová sieť priamo v telefóne a diktovať môžete bez internetu. Bez oprávnenia INTERNET.",
    strip=("ďakujem", "thanks", "diky"),
    kb_label="Ilustrácia: potiahnutie po klávesnici, ktoré napíše ďakujem; v pruhu sú návrhy ďakujem, thanks a diky.",
    cta="Získať na Zapstore",
    apk="APK na GitHube",
    obt="Obtainium",
    cue="ďalšie funkcie a podrobnosti",
    feat_h="čo vie už dnes",
    feat_p="Klávesnica, na ktorú ste zvyknutí. Časti, ktoré inde bežia v cudzom cloude, tu ostávajú v telefóne. Je to beta: na každodenné písanie stačí, ak vám neprekáža občas nahlásiť chybu.",
    side_a="strana a — dnes",
    feats=[
        ("viacjazyčné písanie potiahnutím",
         "V jednej vete potiahnete slová v niekoľkých jazykoch a jazyk neprepínate. Dnes je to slovenčina, čeština a angličtina, ďalej pribudne španielčina a nemčina. Gesto číta FUTO Swipe, náš dekodér doplní diakritiku a podľa predchádzajúceho slova vyberie správneho kandidáta."),
        ("ďalšie slovo odhadne telefón",
         "Malá neurónová sieť odhadne ďalšie slovo podľa toho, čo ste už napísali. Beží v telefóne, nie je žiadny server, kam by sa čokoľvek posielalo."),
        ("diktovanie bez opustenia klávesnice",
         "Ťuknite na mikrofón na konci pruhu s návrhmi a hovorte. Klávesnica si zvuk nahrá sama, z telefónu, Bluetooth slúchadiel, LE Audio slúchadiel aj z načúvacích pomôcok, a Offline Speech to Text ho prepíše priamo v zariadení. Dlhé diktovanie pokračuje aj po minúte."),
        ("ako Gboard",
         "Farby Material You, rovnaké znaky po podržaní klávesu, napísané slovo v strede pruhu, rad číslic v poliach na heslo a žiadne tiché autoopravy."),
        ("emoji podľa ľubovoľnej časti slova",
         "Napíšte do vyhľadávania emoji „aketa“ a nájde sa raketa. Počas písania sa v pruhu ukáže aj zodpovedajúce emoji."),
        ("medzerník ako touchpad",
         "Podržte medzerník a posúvajte kurzor. Ak chcete, môže namiesto toho prepínať jazyk."),
        ("pod kapotou HeliBoard",
         "História schránky, rozloženie na jednu ruku aj rozdelené, motívy, vlastné rozloženia a fyzické klávesnice prišli z HeliBoardu."),
    ],
    side_b="strana b — pripravujeme",
    coming=[
        "Španielčina a nemčina, každá s vlastným slovníkom na písanie potiahnutím a s predikciami",
        "Tlačidlo „oprav vetu“, za ktorým je malý model priamo v telefóne",
    ],
    shots=[
        ("shot-swipe", 1080, 884, "Droneboard v tmavom motíve s nápisom English–Slovak na medzerníku; po potiahnutí slova „keyboard“ pruh ponúka keynotes, keyboards a keypad, vpravo je mikrofón.", "Po potiahnutí: návrhy v pruhu, vpravo mikrofón."),
        ("shot-emoji-search", 1080, 1239, "Vyhľadávanie emoji podľa slova „raketa“: rakety, astronauti a raketa nad klávesnicou.", "Emoji nájdete aj po slovensky: „raketa“."),
    ],
    origin="Prečo „drone“? Potiahnutie po klávesoch pripomína prejdenie rukou po syntetizátore, keď necháte tón doznieť, ako v drone ambiente.",
    priv_h="z telefónu nič neodchádza",
    priv_p="Droneboard sa na internet nedostane, lebo mu to Android nikdy nepovolil.",
    priv=[
        "Žiadne oprávnenie <code>INTERNET</code>, žiadna analytika, žiadne hlásenia o pádoch.",
        "Nič nesťahuje. Ďalšie slovníky sa importujú zo súboru.",
        "Zvuk z diktovania ide do Offline Speech to Text v tom istom telefóne a nikam inam.",
        "Kontakty v návrhoch sú voliteľné a predvolene vypnuté.",
    ],
    check="<strong>Overte si to sami:</strong> Nastavenia → Aplikácie → Droneboard → Povolenia. Prístup k sieti tam nenájdete.",
    num_h="ako presne číta ťahy",
    num_cols=("test", "top-1", "top-3"),
    num_rows=[
        ("Sada českých gest, knižnica gest od Googlu (ako v HeliBoarde)", "82,4", "96,3", False),
        ("Sada českých gest, Droneboard s vlastným českým slovníkom", "78,2", "92,0", True),
        ("Skutočný telefón, 106 zadaných slovenských a anglických slov", "82,1", "92,5", True),
    ],
    num_cap="Top-1: správne slovo je prvé. Top-3: je medzi prvými tromi. Merané na otvorenej sade gest „Sink-or-Swipe“ a na nahrávkach jedného človeka. Berte to ako smer, nie ako sľub.",
    inst_h="inštalácia",
    inst_p="Najjednoduchšie je to cez Zapstore: podpísané verzie a aktualizácie v jednej aplikácii, bez účtu.",
    steps=[
        f"Nainštalujte si Zapstore zo stránky <a href=\"{ZAPSTORE}\">zapstore.dev</a>.",
        f"Otvorte <a href=\"{ZAPSTORE_APP}\">Droneboard na Zapstore</a> a nainštalujte ho.",
        "Spustite Droneboard a podľa pokynov ho zapnite.",
        f"Na diktovanie si nainštalujte aj <a href=\"{OSTT}\">Offline Speech to Text</a>.",
    ],
    routes=[
        ("apk", "Podpísané APK z posledného vydania na GitHube."),
        ("obt", f"Vydania na GitHube môžete sledovať v <a href=\"{OBTAINIUM_SITE}\">Obtainiu</a>; odkaz pridá repozitár rovno."),
    ],
    src="Zostavte si ho sami zo <a href=\"" + REPO + "\">zdrojového kódu</a>. GPL-3.0.",
    news_h="novinky na nostri",
    news_p="Droneboard píše na nostr, otvorenú sieť, ktorú nevlastní žiadna firma. Najnovšie príspevky načíta váš prehliadač priamo z relayov; táto stránka nemá vlastný server a nenastavuje cookies.",
    n_profile="Profil na njump.me",
    n_zap="Zapy",
    credits_h="poďakovanie",
    credits=[
        ("klávesnica", "Fork <a href=\"https://github.com/HeliBorg/HeliBoard\">HeliBoardu</a>, ktorý stavia na OpenBoarde a AOSP LatinIME. GPL-3.0; niektoré súbory Apache-2.0 alebo CC-BY-SA-4.0."),
        ("písanie potiahnutím", "Poháňa ho FUTO Swipe (<a href=\"https://gitlab.futo.org/keyboard/swipe-library\">swipe-library</a>, GPL-3.0). Váhy modelu sú pod licenciou FUTO Model Weights License."),
        ("nahrávanie zo slúchadiel", "Nahrávanie cez Bluetooth a LE Audio pochádza z aplikácie <a href=\"https://github.com/jooray/third-ear\">Third Ear</a> od toho istého autora."),
        ("diktovanie", f"<a href=\"{OSTT}\">Offline Speech to Text</a>, inštaluje sa zvlášť."),
        ("zoznamy slov", "FineWeb-2 (ODC-By), prefiltrované slovníkmi Hunspell."),
        ("údaje o emoji", "Anotácie Unicode CLDR a dáta Signalu na hľadanie emoji, cez aosp-dictionaries."),
        ("písmo", "PT Sans od ParaType, SIL Open Font License."),
    ],
    runout="Droneboard 0.1 beta · GPL-3.0 · vyvíjané s pomocou AI, príspevky vítané",
)

T["cs"] = dict(
    locale="cs_CZ",
    title="Droneboard: vícejazyčná klávesnice pro Android, která funguje offline",
    desc="Droneboard je open-source klávesnice pro Android, která se chová jako Gboard a nikdy nejde na internet: psaní tažením ve více jazycích bez přepínání, návrhy dalšího slova z malé neuronové sítě v telefonu, diktování offline. Bez oprávnění INTERNET.",
    skip="Přeskočit na obsah",
    nav=dict(features="funkce", privacy="soukromí", install="instalace", nostr="nostr"),
    langlabel="Jazyk",
    h1="klávesnice, která hraje s vámi. offline.",
    sub="Pište tažením ve více jazycích najednou, bez přepínání. Další slovo vám navrhne malá neuronová síť přímo v telefonu a diktovat můžete bez internetu. Bez oprávnění INTERNET.",
    strip=("ďakujem", "thanks", "diky"),
    kb_label="Ilustrace: tah po klávesnici, který napíše ďakujem; v pruhu jsou návrhy ďakujem, thanks a diky.",
    cta="Stáhnout na Zapstore",
    apk="APK na GitHubu",
    obt="Obtainium",
    cue="další funkce a podrobnosti",
    feat_h="co umí už dnes",
    feat_p="Klávesnice, na kterou jste zvyklí. Části, které jinde běží v cizím cloudu, tady zůstávají v telefonu. Je to beta: na každodenní psaní stačí, pokud vám nevadí občas nahlásit chybu.",
    side_a="strana a — dnes",
    feats=[
        ("vícejazyčné psaní tažením",
         "V jedné větě přejedete slova v několika jazycích a jazyk nepřepínáte. Dnes je to čeština, slovenština a angličtina, dál přibude španělština a němčina. Gesto čte FUTO Swipe, náš dekodér doplní diakritiku a podle předchozího slova vybere správného kandidáta."),
        ("další slovo odhadne telefon",
         "Malá neuronová síť odhadne další slovo podle toho, co jste už napsali. Běží v telefonu, žádný server, kam by se cokoli posílalo, neexistuje."),
        ("diktování, aniž byste opustili klávesnici",
         "Klepněte na mikrofon na konci pruhu s návrhy a mluvte. Klávesnice si zvuk nahraje sama, z telefonu, Bluetooth sluchátek, LE Audio sluchátek i z naslouchadel, a Offline Speech to Text ho přepíše přímo v zařízení. Dlouhé diktování pokračuje i po minutě."),
        ("jako Gboard",
         "Barvy Material You, stejné znaky po podržení klávesy, napsané slovo uprostřed pruhu, řada číslic v polích pro heslo a žádné tiché opravy."),
        ("emoji podle libovolné části slova",
         "Napište do hledání emoji „aketa“ a najde se raketa. Při psaní se v pruhu objeví i odpovídající emoji."),
        ("mezerník jako touchpad",
         "Podržte mezerník a posouvejte kurzor. Když chcete, může místo toho přepínat jazyk."),
        ("pod kapotou HeliBoard",
         "Historie schránky, rozložení pro jednu ruku i rozdělené, motivy, vlastní rozložení a fyzické klávesnice přišly z HeliBoardu."),
    ],
    side_b="strana b — připravujeme",
    coming=[
        "Španělština a němčina, každá s vlastním slovníkem pro psaní tažením a s předpovědí slov",
        "Tlačítko „oprav větu“, za kterým je malý model přímo v telefonu",
    ],
    shots=[
        ("shot-swipe", 1080, 884, "Droneboard v tmavém motivu s nápisem English–Slovak na mezerníku; po tažení slova „keyboard“ pruh nabízí keynotes, keyboards a keypad, vpravo je mikrofon.", "Po tažení: návrhy v pruhu, vpravo mikrofon."),
        ("shot-emoji-search", 1080, 1239, "Vyhledávání emoji podle slovenského slova „raketa“: rakety, astronauti a raketa nad klávesnicí.", "Emoji najdete i slovensky: <span lang=\"sk\">„raketa“</span>."),
    ],
    origin="Proč „drone“? Tah po klávesách připomíná přejetí rukou po syntezátoru, když necháte tón doznít, jako v drone ambientu.",
    priv_h="z telefonu nic neodchází",
    priv_p="Droneboard se na internet nedostane, protože mu to Android nikdy nepovolil.",
    priv=[
        "Žádné oprávnění <code>INTERNET</code>, žádná analytika, žádná hlášení o pádech.",
        "Nic nestahuje. Další slovníky se importují ze souboru.",
        "Zvuk z diktování jde do Offline Speech to Text ve stejném telefonu a nikam jinam.",
        "Kontakty v návrzích jsou volitelné a ve výchozím stavu vypnuté.",
    ],
    check="<strong>Ověřte si to sami:</strong> Nastavení → Aplikace → Droneboard → Oprávnění. Přístup k síti tam nenajdete.",
    num_h="jak přesně čte tahy",
    num_cols=("test", "top-1", "top-3"),
    num_rows=[
        ("Sada českých gest, knihovna gest od Googlu (jako v HeliBoardu)", "82,4", "96,3", False),
        ("Sada českých gest, Droneboard s vlastním českým slovníkem", "78,2", "92,0", True),
        ("Skutečný telefon, 106 zadaných slovenských a anglických slov", "82,1", "92,5", True),
    ],
    num_cap="Top-1: správné slovo je první. Top-3: je mezi prvními třemi. Měřeno na otevřené sadě gest „Sink-or-Swipe“ a na nahrávkách jednoho člověka. Berte to jako směr, ne jako slib.",
    inst_h="instalace",
    inst_p="Nejjednodušší je Zapstore: podepsané verze a aktualizace v jedné aplikaci, bez účtu.",
    steps=[
        f"Nainstalujte si Zapstore ze stránky <a href=\"{ZAPSTORE}\">zapstore.dev</a>.",
        f"Otevřete <a href=\"{ZAPSTORE_APP}\">Droneboard na Zapstore</a> a nainstalujte ho.",
        "Spusťte Droneboard a podle pokynů ho zapněte.",
        f"Pro diktování si nainstalujte i <a href=\"{OSTT}\">Offline Speech to Text</a>.",
    ],
    routes=[
        ("apk", "Podepsané APK z posledního vydání na GitHubu."),
        ("obt", f"Vydání na GitHubu můžete sledovat v <a href=\"{OBTAINIUM_SITE}\">Obtainiu</a>; odkaz přidá repozitář rovnou."),
    ],
    src="Sestavte si ho sami ze <a href=\"" + REPO + "\">zdrojového kódu</a>. GPL-3.0.",
    news_h="novinky na nostru",
    news_p="Droneboard píše na nostr, otevřenou síť, kterou nevlastní žádná firma. Nejnovější příspěvky načte váš prohlížeč přímo z relayů; tato stránka nemá vlastní server a nenastavuje cookies.",
    n_profile="Profil na njump.me",
    n_zap="Zapy",
    credits_h="poděkování",
    credits=[
        ("klávesnice", "Fork <a href=\"https://github.com/HeliBorg/HeliBoard\">HeliBoardu</a>, který staví na OpenBoardu a AOSP LatinIME. GPL-3.0; některé soubory Apache-2.0 nebo CC-BY-SA-4.0."),
        ("psaní tažením", "Pohání ho FUTO Swipe (<a href=\"https://gitlab.futo.org/keyboard/swipe-library\">swipe-library</a>, GPL-3.0). Váhy modelu jsou pod licencí FUTO Model Weights License."),
        ("nahrávání ze sluchátek", "Nahrávání přes Bluetooth a LE Audio pochází z aplikace <a href=\"https://github.com/jooray/third-ear\">Third Ear</a> od stejného autora."),
        ("diktování", f"<a href=\"{OSTT}\">Offline Speech to Text</a>, instaluje se zvlášť."),
        ("seznamy slov", "FineWeb-2 (ODC-By), přefiltrované slovníky Hunspell."),
        ("data emoji", "Anotace Unicode CLDR a data Signalu pro hledání emoji, přes aosp-dictionaries."),
        ("písmo", "PT Sans od ParaType, SIL Open Font License."),
    ],
    runout="Droneboard 0.1 beta · GPL-3.0 · vyvíjeno s pomocí AI, příspěvky vítány",
)

T["de"] = dict(
    locale="de_DE",
    title="Droneboard: eine mehrsprachige Android-Tastatur, die offline funktioniert",
    desc="Droneboard ist eine Open-Source-Tastatur für Android, die sich wie Gboard anfühlt und nie online geht: Wischen in mehreren Sprachen ohne Umschalten, Wortvorschläge von einem kleinen neuronalen Netz auf dem Telefon, Diktieren offline. Keine INTERNET-Berechtigung.",
    skip="Zum Inhalt springen",
    nav=dict(features="funktionen", privacy="datenschutz", install="installation", nostr="nostr"),
    langlabel="Sprache",
    h1="eine tastatur, die mitspielt. offline.",
    sub="Wischen Sie in mehreren Sprachen gleichzeitig, ohne umzuschalten. Das nächste Wort schlägt ein kleines neuronales Netz auf Ihrem Telefon vor, und diktieren geht auch ohne Internet. Keine INTERNET-Berechtigung.",
    strip=("ďakujem", "thanks", "diky"),
    kb_label="Illustration: eine Wischgeste über die Tastatur, die ďakujem schreibt; in der Leiste stehen die Vorschläge ďakujem, thanks und diky.",
    cta="Bei Zapstore holen",
    apk="APK auf GitHub",
    obt="Obtainium",
    cue="mehr funktionen und details",
    feat_h="was sie heute kann",
    feat_p="Die Tastatur, die Sie kennen. Was anderswo in einer fremden Cloud läuft, bleibt hier auf dem Telefon. Es ist eine Beta: für den Alltag gut genug, wenn Sie ab und zu einen Fehler melden mögen.",
    side_a="seite a — heute",
    feats=[
        ("mehrsprachig wischen",
         "Wischen Sie Wörter aus mehreren Sprachen im selben Satz, ohne eine Sprachtaste anzufassen. Heute sind das Tschechisch, Slowakisch und Englisch; Spanisch und Deutsch folgen. FUTO Swipe liest die Geste, unser Decoder ergänzt die Akzente und lässt das vorherige Wort zwischen den Kandidaten entscheiden."),
        ("das nächste Wort, auf dem Telefon berechnet",
         "Ein kleines neuronales Netz errät Ihr nächstes Wort aus dem, was Sie schon geschrieben haben. Es läuft auf dem Telefon; es gibt keinen Server, an den etwas gehen könnte."),
        ("diktieren, ohne die Tastatur zu verlassen",
         "Tippen Sie auf das Mikrofon am Ende der Vorschlagsleiste und sprechen Sie. Die Tastatur nimmt selbst auf, über das Telefon, ein Bluetooth-Headset, LE-Audio-Kopfhörer oder Hörgeräte, und Offline Speech to Text macht daraus Text auf dem Gerät. Lange Diktate laufen auch nach einer Minute weiter."),
        ("fühlt sich an wie Gboard",
         "Material-You-Farben, dieselben Zeichen beim langen Drücken, das getippte Wort in der Mitte der Leiste, eine Zahlenreihe in Passwortfeldern und keine stille Autokorrektur."),
        ("Emoji über jeden Wortteil",
         "Geben Sie in der Emoji-Suche „akete“ ein, und die Rakete erscheint. Beim Schreiben zeigt die Leiste außerdem ein passendes Emoji."),
        ("die Leertaste als Trackpad",
         "Halten Sie die Leertaste gedrückt und schieben Sie den Cursor. Auf Wunsch wechselt sie stattdessen die Sprache."),
        ("darunter steckt HeliBoard",
         "Zwischenablage-Verlauf, Einhand- und geteilte Layouts, Designs, eigene Layouts und Hardware-Tastaturen stammen aus HeliBoard."),
    ],
    side_b="seite b — in arbeit",
    coming=[
        "Spanisch und Deutsch, jeweils mit eigenem Wischwörterbuch und Wortvorhersage",
        "Eine Taste „Satz korrigieren“ mit einem kleinen Modell direkt auf dem Telefon",
    ],
    shots=[
        ("shot-swipe", 1080, 884, "Droneboard im dunklen Design, auf der Leertaste steht English–Slovak; nach dem Wischen von „keyboard“ bietet die Leiste keynotes, keyboards und keypad an, rechts das Mikrofon.", "Nach dem Wischen: Vorschläge in der Leiste, rechts das Mikrofon."),
        ("shot-emoji-search", 1080, 1239, "Emoji-Suche nach dem slowakischen Wort „raketa“: Schläger, Astronauten und eine Rakete über der Tastatur.", "Die Emoji-Suche versteht auch Slowakisch: <span lang=\"sk\">„raketa“</span>."),
    ],
    origin="Warum „Drone“? Ein Wisch über die Tasten fühlt sich an, als glitte die Hand über einen Synthesizer und ließe den Ton stehen, wie im Drone Ambient.",
    priv_h="nichts verlässt ihr telefon",
    priv_p="Droneboard kommt nicht ins Internet, weil Android ihm das nie erlaubt hat.",
    priv=[
        "Keine <code>INTERNET</code>-Berechtigung, keine Analyse, keine Absturzberichte.",
        "Sie lädt nichts herunter. Zusätzliche Wörterbücher werden aus einer Datei importiert.",
        "Der Ton beim Diktieren geht an Offline Speech to Text auf demselben Telefon und sonst nirgendwohin.",
        "Kontakte in den Vorschlägen sind optional und standardmäßig aus.",
    ],
    check="<strong>Prüfen Sie es selbst:</strong> Einstellungen → Apps → Droneboard → Berechtigungen. Netzwerkzugriff steht dort nicht.",
    num_h="wie gut das wischen ist",
    num_cols=("test", "top-1", "top-3"),
    num_rows=[
        ("Tschechischer Gestendatensatz, Googles Gestenbibliothek (wie in HeliBoard)", "82,4", "96,3", False),
        ("Tschechischer Gestendatensatz, Droneboard mit eigenem tschechischem Wörterbuch", "78,2", "92,0", True),
        ("Echtes Telefon, 106 vorgegebene slowakische und englische Wörter", "82,1", "92,5", True),
    ],
    num_cap="Top-1: Das richtige Wort steht vorn. Top-3: Es ist unter den ersten drei. Gemessen mit dem offenen Datensatz „Sink-or-Swipe“ und den Aufnahmen einer Person; eine Richtung, kein Versprechen.",
    inst_h="installation",
    inst_p="Am einfachsten über Zapstore: signierte Versionen und Updates aus einer App, ohne Konto.",
    steps=[
        f"Installieren Sie Zapstore von <a href=\"{ZAPSTORE}\">zapstore.dev</a>.",
        f"Öffnen Sie <a href=\"{ZAPSTORE_APP}\">Droneboard auf Zapstore</a> und installieren Sie es.",
        "Öffnen Sie Droneboard und schalten Sie die Tastatur nach den Hinweisen ein.",
        f"Fürs Diktieren installieren Sie zusätzlich <a href=\"{OSTT}\">Offline Speech to Text</a>.",
    ],
    routes=[
        ("apk", "Die signierte APK aus dem neuesten GitHub-Release."),
        ("obt", f"Verfolgen Sie die GitHub-Releases in <a href=\"{OBTAINIUM_SITE}\">Obtainium</a>; der Link fügt das Repository direkt hinzu."),
    ],
    src="Selbst bauen? Der <a href=\"" + REPO + "\">Quellcode</a> liegt offen. GPL-3.0.",
    news_h="neues auf nostr",
    news_p="Droneboard schreibt auf nostr, einem offenen Netzwerk, das keiner Firma gehört. Ihr Browser lädt die neuesten Beiträge direkt von den Relays; diese Seite hat keinen eigenen Server und setzt keine Cookies.",
    n_profile="Profil auf njump.me",
    n_zap="Zaps",
    credits_h="dank",
    credits=[
        ("tastatur", "Ein Fork von <a href=\"https://github.com/HeliBorg/HeliBoard\">HeliBoard</a>, das auf OpenBoard und AOSP LatinIME aufbaut. GPL-3.0; einzelne Dateien Apache-2.0 oder CC-BY-SA-4.0."),
        ("wischen", "Wischeingabe mit FUTO Swipe (<a href=\"https://gitlab.futo.org/keyboard/swipe-library\">swipe-library</a>, GPL-3.0). Die Modellgewichte stehen unter der FUTO Model Weights License."),
        ("headset-aufnahme", "Die Aufnahme über Bluetooth und LE Audio stammt aus <a href=\"https://github.com/jooray/third-ear\">Third Ear</a> vom selben Autor."),
        ("diktat", f"<a href=\"{OSTT}\">Offline Speech to Text</a>, wird separat installiert."),
        ("wortlisten", "FineWeb-2 (ODC-By), gefiltert mit Hunspell-Wörterbüchern."),
        ("emoji-daten", "Unicode-CLDR-Annotationen und Signals Daten zur Emoji-Suche, über aosp-dictionaries."),
        ("schrift", "PT Sans von ParaType, SIL Open Font License."),
    ],
    runout="Droneboard 0.1 Beta · GPL-3.0 · mit KI-Unterstützung gebaut; Beiträge willkommen",
)

T["es"] = dict(
    locale="es_ES",
    title="Droneboard: un teclado multilingüe para Android que funciona sin conexión",
    desc="Droneboard es un teclado de código abierto para Android que se siente como Gboard y nunca se conecta a internet: desliza en varios idiomas sin cambiar, predicción de la siguiente palabra con una pequeña red neuronal en el teléfono, dictado sin conexión. Sin permiso INTERNET.",
    skip="Saltar al contenido",
    nav=dict(features="funciones", privacy="privacidad", install="instalar", nostr="nostr"),
    langlabel="Idioma",
    h1="un teclado que te sigue el ritmo. sin conexión.",
    sub="Desliza en varios idiomas a la vez sin cambiar de teclado. Una pequeña red neuronal en tu teléfono te sugiere la siguiente palabra y puedes dictar sin internet. Sin permiso INTERNET.",
    strip=("ďakujem", "thanks", "diky"),
    kb_label="Ilustración: un deslizamiento sobre el teclado que escribe ďakujem; en la barra, las sugerencias ďakujem, thanks y diky.",
    cta="Descárgalo en Zapstore",
    apk="APK en GitHub",
    obt="Obtainium",
    cue="más funciones y detalles",
    feat_h="lo que hace hoy",
    feat_p="El teclado que ya conoces. Lo que en otros vive en la nube de alguien, aquí se queda en tu teléfono. Es una beta: sirve para el día a día si no te importa avisar de algún fallo de vez en cuando.",
    side_a="cara a — hoy",
    feats=[
        ("deslizar en varios idiomas",
         "Desliza palabras de varios idiomas en la misma frase sin tocar ninguna tecla de idioma. Hoy son el checo, el eslovaco y el inglés; el español y el alemán llegan después. FUTO Swipe lee el gesto; nuestro decodificador añade los acentos y deja que la palabra anterior elija entre los candidatos."),
        ("la siguiente palabra, calculada en el teléfono",
         "Una pequeña red neuronal adivina tu siguiente palabra a partir de lo que ya has escrito. Funciona en el teléfono; no hay ningún servidor al que enviar nada."),
        ("dictar sin salir del teclado",
         "Toca el micrófono al final de la barra de sugerencias y habla. El teclado graba el audio por su cuenta, desde el teléfono, unos auriculares Bluetooth, auriculares LE Audio o audífonos, y Offline Speech to Text lo convierte en texto en el propio dispositivo. Los dictados largos siguen más allá del minuto."),
        ("se siente como Gboard",
         "Colores Material You, los mismos caracteres al mantener pulsada una tecla, la palabra escrita en el centro de la barra, una fila de números en los campos de contraseña y nada de autocorrección silenciosa."),
        ("emoji por cualquier parte de la palabra",
         "Escribe «ohete» en la búsqueda de emoji y aparece el cohete. Mientras escribes, la barra también te sugiere el emoji que encaja."),
        ("la barra espaciadora como trackpad",
         "Mantén pulsada la barra espaciadora y desliza para mover el cursor, o configúrala para cambiar de idioma."),
        ("HeliBoard por debajo",
         "El historial del portapapeles, los diseños a una mano y divididos, los temas, los diseños propios y los teclados físicos vienen de HeliBoard."),
    ],
    side_b="cara b — en camino",
    coming=[
        "El español y el alemán, cada uno con su diccionario para deslizar y sus predicciones",
        "Un botón «corregir frase» con un modelo pequeño en el propio teléfono",
    ],
    shots=[
        ("shot-swipe", 1080, 884, "Droneboard en tema oscuro con English–Slovak en la barra espaciadora; tras deslizar «keyboard» la barra ofrece keynotes, keyboards y keypad, con el micrófono a la derecha.", "Tras deslizar: sugerencias en la barra, el micrófono a la derecha."),
        ("shot-emoji-search", 1080, 1239, "Búsqueda de emoji con la palabra eslovaca «raketa»: raquetas, astronautas y un cohete sobre el teclado.", "La búsqueda de emoji también entiende eslovaco: <span lang=\"sk\">«raketa»</span>."),
    ],
    origin="¿Por qué «drone»? Deslizar por las teclas se siente como pasar la mano por un sintetizador y dejar que la nota se sostenga, como en el drone ambient.",
    priv_h="nada sale de tu teléfono",
    priv_p="Droneboard no puede llegar a internet porque Android nunca le dio permiso.",
    priv=[
        "Sin permiso <code>INTERNET</code>, sin analíticas, sin informes de fallos.",
        "No descarga nada. Los diccionarios adicionales se importan desde un archivo.",
        "El audio del dictado va a Offline Speech to Text en el mismo teléfono y a ningún otro sitio.",
        "Los contactos en las sugerencias son opcionales y vienen desactivados.",
    ],
    check="<strong>Compruébalo tú:</strong> Ajustes → Aplicaciones → Droneboard → Permisos. El acceso a la red no aparece.",
    num_h="qué tal desliza",
    num_cols=("prueba", "top-1", "top-3"),
    num_rows=[
        ("Conjunto de gestos en checo, biblioteca de gestos de Google (como en HeliBoard)", "82,4", "96,3", False),
        ("Conjunto de gestos en checo, Droneboard con su propio diccionario checo", "78,2", "92,0", True),
        ("Un teléfono real, 106 palabras propuestas en eslovaco e inglés", "82,1", "92,5", True),
    ],
    num_cap="Top-1: la palabra correcta sale la primera. Top-3: está entre las tres primeras. Medido con el conjunto abierto «Sink-or-Swipe» y las grabaciones de una persona; una orientación, no una promesa.",
    inst_h="instalar",
    inst_p="Lo más fácil es Zapstore: versiones firmadas y actualizaciones desde una sola app, sin cuenta.",
    steps=[
        f"Instala Zapstore desde <a href=\"{ZAPSTORE}\">zapstore.dev</a>.",
        f"Abre <a href=\"{ZAPSTORE_APP}\">Droneboard en Zapstore</a> e instálalo.",
        "Abre Droneboard y sigue las indicaciones para activarlo.",
        f"Para dictar, instala también <a href=\"{OSTT}\">Offline Speech to Text</a>.",
    ],
    routes=[
        ("apk", "El APK firmado de la última versión en GitHub."),
        ("obt", f"Sigue las versiones de GitHub en <a href=\"{OBTAINIUM_SITE}\">Obtainium</a>; el enlace añade el repositorio directamente."),
    ],
    src="¿Prefieres compilarlo? El <a href=\"" + REPO + "\">código fuente</a> es abierto. GPL-3.0.",
    news_h="novedades en nostr",
    news_p="Droneboard publica en nostr, una red abierta que no es de ninguna empresa. Tu navegador carga las publicaciones más recientes directamente desde los relés; esta página no tiene servidor propio y no usa cookies.",
    n_profile="Perfil en njump.me",
    n_zap="Zaps",
    credits_h="créditos",
    credits=[
        ("teclado", "Bifurcación de <a href=\"https://github.com/HeliBorg/HeliBoard\">HeliBoard</a>, basado en OpenBoard y AOSP LatinIME. GPL-3.0; algunos archivos Apache-2.0 o CC-BY-SA-4.0."),
        ("deslizar", "Escritura deslizando con FUTO Swipe (<a href=\"https://gitlab.futo.org/keyboard/swipe-library\">swipe-library</a>, GPL-3.0). Los pesos del modelo están bajo la FUTO Model Weights License."),
        ("grabación con auriculares", "La grabación por Bluetooth y LE Audio viene de <a href=\"https://github.com/jooray/third-ear\">Third Ear</a>, del mismo autor."),
        ("dictado", f"<a href=\"{OSTT}\">Offline Speech to Text</a>, se instala aparte."),
        ("listas de palabras", "FineWeb-2 (ODC-By), filtradas con diccionarios Hunspell."),
        ("datos de emoji", "Anotaciones de Unicode CLDR y datos de búsqueda de emoji de Signal, a través de aosp-dictionaries."),
        ("tipografía", "PT Sans de ParaType, SIL Open Font License."),
    ],
    runout="Droneboard 0.1 beta · GPL-3.0 · hecho con ayuda de IA; las contribuciones son bienvenidas",
)

# small, single-stroke icons (24px grid, 1.6 stroke) used inside the illustrated keyboard and the cue
ICON = {
    "chev": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>',
    "mic": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/></svg>',
    "shift": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M12 4 4 12h4.5v7h7v-7H20z"/></svg>',
    "del": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6h11v12H9l-6-6z"/><path d="m12 9.5 5 5m0-5-5 5"/></svg>',
    "smile": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="12" r="8.5"/><path d="M8.5 14a4.2 4.2 0 0 0 7 0"/><circle cx="9.3" cy="10" r=".6" fill="currentColor"/><circle cx="14.7" cy="10" r=".6" fill="currentColor"/></svg>',
    "enter": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M19 6v5a3 3 0 0 1-3 3H6"/><path d="m9.5 10.5-3.5 3.5 3.5 3.5"/></svg>',
    "down": '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>',
}


def keyboard(t):
    rows = ["qwertyuiop", "asdfghjkl"]
    keys = "".join(f'<div class="kb-row{" mid" if i else ""}">{"".join(f"<span class=k data-k={c}>{c}</span>" for c in r)}</div>' for i, r in enumerate(rows))
    keys += '<div class="kb-row"><span class="k fn wide">' + ICON["shift"] + '</span>' + "".join(f"<span class=k data-k={c}>{c}</span>" for c in "zxcvbnm") + '<span class="k fn wide">' + ICON["del"] + "</span></div>"
    keys += '<div class="kb-row"><span class="k fn wide">?123</span><span class="k fn">,</span><span class="k fn">' + ICON["smile"] + '</span><span class="k space"></span><span class="k fn">.</span><span class="k fn wide">' + ICON["enter"] + "</span></div>"
    words = "".join(f'<span class="w">{escape(w)}</span>' for w in t["strip"])
    return (f'<div class="kb" role="img" aria-label="{escape(t["kb_label"])}" data-word="dakujem">'
            f'<div class="kb-strip" aria-hidden="true">{ICON["chev"]}{words}{ICON["mic"]}</div>'
            f'<div class="kb-rows" aria-hidden="true">{keys}</div><canvas aria-hidden="true"></canvas></div>')


def page(code):
    t = T[code]
    base = "" if code == "en" else "../"
    self_url = SITE + ("" if code == "en" else code + "/")
    e = escape
    href = lambda c: base + ("" if c == "en" else c + "/")
    switcher = "".join(
        f'<li><a href="{href(c)}" hreflang="{c}" lang="{c}" title="{e(name)}"' + (' aria-current="page"' if c == code else "") + f">{c}</a></li>"
        for c, name in LANGS
    )
    foot_langs = "".join(
        f'<li><a href="{href(c)}" hreflang="{c}" lang="{c}"' + (' aria-current="page"' if c == code else "") + f">{e(name)}</a></li>"
        for c, name in LANGS
    )
    alternates = "\n".join(f'<link rel="alternate" hreflang="{c}" href="{SITE + ("" if c == "en" else c + "/")}">' for c, _ in LANGS)
    alternates += f'\n<link rel="alternate" hreflang="x-default" href="{SITE}">'
    nav = t["nav"]

    tracks = "".join(
        f'<li><span class="no">a{i + 1}</span><h3>{title}</h3><p>{text}</p></li>' for i, (title, text) in enumerate(t["feats"])
    )
    coming = "".join(f'<li><span class="no">b{i + 1}</span><h3>{c}</h3></li>' for i, c in enumerate(t["coming"]))
    shots = "".join(
        f'<figure><img src="{base}img/{n}.webp" width="{w}" height="{h}" alt="{e(alt)}"><figcaption>{cap}</figcaption></figure>'
        for n, w, h, alt, cap in t["shots"]
    )
    priv = "".join(f"<li>{p}</li>" for p in t["priv"])
    c1, c2, c3 = t["num_cols"]
    rows = "".join(
        f'<tr{" class=us" if us else ""}><th scope="row">{e(label)}</th><td class="n">{a}</td><td class="n">{b}</td></tr>'
        for label, a, b, us in t["num_rows"]
    )
    steps = "".join(f"<li><span>{s}</span></li>" for s in t["steps"])
    route_links = {"apk": (APK, t["apk"]), "obt": (OBTAINIUM, t["obt"])}
    routes = "".join(
        f'<div class="route"><a href="{route_links[k][0]}">{e(route_links[k][1])}</a><span class="what">{txt}</span></div>' for k, txt in t["routes"]
    )
    credits = "".join(f"<dt>{k}</dt><dd>{v}</dd>" for k, v in t["credits"])

    return f"""<!doctype html>
<html lang="{code}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{e(t['title'])}</title>
<meta name="description" content="{e(t['desc'])}">
<link rel="canonical" href="{self_url}">
{alternates}
<meta property="og:type" content="website">
<meta property="og:title" content="{e(t['title'])}">
<meta property="og:description" content="{e(t['desc'])}">
<meta property="og:url" content="{self_url}">
<meta property="og:image" content="{SITE}img/og.jpg">
<meta property="og:locale" content="{t['locale']}">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#2e3a44">
<link rel="icon" href="{base}img/icon-512.png">
<link rel="apple-touch-icon" href="{base}img/apple-touch-icon.png">
<link rel="preload" href="{base}fonts/ptsans-400-latin.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="{base}style.css">
</head>
<body>
<a class="skip" href="#features">{e(t['skip'])}</a>

<header class="sleeve" id="top">
  <picture><source type="image/webp" srcset="{base}img/fog-photo.webp"><img class="fog" src="{base}img/fog-photo.png" alt="" width="1600" height="1600" fetchpriority="high"></picture>
  <div class="masthead">
    <a class="wordmark" href="#top">droneboard</a>
    <nav class="topnav" aria-label="Droneboard">
      <ul class="sections">
        <li><a href="#features">{nav['features']}</a></li>
        <li><a href="#privacy">{nav['privacy']}</a></li>
        <li><a href="#install">{nav['install']}</a></li>
        <li><a href="#nostr">{nav['nostr']}</a></li>
      </ul>
      <ul class="langs" aria-label="{e(t['langlabel'])}">{switcher}</ul>
    </nav>
  </div>
  <div class="stack">
    <h1>{e(t['h1'])}</h1>
    <p class="sub">{t['sub']}</p>
    {keyboard(t)}
    <div class="install">
      <a class="primary" href="{ZAPSTORE_APP}">{e(t['cta'])}</a>
      <span class="alt"><a href="{APK}">{e(t['apk'])}</a><a href="{OBTAINIUM}">{e(t['obt'])}</a></span>
    </div>
  </div>
  <a class="cue" href="#features">{ICON['down']}<span>{e(t['cue'])}</span></a>
</header>

<main>
<section class="panel" id="features" aria-labelledby="features-h">
  <h2 id="features-h">{e(t['feat_h'])}</h2>
  <p class="lede">{t['feat_p']}</p>
  <p class="side-label">{e(t['side_a'])}</p>
  <ol class="tracks">{tracks}</ol>
  <div class="side-b">
    <p class="side-label">{e(t['side_b'])}</p>
    <ol class="tracks b">{coming}</ol>
  </div>
  <div class="shots">{shots}</div>
</section>

<figure class="held">
  <picture><img src="{base}img/held-note.webp" alt="" width="1919" height="806" decoding="async"></picture>
  <figcaption>{t['origin']}</figcaption>
</figure>

<section class="panel" id="privacy" aria-labelledby="privacy-h">
  <div class="two">
    <div>
      <h2 id="privacy-h">{e(t['priv_h'])}</h2>
      <p class="lede">{t['priv_p']}</p>
      <ul class="plainlist">{priv}</ul>
      <p class="check">{t['check']}</p>
    </div>
    <div>
      <h2 id="numbers-h">{e(t['num_h'])}</h2>
      <table class="stats" aria-labelledby="numbers-h">
        <thead><tr><th scope="col">{c1}</th><th scope="col" class="n">{c2}</th><th scope="col" class="n">{c3}</th></tr></thead>
        <tbody>{rows}</tbody>
        <caption>{t['num_cap']}</caption>
      </table>
    </div>
  </div>
</section>

<section class="panel" id="install" aria-labelledby="install-h">
  <h2 id="install-h">{e(t['inst_h'])}</h2>
  <p class="lede">{t['inst_p']}</p>
  <div class="two">
    <ol class="steps">{steps}</ol>
    <div class="routes">
      <a class="primary" href="{ZAPSTORE_APP}">{e(t['cta'])}</a>
      {routes}
    </div>
  </div>
  <p class="inst-note">{t['src']}</p>
</section>

<section class="panel" id="nostr" aria-labelledby="nostr-h">
  <h2 id="nostr-h">{e(t['news_h'])}</h2>
  <p class="lede">{t['news_p']}</p>
  <div class="nostr-feed" data-nostr-live data-nprofile="{NPROFILE}" data-limit="4" data-lang="{code}" data-layout="list" data-relays="wss://nostr.cypherpunk.today,wss://relay.primal.net"></div>
  <ul class="nostr-links">
    <li><a href="https://njump.me/{NPROFILE}">{e(t['n_profile'])}</a></li>
    <li>droneboard@cypherpunk.today</li>
    <li>{e(t['n_zap'])}: droneboard@pay.cypherpunk.today</li>
  </ul>
</section>

<section class="panel credits" id="credits" aria-labelledby="credits-h">
  <h2 id="credits-h">{e(t['credits_h'])}</h2>
  <dl>{credits}</dl>
</section>
</main>

<footer class="foot">
  <span>{t['runout']}</span>
  <ul aria-label="{e(t['langlabel'])}">{foot_langs}</ul>
</footer>

<script src="{base}drone.js" defer></script>
<script src="{base}nostr-live.js?v=1.0.1" defer></script>
</body>
</html>
"""


def fog_image():
    out = ROOT / "img" / "fog-photo.webp"
    if PLATE.exists() and (not out.exists() or out.stat().st_mtime < PLATE.stat().st_mtime):
        cwebp = shutil.which("cwebp")
        if cwebp:
            subprocess.run([cwebp, "-quiet", "-q", "82", "-metadata", "all", str(PLATE), "-o", str(out)], check=True)
            print("wrote", out.relative_to(ROOT))
    png = ROOT / "img" / "fog-photo.png"
    if PLATE.exists() and (not png.exists() or png.stat().st_mtime < PLATE.stat().st_mtime):
        shutil.copy2(PLATE, png)


def main():
    fog_image()
    for code, _ in LANGS:
        out = ROOT / ("index.html" if code == "en" else f"{code}/index.html")
        out.parent.mkdir(exist_ok=True)
        out.write_text(page(code), encoding="utf-8")
        print("wrote", out.relative_to(ROOT))


if __name__ == "__main__":
    main()
