"""The page for AI agents: agents/index.html (en) and <lang>/agents/index.html, the guides as plain
Markdown in agents/ (English only), and llms.txt.

The Markdown guides are copied from the repository (the skill and docs/), so the website and the repository
never say different things. Called from build.py.
"""
import shutil
from html import escape

import crosspromo

PATH = "agents/"
REPO = "https://github.com/jooray/droneboard"
RAW = "https://raw.githubusercontent.com/jooray/droneboard/main/"

# (file on the website, source in the repository, what it is)
GUIDES = [
    ("personal-dictionary.md", "skills/droneboard-personal-dictionary/SKILL.md",
     "Build a personal dictionary for Droneboard from text the user has already written."),
    ("new-language.md", "docs/adding-a-language.md",
     "Add a language to Droneboard: dictionaries, swipe word list, neural next-word model, app wiring."),
]

# the pointer on the main pages
MAIN_NOTE = dict(
    en="Working with an AI agent? There is <a href=\"{href}\">a page for agents</a>: build a personal dictionary from your own text, or teach Droneboard a new language.",
    sk="Pracujete s AI agentom? Je tu <a href=\"{href}\">stránka pre agentov</a>: osobný slovník z vlastných textov alebo nový jazyk pre Droneboard.",
    cs="Pracujete s AI agentem? Je tu <a href=\"{href}\">stránka pro agenty</a>: osobní slovník z vlastních textů nebo nový jazyk pro Droneboard.",
    de="Arbeiten Sie mit einem KI-Agenten? Es gibt <a href=\"{href}\">eine Seite für Agenten</a>: ein persönliches Wörterbuch aus eigenen Texten oder eine neue Sprache für Droneboard.",
    es="¿Trabajas con un agente de IA? Hay <a href=\"{href}\">una página para agentes</a>: un diccionario personal a partir de tus textos o un idioma nuevo para Droneboard.",
)

LABEL = dict(en="agents", sk="agenti", cs="agenti", de="agenten", es="agentes")
TIPS = dict(en="tips", sk="tipy", cs="tipy", de="tipps", es="trucos")

# Texts may contain HTML. Placeholders: {raw} {repo} {repo_short} {g_dict} {g_lang} {llms} {p_dict} {p_lang}
X = {}

X["en"] = dict(
    title="Droneboard for AI agents: personal dictionary and new languages",
    desc="Two jobs an AI coding agent can do for you: build a personal dictionary for the Droneboard keyboard from your own text, or train and add a new language. Plain Markdown guides and one small tool.",
    h1="hand it to your agent.",
    sub="An AI coding agent can do two jobs for you from start to finish: make a personal dictionary out of text you have already written, and add a language Droneboard does not have yet. Each job has a Markdown guide the agent reads on its own. Everything runs on your computer.",
    p_dict="Read {url}personal-dictionary.md and build me a personal dictionary for the Droneboard keyboard from my own writing. My text is in <folder or file>. I type in <languages>. Keep everything on this computer and show me the list before I import it.",
    p_lang="Read {url}new-language.md, check out the latest {repo} and add <language> to Droneboard: dictionaries, swipe word list and the neural next-word model. Before you start, tell me what data you want to download and how long training will take.",
    back="back to the main page", source="source code",
    sections=[
        ("a personal dictionary from your own text", [
            ("what it does", "You type words no dictionary has: family names, streets, company and project names, jargon. The agent reads text you have already written (notes, sent mail, your own chat messages), finds the words Droneboard does not know yet, cleans the list and writes a file. You import that file in the keyboard. The tool only reads and writes files on your computer."),
            ("what the agent needs", "Python 3.8 or newer, the tool <a href=\"{raw}tools/personal-dict/personal_dict.py\"><code>personal_dict.py</code></a> (one file, standard library only), the Droneboard APK from the <a href=\"{repo}/releases/latest\">latest release</a> and your text. The tool reads the built-in dictionaries out of the APK, so words the keyboard already knows are not added again."),
            ("the guide", "<a href=\"{g_dict}\"><code>personal-dictionary.md</code></a> says where to look for text, how to take only your own messages from a chat export, which three commands to run, how to clean the word list, what the import file looks like and how to get it onto the phone. It is also a skill: copy it into your agent's skills folder."),
            ("say this to your agent", "<span class=\"prompt\">{p_dict}</span>"),
            ("then, on the phone", "In Droneboard 0.2.1 or newer open <strong>Settings → Text correction → Personal dictionary → Import from file</strong>. It shows how many words are new and adds them after you confirm. Words that are already there stay. The same screen imports the dictionary exported from Gboard, if you only want to move the words you added there by hand."),
        ]),
        ("teach droneboard a new language", [
            ("what a language consists of", "A main dictionary for tap typing, a swipe word list with word-pair and word-triple statistics, an emoji dictionary, a small neural next-word model that runs on the phone, and a keyboard layout. Each part is built from open data with scripts in the repository."),
            ("what the agent needs", "A checkout of the <a href=\"{repo}\">latest main</a>, Python and disk space for the corpus. The neural model is trained with MLX, so it needs an Apple Silicon Mac. The guide says what each step downloads and how long it takes."),
            ("the guide", "<a href=\"{g_lang}\"><code>new-language.md</code></a> covers how to check that a language is feasible, where our data came from and how to find the same for another language, the commands for dictionaries and n-grams, training, exporting and verifying the model, and every file in the app that has to change. The commits that added Hungarian serve as a worked example. It ends with how to measure the result and what to put in the pull request."),
            ("say this to your agent", "<span class=\"prompt\">{p_lang}</span>"),
            ("send it back", "A language that works for you will work for the next person too. Open a pull request at <a href=\"{repo}\">{repo_short}</a> with the built files, the commands you ran and the numbers you measured."),
        ]),
        ("facts an agent should know", [
            ("the project", "Droneboard is an open-source Android keyboard (GPL-3.0), a fork of HeliBoard. Its application id is <code>today.cypherpunk.droneboard</code>. It has no INTERNET permission, so everything it uses ships in the APK or is imported from a file by the user."),
            ("where things are", "Source: <a href=\"{repo}\">{repo_short}</a>. The APK is in <a href=\"{repo}/releases/latest\">GitHub releases</a>. Dictionary tooling is in <code>tools/dicts</code>, neural model tooling in <code>tools/lm</code>, swipe replay and evaluation in <code>tools/swipe-replay</code>. File formats are described in <code>native/droneswipe/FORMAT.md</code> and <code>docs/dictionaries.md</code>."),
            ("plain text for machines", "The two guides are Markdown files with stable addresses, listed in <a href=\"{llms}\"><code>llms.txt</code></a>. They are copies of files in the repository and are rebuilt with the website."),
            ("limits", "The personal dictionary cannot be filled over adb. Android lets only the keyboard write it, so the import happens in the app. Swipe typing works on single words in the Latin alphabet. The swipe encoder weights come from FUTO under their own licence."),
        ]),
    ],
)

X["sk"] = dict(
    title="Droneboard pre AI agentov: osobný slovník a nové jazyky",
    desc="Dve úlohy, ktoré za vás spraví AI agent: osobný slovník pre klávesnicu Droneboard z vašich vlastných textov a natrénovanie a pridanie nového jazyka. Návody v Markdowne a jeden malý nástroj.",
    h1="dajte to svojmu agentovi.",
    sub="AI agent za vás spraví od začiatku do konca dve úlohy: z textov, ktoré ste už napísali, vyrobí osobný slovník, a pridá jazyk, ktorý Droneboard ešte nemá. Ku každej je návod v Markdowne (v angličtine), ktorý si agent prečíta sám. Všetko beží na vašom počítači.",
    p_dict="Prečítaj si {url}personal-dictionary.md a vyrob mi osobný slovník pre klávesnicu Droneboard z mojich vlastných textov. Texty mám v <priečinok alebo súbor>. Píšem v jazykoch <jazyky>. Všetko nechaj na tomto počítači a pred importom mi ukáž zoznam.",
    p_lang="Prečítaj si {url}new-language.md, stiahni si najnovší {repo} a pridaj do Droneboardu <jazyk>: slovníky, zoznam slov pre swipe a neurónový model na predikciu ďalšieho slova. Skôr než začneš, povedz mi, aké dáta chceš stiahnuť a ako dlho potrvá trénovanie.",
    back="späť na hlavnú stránku", source="zdrojový kód",
    sections=[
        ("osobný slovník z vlastných textov", [
            ("čo to robí", "Píšete slová, ktoré nie sú v žiadnom slovníku: priezviská, ulice, názvy firiem a projektov, žargón. Agent prejde texty, ktoré ste už napísali (poznámky, odoslané e-maily, vaše správy z chatu), nájde slová, ktoré Droneboard ešte nepozná, zoznam prečistí a zapíše do súboru. Ten potom v klávesnici naimportujete. Nástroj iba číta a zapisuje súbory vo vašom počítači."),
            ("čo agent potrebuje", "Python 3.8 alebo novší, nástroj <a href=\"{raw}tools/personal-dict/personal_dict.py\"><code>personal_dict.py</code></a> (jeden súbor, stačí štandardná knižnica), APK Droneboardu z <a href=\"{repo}/releases/latest\">najnovšieho vydania</a> a vaše texty. Nástroj si z APK prečíta vstavané slovníky, takže slová, ktoré klávesnica už pozná, sa nepridajú znova."),
            ("návod", "V súbore <a href=\"{g_dict}\"><code>personal-dictionary.md</code></a> (v angličtine) je, kde hľadať texty, ako z exportu chatu vybrať len vaše správy, ktoré tri príkazy spustiť, ako zoznam prečistiť, ako vyzerá súbor na import a ako ho dostať do telefónu. Je to zároveň skill: skopírujte ho do priečinka so skillmi svojho agenta."),
            ("povedzte agentovi", "<span class=\"prompt\">{p_dict}</span>"),
            ("potom v telefóne", "V Droneboarde 0.2.1 alebo novšom otvorte <strong>Nastavenia → Oprava textu → Osobný slovník → Importovať zo súboru</strong>. Ukáže, koľko slov je nových, a po potvrdení ich pridá. Slová, ktoré v slovníku už sú, ostanú. Tá istá obrazovka vie naimportovať aj slovník exportovaný z Gboardu, ak chcete len preniesť slová, ktoré ste si tam pridali ručne."),
        ]),
        ("naučte droneboard nový jazyk", [
            ("z čoho sa jazyk skladá", "Hlavný slovník na písanie ťukaním, zoznam slov pre swipe so štatistikou dvojíc a trojíc slov, slovník emoji, malý neurónový model na predikciu ďalšieho slova, ktorý beží v telefóne, a rozloženie klávesnice. Každá časť sa stavia z otvorených dát skriptmi z repozitára."),
            ("čo agent potrebuje", "Stiahnutý <a href=\"{repo}\">najnovší main</a>, Python a miesto na disku pre korpus. Neurónový model sa trénuje cez MLX, takže treba Mac s Apple Silicon. V návode je, čo ktorý krok sťahuje a ako dlho trvá."),
            ("návod", "Súbor <a href=\"{g_lang}\"><code>new-language.md</code></a> (v angličtine) popisuje, ako overiť, že sa jazyk dá spraviť, odkiaľ sme brali dáta a ako nájsť to isté pre iný jazyk, príkazy na slovníky a n-gramy, trénovanie, export a overenie modelu a všetky súbory v aplikácii, ktoré treba zmeniť. Ako príklad slúžia commity, ktorými pribudla maďarčina. Na konci je, ako výsledok zmerať a čo dať do pull requestu."),
            ("povedzte agentovi", "<span class=\"prompt\">{p_lang}</span>"),
            ("pošlite to späť", "Jazyk, ktorý funguje vám, bude fungovať aj ďalšiemu človeku. Otvorte pull request na <a href=\"{repo}\">{repo_short}</a> s hotovými súbormi, príkazmi, ktoré ste spustili, a číslami, ktoré ste namerali."),
        ]),
        ("fakty, ktoré má agent vedieť", [
            ("projekt", "Droneboard je open-source klávesnica pre Android (GPL-3.0), fork HeliBoardu. Identifikátor aplikácie je <code>today.cypherpunk.droneboard</code>. Nemá povolenie INTERNET, takže všetko, čo používa, je v APK alebo si to používateľ naimportuje zo súboru."),
            ("kde čo je", "Zdrojový kód: <a href=\"{repo}\">{repo_short}</a>. APK je vo <a href=\"{repo}/releases/latest\">vydaniach na GitHube</a>. Nástroje na slovníky sú v <code>tools/dicts</code>, na neurónový model v <code>tools/lm</code>, prehrávanie a meranie swipe v <code>tools/swipe-replay</code>. Formáty súborov popisujú <code>native/droneswipe/FORMAT.md</code> a <code>docs/dictionaries.md</code>."),
            ("čistý text pre stroje", "Oba návody sú súbory v Markdowne so stálou adresou a ich zoznam je v <a href=\"{llms}\"><code>llms.txt</code></a>. Sú to kópie súborov z repozitára a generujú sa spolu s webom."),
            ("obmedzenia", "Osobný slovník sa nedá naplniť cez adb. Android doň dovolí zapisovať len klávesnici, preto sa importuje v aplikácii. Swipe funguje na jednotlivé slová v latinke. Váhy swipe enkodéra sú od FUTO a majú vlastnú licenciu."),
        ]),
    ],
)

X["cs"] = dict(
    title="Droneboard pro AI agenty: osobní slovník a nové jazyky",
    desc="Dva úkoly, které za vás udělá AI agent: osobní slovník pro klávesnici Droneboard z vašich vlastních textů a natrénování a přidání nového jazyka. Návody v Markdownu a jeden malý nástroj.",
    h1="dejte to svému agentovi.",
    sub="AI agent za vás od začátku do konce udělá dva úkoly: z textů, které jste už napsali, vyrobí osobní slovník, a přidá jazyk, který Droneboard ještě nemá. Ke každému je návod v Markdownu (anglicky), který si agent přečte sám. Všechno běží na vašem počítači.",
    p_dict="Přečti si {url}personal-dictionary.md a vyrob mi osobní slovník pro klávesnici Droneboard z mých vlastních textů. Texty mám v <složka nebo soubor>. Píšu v jazycích <jazyky>. Všechno nech na tomhle počítači a před importem mi ukaž seznam.",
    p_lang="Přečti si {url}new-language.md, stáhni si nejnovější {repo} a přidej do Droneboardu <jazyk>: slovníky, seznam slov pro swipe a neuronový model pro predikci dalšího slova. Než začneš, řekni mi, jaká data chceš stáhnout a jak dlouho potrvá trénování.",
    back="zpět na hlavní stránku", source="zdrojový kód",
    sections=[
        ("osobní slovník z vlastních textů", [
            ("co to dělá", "Píšete slova, která nejsou v žádném slovníku: příjmení, ulice, názvy firem a projektů, žargon. Agent projde texty, které jste už napsali (poznámky, odeslané e-maily, vaše zprávy z chatu), najde slova, která Droneboard ještě nezná, seznam pročistí a zapíše do souboru. Ten pak v klávesnici naimportujete. Nástroj jen čte a zapisuje soubory ve vašem počítači."),
            ("co agent potřebuje", "Python 3.8 nebo novější, nástroj <a href=\"{raw}tools/personal-dict/personal_dict.py\"><code>personal_dict.py</code></a> (jeden soubor, stačí standardní knihovna), APK Droneboardu z <a href=\"{repo}/releases/latest\">nejnovějšího vydání</a> a vaše texty. Nástroj si z APK přečte vestavěné slovníky, takže slova, která klávesnice už zná, se nepřidají znovu."),
            ("návod", "V souboru <a href=\"{g_dict}\"><code>personal-dictionary.md</code></a> (anglicky) je, kde hledat texty, jak z exportu chatu vybrat jen vaše zprávy, které tři příkazy spustit, jak seznam pročistit, jak vypadá soubor pro import a jak ho dostat do telefonu. Je to zároveň skill: zkopírujte ho do složky se skilly svého agenta."),
            ("řekněte agentovi", "<span class=\"prompt\">{p_dict}</span>"),
            ("potom v telefonu", "V Droneboardu 0.2.1 nebo novějším otevřete <strong>Nastavení → Oprava textu → Vlastní slovník → Importovat ze souboru</strong>. Ukáže, kolik slov je nových, a po potvrzení je přidá. Slova, která ve slovníku už jsou, zůstanou. Stejná obrazovka umí naimportovat i slovník exportovaný z Gboardu, pokud chcete jen přenést slova, která jste si tam přidali ručně."),
        ]),
        ("naučte droneboard nový jazyk", [
            ("z čeho se jazyk skládá", "Hlavní slovník pro psaní ťukáním, seznam slov pro swipe se statistikou dvojic a trojic slov, slovník emoji, malý neuronový model pro predikci dalšího slova, který běží v telefonu, a rozložení klávesnice. Každá část se staví z otevřených dat skripty z repozitáře."),
            ("co agent potřebuje", "Stažený <a href=\"{repo}\">nejnovější main</a>, Python a místo na disku pro korpus. Neuronový model se trénuje přes MLX, takže je potřeba Mac s Apple Silicon. V návodu je, co který krok stahuje a jak dlouho trvá."),
            ("návod", "Soubor <a href=\"{g_lang}\"><code>new-language.md</code></a> (anglicky) popisuje, jak ověřit, že se jazyk dá udělat, odkud jsme brali data a jak najít totéž pro jiný jazyk, příkazy pro slovníky a n-gramy, trénování, export a ověření modelu a všechny soubory v aplikaci, které je třeba změnit. Jako příklad slouží commity, kterými přibyla maďarština. Na konci je, jak výsledek změřit a co dát do pull requestu."),
            ("řekněte agentovi", "<span class=\"prompt\">{p_lang}</span>"),
            ("pošlete to zpět", "Jazyk, který funguje vám, bude fungovat i dalšímu člověku. Otevřete pull request na <a href=\"{repo}\">{repo_short}</a> s hotovými soubory, příkazy, které jste spustili, a čísly, která jste naměřili."),
        ]),
        ("fakta, která má agent znát", [
            ("projekt", "Droneboard je open-source klávesnice pro Android (GPL-3.0), fork HeliBoardu. Identifikátor aplikace je <code>today.cypherpunk.droneboard</code>. Nemá oprávnění INTERNET, takže všechno, co používá, je v APK nebo si to uživatel naimportuje ze souboru."),
            ("kde co je", "Zdrojový kód: <a href=\"{repo}\">{repo_short}</a>. APK je ve <a href=\"{repo}/releases/latest\">vydáních na GitHubu</a>. Nástroje pro slovníky jsou v <code>tools/dicts</code>, pro neuronový model v <code>tools/lm</code>, přehrávání a měření swipe v <code>tools/swipe-replay</code>. Formáty souborů popisují <code>native/droneswipe/FORMAT.md</code> a <code>docs/dictionaries.md</code>."),
            ("čistý text pro stroje", "Oba návody jsou soubory v Markdownu se stálou adresou a jejich seznam je v <a href=\"{llms}\"><code>llms.txt</code></a>. Jsou to kopie souborů z repozitáře a generují se spolu s webem."),
            ("omezení", "Osobní slovník se nedá naplnit přes adb. Android do něj dovolí zapisovat jen klávesnici, proto se importuje v aplikaci. Swipe funguje na jednotlivá slova v latince. Váhy swipe enkodéru jsou od FUTO a mají vlastní licenci."),
        ]),
    ],
)

X["de"] = dict(
    title="Droneboard für KI-Agenten: persönliches Wörterbuch und neue Sprachen",
    desc="Zwei Aufgaben, die ein KI-Agent für Sie erledigt: ein persönliches Wörterbuch für die Tastatur Droneboard aus Ihren eigenen Texten und das Trainieren und Hinzufügen einer neuen Sprache. Anleitungen in Markdown und ein kleines Werkzeug.",
    h1="geben sie es ihrem agenten.",
    sub="Ein KI-Agent erledigt für Sie zwei Aufgaben von Anfang bis Ende: Er baut aus Texten, die Sie schon geschrieben haben, ein persönliches Wörterbuch, und er fügt eine Sprache hinzu, die Droneboard noch nicht hat. Zu jeder Aufgabe gibt es eine Anleitung in Markdown (auf Englisch), die der Agent selbst liest. Alles läuft auf Ihrem Computer.",
    p_dict="Lies {url}personal-dictionary.md und baue mir aus meinen eigenen Texten ein persönliches Wörterbuch für die Tastatur Droneboard. Meine Texte liegen in <Ordner oder Datei>. Ich schreibe in <Sprachen>. Lass alles auf diesem Computer und zeig mir die Liste, bevor ich sie importiere.",
    p_lang="Lies {url}new-language.md, hole den aktuellen Stand von {repo} und füge <Sprache> zu Droneboard hinzu: Wörterbücher, Wortliste für das Wischen und das neuronale Modell für das nächste Wort. Sag mir vorher, welche Daten du herunterladen willst und wie lange das Training dauert.",
    back="zurück zur Hauptseite", source="Quellcode",
    sections=[
        ("ein persönliches wörterbuch aus eigenen texten", [
            ("was es tut", "Sie schreiben Wörter, die in keinem Wörterbuch stehen: Familiennamen, Straßen, Firmen- und Projektnamen, Fachjargon. Der Agent liest Texte, die Sie schon geschrieben haben (Notizen, gesendete E-Mails, Ihre eigenen Chatnachrichten), findet die Wörter, die Droneboard noch nicht kennt, bereinigt die Liste und schreibt eine Datei. Diese Datei importieren Sie in der Tastatur. Das Werkzeug liest und schreibt nur Dateien auf Ihrem Computer."),
            ("was der agent braucht", "Python 3.8 oder neuer, das Werkzeug <a href=\"{raw}tools/personal-dict/personal_dict.py\"><code>personal_dict.py</code></a> (eine Datei, nur Standardbibliothek), das Droneboard-APK aus der <a href=\"{repo}/releases/latest\">neuesten Version</a> und Ihre Texte. Das Werkzeug liest die eingebauten Wörterbücher aus dem APK, damit Wörter, die die Tastatur schon kennt, nicht noch einmal hinzukommen."),
            ("die anleitung", "In <a href=\"{g_dict}\"><code>personal-dictionary.md</code></a> (auf Englisch) steht, wo man nach Texten sucht, wie man aus einem Chat-Export nur die eigenen Nachrichten nimmt, welche drei Befehle auszuführen sind, wie die Wortliste bereinigt wird, wie die Importdatei aussieht und wie sie aufs Telefon kommt. Die Datei ist zugleich ein Skill: Kopieren Sie sie in den Skills-Ordner Ihres Agenten."),
            ("sagen sie ihrem agenten", "<span class=\"prompt\">{p_dict}</span>"),
            ("danach auf dem telefon", "Öffnen Sie in Droneboard 0.2.1 oder neuer <strong>Einstellungen → Textkorrektur → Persönliches Wörterbuch → Aus Datei importieren</strong>. Die Tastatur zeigt, wie viele Wörter neu sind, und fügt sie nach Ihrer Bestätigung hinzu. Vorhandene Wörter bleiben. Derselbe Bildschirm importiert auch das aus Gboard exportierte Wörterbuch, falls Sie nur die Wörter mitnehmen wollen, die Sie dort von Hand hinzugefügt haben."),
        ]),
        ("bringen sie droneboard eine neue sprache bei", [
            ("woraus eine sprache besteht", "Ein Hauptwörterbuch für das Tippen, eine Wortliste für das Wischen mit Statistiken zu Wortpaaren und Dreiergruppen, ein Emoji-Wörterbuch, ein kleines neuronales Modell für das nächste Wort, das auf dem Telefon läuft, und ein Tastaturlayout. Jeder Teil wird mit Skripten aus dem Repository aus offenen Daten gebaut."),
            ("was der agent braucht", "Den <a href=\"{repo}\">aktuellen main</a>, Python und Speicherplatz für das Korpus. Das neuronale Modell wird mit MLX trainiert, dafür braucht es einen Mac mit Apple Silicon. In der Anleitung steht, was jeder Schritt herunterlädt und wie lange er dauert."),
            ("die anleitung", "<a href=\"{g_lang}\"><code>new-language.md</code></a> (auf Englisch) beschreibt, wie man prüft, ob eine Sprache machbar ist, woher unsere Daten stammen und wie man dasselbe für eine andere Sprache findet, die Befehle für Wörterbücher und N-Gramme, das Trainieren, Exportieren und Prüfen des Modells und jede Datei in der App, die geändert werden muss. Als Beispiel dienen die Commits, mit denen Ungarisch hinzukam. Am Ende steht, wie man das Ergebnis misst und was in den Pull Request gehört."),
            ("sagen sie ihrem agenten", "<span class=\"prompt\">{p_lang}</span>"),
            ("schicken sie es zurück", "Eine Sprache, die bei Ihnen funktioniert, funktioniert auch beim Nächsten. Öffnen Sie einen Pull Request auf <a href=\"{repo}\">{repo_short}</a> mit den gebauten Dateien, den ausgeführten Befehlen und den gemessenen Zahlen."),
        ]),
        ("fakten, die ein agent kennen sollte", [
            ("das projekt", "Droneboard ist eine quelloffene Android-Tastatur (GPL-3.0), ein Fork von HeliBoard. Die Anwendungs-ID lautet <code>today.cypherpunk.droneboard</code>. Die App hat keine INTERNET-Berechtigung. Alles, was sie nutzt, steckt im APK oder wird vom Nutzer aus einer Datei importiert."),
            ("wo was liegt", "Quellcode: <a href=\"{repo}\">{repo_short}</a>. Das APK liegt in den <a href=\"{repo}/releases/latest\">GitHub-Releases</a>. Die Werkzeuge für Wörterbücher sind in <code>tools/dicts</code>, die für das neuronale Modell in <code>tools/lm</code>, Wiedergabe und Auswertung von Wischgesten in <code>tools/swipe-replay</code>. Die Dateiformate beschreiben <code>native/droneswipe/FORMAT.md</code> und <code>docs/dictionaries.md</code>."),
            ("reiner text für maschinen", "Beide Anleitungen sind Markdown-Dateien mit festen Adressen und stehen in <a href=\"{llms}\"><code>llms.txt</code></a>. Sie sind Kopien von Dateien aus dem Repository und werden mit der Website neu gebaut."),
            ("grenzen", "Das persönliche Wörterbuch lässt sich nicht über adb füllen. Android erlaubt nur der Tastatur, darin zu schreiben, deshalb läuft der Import in der App. Wischen funktioniert mit einzelnen Wörtern in lateinischer Schrift. Die Gewichte des Wisch-Encoders stammen von FUTO und haben eine eigene Lizenz."),
        ]),
    ],
)

X["es"] = dict(
    title="Droneboard para agentes de IA: diccionario personal e idiomas nuevos",
    desc="Dos tareas que un agente de IA hace por ti: un diccionario personal para el teclado Droneboard a partir de tus propios textos, y entrenar y añadir un idioma nuevo. Guías en Markdown y una herramienta pequeña.",
    h1="déjaselo a tu agente.",
    sub="Un agente de IA puede hacer por ti dos tareas de principio a fin: crear un diccionario personal con los textos que ya has escrito y añadir un idioma que Droneboard todavía no tiene. Cada tarea tiene una guía en Markdown (en inglés) que el agente lee por su cuenta. Todo se ejecuta en tu ordenador.",
    p_dict="Lee {url}personal-dictionary.md y créame un diccionario personal para el teclado Droneboard a partir de mis propios textos. Mis textos están en <carpeta o archivo>. Escribo en <idiomas>. Déjalo todo en este ordenador y enséñame la lista antes de importarla.",
    p_lang="Lee {url}new-language.md, descarga la última versión de {repo} y añade <idioma> a Droneboard: diccionarios, lista de palabras para deslizar y el modelo neuronal de la palabra siguiente. Antes de empezar, dime qué datos quieres descargar y cuánto tardará el entrenamiento.",
    back="volver a la página principal", source="código fuente",
    sections=[
        ("un diccionario personal con tus propios textos", [
            ("qué hace", "Escribes palabras que no están en ningún diccionario: apellidos, calles, nombres de empresas y proyectos, jerga. El agente lee textos que ya has escrito (notas, correos enviados, tus propios mensajes de chat), encuentra las palabras que Droneboard aún no conoce, limpia la lista y escribe un archivo. Ese archivo lo importas en el teclado. La herramienta solo lee y escribe archivos en tu ordenador."),
            ("qué necesita el agente", "Python 3.8 o posterior, la herramienta <a href=\"{raw}tools/personal-dict/personal_dict.py\"><code>personal_dict.py</code></a> (un archivo, solo la biblioteca estándar), el APK de Droneboard de la <a href=\"{repo}/releases/latest\">última versión</a> y tus textos. La herramienta lee los diccionarios incluidos en el APK, así que las palabras que el teclado ya conoce no se añaden otra vez."),
            ("la guía", "<a href=\"{g_dict}\"><code>personal-dictionary.md</code></a> (en inglés) explica dónde buscar textos, cómo tomar solo tus mensajes de una exportación de chat, qué tres comandos ejecutar, cómo limpiar la lista de palabras, cómo es el archivo de importación y cómo pasarlo al teléfono. También es un skill: cópialo en la carpeta de skills de tu agente."),
            ("dile esto a tu agente", "<span class=\"prompt\">{p_dict}</span>"),
            ("después, en el teléfono", "En Droneboard 0.2.1 o posterior abre <strong>Ajustes → Corrección de texto → Diccionario personal → Importar desde un archivo</strong>. Muestra cuántas palabras son nuevas y las añade cuando confirmas. Las palabras que ya estaban se quedan. La misma pantalla importa el diccionario exportado de Gboard, si solo quieres traer las palabras que añadiste allí a mano."),
        ]),
        ("enséñale a droneboard un idioma nuevo", [
            ("de qué consta un idioma", "Un diccionario principal para escribir tocando, una lista de palabras para deslizar con estadísticas de pares y tríos de palabras, un diccionario de emojis, un pequeño modelo neuronal de la palabra siguiente que se ejecuta en el teléfono y una distribución de teclado. Cada parte se construye con datos abiertos y scripts del repositorio."),
            ("qué necesita el agente", "Una copia del <a href=\"{repo}\">main más reciente</a>, Python y espacio en disco para el corpus. El modelo neuronal se entrena con MLX, así que hace falta un Mac con Apple Silicon. La guía dice qué descarga cada paso y cuánto tarda."),
            ("la guía", "<a href=\"{g_lang}\"><code>new-language.md</code></a> (en inglés) explica cómo comprobar que un idioma es viable, de dónde salieron nuestros datos y cómo encontrar lo mismo para otro idioma, los comandos para diccionarios y n-gramas, el entrenamiento, la exportación y la verificación del modelo, y cada archivo de la app que hay que cambiar. Los commits que añadieron el húngaro sirven de ejemplo. Termina con cómo medir el resultado y qué poner en el pull request."),
            ("dile esto a tu agente", "<span class=\"prompt\">{p_lang}</span>"),
            ("envíalo de vuelta", "Un idioma que te funciona a ti le funcionará también a la siguiente persona. Abre un pull request en <a href=\"{repo}\">{repo_short}</a> con los archivos generados, los comandos que ejecutaste y las cifras que mediste."),
        ]),
        ("datos que un agente debe conocer", [
            ("el proyecto", "Droneboard es un teclado para Android de código abierto (GPL-3.0), un fork de HeliBoard. Su identificador de aplicación es <code>today.cypherpunk.droneboard</code>. No tiene el permiso INTERNET, así que todo lo que usa viene en el APK o lo importa el usuario desde un archivo."),
            ("dónde está cada cosa", "Código fuente: <a href=\"{repo}\">{repo_short}</a>. El APK está en las <a href=\"{repo}/releases/latest\">versiones de GitHub</a>. Las herramientas de diccionarios están en <code>tools/dicts</code>, las del modelo neuronal en <code>tools/lm</code>, la reproducción y evaluación de gestos en <code>tools/swipe-replay</code>. Los formatos de archivo se describen en <code>native/droneswipe/FORMAT.md</code> y <code>docs/dictionaries.md</code>."),
            ("texto plano para máquinas", "Las dos guías son archivos Markdown con direcciones estables y figuran en <a href=\"{llms}\"><code>llms.txt</code></a>. Son copias de archivos del repositorio y se regeneran con el sitio web."),
            ("límites", "El diccionario personal no se puede rellenar por adb. Android solo deja escribir en él al teclado, por eso la importación se hace en la app. La escritura deslizando funciona con palabras sueltas en alfabeto latino. Los pesos del codificador de gestos son de FUTO y tienen su propia licencia."),
        ]),
    ],
)

CSS = """
.prompt { display: block; padding: 0.9rem 1rem; border: 1px solid var(--rule); border-radius: 6px; font-style: italic; user-select: all; overflow-wrap: anywhere; }
.agents .tracks p { overflow-wrap: anywhere; }
"""


def page(code, t, langs, site):
    d = X[code]
    e = escape
    root = "../" if code == "en" else "../../"
    home = root + ("" if code == "en" else code + "/")
    url = lambda c: site + ("" if c == "en" else c + "/") + PATH
    href = lambda c: root + ("" if c == "en" else c + "/") + PATH
    self_url = url(code)
    guides = root + PATH  # the Markdown guides exist once, in English
    nav = t["nav"]
    fmt = dict(raw=RAW, repo=REPO, repo_short=REPO.replace("https://", ""), llms=root + "llms.txt",
               g_dict=guides + "personal-dictionary.md", g_lang=guides + "new-language.md",
               p_dict=e(d["p_dict"].format(url=site + PATH)), p_lang=e(d["p_lang"].format(url=site + PATH, repo=REPO)))
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
    panels = []
    n = 0
    for si, (title, items) in enumerate(d["sections"]):
        lis = []
        for head, text in items:
            n += 1
            lis.append(f'<li><span class="no">{n:02d}</span><h3>{e(head)}</h3><p>{text.format(**fmt)}</p></li>')
        panels.append(
            f'<section class="panel agents" id="g{si + 1}" aria-labelledby="g{si + 1}-h">\n'
            f'  <h2 id="g{si + 1}-h">{e(title)}</h2>\n'
            f'  <ol class="tracks tips">{"".join(lis)}</ol>\n'
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
<link rel="alternate" type="text/markdown" href="{site}{PATH}personal-dictionary.md" title="personal-dictionary.md">
<link rel="alternate" type="text/markdown" href="{site}{PATH}new-language.md" title="new-language.md">
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
<style>{CSS}</style>
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
        <li><a href="{home}tips/">{TIPS[code]}</a></li>
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

<section class="panel" id="more" aria-label="{e(d['back'])}">
  <p class="inst-note"><a href="{home}">{e(d['back'])}</a> · <a href="{REPO}">{e(d['source'])}</a></p>
</section>

{crosspromo.section(code, root, "../dictation/#languages", compact=True)}
</main>

<footer class="foot">
  <span>{t['runout']}</span>
  <ul aria-label="{e(t['langlabel'])}">{foot_langs}</ul>
</footer>
</body>
</html>
"""


def llms_txt(site):
    lines = [
        "# Droneboard",
        "",
        "> Droneboard is an open-source Android keyboard that works entirely offline: multilingual swipe typing,",
        "> on-device neural next-word prediction and offline dictation. No INTERNET permission. GPL-3.0.",
        "",
        "## Guides for agents",
        "",
    ]
    for name, _, what in GUIDES:
        lines.append(f"- [{name}]({site}{PATH}{name}): {what}")
    lines += [
        "",
        "## Project",
        "",
        f"- [Source code]({REPO}): app, dictionary and model tooling, website",
        f"- [Releases]({REPO}/releases/latest): the signed APK",
        f"- [Website]({site}): features, privacy, install",
        f"- [Tips]({site}tips/): gestures and settings that are easy to miss",
        f"- [Dictation]({site}dictation/): how offline dictation is set up",
        "",
    ]
    return "\n".join(lines)


def write_pages(root, T, langs, site):
    repo_root = root.parent
    guides = root / PATH
    guides.mkdir(parents=True, exist_ok=True)
    for name, source, _ in GUIDES:
        src = repo_root / source
        if not src.exists():
            raise SystemExit(f"agents page: {source} is missing")
        shutil.copyfile(src, guides / name)
        print("wrote", (guides / name).relative_to(root))
    for code, _ in langs:
        out = root / (PATH + "index.html" if code == "en" else f"{code}/{PATH}index.html")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(page(code, T[code], langs, site), encoding="utf-8")
        print("wrote", out.relative_to(root))
    (root / "llms.txt").write_text(llms_txt(site), encoding="utf-8")
    print("wrote llms.txt")
