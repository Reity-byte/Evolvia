# Evolvia – pokyny pro Claude Code

God game o evoluci druhu (Java 21, LWJGL 3, vlastní ECS). **Hlavní dokument je `DESIGN.md`**: vize, architektura, pravidla (sekce 0), plán fází 0–20 a detail každé hotové fáze. Než začneš cokoli měnit, přečti si v něm sekci 0, tabulku „Fáze 10–20 — plán do verze 1.0“ a detail poslední hotové fáze.

## Komunikace
- S uživatelem (Lucas) mluv **česky**. Kód, komentáře, commity a identifikátory jsou **anglicky**, herní texty česky.
- Rozhodnutí, která mění hru, nejdřív polož jako krátké otázky s doporučenou variantou; drobnosti rozhodni sám a zmiň je.

## Postup u každé fáze / podfáze
1. **Nejdřív rozepiš detail do `DESIGN.md`** (podfáze s DoD, data, testy). Novou fázi začni až po schválení uživatelem („můžeme pokračovat“ = schválení).
2. Implementuj po podfázích; herní data a balancování patří do JSON v `src/main/resources/data/`.
3. **Testuj:** unit testy (`./mvnw -o package`, všechny musí projít) + ověření ve hře screenshoty (viz níže). Křehké scénářové testy, které stojí na konkrétní mapě, zpevni, neobcházej.
4. Do `DESIGN.md` doplň k podfázi odrážku **„Upřesněno při implementaci“** (co se oproti plánu změnilo a naměřené hodnoty).
5. **Commit a push** každé podfáze na `master` (commit anglicky, s řádkem `Co-Authored-By`).
6. **Na konci celé fáze (nebo samostatné mezifáze) vydání:** `git tag v0.N` + push tagu; CI (`.github/workflows/release.yml`) postaví 6 balíčků a launcher na ně aktualizuje. Stav CI: veřejné API `https://api.github.com/repos/Reity-byte/Evolvia/actions/runs` (nemáme `gh`), výsledek oznam uživateli.
7. **Shrnutí česky:** co vzniklo, klíčová rozhodnutí, známé limity, jak to ručně ověřit (a že je vydání v launcheru).
8. Pak čekej na zpětnou vazbu; další fázi rozepiš až po ní.

## Build a test
- JDK 26: `export JAVA_HOME="$USERPROFILE/.jdks/openjdk-26.0.2.1"`, Maven wrapper `./mvnw -o package > target/build.log 2>&1` a pak grep logu (`Tests run:`, `BUILD`, `[ERROR]`).
- Hra: `java -jar target/evolvia.jar` (vývojová data v `./run`), ovladač testů je skript `game.ps1` ve scratchpadu (start/keys/click/sclick/move/wheel/hold/shot). Ladicí klávesy: F7 +EP/+ZN, F8 +Víra, F10 vyvinout všechny, F11 počasí, F12 katastrofa, F2 věda, F4 evoluce, Tab záložky spodní lišty.
- Podrobnosti prostředí a triky jsou v paměti (`memory/build-environment.md`): **nikdy neupravuj soubory přes PowerShell Set-Content** (rozbije kódování), vícesouborové úpravy dělej Python skriptem.
- Rychlé herní stavy: dočasný JUnit test v `src/test/java/evolvia/save/` postaví svět (`world.develop(id)`) a uloží ho do `run/saves` (pak soubor smaž). V `./run/saves` jsou savy `kmen`, `clovek`, `soupere`.

## Stav projektu (aktualizuj po každém vydání)
- Hotovo: fáze 0–11 včetně mezifází 9i (spodní lišta), 10d (rovinatější svět, klima, svahy hor). Poslední vydání **v0.19** (fáze 11 Soupeřící kmeny: Hrubci, jejich kmen, nájezdy, zničení). Save verze 11, 249 testů.
- Další podle plánu: **fáze 12 Válka a obrana** (zbraně jako věda, válečníci a hlídky, palisáda a věž, morálka v boji, božské zásahy do bitvy). Před začátkem ji rozepiš do `DESIGN.md` a nech schválit.
- Nápady uživatele do dalších fází: domy s omezenou kapacitou a rodiny (fáze 14), nové UI staveb v kategoriích spodní lišty, případně hřebeny hor; tempo evoluce, vědy a nájezdů doladit podle jeho hraní.
