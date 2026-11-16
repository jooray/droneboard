"""Small looping animations for the tips page, one per tip.

Each one is an inline SVG (a text line above a simplified keyboard) moved by CSS keyframes. The keyframes
depend on the language (sample words, keyboard layout), so they are generated here and inlined in the
page head; the colours live in style.css (.ta). Every animated element also gets a static style, which is
what people with prefers-reduced-motion see: the most telling frame of the gesture. The animations are
only attached inside @media (prefers-reduced-motion: no-preference).
"""
from html import escape

W, H = 240, 164
CW, X0, BASE = 7.8, 8.0, 22  # field text: monospaced cells (13px), first cell, baseline
SY = 50  # centre line of the suggestion strip
ROW_Y = [63, 87, 111, 135]
KH = 21
GAP = 3

LAYOUT = dict(en="qwerty", es="qwerty", de="qwertz", sk="qwertz", cs="qwertz")
ROWS = {
    "qwerty": ["qwertyuiop", "asdfghjkl", "zxcvbnm"],
    "qwertz": ["qwertzuiop", "asdfghjkl", "yxcvbnm"],
}

# Sample words. Field phrases must stay under ~27 characters.
L = dict(
    en=dict(phrase="see you tomorrow", lp=("caf", "e", "èéêë", 1), swipe="hello",
            multi=(("thank", "thanks", "thanked"), ("dank", "danke", "Danke"), "EN · DE"),
            nodia=("dakujem", ("dakujeme", "dakujem", "dakujes")), emoji=":rocket", incog="secret"),
    sk=dict(phrase="uvidíme sa zajtra", lp=("pekn", "e", "ěéëè", 1), swipe="ahoj",
            multi=(("ďakujeme", "ďakujem", "ďakovať"), ("thank", "thanks", "than"), "SK · EN"),
            nodia=("dakujem", ("dakujeme", "dakujem", "dakujes")), emoji=":raketa", incog="heslo"),
    cs=dict(phrase="uvidíme se zítra", lp=("Jiř", "i", "ìíïî", 1), swipe="ahoj",
            multi=(("děkujeme", "děkuji", "děkovat"), ("thank", "thanks", "than"), "CS · EN"),
            nodia=("dekuji", ("dekujeme", "dekuji", "dekujes")), emoji=":raketa", incog="heslo"),
    de=dict(phrase="wir sehen uns morgen", lp=("Caf", "e", "èéêë", 1), swipe="hallo",
            multi=(("dank", "danke", "Dank"), ("thank", "thanks", "than"), "DE · EN"),
            nodia=("dakujem", ("dakujeme", "dakujem", "dakujes")), emoji=":rakete", incog="geheim"),
    es=dict(phrase="nos vemos mañana", lp=("mam", "a", "àáâä", 1), swipe="hola",
            multi=(("gracia", "gracias", "gratis"), ("thank", "thanks", "than"), "ES · EN"),
            nodia=("dakujem", ("dakujeme", "dakujem", "dakujes")), emoji=":cohete", incog="secreto"),
)

# What each animation shows, for screen readers, in the page language.
LABELS = dict(
    en=[
        "Animation: a finger slides along the space bar and the cursor in the text moves back letter by letter.",
        "Animation: a finger holds the space bar still, the phone vibrates, then the finger moves over the keys and the cursor follows.",
        "Animation: a finger slides left from the delete key, the last words get selected, sliding back selects less, and lifting deletes the selection.",
        "Animation: holding a key opens a row of accented letters; the finger slides to one and it is typed.",
        "Animation: a finger draws a line through the letters of a word and the word appears.",
        "Animation: suggestions in two languages appear and words from both are typed without switching.",
        "Animation: a word typed without diacritics gets suggestions without diacritics.",
        "Animation: in a password field a number row appears above the letters and digits are typed.",
        "Animation: typing a colon and a word brings up a matching emoji in the suggestion strip.",
        "Animation: a finger drags the corner of the keyboard and the keyboard gets smaller.",
        "Animation: tapping the arrow at the left of the suggestion strip opens the toolbar.",
        "Animation: tapping Clipboard shows copied items, and tapping one pastes it.",
        "Animation: tapping the mic and speaking types the words into the text.",
        "Animation: tapping the incognito key turns it on before typing.",
    ],
    sk=[
        "Animácia: prst sa posúva po medzerníku a kurzor v texte ide späť po písmenách.",
        "Animácia: prst drží medzerník bez pohnutia, telefón zavibruje, potom prst ide po klávesoch a kurzor ide s ním.",
        "Animácia: prst ide od mazania doľava, posledné slová sa označia, posunutím späť sa označí menej a po zdvihnutí sa označený text zmaže.",
        "Animácia: podržaním klávesu sa otvorí rad písmen s diakritikou, prst prejde na jedno z nich a to sa napíše.",
        "Animácia: prst prejde čiaru cez písmená slova a slovo sa objaví.",
        "Animácia: objavia sa návrhy v dvoch jazykoch a napíšu sa slová z oboch bez prepínania.",
        "Animácia: slovo písané bez diakritiky dostane návrhy bez diakritiky.",
        "Animácia: v poli na heslo sa nad písmenami objaví číselný riadok a napíšu sa číslice.",
        "Animácia: po napísaní dvojbodky a slova sa v pruhu s návrhmi objaví zodpovedajúce emoji.",
        "Animácia: prst ťahá roh klávesnice a klávesnica sa zmenší.",
        "Animácia: ťuknutím na šípku vľavo v pruhu s návrhmi sa otvorí panel nástrojov.",
        "Animácia: ťuknutím na Schránku sa zobrazia skopírované položky a ťuknutím na jednu sa vloží.",
        "Animácia: ťuknutím na mikrofón a hovorením sa slová napíšu do textu.",
        "Animácia: ťuknutím na kláves inkognito sa pred písaním zapne.",
    ],
    cs=[
        "Animace: prst se posouvá po mezerníku a kurzor v textu jde zpět po písmenech.",
        "Animace: prst drží mezerník bez hnutí, telefon zavibruje, pak prst jede po klávesách a kurzor jde s ním.",
        "Animace: prst jede od mazání doleva, poslední slova se označí, posunutím zpět se označí méně a po zvednutí se označený text smaže.",
        "Animace: podržením klávesy se otevře řada písmen s diakritikou, prst přejede na jedno z nich a to se napíše.",
        "Animace: prst projede čáru přes písmena slova a slovo se objeví.",
        "Animace: objeví se návrhy ve dvou jazycích a napíšou se slova z obou bez přepínání.",
        "Animace: slovo psané bez diakritiky dostane návrhy bez diakritiky.",
        "Animace: v poli pro heslo se nad písmeny objeví číselný řádek a napíšou se číslice.",
        "Animace: po napsání dvojtečky a slova se v pruhu s návrhy objeví odpovídající emoji.",
        "Animace: prst táhne roh klávesnice a klávesnice se zmenší.",
        "Animace: klepnutím na šipku vlevo v pruhu s návrhy se otevře panel nástrojů.",
        "Animace: klepnutím na Schránku se zobrazí zkopírované položky a klepnutím na jednu se vloží.",
        "Animace: klepnutím na mikrofon a mluvením se slova napíšou do textu.",
        "Animace: klepnutím na klávesu inkognito se před psaním zapne.",
    ],
    de=[
        "Animation: Ein Finger wischt über die Leertaste, und der Cursor im Text wandert Buchstabe für Buchstabe zurück.",
        "Animation: Ein Finger hält die Leertaste still, das Telefon vibriert, dann fährt der Finger über die Tasten und der Cursor folgt.",
        "Animation: Ein Finger wischt von der Löschtaste nach links, die letzten Wörter werden markiert, zurückwischen markiert weniger, und beim Loslassen wird die Markierung gelöscht.",
        "Animation: Langes Drücken einer Taste öffnet eine Reihe Buchstaben mit Akzent; der Finger fährt zu einem, und er wird getippt.",
        "Animation: Ein Finger zieht eine Linie durch die Buchstaben eines Wortes, und das Wort erscheint.",
        "Animation: Vorschläge in zwei Sprachen erscheinen, und Wörter aus beiden werden ohne Umschalten getippt.",
        "Animation: Ein Wort ohne Diakritika bekommt Vorschläge ohne Diakritika.",
        "Animation: In einem Passwortfeld erscheint über den Buchstaben eine Ziffernzeile, und Ziffern werden getippt.",
        "Animation: Nach einem Doppelpunkt und einem Wort erscheint ein passendes Emoji in der Vorschlagsleiste.",
        "Animation: Ein Finger zieht an der Ecke der Tastatur, und die Tastatur wird kleiner.",
        "Animation: Ein Tipp auf den Pfeil links in der Vorschlagsleiste öffnet die Symbolleiste.",
        "Animation: Ein Tipp auf Zwischenablage zeigt kopierte Einträge, und ein Tipp auf einen fügt ihn ein.",
        "Animation: Nach einem Tipp auf das Mikrofon wird Gesprochenes in den Text getippt.",
        "Animation: Ein Tipp auf die Inkognito-Taste schaltet sie vor dem Tippen ein.",
    ],
    es=[
        "Animación: un dedo se desliza por la barra espaciadora y el cursor del texto retrocede letra a letra.",
        "Animación: un dedo mantiene quieta la barra espaciadora, el teléfono vibra, luego el dedo se mueve sobre las teclas y el cursor lo sigue.",
        "Animación: un dedo se desliza a la izquierda desde borrar, se seleccionan las últimas palabras, al volver se selecciona menos y al levantar el dedo se borra la selección.",
        "Animación: al mantener una tecla aparece una fila de letras con acento; el dedo va a una y se escribe.",
        "Animación: un dedo traza una línea por las letras de una palabra y la palabra aparece.",
        "Animación: aparecen sugerencias en dos idiomas y se escriben palabras de ambos sin cambiar.",
        "Animación: una palabra escrita sin diacríticos recibe sugerencias sin diacríticos.",
        "Animación: en un campo de contraseña aparece una fila de números sobre las letras y se escriben cifras.",
        "Animación: al escribir dos puntos y una palabra aparece un emoji en la barra de sugerencias.",
        "Animación: un dedo arrastra la esquina del teclado y el teclado se hace más pequeño.",
        "Animación: al tocar la flecha de la izquierda de la barra de sugerencias se abre la barra de herramientas.",
        "Animación: al tocar Portapapeles aparecen los elementos copiados y al tocar uno se pega.",
        "Animación: al tocar el micrófono y hablar, las palabras se escriben en el texto.",
        "Animación: al tocar la tecla de incógnito se activa antes de escribir.",
    ],
)


def n(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def tr(x, y=0):
    return f"transform:translate({n(x)}px,{n(y)}px)"


def keys(code):
    """Key boxes: name -> (x, y, w, h). Letters by their character."""
    r1, r2, r3 = ROWS[LAYOUT[code]]
    if code == "es":
        r2 = r2 + "ñ"
    k = {}
    inner, x0 = W - 8, 4.0
    w1 = (inner - 9 * GAP) / 10

    def row(names, widths, y, start=x0):
        x = start
        for nm, w in zip(names, widths):
            k[nm] = (x, y, w, KH)
            x += w + GAP

    row(list(r1), [w1] * 10, ROW_Y[0])
    row(list(r2), [w1] * len(r2), ROW_Y[1], x0 if len(r2) == 10 else x0 + (w1 + GAP) / 2)
    ws = (inner - 7 * w1 - 8 * GAP) / 2
    row(["shift"] + list(r3) + ["del"], [ws] + [w1] * 7 + [ws], ROW_Y[2])
    sp = inner - 2 * ws - 2 * w1 - 4 * GAP
    row(["sym", ",", "space", ".", "enter"], [ws, w1, sp, w1, ws], ROW_Y[3])
    return k


def center(box):
    x, y, w, h = box
    return x + w / 2, y + h / 2


def icon(kind, cx, cy, cls="ic"):
    p = {
        "chev_r": f"M{n(cx - 2)} {n(cy - 4)}l4 4-4 4",
        "chev_l": f"M{n(cx + 2)} {n(cy - 4)}l-4 4 4 4",
        "mic": f"M{n(cx - 2.5)} {n(cy - 3.5)}a2.5 2.5 0 0 1 5 0v3a2.5 2.5 0 0 1-5 0zM{n(cx - 5)} {n(cy - 1)}a5 5 0 0 0 10 0M{n(cx)} {n(cy + 4)}v3",
        "clip": f"M{n(cx - 4.5)} {n(cy - 4.5)}h9v11h-9zM{n(cx - 2)} {n(cy - 6)}h4v2.5h-4z",
        "dpad": f"M{n(cx)} {n(cy - 7)}l2.5 3h-5zM{n(cx)} {n(cy + 7)}l2.5-3h-5zM{n(cx - 7)} {n(cy)}l3 2.5v-5zM{n(cx + 7)} {n(cy)}l-3 2.5v-5z",
        "undo": f"M{n(cx - 5)} {n(cy - 2)}h6.5a3.5 3.5 0 0 1 0 7h-3M{n(cx - 2)} {n(cy - 5)}l-3 3 3 3",
        "redo": f"M{n(cx + 5)} {n(cy - 2)}h-6.5a3.5 3.5 0 0 0 0 7h3M{n(cx + 2)} {n(cy - 5)}l3 3-3 3",
        "gear": f"M{n(cx + 2.5)} {n(cy)}a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0z",
        "incog": f"M{n(cx - 7)} {n(cy)}h14M{n(cx - 4.5)} {n(cy)}l1.5-5.5h6l1.5 5.5M{n(cx - 1.6)} {n(cy + 3.6)}a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 1 1 4.4 0zM{n(cx + 6)} {n(cy + 3.6)}a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 1 1 4.4 0zM{n(cx - 1.6)} {n(cy + 3.4)}h3.2",
        "lock": f"M{n(cx - 4)} {n(cy - 1)}h8v6h-8zM{n(cx - 2.5)} {n(cy - 1)}v-2a2.5 2.5 0 0 1 5 0v2",
        "pin": f"M{n(cx)} {n(cy + 5)}v-4M{n(cx - 3.5)} {n(cy + 1)}h7l-1.5-2v-4h-4v4z",
    }[kind]
    extra = ""
    if kind == "gear":
        extra = f'<circle class="{cls} gr" cx="{n(cx)}" cy="{n(cy)}" r="5.5"/>'
    if kind == "dpad":
        return f'<path class="icf" d="{p}"/>'
    return f'<path class="{cls}" d="{p}"/>{extra}'


def keyboard(code, hints=False, space_label="", rows_cls=""):
    k = keys(code)
    out = [f'<rect class="kbp" x="0" y="38" width="{W}" height="{H - 38}" rx="8"/>']
    rows = []
    r1 = ROWS[LAYOUT[code]][0]
    for nm, (x, y, w, h) in k.items():
        fn = nm in ("shift", "del", "sym", "enter")
        rows.append(f'<rect class="{"kf" if fn else "k"}" x="{n(x)}" y="{y}" width="{n(w)}" height="{h}" rx="3.5"/>')
        cx, cy = center((x, y, w, h))
        if len(nm) == 1:
            rows.append(f'<text class="kl" x="{n(cx)}" y="{n(cy + 3.5)}">{escape(nm)}</text>')
            if hints and nm in r1:
                rows.append(f'<text class="kh" x="{n(x + w - 3)}" y="{y + 7}">{(r1.index(nm) + 1) % 10}</text>')
        elif nm == "sym":
            rows.append(f'<text class="kl ks" x="{n(cx)}" y="{n(cy + 3)}">?123</text>')
        elif nm == "shift":
            rows.append(f'<path class="ic" d="M{n(cx)} {n(cy - 5)}l5 5h-2.6v4h-4.8v-4H{n(cx - 5)}z"/>')
        elif nm == "del":
            rows.append(f'<path class="ic" d="M{n(cx - 7)} {n(cy)}l3.5-4.5h10v9h-10zM{n(cx - 1)} {n(cy - 2)}l4 4m0-4l-4 4"/>')
        elif nm == "enter":
            rows.append(f'<path class="ic" d="M{n(cx + 5)} {n(cy - 4)}v4h-10m3-3-3 3 3 3"/>')
        elif nm == "space" and space_label:
            rows.append(f'<text class="kl ks" x="{n(cx)}" y="{n(cy + 3)}">{escape(space_label)}</text>')
    cls = f' class="{rows_cls}"' if rows_cls else ""
    out.append(f"<g{cls}>{''.join(rows)}</g>")
    return "".join(out)


def field_text(s, start=0, cls="ft", extra=""):
    xs = " ".join(n(X0 + (start + i) * CW) for i in range(len(s)))
    c = f"{cls} {extra}".strip()
    return f'<text class="{c}" x="{xs}" y="{BASE}">{escape(s)}</text>'


def field_line():
    return f'<line class="ln" x1="0" y1="32" x2="{W}" y2="32"/>'


def cx_at(k):
    """translate for a cursor (drawn at cell 0) to sit before cell k"""
    return k * CW


class Anim:
    def __init__(self, name, dur):
        self.name, self.dur = name, dur
        self.css, self.media, self.i = [], [], 0

    def cls(self):
        self.i += 1
        return f"e{self.i}"

    def el(self, static, frames, tf="linear", extra="", c=None):
        """Animate one element. frames: [(pct, decls) | (pct, decls, timing)]. Returns its class."""
        c = c or self.cls()
        sel = f".ta-{self.name} .{c}"
        if extra or static:
            self.css.append(f"{sel}{{{';'.join(x for x in (extra, static) if x)}}}")
        kf = f"ta-{self.name}-{c}"
        steps = []
        for f in frames:
            pct, decl = f[0], f[1]
            t = f";animation-timing-function:{f[2]}" if len(f) > 2 else ""
            steps.append(f"{n(pct)}%{{{decl}{t}}}")
        self.css.append(f"@keyframes {kf}{{{''.join(steps)}}}")
        self.media.append(f"{sel}{{animation:{kf} {self.dur}s {tf} infinite}}")
        return c


def finger(a, static_xy, static_op, moves, ops, move_tf="linear", op_tf="linear"):
    """A touch circle: the <g> moves, the <circle> fades. moves/ops are keyframe lists."""
    gx = a.el(tr(*static_xy), [(p, tr(*xy)) for p, xy in moves], move_tf)
    co = a.el(f"opacity:{static_op}", [(p, f"opacity:{o}") for p, o in ops], op_tf)
    return f'<g class="{gx}"><circle class="fg {co}" r="9"/></g>'


def cursor(a, static_k, frames, extra_static=""):
    """Text cursor, moved in whole cells; frames: [(pct, k)] or [(pct, k, opacity)]."""
    def decl(f):
        d = tr(cx_at(f[1]))
        return d + (f";opacity:{f[2]}" if len(f) > 2 else "")
    st = tr(cx_at(static_k)) + (f";{extra_static}" if extra_static else "")
    c = a.el(st, [(f[0], decl(f)) for f in frames], "step-end")
    return f'<rect class="cur {c}" x="{n(X0 - 1)}" y="9" width="1.6" height="17"/>'


def cover(a, static_k, frames):
    """A rect in the page colour that hides the not-yet-typed cells; frames: [(pct, k)]."""
    c = a.el(tr(cx_at(static_k)), [(p, tr(cx_at(k))) for p, k in frames], "step-end")
    return f'<rect class="cover {c}" x="{n(X0 - 0.5)}" y="7" width="{W}" height="22"/>'


def opac(a, static, frames, tf="step-end"):
    return a.el(f"opacity:{static}", [(p, f"opacity:{o}") for p, o in frames], tf)


def strip_default(chev="r"):
    return icon("chev_" + chev, 12, SY) + icon("mic", 228, SY)


def sugg(words, cls=""):
    c = f' class="{cls}"' if cls else ""
    return f"<g{c}>" + "".join(f'<text class="sg" x="{x}" y="{SY + 4}">{escape(w)}</text>' for x, w in zip((70, 120, 170), words)) + "</g>"


def toolbar(icons, xs=(40, 72, 104, 136, 168)):
    return "".join(icon(i, x, SY) for i, x in zip(icons, xs))


# ───── the fourteen animations ─────

def a_space(code, a):
    P = L[code]["phrase"]; m = len(P)
    sx, sy = center(keys(code)["space"])
    body = field_line() + field_text(P)
    body += cursor(a, m - 5, [(0, m)] + [(12 + 8 * i, m - i) for i in range(1, 6)] + [(100, m - 5)])
    body += keyboard(code) + strip_default()
    body += finger(a, (sx - 30, sy), 1, [(0, (sx + 30, sy)), (12, (sx + 30, sy)), (54, (sx - 30, sy)), (100, (sx - 30, sy))],
                   [(0, 0), (9, 0), (12, 1), (60, 1), (64, 0), (100, 0)])
    return body


def a_trackpad(code, a):
    P = L[code]["phrase"]; m = len(P)
    sx, sy = center(keys(code)["space"])
    body = field_line() + field_text(P)
    body += cursor(a, m - 8, [(0, m)] + [(38 + 4.25 * i, m - i) for i in range(1, 9)] + [(100, m - 8)])
    # the app fades the keys to 15 % while the trackpad is on (KeyboardActionListenerImpl)
    body += keyboard(code, rows_cls=opac(a, 0.15, [(0, 1), (17, 1), (19, 0.15), (79, 0.15), (81, 1), (100, 1)], "linear")) + strip_default()
    for d in (0, 5):
        rc = a.el("transform:scale(1.7);opacity:.35",
                  [(0, "transform:scale(1);opacity:0"), (17 + d, "transform:scale(1);opacity:0"), (17.5 + d, "transform:scale(1);opacity:.9"),
                   (30 + d, "transform:scale(2.6);opacity:0"), (100, "transform:scale(2.6);opacity:0")],
                  extra="transform-box:fill-box;transform-origin:center")
        body += f'<circle class="rg {rc}" cx="{n(sx)}" cy="{n(sy)}" r="10"/>'
    p1, p2 = (sx - 35, sy - 50), (sx - 72, sy - 28)
    body += finger(a, p2, 1, [(0, (sx, sy)), (36, (sx, sy)), (54, p1), (72, p2), (100, p2)],
                   [(0, 0), (6, 0), (8, 1), (78, 1), (81, 0), (100, 0)], move_tf="ease-in-out")
    return body


def a_delete(code, a):
    P = L[code]["phrase"]; m = len(P)
    il = P.rindex(" ") + 1
    ip = P[:il - 1].rindex(" ") + 1
    r1 = (m - il) / (m - ip)
    dx, dy = center(keys(code)["del"])
    sc = a.el(f"opacity:1;transform:scaleX({n(r1)})",
              [(0, f"opacity:0;transform:scaleX({n(r1)})"), (14, f"opacity:1;transform:scaleX({n(r1)})"), (26, "opacity:1;transform:scaleX(1)"),
               (46, f"opacity:1;transform:scaleX({n(r1)})"), (58, f"opacity:0;transform:scaleX({n(r1)})"), (100, f"opacity:0;transform:scaleX({n(r1)})")],
              "step-end", extra="transform-box:fill-box;transform-origin:100% 50%")
    body = field_line()
    body += f'<rect class="sel {sc}" x="{n(X0 + ip * CW - 1)}" y="8" width="{n((m - ip) * CW + 1)}" height="19" rx="2"/>'
    body += field_text(P[:il]) + field_text(P[il:], il, extra=opac(a, 1, [(0, 1), (58, 0), (100, 0)]))
    body += cursor(a, m, [(0, m, 1), (12, m, 0), (58, il, 1), (100, il, 1)], "opacity:0")
    body += keyboard(code) + strip_default()
    body += finger(a, (dx - 26, dy), 1, [(0, (dx, dy)), (8, (dx, dy)), (30, (dx - 48, dy)), (40, (dx - 48, dy)), (48, (dx - 26, dy)), (100, (dx - 26, dy))],
                   [(0, 0), (6, 0), (8, 1), (56, 1), (59, 0), (100, 0)])
    return body


def a_longpress(code, a):
    pre, key, var, ch = L[code]["lp"]
    kx, ky = center(keys(code)[key])
    cells = len(var); pw = cells * 20 + (cells - 1) * 2 + 4
    px = min(max(kx - pw / 2, 2), W - 2 - pw)
    py = ky - KH / 2 - 30
    ccx, ccy = px + 2 + ch * 22 + 10, py + 13
    body = field_line() + field_text(pre)
    body += field_text(var[ch], len(pre), extra=opac(a, 0, [(0, 0), (50, 1), (100, 1)]))
    body += cursor(a, len(pre), [(0, len(pre)), (50, len(pre) + 1), (100, len(pre) + 1)])
    body += keyboard(code, hints=True) + strip_default()
    pop = opac(a, 1, [(0, 0), (22, 1), (50, 0), (100, 0)])
    hl = opac(a, 1, [(0, 0), (34, 1), (50, 0), (100, 0)])
    g = [f'<rect class="pop" x="{n(px)}" y="{n(py)}" width="{n(pw)}" height="26" rx="5"/>',
         f'<rect class="hl {hl}" x="{n(ccx - 10)}" y="{n(py + 3)}" width="20" height="20" rx="3"/>']
    for i, c in enumerate(var):
        g.append(f'<text class="kl pl" x="{n(px + 2 + i * 22 + 10)}" y="{n(py + 17.5)}">{escape(c)}</text>')
    body += f'<g class="{pop}">{"".join(g)}</g>'
    body += finger(a, (ccx, ccy), 1, [(0, (kx, ky)), (26, (kx, ky)), (34, (ccx, ccy)), (100, (ccx, ccy))],
                   [(0, 0), (6, 0), (8, 1), (48, 1), (51, 0), (100, 0)])
    return body


def a_swipe(code, a):
    w = L[code]["swipe"]
    k = keys(code)
    pts = []
    for c in w:
        p = center(k[c])
        if not pts or pts[-1] != p:
            pts.append(p)
    seg = [0.0]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        seg.append(seg[-1] + ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5)
    tot = seg[-1] or 1
    d = "M" + " L".join(f"{n(x)} {n(y)}" for x, y in pts)
    body = field_line()
    body += field_text(w, extra=opac(a, 1, [(0, 0), (50, 1), (100, 1)]))
    body += cursor(a, len(w) + 1, [(0, 0), (50, len(w) + 1), (100, len(w) + 1)])
    body += keyboard(code) + strip_default()
    dash = a.el("stroke-dashoffset:0", [(0, "stroke-dashoffset:1"), (10, "stroke-dashoffset:1"), (48, "stroke-dashoffset:0"), (100, "stroke-dashoffset:0")])
    fade = a.el("opacity:1", [(0, "opacity:1"), (54, "opacity:1"), (66, "opacity:0"), (100, "opacity:0")])
    body += f'<g class="{fade}"><path class="trail {dash}" pathLength="1" d="{d}"/></g>'
    moves = [(0, pts[0]), (10, pts[0])] + [(10 + 38 * s / tot, p) for s, p in zip(seg[1:], pts[1:])] + [(100, pts[-1])]
    body += finger(a, pts[-1], 1, moves, [(0, 0), (7, 0), (9, 1), (48, 1), (51, 0), (100, 0)])
    return body


def a_multi(code, a):
    s1, s2, lab = L[code]["multi"]
    w1, w2 = s1[1], s2[1]
    body = field_line()
    body += field_text(w1, extra=opac(a, 1, [(0, 0), (20, 1), (100, 1)]))
    body += field_text(w2, len(w1) + 1, extra=opac(a, 1, [(0, 0), (48, 1), (100, 1)]))
    e2 = len(w1) + len(w2) + 2
    body += cursor(a, e2, [(0, 0), (20, len(w1) + 1), (48, e2), (100, e2)])
    body += keyboard(code, space_label=lab) + strip_default()
    body += sugg(s1, opac(a, 0, [(0, 1), (28, 0), (100, 0)]))
    body += sugg(s2, opac(a, 1, [(0, 0), (30, 1), (100, 1)]))
    body += finger(a, (120, SY), 0, [(0, (120, SY)), (100, (120, SY))],
                   [(0, 0), (14, 1), (21, 0), (42, 1), (49, 0), (100, 0)], op_tf="step-end")
    return body


def taps(a, code, word, t0, step, final_xy=None, final_t=None, static_op=0, first_from=0):
    """Finger hops over the keys of [word] from char index [first_from]: (finger svg, reveal times)."""
    k = keys(code)
    moves, ops, times = [], [(0, 0)], []
    for i, c in enumerate(word):
        t = t0 + step * i
        times.append(t)
        if i < first_from or c not in k:
            continue
        xy = center(k[c])
        moves.append((t, xy))
        ops += [(t, 1), (t + step * 0.6, 0)]
    if final_xy:
        moves.append((final_t, final_xy))
        ops += [(final_t, 1), (final_t + 5, 0)]
    moves = [(0, moves[0][1])] + moves + [(100, moves[-1][1])]
    ops.append((100, 0))
    static_xy = final_xy or moves[-1][1]
    return finger(a, static_xy, static_op, moves, ops, "step-end", "step-end"), times


def a_nodia(code, a):
    w, sg = L[code]["nodia"]
    f, times = taps(a, code, w[:3], 8, 10, (120, SY), 44)
    body = field_line() + field_text(w)
    body += cover(a, len(w) + 1, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(48, len(w) + 1), (100, len(w) + 1)])
    body += cursor(a, len(w) + 1, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(48, len(w) + 1), (100, len(w) + 1)])
    body += keyboard(code) + strip_default()
    body += sugg(sg, opac(a, 1, [(0, 0), (36, 1), (100, 1)]))
    return body + f


def a_numrow(code, a):
    k = keys(code)
    body = field_line() + field_text("•••••") + icon("lock", 228, 18)
    body += cover(a, 5, [(0, 3), (42, 4), (62, 5), (100, 5)])
    body += cursor(a, 5, [(0, 3), (42, 4), (62, 5), (100, 5)])
    body += keyboard(code)
    sd = a.el("opacity:0", [(0, "opacity:1"), (14, "opacity:1"), (18, "opacity:0"), (100, "opacity:0")])
    body += f'<g class="{sd}">{strip_default()}</g>'
    nr = a.el("opacity:1;transform:translateY(0)", [(0, "opacity:0;transform:translateY(6px)"), (14, "opacity:0;transform:translateY(6px)"),
                                                    (22, "opacity:1;transform:translateY(0)"), (100, "opacity:1;transform:translateY(0)")], "ease-out")
    g = []
    for i, c in enumerate("qwertyuiop"):
        x, _, w, _ = k[ROWS[LAYOUT[code]][0][i]]
        g.append(f'<rect class="k" x="{n(x)}" y="41" width="{n(w)}" height="18" rx="3.5"/><text class="kl" x="{n(x + w / 2)}" y="53.5">{(i + 1) % 10}</text>')
    body += f'<g class="{nr}">{"".join(g)}</g>'
    x7, x3 = k[ROWS[LAYOUT[code]][0][6]], k[ROWS[LAYOUT[code]][0][2]]
    p7, p3 = (x7[0] + x7[2] / 2, 50), (x3[0] + x3[2] / 2, 50)
    body += finger(a, p3, 0, [(0, p7), (50, p3), (100, p3)], [(0, 0), (40, 1), (45, 0), (60, 1), (65, 0), (100, 0)], "step-end", "step-end")
    return body


def a_emoji(code, a):
    w = L[code]["emoji"]; m = len(w)
    f, times = taps(a, code, w, 6, 4, (120, SY), 50, static_op=1, first_from=1)
    gone = opac(a, 1, [(0, 1), (55, 0), (100, 0)])
    body = field_line() + field_text(w, extra=gone)
    body += f'<text class="fe {opac(a, 0, [(0, 0), (55, 1), (100, 1)])}" x="{n(X0)}" y="{BASE + 1}">🚀</text>'
    body += cover(a, m, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(100, m)])
    body += cursor(a, m, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(55, 2.6), (100, 2.6)])
    body += keyboard(code) + strip_default()
    body += f'<text class="se {opac(a, 1, [(0, 0), (34, 1), (100, 1)])}" x="120" y="{SY + 5}">🚀</text>'
    return body + f


def a_resize(code, a):
    s = 0.8
    gc = a.el(f"transform:scale({s})",
              [(0, "transform:scale(1);opacity:1"), (20, "transform:scale(1);opacity:1"), (50, f"transform:scale({s});opacity:1"),
               (86, f"transform:scale({s});opacity:1"), (92, f"transform:scale({s});opacity:0"), (93, "transform:scale(1);opacity:0"),
               (100, "transform:scale(1);opacity:1")], "ease-in-out", extra=f"transform-box:view-box;transform-origin:0 {H}px")
    handles = "".join(f'<rect class="hd" x="{n(x - 3)}" y="{n(y - 3)}" width="6" height="6"/>' for x, y in ((3.5, 41.5), (W - 3.5, 41.5), (3.5, H - 3.5), (W - 3.5, H - 3.5)))
    body = field_line() + cursor(a, 0, [(0, 0), (100, 0)])
    body += f'<g class="{gc}">{keyboard(code)}{strip_default()}<rect class="fr" x="3.5" y="41.5" width="{W - 7}" height="{H - 45}"/>{handles}</g>'
    c0 = (W - 3.5, 41.5)
    c1 = ((W - 3.5) * s, H - (H - 41.5) * s)
    body += finger(a, c1, 1, [(0, c0), (20, c0), (50, c1), (100, c1)], [(0, 0), (12, 0), (16, 1), (56, 1), (60, 0), (100, 0)], "ease-in-out")
    return body


def a_toolbar(code, a):
    P = L[code]["phrase"].split()
    body = field_line() + field_text(L[code]["phrase"]) + cursor(a, len(L[code]["phrase"]), [(0, len(L[code]["phrase"])), (100, len(L[code]["phrase"]))])
    body += keyboard(code) + icon("mic", 228, SY)
    ch = a.el("transform:rotate(180deg)", [(0, "transform:rotate(0)"), (18, "transform:rotate(180deg)"), (88, "transform:rotate(180deg)"), (90, "transform:rotate(0)"), (100, "transform:rotate(0)")],
              "step-end", extra="transform-box:fill-box;transform-origin:center")
    body += f'<g class="{ch}"><rect x="6" y="{SY - 6}" width="12" height="12" fill="none"/>{icon("chev_r", 12, SY)}</g>'
    sg = a.el("opacity:0", [(0, "opacity:1"), (18, "opacity:1"), (21, "opacity:0"), (90, "opacity:0"), (95, "opacity:1"), (100, "opacity:1")])
    body += sugg(P, sg)
    tb = a.el("opacity:1;transform:translateX(0)", [(0, "opacity:0;transform:translateX(-10px)"), (18, "opacity:0;transform:translateX(-10px)"),
                                                    (26, "opacity:1;transform:translateX(0)"), (86, "opacity:1;transform:translateX(0)"),
                                                    (90, "opacity:0;transform:translateX(0)"), (100, "opacity:0;transform:translateX(-10px)")], "ease-out")
    body += f'<g class="{tb}">{toolbar(["clip", "dpad", "undo", "redo", "gear"])}</g>'
    body += finger(a, (12, SY), 0, [(0, (12, SY)), (100, (12, SY))], [(0, 0), (12, 1), (18, 0), (82, 1), (88, 0), (100, 0)], op_tf="step-end")
    return body


def a_clipboard(code, a):
    P = L[code]["phrase"]
    entries = [P, "cypherpunk.today/droneboard", "08:30"]
    body = field_line()
    body += field_text(P, extra=opac(a, 1, [(0, 0), (46, 1), (90, 0), (100, 0)]))
    body += cursor(a, len(P), [(0, 0), (46, len(P)), (90, 0), (100, 0)])
    rows = opac(a, 0, [(0, 1), (14, 1), (18, 0), (86, 0), (92, 1), (100, 1)], "linear")
    body += keyboard(code, rows_cls=rows) + icon("chev_l", 12, SY) + toolbar(["clip", "dpad", "undo", "redo", "gear"]) + icon("mic", 228, SY)
    cp = opac(a, 1, [(0, 0), (14, 0), (18, 1), (86, 1), (92, 0), (100, 0)], "linear")
    g = []
    for i, t in enumerate(entries):
        y = 66 + i * 31
        g.append(f'<rect class="k" x="4" y="{y}" width="{W - 8}" height="27" rx="4"/><text class="kl cl" x="12" y="{y + 17.5}">{escape(t)}</text>')
        if i == 1:
            g.append(icon("pin", W - 16, y + 12.5))
    body += f'<g class="{cp}">{"".join(g)}</g>'
    body += finger(a, (120, 79.5), 0, [(0, (40, SY)), (30, (120, 79.5)), (100, (120, 79.5))],
                   [(0, 0), (8, 1), (15, 0), (40, 1), (46, 0), (100, 0)], "step-end", "step-end")
    return body


def a_mic(code, a):
    P = L[code]["phrase"]; m = len(P)
    ends = [i for i, c in enumerate(P) if c == " "] + [m]
    times = [26 + 14 * i for i in range(len(ends))]
    body = field_line() + field_text(P)
    body += cover(a, m, [(0, 0)] + [(t, e + 1) for t, e in zip(times, ends)] + [(100, m + 1)])
    body += cursor(a, m, [(0, 0)] + [(t, min(e + 1, m)) for t, e in zip(times, ends)] + [(100, m)])
    body += keyboard(code) + strip_default()
    body += f'<g class="{opac(a, 1, [(0, 0), (12, 1), (74, 0), (100, 0)])}">{icon("mic", 228, SY, "ic icr")}</g>'
    ring = a.el("transform:scale(1.5);opacity:.5",
                [(0, "transform:scale(1);opacity:0"), (14, "transform:scale(1);opacity:.8"), (30, "transform:scale(1.9);opacity:0"),
                 (31, "transform:scale(1);opacity:.8"), (47, "transform:scale(1.9);opacity:0"), (48, "transform:scale(1);opacity:.8"),
                 (64, "transform:scale(1.9);opacity:0"), (100, "transform:scale(1.9);opacity:0")], extra="transform-box:fill-box;transform-origin:center")
    body += f'<circle class="rgr {ring}" cx="228" cy="{SY}" r="8"/>'
    body += finger(a, (228, SY), 0, [(0, (228, SY)), (100, (228, SY))], [(0, 0), (8, 1), (15, 0), (100, 0)], op_tf="step-end")
    return body


def a_incognito(code, a):
    w = L[code]["incog"]; m = len(w)
    f, times = taps(a, code, w, 28, 6)
    body = field_line() + field_text(w)
    body += cover(a, m, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(100, m)])
    body += cursor(a, m, [(0, 0)] + [(t, i + 1) for i, t in enumerate(times)] + [(100, m)])
    body += keyboard(code) + icon("chev_l", 12, SY) + icon("mic", 228, SY)
    body += f'<circle class="act {opac(a, 1, [(0, 0), (14, 1), (100, 1)])}" cx="168" cy="{SY}" r="10.5"/>'
    body += toolbar(["clip", "undo", "redo", "gear", "incog"])
    body += finger(a, (168, SY), 0, [(0, (168, SY)), (100, (168, SY))], [(0, 0), (10, 1), (16, 0), (100, 0)], op_tf="step-end")
    return body + f


ANIMS = [  # (name, seconds, builder) in the order of the tips on the page
    ("space", 5, a_space), ("trackpad", 6, a_trackpad), ("delete", 6, a_delete),
    ("longpress", 5, a_longpress), ("swipe", 5, a_swipe), ("multi", 7, a_multi), ("nodia", 6, a_nodia),
    ("numrow", 5.5, a_numrow), ("emoji", 6.5, a_emoji), ("resize", 6, a_resize), ("toolbar", 6, a_toolbar),
    ("clipboard", 7, a_clipboard), ("mic", 6, a_mic), ("incognito", 6.5, a_incognito),
]


def build(code):
    """(list of 14 SVG strings, CSS for the page head)"""
    svgs, css, media = [], [], []
    for i, (name, dur, fn) in enumerate(ANIMS):
        a = Anim(name, dur)
        inner = fn(code, a)
        svgs.append(f'<svg class="ta ta-{name}" viewBox="0 0 {W} {H}" role="img" aria-label="{escape(LABELS[code][i])}">{inner}</svg>')
        css += a.css
        media += a.media
    return svgs, "".join(css) + "@media (prefers-reduced-motion:no-preference){" + "".join(media) + "}"
