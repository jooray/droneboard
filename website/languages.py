"""The languages panel of the main page and the support link. Called from build.py.

Which language has what is read off the app's assets: a swipe word list is assets/swipe/<lang>.kpd, a neural
model <lang>.lmb + .lmv, a built-in tap dictionary assets/dicts/main_<lang>.dict. Update the lists here when
a language is added (docs/adding-a-language.md, section on the website).
"""
CONTACT = "https://cypherpunk.today/static/contact-juraj.html"
SUPPORT = "https://hackyourself.io/support/s/droneboard?ref=web&lang={lang}"

X = dict(
    en=dict(
        h="languages",
        p="What each language gets today. Languages with swipe can be mixed in one keyboard without switching.",
        rows=[
            ("swipe, neural predictions, emoji search",
             "Czech, Dutch, English, German, Hungarian, Slovak and Spanish. Each has a swipe word list, a neural next-word model that runs on the phone, and emoji search."),
            ("typed without accents",
             "Slovak and Czech also come in a version for people who type without diacritics. It has its own swipe word list and its own neural model."),
            ("tap typing with a dictionary",
             "Bulgarian, Bengali, British English, French, Greek, Italian, Polish, Portuguese (Brazil and Portugal), Romanian, Russian, Swedish and Turkish have a built-in dictionary that suggests words while you tap. They have no swipe typing and no neural model yet."),
            ("other languages",
             "The layouts HeliBoard has for many more languages are included. You add a dictionary for them from a file, in <strong>Settings → Dictionaries</strong>."),
            ("your language is missing",
             "An AI agent can train it on your own computer. The <a href=\"{agents}\">page for agents</a> has the guide. If you would rather not do it yourself, the author can add your language for a small fee: write through the <a href=\"{contact}\">contact form</a> or on <a href=\"{nostr}\">Nostr</a>."),
        ],
        support="Droneboard is free and open source. <a href=\"{support}\">Support it and get an e-book</a>.",
        support_short="support",
    ),
    sk=dict(
        h="jazyky",
        p="Čo ktorý jazyk dnes má. Jazyky so swipe sa dajú miešať v jednej klávesnici bez prepínania.",
        rows=[
            ("swipe, neurónové predikcie, hľadanie emoji",
             "Čeština, holandčina, angličtina, nemčina, maďarčina, slovenčina a španielčina. Každý z nich má zoznam slov pre swipe, neurónový model ďalšieho slova, ktorý beží v telefóne, a hľadanie emoji."),
            ("písanie bez diakritiky",
             "Slovenčina a čeština sú aj vo verzii pre ľudí, ktorí píšu bez mäkčeňov a dĺžňov. Má vlastný zoznam slov pre swipe aj vlastný neurónový model."),
            ("ťukanie so slovníkom",
             "Bulharčina, bengálčina, britská angličtina, francúzština, gréčtina, taliančina, poľština, portugalčina (Brazília aj Portugalsko), rumunčina, ruština, švédčina a turečtina majú vstavaný slovník, ktorý navrhuje slová pri ťukaní. Swipe ani neurónový model zatiaľ nemajú."),
            ("ostatné jazyky",
             "Rozloženia, ktoré má HeliBoard pre mnoho ďalších jazykov, sú súčasťou klávesnice. Slovník k nim pridáte zo súboru v <strong>Nastavenia → Slovníky</strong>."),
            ("váš jazyk chýba",
             "AI agent ho vie natrénovať na vašom počítači. Návod je na <a href=\"{agents}\">stránke pre agentov</a>. Ak to nechcete robiť sami, autor vám jazyk pridá za malý poplatok: napíšte cez <a href=\"{contact}\">kontaktný formulár</a> alebo na <a href=\"{nostr}\">Nostr</a>."),
        ],
        support="Droneboard je zadarmo a open source. <a href=\"{support}\">Podporte ho a dostanete e-knihu</a>.",
        support_short="podpora",
    ),
    cs=dict(
        h="jazyky",
        p="Co který jazyk dnes má. Jazyky se swipe se dají míchat v jedné klávesnici bez přepínání.",
        rows=[
            ("swipe, neuronové predikce, hledání emoji",
             "Čeština, nizozemština, angličtina, němčina, maďarština, slovenština a španělština. Každý má seznam slov pro swipe, neuronový model dalšího slova, který běží v telefonu, a hledání emoji."),
            ("psaní bez diakritiky",
             "Slovenština a čeština jsou i ve verzi pro lidi, kteří píšou bez háčků a čárek. Má vlastní seznam slov pro swipe i vlastní neuronový model."),
            ("ťukání se slovníkem",
             "Bulharština, bengálština, britská angličtina, francouzština, řečtina, italština, polština, portugalština (Brazílie i Portugalsko), rumunština, ruština, švédština a turečtina mají vestavěný slovník, který navrhuje slova při ťukání. Swipe ani neuronový model zatím nemají."),
            ("ostatní jazyky",
             "Rozložení, která má HeliBoard pro mnoho dalších jazyků, jsou součástí klávesnice. Slovník k nim přidáte ze souboru v <strong>Nastavení → Slovníky</strong>."),
            ("váš jazyk chybí",
             "AI agent ho umí natrénovat na vašem počítači. Návod je na <a href=\"{agents}\">stránce pro agenty</a>. Pokud to nechcete dělat sami, autor vám jazyk přidá za malý poplatek: napište přes <a href=\"{contact}\">kontaktní formulář</a> nebo na <a href=\"{nostr}\">Nostr</a>."),
        ],
        support="Droneboard je zdarma a open source. <a href=\"{support}\">Podpořte ho a dostanete e-knihu</a>.",
        support_short="podpora",
    ),
    de=dict(
        h="sprachen",
        p="Was jede Sprache heute hat. Sprachen mit Wischen lassen sich in einer Tastatur mischen, ohne umzuschalten.",
        rows=[
            ("wischen, neuronale vorhersage, emoji-suche",
             "Tschechisch, Niederländisch, Englisch, Deutsch, Ungarisch, Slowakisch und Spanisch. Jede hat eine Wortliste für das Wischen, ein neuronales Modell für das nächste Wort, das auf dem Telefon läuft, und die Emoji-Suche."),
            ("schreiben ohne akzente",
             "Slowakisch und Tschechisch gibt es auch in einer Variante für Menschen, die ohne diakritische Zeichen schreiben. Sie hat eine eigene Wortliste für das Wischen und ein eigenes neuronales Modell."),
            ("tippen mit wörterbuch",
             "Bulgarisch, Bengalisch, britisches Englisch, Französisch, Griechisch, Italienisch, Polnisch, Portugiesisch (Brasilien und Portugal), Rumänisch, Russisch, Schwedisch und Türkisch haben ein eingebautes Wörterbuch, das beim Tippen Wörter vorschlägt. Wischen und ein neuronales Modell haben sie noch nicht."),
            ("weitere sprachen",
             "Die Layouts, die HeliBoard für viele weitere Sprachen hat, sind enthalten. Ein Wörterbuch dazu fügen Sie aus einer Datei hinzu, unter <strong>Einstellungen → Wörterbücher</strong>."),
            ("ihre sprache fehlt",
             "Ein KI-Agent kann sie auf Ihrem eigenen Computer trainieren. Die Anleitung steht auf der <a href=\"{agents}\">Seite für Agenten</a>. Wenn Sie das nicht selbst machen möchten, fügt der Autor Ihre Sprache gegen eine kleine Gebühr hinzu: Schreiben Sie über das <a href=\"{contact}\">Kontaktformular</a> oder auf <a href=\"{nostr}\">Nostr</a>."),
        ],
        support="Droneboard ist kostenlos und quelloffen. <a href=\"{support}\">Unterstützen Sie es und erhalten Sie ein E-Book</a>.",
        support_short="unterstützen",
    ),
    es=dict(
        h="idiomas",
        p="Lo que tiene hoy cada idioma. Los idiomas con escritura deslizando se pueden mezclar en un mismo teclado sin cambiar.",
        rows=[
            ("deslizar, predicción neuronal, búsqueda de emojis",
             "Checo, neerlandés, inglés, alemán, húngaro, eslovaco y español. Cada uno tiene una lista de palabras para deslizar, un modelo neuronal de la palabra siguiente que funciona en el teléfono y búsqueda de emojis."),
            ("escribir sin acentos",
             "El eslovaco y el checo existen también en una versión para quien escribe sin signos diacríticos. Tiene su propia lista de palabras para deslizar y su propio modelo neuronal."),
            ("escribir tocando, con diccionario",
             "Búlgaro, bengalí, inglés británico, francés, griego, italiano, polaco, portugués (Brasil y Portugal), rumano, ruso, sueco y turco tienen un diccionario incluido que sugiere palabras mientras tocas las teclas. Todavía no tienen escritura deslizando ni modelo neuronal."),
            ("otros idiomas",
             "Las distribuciones que HeliBoard tiene para muchos más idiomas están incluidas. Su diccionario lo añades desde un archivo en <strong>Ajustes → Diccionarios</strong>."),
            ("falta tu idioma",
             "Un agente de IA puede entrenarlo en tu propio ordenador. La guía está en la <a href=\"{agents}\">página para agentes</a>. Si prefieres no hacerlo tú, el autor añade tu idioma por una pequeña tarifa: escribe por el <a href=\"{contact}\">formulario de contacto</a> o por <a href=\"{nostr}\">Nostr</a>."),
        ],
        support="Droneboard es gratis y de código abierto. <a href=\"{support}\">Apóyalo y recibe un libro electrónico</a>.",
        support_short="apoyar",
    ),
)


def support_url(code):
    return SUPPORT.format(lang=code)


def section(code, agents_href, nostr_href):
    d = X[code]
    fmt = dict(agents=agents_href, contact=CONTACT, nostr=nostr_href)
    rows = "".join(
        f'<li><span class="no">l{i + 1}</span><h3>{title}</h3><p>{text.format(**fmt)}</p></li>'
        for i, (title, text) in enumerate(d["rows"])
    )
    return (
        f'<section class="panel" id="languages" aria-labelledby="languages-h">\n'
        f'  <h2 id="languages-h">{d["h"]}</h2>\n'
        f'  <p class="lede">{d["p"]}</p>\n'
        f'  <ol class="tracks">{rows}</ol>\n'
        f"</section>"
    )


def support_note(code):
    return X[code]["support"].format(support=support_url(code))


def support_footer(code):
    return f'<a href="{support_url(code)}">{X[code]["support_short"]}</a>'
