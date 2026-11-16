"""The tips subpage: tips/index.html (en) and <lang>/tips/index.html.

Called from build.py, which passes in the shared copy (T) for the header and footer. The strings
here are written by us and may contain HTML. Every tip was checked against the app's code and its
defaults (Defaults.kt); UI names are the app's own string resources in each language.
"""
import crosspromo
from html import escape

PATH = "tips/"
DICT_HREF = "../dictation/"  # from <lang>/tips/ (and tips/) to the dictation page of the same language

# Short label for the nav, longer one for links in the dictation footer, and the pointer on the main page.
LABEL = dict(en="tips", sk="tipy", cs="tipy", de="tipps", es="trucos")
LABEL_LONG = dict(en="tips and tricks", sk="tipy a triky", cs="tipy a triky", de="Tipps und Tricks", es="trucos y consejos")
DICT_LINK = dict(en="more about dictation", sk="viac o diktovaní", cs="víc o diktování", de="mehr zum Diktieren", es="más sobre el dictado")
MAIN_NOTE = dict(
    en="Tips and tricks: features many people never find, like sliding along the space bar to move the cursor. <a href=\"{href}\">See the tips</a>",
    sk="Tipy a triky: funkcie, ktoré veľa ľudí nenájde, napríklad potiahnutie po medzerníku, ktoré posúva kurzor. <a href=\"{href}\">Pozrite si tipy</a>",
    cs="Tipy a triky: funkce, které spousta lidí nenajde, třeba tažení po mezerníku, které posouvá kurzor. <a href=\"{href}\">Podívejte se na tipy</a>",
    de="Tipps und Tricks: Funktionen, die viele nie entdecken, etwa das Wischen über die Leertaste, das den Cursor bewegt. <a href=\"{href}\">Zu den Tipps</a>",
    es="Trucos y consejos: funciones que mucha gente no encuentra, como deslizar por la barra espaciadora para mover el cursor. <a href=\"{href}\">Ver los consejos</a>",
)

X = {}

X["en"] = dict(
    title="Droneboard tips and tricks: features many people miss",
    desc="Things Droneboard can do that many people never find: slide along the space bar to move the cursor, slide from delete to remove words, mix languages, resize the keyboard and more. Each tip says where to switch it on.",
    h1="things you may not know it does.",
    sub="Droneboard has gestures and settings that are easy to miss. Each tip says whether it works out of the box or needs switching on, and where. “Settings” are Droneboard’s own settings: tap the arrow at the left end of the suggestion strip to open the toolbar, then the gear.",
    groups=[
        ("text and cursor", [
            ("slide along the space bar to move the cursor",
             "Put a finger on the space bar and slide left or right: the cursor follows. It works out of the box. What the slide does can be changed in <strong>Settings → Advanced → Horizontal spacebar swipe gesture</strong>."),
            ("hold the space bar for a trackpad",
             "Press and hold the space bar, then slide anywhere on the keyboard to steer the cursor like on a laptop trackpad. Lifting your finger does not type a space. It is on by default; <strong>Settings → Advanced → Long press on space bar</strong> offers Trackpad, Switch language or keyboard, or None."),
            ("slide left from the delete key to select and delete",
             "Hold the delete key and slide left: the text before the cursor is selected word by word, and lifting your finger deletes it. Slide back to the right to select less. <strong>Settings → Advanced → Delete swipe</strong> switches between Words (the default) and Letters."),
        ]),
        ("typing", [
            ("long-press for accents and symbols",
             "Hold a key to get more characters, such as accented letters and symbols, and slide to the one you want. The small characters in the corner of a key show what waits under a long press. They are on by default: <strong>Settings → Preferences → Show key hints</strong>."),
            ("swipe over the letters",
             "Slide through the letters of a word without lifting your finger, then lift. Droneboard types the word and adds the space. It is on by default (<strong>Settings → Gesture Typing</strong>). <strong>Swipe search width</strong> on the same screen trades speed for accuracy; 200 is the recommended value."),
            ("mix languages without switching",
             "This needs a one-time setup. In <strong>Settings → Languages &amp; Layouts</strong> open a language and pick more languages under <strong>Multilingual typing</strong>. Suggestions and swipes then handle words from all of them, with no language switch in between."),
            ("slovak and czech without diacritics",
             "If you type “dakujem” instead of “ďakujem”, add <strong>Slovak (no diacritics)</strong> or <strong>Czech (no diacritics)</strong> in <strong>Settings → Languages &amp; Layouts</strong>. They are separate languages with their own dictionaries, so suggestions and swipes work without diacritics."),
            ("number row",
             "In password fields the number row appears on its own. To get it in every field, switch on <strong>Settings → Preferences → Number row</strong>."),
            ("search emoji while you type",
             "Type a colon and a word, for example :rocket, and matching emoji appear in the suggestion strip. The emoji panel also has a search key in its bottom row. Inline search is on by default: <strong>Settings → Text correction → Inline emoji search</strong>."),
        ]),
        ("keyboard and toolbar", [
            ("size and position of the keyboard",
             "Tap <strong>Size &amp; position</strong> in the toolbar and drag the edges or corners to resize. Choose Full width, Compact, One-handed, Floating or Split, and tap Done when it fits. A floating keyboard moves by its drag handle."),
            ("make the toolbar yours",
             "The arrow at the left end of the suggestion strip opens the toolbar: Clipboard, D-pad (arrow keys), Undo, Redo and more. <strong>Settings → Toolbar → Select toolbar keys</strong> changes the keys and their order. The keys that stay in the strip (the mic, by default) are chosen under <strong>Select pinned toolbar keys</strong>."),
            ("clipboard history",
             "Tap Clipboard in the toolbar to paste something you copied a while ago. Entries are kept for 10 minutes by default; press and hold one to pin it, and it stays. Settings are in <strong>Settings → Preferences → Clipboard history</strong>."),
        ]),
        ("voice and privacy", [
            ("dictate with the mic",
             "The mic at the right end of the suggestion strip types what you say. It needs the free app Offline Speech to Text, installed separately, and works without internet. <a href=\"{dict}\">How dictation works</a>."),
            ("incognito mode",
             "Switch on <strong>Settings → Advanced → Force incognito mode</strong> and Droneboard stops learning new words. It is off by default. You can also add the same key to the toolbar."),
        ]),
    ],
    back="back to the main page",
)

X["sk"] = dict(
    title="Tipy a triky pre Droneboard: funkcie, ktoré ľudia prehliadnu",
    desc="Čo všetko Droneboard vie, hoci to veľa ľudí nikdy nenájde: potiahnutie po medzerníku posunie kurzor, potiahnutie od mazania zmaže slová, jazyky sa dajú miešať, veľkosť klávesnice sa mení a ďalšie veci. Pri každom tipe je, kde ho zapnúť.",
    h1="čo všetko vie, o čom možno neviete.",
    sub="Droneboard má gestá a nastavenia, ktoré ľahko prehliadnete. Pri každom tipe je napísané, či funguje hneď, alebo ho treba zapnúť, a kde. „Nastavenia“ sú nastavenia samotného Droneboardu: ťuknite na šípku na ľavom konci pruhu s návrhmi, otvorí sa panel nástrojov, a v ňom je ozubené koliesko.",
    groups=[
        ("text a kurzor", [
            ("potiahnutím po medzerníku posuniete kurzor",
             "Priložte prst na medzerník a potiahnite doľava alebo doprava, kurzor ide s ním. Funguje to hneď. Čo má potiahnutie robiť, zmeníte v <strong>Nastavenia → Rozšírené → Gesto potiahnutia horizontálne po medzerníku</strong>."),
            ("podržaním medzerníka dostanete trackpad",
             "Podržte medzerník a potom ťahajte prstom kdekoľvek po klávesnici: kurzor sa hýbe ako na trackpade notebooku. Po zdvihnutí prsta sa nevloží medzera. Je to predvolene zapnuté. V <strong>Nastavenia → Rozšírené → Podržanie medzerníka</strong> môžete vybrať Trackpad, Prepnúť jazyk alebo klávesnicu, alebo Žiadne."),
            ("potiahnutím od mazania doľava text označíte a zmažete",
             "Podržte kláves mazania a potiahnite doľava: text pred kurzorom sa označuje po slovách a po zdvihnutí prsta sa zmaže. Potiahnutím späť doprava označíte menej. V <strong>Nastavenia → Rozšírené → Potiahnutím odstrániť</strong> si vyberiete Slová (predvolené) alebo Písmená."),
        ]),
        ("písanie", [
            ("podržaním klávesu získate diakritiku a symboly",
             "Podržte kláves a zobrazia sa ďalšie znaky, napríklad písmená s diakritikou a symboly. Prstom prejdite na ten, ktorý chcete. Malé znaky v rohu klávesu ukazujú, čo sa skrýva pod dlhým stlačením. Sú predvolene zapnuté: <strong>Nastavenia → Predvoľby → Zobraziť tipy k klávesám</strong>."),
            ("píšte potiahnutím po písmenách",
             "Prejdite prstom po písmenách slova a nezdvíhajte ho, až na konci. Droneboard slovo napíše a doplní medzeru. Je to predvolene zapnuté (<strong>Nastavenia → Písanie gestami</strong>). <strong>Šírka hľadania pri písaní potiahnutím</strong> na tej istej obrazovke vyvažuje rýchlosť a presnosť, odporúčaná hodnota je 200."),
            ("miešajte jazyky bez prepínania",
             "Najprv to treba raz nastaviť. V <strong>Nastavenia → Jazyky a rozloženia</strong> otvorte jazyk a pod <strong>Viacjazyčné písanie</strong> vyberte ďalšie jazyky. Návrhy aj písanie potiahnutím potom zvládnu slová zo všetkých, bez prepínania jazyka."),
            ("slovenčina a čeština bez diakritiky",
             "Ak píšete „dakujem“ namiesto „ďakujem“, pridajte si v <strong>Nastavenia → Jazyky a rozloženia</strong> jazyk <strong>slovenčina (bez diakritiky)</strong> alebo <strong>čeština (bez diakritiky)</strong>. Sú to samostatné jazyky s vlastnými slovníkmi, takže návrhy aj písanie potiahnutím fungujú bez diakritiky."),
            ("číselný riadok",
             "V poliach na heslo sa číselný riadok objaví sám. Ak ho chcete v každom poli, zapnite <strong>Nastavenia → Predvoľby → Číselný riadok</strong>."),
            ("emoji hľadajte priamo pri písaní",
             "Napíšte dvojbodku a slovo, napríklad :raketa, a v pruhu s návrhmi sa objavia zodpovedajúce emoji. Panel s emoji má v spodnom riadku aj kláves na hľadanie. Hľadanie v texte je predvolene zapnuté: <strong>Nastavenia → Oprava textu → Vyhľadávanie emodži priamo v texte</strong>."),
        ]),
        ("klávesnica a panel nástrojov", [
            ("veľkosť a poloha klávesnice",
             "Na paneli nástrojov ťuknite na <strong>Veľkosť a poloha</strong> a ťahaním za okraje alebo rohy zmeňte veľkosť. Vyberte Celá šírka, Kompaktná, Jednou rukou, Plávajúca alebo Rozdelená a keď to sedí, ťuknite na Hotovo. Plávajúcu klávesnicu presuniete za jej úchyt."),
            ("panel nástrojov podľa seba",
             "Šípka na ľavom konci pruhu s návrhmi otvorí panel nástrojov: Schránka, D-pad (šípky), Späť, Znova a ďalšie. V <strong>Nastavenia → Panel nástrojov → Vyberte klávesy na paneli nástrojov</strong> zmeníte klávesy aj ich poradie. Klávesy, ktoré ostávajú v pruhu (predvolene mikrofón), sa menia pod <strong>Vyberte tlačidlá na pripnutom paneli nástrojov</strong>."),
            ("história schránky",
             "Ťuknite na Schránka na paneli nástrojov a vložte, čo ste skopírovali predtým. Položky sa predvolene držia 10 minút. Ak položku podržíte, pripne sa a zostane. Nastavenia nájdete v <strong>Nastavenia → Predvoľby → História schránky</strong>."),
        ]),
        ("hlas a súkromie", [
            ("diktujte mikrofónom",
             "Mikrofón na pravom konci pruhu s návrhmi píše, čo poviete. Potrebuje bezplatnú aplikáciu Offline Speech to Text, ktorú si nainštalujete zvlášť, a internet nepotrebuje. <a href=\"{dict}\">Ako diktovanie funguje</a>."),
            ("režim inkognito",
             "Zapnite <strong>Nastavenia → Rozšírené → Vynútiť režim inkognito</strong> a Droneboard sa prestane učiť nové slová. Predvolene je vypnutý. Rovnakú klávesu môžete pridať aj na panel nástrojov."),
        ]),
    ],
    back="späť na hlavnú stránku",
)

X["cs"] = dict(
    title="Tipy a triky pro Droneboard: funkce, které lidé přehlédnou",
    desc="Co všechno Droneboard umí, přestože to spousta lidí nikdy nenajde: tažení po mezerníku posune kurzor, tažení od mazání smaže slova, jazyky se dají míchat, velikost klávesnice se mění a další věci. U každého tipu je, kde ho zapnout.",
    h1="co všechno umí, o čem možná nevíte.",
    sub="Droneboard má gesta a nastavení, která snadno přehlédnete. U každého tipu je napsáno, jestli funguje hned, nebo ho musíte zapnout, a kde. „Nastavení“ jsou nastavení samotného Droneboardu: klepněte na šipku na levém konci pruhu s návrhy, otevře se panel nástrojů, a v něm je ozubené kolečko.",
    groups=[
        ("text a kurzor", [
            ("tažením po mezerníku posunete kurzor",
             "Položte prst na mezerník a táhněte doleva nebo doprava, kurzor jde s ním. Funguje to hned. Co má tažení dělat, změníte v <strong>Nastavení → Rozšířená nastavení → Vodorovné gesto přejetí mezerníku</strong>."),
            ("podržením mezerníku dostanete trackpad",
             "Podržte mezerník a pak jezděte prstem kamkoli po klávesnici: kurzor se hýbe jako na trackpadu notebooku. Po zvednutí prstu se nevloží mezera. Je to ve výchozím nastavení zapnuté. V <strong>Nastavení → Rozšířená nastavení → Podržení mezerníku</strong> můžete zvolit Trackpad, Přepnout jazyk nebo klávesnici, nebo Žádné."),
            ("tažením od mazání doleva text označíte a smažete",
             "Podržte klávesu mazání a táhněte doleva: text před kurzorem se označuje po slovech a po zvednutí prstu se smaže. Tažením zpět doprava označíte méně. V <strong>Nastavení → Rozšířená nastavení → Mazat posunutím</strong> si vyberete Slova (výchozí) nebo Písmena."),
        ]),
        ("psaní", [
            ("podržením klávesy získáte diakritiku a symboly",
             "Podržte klávesu a objeví se další znaky, třeba písmena s diakritikou a symboly. Prstem přejeďte na ten, který chcete. Malé znaky v rohu klávesy ukazují, co se pod dlouhým stiskem skrývá. Jsou ve výchozím nastavení zapnuté: <strong>Nastavení → Předvolby → Nápověda kláves</strong>."),
            ("pište tažením po písmenech",
             "Přejeďte prstem po písmenech slova a zvedněte ho až na konci. Droneboard slovo napíše a doplní mezeru. Je to ve výchozím nastavení zapnuté (<strong>Nastavení → Psaní gesty</strong>). <strong>Šířka hledání při psaní tažením</strong> na stejné obrazovce vyvažuje rychlost a přesnost, doporučená hodnota je 200."),
            ("míchejte jazyky bez přepínání",
             "Napřed to musíte jednou nastavit. V <strong>Nastavení → Jazyky a rozložení</strong> otevřete jazyk a pod <strong>Vícejazyčné psaní</strong> zvolte další jazyky. Návrhy i psaní tažením pak zvládnou slova ze všech, bez přepínání jazyka."),
            ("čeština a slovenština bez diakritiky",
             "Když píšete „dekuji“ místo „děkuji“, přidejte si v <strong>Nastavení → Jazyky a rozložení</strong> jazyk <strong>čeština (bez diakritiky)</strong> nebo <strong>slovenština (bez diakritiky)</strong>. Jsou to samostatné jazyky s vlastními slovníky, takže návrhy i psaní tažením fungují bez diakritiky."),
            ("číselný řádek",
             "V polích pro heslo se číselný řádek objeví sám. Chcete ho v každém poli? Zapněte <strong>Nastavení → Předvolby → Číselný řádek</strong>."),
            ("emoji hledejte přímo při psaní",
             "Napište dvojtečku a slovo, třeba :raketa, a v pruhu s návrhy se objeví odpovídající emoji. Panel s emoji má ve spodním řádku i klávesu pro hledání. Hledání v textu je ve výchozím nastavení zapnuté: <strong>Nastavení → Oprava textu → Vyhledávání emodži na řádku</strong>."),
        ]),
        ("klávesnice a panel nástrojů", [
            ("velikost a poloha klávesnice",
             "V panelu nástrojů klepněte na <strong>Velikost a poloha</strong> a tažením za okraje nebo rohy změňte velikost. Zvolte Celá šířka, Kompaktní, Jednou rukou, Plovoucí nebo Rozdělená a až to sedí, klepněte na Hotovo. Plovoucí klávesnici posunete za její úchyt."),
            ("panel nástrojů podle sebe",
             "Šipka na levém konci pruhu s návrhy otevře panel nástrojů: Schránka, Směrové klávesy, Vrátit zpět, Opakovat a další. V <strong>Nastavení → Panel nástrojů → Výběr kláves na panelu nástrojů</strong> změníte klávesy i jejich pořadí. Klávesy, které zůstávají v pruhu (výchozí je mikrofon), se mění pod <strong>Vyberte připnuté klávesy v panelu nástrojů</strong>."),
            ("historie schránky",
             "Klepněte na Schránka v panelu nástrojů a vložte, co jste zkopírovali dřív. Položky se ve výchozím nastavení drží 10 minut. Když položku podržíte, připne se a zůstane. Nastavení najdete v <strong>Nastavení → Předvolby → Historie schránky</strong>."),
        ]),
        ("hlas a soukromí", [
            ("diktujte mikrofonem",
             "Mikrofon na pravém konci pruhu s návrhy píše, co řeknete. Potřebuje bezplatnou aplikaci Offline Speech to Text, kterou si nainstalujete zvlášť, a internet nepotřebuje. <a href=\"{dict}\">Jak diktování funguje</a>."),
            ("režim inkognito",
             "Zapněte <strong>Nastavení → Rozšířená nastavení → Vynutit režim inkognito</strong> a Droneboard se přestane učit nová slova. Ve výchozím nastavení je vypnutý. Stejnou klávesu můžete přidat i do panelu nástrojů."),
        ]),
    ],
    back="zpět na hlavní stránku",
)

X["de"] = dict(
    title="Droneboard Tipps und Tricks: Funktionen, die viele übersehen",
    desc="Was Droneboard kann, obwohl es viele nie finden: über die Leertaste wischen bewegt den Cursor, von der Löschtaste wischen löscht Wörter, Sprachen mischen, die Tastaturgröße ändern und mehr. Bei jedem Tipp steht, wo Sie ihn einschalten.",
    h1="was sie kann, ohne dass sie es sagt.",
    sub="Droneboard hat Gesten und Einstellungen, die man leicht übersieht. Bei jedem Tipp steht, ob er sofort funktioniert oder eingeschaltet werden muss, und wo. „Einstellungen“ sind die Einstellungen von Droneboard selbst: Tippen Sie auf den Pfeil am linken Ende der Vorschlagsleiste, dann öffnet sich die Symbolleiste, und darin ist das Zahnrad.",
    groups=[
        ("text und cursor", [
            ("über die leertaste wischen bewegt den cursor",
             "Legen Sie den Finger auf die Leertaste und wischen Sie nach links oder rechts: Der Cursor folgt. Das geht ohne Einrichtung. Was die Geste tut, ändern Sie unter <strong>Einstellungen → Erweitert → Horizontale Wischgeste der Leertaste</strong>."),
            ("leertaste halten für das trackpad",
             "Halten Sie die Leertaste gedrückt und fahren Sie dann irgendwo über die Tastatur: Der Cursor bewegt sich wie auf dem Trackpad eines Laptops. Beim Loslassen wird kein Leerzeichen getippt. Das ist standardmäßig an. Unter <strong>Einstellungen → Erweitert → Lange auf die Leertaste drücken</strong> wählen Sie Trackpad, Sprache oder Tastatur wechseln oder Nichts."),
            ("von der löschtaste nach links wischen, um zu markieren und zu löschen",
             "Halten Sie die Löschtaste und wischen Sie nach links: Der Text vor dem Cursor wird wortweise markiert, und beim Loslassen wird er gelöscht. Wischen Sie zurück nach rechts, markieren Sie weniger. Unter <strong>Einstellungen → Erweitert → Löschen durch Wischen</strong> stellen Sie Wörter (Standard) oder Buchstaben ein."),
        ]),
        ("tippen", [
            ("lange drücken für akzente und symbole",
             "Halten Sie eine Taste gedrückt, dann erscheinen weitere Zeichen wie Buchstaben mit Akzent und Symbole. Fahren Sie zum gewünschten. Die kleinen Zeichen an der Tastenecke zeigen, was unter dem langen Druck steckt. Sie sind standardmäßig an: <strong>Einstellungen → Optionen → Tastenhinweise anzeigen</strong>."),
            ("über die buchstaben wischen",
             "Fahren Sie über die Buchstaben eines Wortes, ohne abzusetzen, und heben Sie erst am Ende den Finger. Droneboard tippt das Wort und setzt das Leerzeichen. Das ist standardmäßig an (<strong>Einstellungen → Gesten-Tippen</strong>). Die <strong>Suchbreite beim Wischen</strong> auf derselben Seite wägt Tempo gegen Treffsicherheit ab; empfohlen sind 200."),
            ("sprachen mischen, ohne umzuschalten",
             "Das müssen Sie einmal einrichten. Öffnen Sie unter <strong>Einstellungen → Sprachen &amp; Layouts</strong> eine Sprache und wählen Sie unter <strong>Mehrsprachiges Tippen</strong> weitere Sprachen. Vorschläge und Wischen kommen dann mit Wörtern aus allen zurecht, ohne Sprachwechsel."),
            ("slowakisch und tschechisch ohne diakritika",
             "Wenn Sie „dakujem“ statt „ďakujem“ schreiben, fügen Sie unter <strong>Einstellungen → Sprachen &amp; Layouts</strong> <strong>Slowakisch (ohne Diakritika)</strong> oder <strong>Tschechisch (ohne Diakritika)</strong> hinzu. Das sind eigene Sprachen mit eigenen Wörterbüchern, daher funktionieren Vorschläge und Wischen ohne Diakritika."),
            ("ziffernzeile",
             "In Passwortfeldern erscheint die Ziffernzeile von selbst. Wollen Sie sie in jedem Feld, schalten Sie <strong>Einstellungen → Optionen → Ziffernzeile</strong> ein."),
            ("emojis beim tippen suchen",
             "Tippen Sie einen Doppelpunkt und ein Wort, zum Beispiel :rakete, dann erscheinen passende Emojis in der Vorschlagsleiste. Auch das Emoji-Panel hat in der unteren Reihe eine Suchtaste. Die Suche im Text ist standardmäßig an: <strong>Einstellungen → Textkorrektur → Inline-Emoji-Suche</strong>."),
        ]),
        ("tastatur und symbolleiste", [
            ("größe und position der tastatur",
             "Tippen Sie in der Symbolleiste auf <strong>Größe &amp; Position</strong> und ziehen Sie an den Rändern oder Ecken, um die Größe zu ändern. Wählen Sie Volle Breite, Kompakt, Einhandmodus, Schwebend oder Geteilt und tippen Sie auf Fertig, wenn es passt. Eine schwebende Tastatur verschieben Sie an ihrem Griff."),
            ("die symbolleiste nach ihrem geschmack",
             "Der Pfeil am linken Ende der Vorschlagsleiste öffnet die Symbolleiste: Zwischenablage, Steuerkreuz, Rückgängig, Wiederholen und mehr. Unter <strong>Einstellungen → Symbolleiste → Symbolleistentasten wählen</strong> ändern Sie Tasten und Reihenfolge. Die Tasten, die in der Leiste bleiben (standardmäßig das Mikrofon), wählen Sie unter <strong>Angeheftete Tasten der Symbolleiste auswählen</strong>."),
            ("zwischenablage-verlauf",
             "Tippen Sie in der Symbolleiste auf Zwischenablage und fügen Sie ein, was Sie vorhin kopiert haben. Einträge bleiben standardmäßig 10 Minuten. Halten Sie einen Eintrag gedrückt, wird er angeheftet und bleibt. Die Einstellungen finden Sie unter <strong>Einstellungen → Optionen → Zwischenablage-Verlauf</strong>."),
        ]),
        ("sprache und privatsphäre", [
            ("diktieren mit dem mikrofon",
             "Das Mikrofon am rechten Ende der Vorschlagsleiste tippt, was Sie sagen. Es braucht die kostenlose App Offline Speech to Text, die Sie separat installieren, und kein Internet. <a href=\"{dict}\">So funktioniert das Diktieren</a>."),
            ("inkognito-modus",
             "Schalten Sie <strong>Einstellungen → Erweitert → Inkognito-Modus erzwingen</strong> ein, dann lernt Droneboard keine neuen Wörter mehr. Standardmäßig ist er aus. Dieselbe Taste können Sie auch in die Symbolleiste legen."),
        ]),
    ],
    back="zurück zur Hauptseite",
)

X["es"] = dict(
    title="Trucos y consejos de Droneboard: funciones que se pasan por alto",
    desc="Lo que Droneboard sabe hacer aunque mucha gente nunca lo encuentre: deslizar por la barra espaciadora mueve el cursor, deslizar desde borrar elimina palabras, mezclar idiomas, cambiar el tamaño del teclado y más. Cada consejo dice dónde activarlo.",
    h1="cosas que quizá no sabías que hace.",
    sub="Droneboard tiene gestos y ajustes que es fácil pasar por alto. En cada consejo se indica si funciona de entrada o hay que activarlo, y dónde. «Ajustes» son los ajustes del propio Droneboard: toca la flecha del extremo izquierdo de la barra de sugerencias para abrir la barra de herramientas, y en ella está la rueda dentada.",
    groups=[
        ("texto y cursor", [
            ("desliza por la barra espaciadora para mover el cursor",
             "Pon el dedo en la barra espaciadora y deslízalo a izquierda o derecha: el cursor te sigue. Funciona sin configurar nada. Lo que hace el gesto lo cambias en <strong>Ajustes → Avanzado → Gesto de deslizar horizontalmente barra espaciadora</strong>."),
            ("mantén la barra espaciadora para usar un panel táctil",
             "Mantén pulsada la barra espaciadora y desliza el dedo por cualquier parte del teclado: el cursor se mueve como en el panel táctil de un portátil. Al levantar el dedo no se escribe un espacio. Viene activado de serie. En <strong>Ajustes → Avanzado → Mantener pulsada la barra espaciadora</strong> puedes elegir Panel táctil, Cambiar de idioma o de teclado, o Ninguna."),
            ("desliza a la izquierda desde borrar para seleccionar y borrar",
             "Mantén pulsada la tecla de borrar y desliza a la izquierda: el texto anterior al cursor se selecciona palabra a palabra y, al levantar el dedo, se borra. Si vuelves hacia la derecha, seleccionas menos. En <strong>Ajustes → Avanzado → Borrar deslizando</strong> eliges Palabras (lo predeterminado) o Letras."),
        ]),
        ("escribir", [
            ("mantén una tecla para acentos y símbolos",
             "Mantén pulsada una tecla y aparecen más caracteres, como letras con acento y símbolos. Desliza hasta el que quieras. Los caracteres pequeños de la esquina de la tecla muestran lo que hay bajo la pulsación larga. Vienen activados de serie: <strong>Ajustes → Preferencias → Mostrar símbolos en las teclas</strong>."),
            ("escribe deslizando por las letras",
             "Pasa el dedo por las letras de una palabra y levántalo solo al final. Droneboard escribe la palabra y añade el espacio. Viene activado de serie (<strong>Ajustes → Escritura gestual</strong>). La <strong>Amplitud de búsqueda del gesto</strong>, en la misma pantalla, equilibra velocidad y acierto; el valor recomendado es 200."),
            ("mezcla idiomas sin cambiar",
             "Hay que configurarlo una vez. En <strong>Ajustes → Idiomas y diseños</strong> abre un idioma y elige más idiomas en <strong>Escritura multilingüe</strong>. Las sugerencias y el deslizado entienden entonces palabras de todos ellos, sin cambiar de idioma."),
            ("eslovaco y checo sin signos diacríticos",
             "Si escribes «dakujem» en lugar de «ďakujem», añade en <strong>Ajustes → Idiomas y diseños</strong> <strong>Eslovaco (sin signos diacríticos)</strong> o <strong>Checo (sin signos diacríticos)</strong>. Son idiomas aparte, con sus propios diccionarios, así que las sugerencias y el deslizado funcionan sin diacríticos."),
            ("fila de números",
             "En los campos de contraseña la fila de números aparece sola. Si la quieres en todos los campos, activa <strong>Ajustes → Preferencias → Fila de números</strong>."),
            ("busca emojis mientras escribes",
             "Escribe dos puntos y una palabra, por ejemplo :cohete, y los emojis que coinciden aparecen en la barra de sugerencias. El panel de emojis también tiene una tecla de búsqueda en la fila inferior. La búsqueda en el texto viene activada de serie: <strong>Ajustes → Corrección de texto → Búsqueda integrada de emojis</strong>."),
        ]),
        ("teclado y barra de herramientas", [
            ("tamaño y posición del teclado",
             "En la barra de herramientas toca <strong>Tamaño y posición</strong> y arrastra los bordes o las esquinas para cambiar el tamaño. Elige Ancho completo, Compacto, Una mano, Flotante o Dividido y toca Listo cuando te guste. Un teclado flotante se mueve con su asa."),
            ("la barra de herramientas a tu manera",
             "La flecha del extremo izquierdo de la barra de sugerencias abre la barra de herramientas: Portapapeles, Cruceta, Deshacer, Rehacer y más. En <strong>Ajustes → Barra de herramientas → Seleccionar teclas de la barra de herramientas</strong> cambias las teclas y su orden. Las que se quedan en la barra de sugerencias (de serie, el micrófono) se eligen en <strong>Seleccionar teclas fijas de la barra de herramientas</strong>."),
            ("historial del portapapeles",
             "Toca Portapapeles en la barra de herramientas y pega lo que copiaste antes. Las entradas se guardan 10 minutos de serie. Si mantienes pulsada una, se fija y se queda. Los ajustes están en <strong>Ajustes → Preferencias → Historial del portapapeles</strong>."),
        ]),
        ("voz y privacidad", [
            ("dicta con el micrófono",
             "El micrófono del extremo derecho de la barra de sugerencias escribe lo que dices. Necesita la app gratuita Offline Speech to Text, que se instala aparte, y no necesita internet. <a href=\"{dict}\">Cómo funciona el dictado</a>."),
            ("modo incógnito",
             "Activa <strong>Ajustes → Avanzado → Forzar el modo incógnito</strong> y Droneboard deja de aprender palabras nuevas. Viene desactivado de serie. También puedes añadir esa tecla a la barra de herramientas."),
        ]),
    ],
    back="volver a la página principal",
)


def page(code, t, langs, site):
    """One tips page; [t] is the main page's copy for the shared header and footer."""
    d = X[code]
    e = escape
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

    panels = []
    n = 0
    for gi, (gtitle, tips) in enumerate(d["groups"]):
        items = []
        for title, text in tips:
            n += 1
            items.append(f'<li><span class="no">{n:02d}</span><h3>{title}</h3><p>{text.replace("{dict}", DICT_HREF)}</p></li>')
        panels.append(
            f'<section class="panel" id="g{gi + 1}" aria-labelledby="g{gi + 1}-h">\n'
            f'  <h2 id="g{gi + 1}-h">{e(gtitle)}</h2>\n'
            f'  <ol class="tracks">{"".join(items)}</ol>\n'
            f"</section>"
        )
    panels = "\n\n".join(panels)

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
<a class="skip" href="#g1">{e(t['skip'])}</a>

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
        <li><a href="{home}{PATH}" aria-current="page">{LABEL[code]}</a></li>
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
{panels}

<section class="panel" id="more-tips" aria-label="{e(d['back'])}">
  <p class="inst-note"><a href="{home}">{e(d['back'])}</a> · <a href="{DICT_HREF}">{e(DICT_LINK[code])}</a></p>
</section>

{crosspromo.section(code, root, DICT_HREF + '#languages', compact=True)}
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
        out = root / (PATH + "index.html" if code == "en" else f"{code}/{PATH}index.html")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page(code, T[code], langs, site), encoding="utf-8")
        print("wrote", out.relative_to(root))
