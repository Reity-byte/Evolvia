# DESIGN.md — Evolvia (pracovní název)

God-game / life-sim ve stylu *The Universim* a *Black & White*: hráč pozoruje a ovlivňuje druh bytostí, který se v 3D světě vyvíjí po evolučním stromě od primitivních tvorů po kmen se stavbami.

Tento dokument je **hlavní zdroj pravdy** pro architekturu i postup vývoje. Claude Code ho čte na začátku každé session.

---

## 0. Pravidla pro Claude Code

1. **Pracuj po fázích** (sekce 11). Nezačínej další fázi, dokud aktuální nesplňuje svůj *Definition of Done*.
2. **Neměň architekturu na vlastní pěst.** Pokud narazíš na důvod něco z tohoto dokumentu změnit, zastav se, popiš problém a navrhni změnu. Po schválení aktualizuj tento soubor.
3. **Na konci každé fáze** napiš krátké shrnutí: co vzniklo, klíčová rozhodnutí a proč, známé limity, a jak to ručně ověřit.
4. **Každá fáze = samostatný commit** (případně víc menších). Build musí projít vždy.
5. **Herní data patří do JSON**, ne do kódu (evoluční strom, druhy zdrojů, biomy, balancování).
6. Preferuj jednoduché, čitelné řešení před chytrým. Žádné předčasné optimalizace mimo výkonnostní cíle v sekci 10.
7. Nepřidávej závislosti mimo sekci 1 bez zdůvodnění.

---

## 1. Tech stack

| Oblast | Volba | Poznámka |
|---|---|---|
| Jazyk | Java 21 (LTS) | |
| Build | Maven | fat jar přes `maven-shade-plugin` |
| Okno / vstup | LWJGL 3 (GLFW) | |
| Grafika | LWJGL 3 – **OpenGL 3.3 core profile** | viz níže |
| Matematika | JOML | |
| Obrázky / fonty | LWJGL STB (`stb_image`, `stb_truetype`) | |
| JSON | Gson | herní data + savy |
| Testy | JUnit 5 | hlavně simulační logika |

**Proč OpenGL 3.3 core a ne legacy immediate mode:** budeme vykreslovat stovky až tisíce bytostí → potřebujeme VBO/VAO, shadery a **instanced rendering**. Immediate mode (`glBegin/glEnd`) by to neutáhl.

**macOS:** hra se testuje i na MacBooku. macOS podporuje max OpenGL 4.1 a vyžaduje core profile s forward-compat:
```java
glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
```
Nepoužívat nic nad OpenGL 4.1.

**Distribuce:** fat jar (s LWJGL natives pro `windows`, `linux`, `macos`, `macos-arm64`) → jpackage app-image (hra + launcher) pro windows/macos/linux, publikované přes GitHub Releases (`.github/workflows/release.yml` na tag `v*`). Hráč instaluje jen launcher, ten stahuje a aktualizuje hru do `<data>/versions/`. Vývoj: `java -jar`, data v `./run`. Lokální test balení: `packaging/package.ps1 -Launch`. Detaily viz §11 „Průřezová infrastruktura“.

**Známý problém vývojového prostředí:** spuštění `Main` přes IntelliJ Run configuration padá nativně v `lwjgl_opengl.dll` kvůli IntelliJ javaagentovi. Ruční testování probíhá přes `mvn package` a `java -jar target/<jar>.jar`. Na macOS je navíc potřeba `-XstartOnFirstThread`.

---

## 2. Vize hry

- Hráč je „božská entita“ nad světem. Nemá přímou kontrolu nad jednotlivci, ovlivňuje je nepřímo.
- Začíná se s malou populací primitivních tvorů jednoho druhu.
- Populace přežívá, rozmnožuje se a generuje **Evoluční body (EP)**.
- Hráč za EP odemyká uzly **evolučního stromu** → mění se schopnosti, chování i vzhled celého druhu.
- Pozdější větve vedou k inteligenci: nástroje, kmen, sběr zdrojů, stavby.
- Hráč má **božské zásahy** (déšť, jídlo, terraforming, trest/odměna), placené **Vírou**.
- Cíl první verze: sandbox bez výhry/prohry, jen vyhynutí druhu = game over.

**Vizuální styl:** low-poly, jednobarevné plochy, jednoduché osvětlení. Bytosti se skládají **procedurálně z primitiv** (tělo, hlava, nohy, ocas…) podle svých vlastností, takže evoluce je vidět bez ručně modelovaných assetů.

---

## 3. Struktura projektu

```
src/main/java/evolvia/
  Main.java           vstupní bod pro vývoj (IDE, java -jar), data v ./run
  Launch.java         vstupní bod hry pro hráče (nastaví GameDirs, pak Main)
  LauncherMain.java   vstupní bod launcheru
  core/        GameLoop, Time (tick rate, rychlost hry), Input, Window, GameDirs (datová složka), Platform
  launcher/    LauncherCore (logika, testovatelná offline), LauncherWindow (Swing), Archives (bezpečné rozbalení)
  ecs/         Entity, ComponentStore, EcsWorld, GameSystem
  components/  Transform, Velocity, Needs, Genome, Species, Diet, AiState, ...
  systems/     MovementSystem, NeedsSystem, AiSystem, ReproductionSystem, ...
  world/       World (terén + entity + systémy, jeden Random), Terrain, TerrainGenerator, Biome, SpatialGrid, ResourceNode
  evolution/   SpeciesDefinition (základ ze species.json), Species (stav druhu: EP, odemčené uzly, přepočtené statistiky, schopnosti), SpeciesStats, EvolutionTree, EvolutionNode, Effect, Condition, Stat
  ai/          Pathfinder (A*), Navigation (souš / plavání), akce utility AI (WanderAction, SeekResourceAction, ConsumeAction, SleepAction, SeekMateAction)
  god/         Faith (Víra, věřící, morálka), DivinePower, GodConfig, GodPowers (fronta příkazů, aktivní déšť, události pro efekty)
  render/      Shader, Mesh, MeshData, Camera, TerrainRenderer, CreatureRenderer, CreatureMeshBuilder, PartMeshBuilder
  ui/          Ui (immediate-mode UI), UiRenderer, FontAtlas + Font (stb_truetype), Hud, EvolutionTreeView, TreeLayout, CreatureSelection, DebugOverlay (F3)
  data/        načítání JSON definic
  save/        SaveManager, serializace
src/main/resources/
  shaders/
  data/evolution/*.json   větve stromu (seznam v branches.json)
  data/biomes.json
  data/species.json       základní statistiky startovního druhu (velikost, rychlost, bloudění, počáteční populace)
  data/world.json         parametry generování světa (velikost, noise, hladina moře, vzhled vody)
  data/resources.json
  data/powers.json        Víra (start, příjem) a parametry zásahů
  fonts/                  Droid Sans (Apache 2.0, licence vedle), UI font s češtinou
src/test/java/evolvia/
packaging/package.ps1     lokální balení na Windows (totéž co CI, jako lokální release)
.github/workflows/        release.yml (matrix build + GitHub Release)
```

---

## 4. Game loop a čas

- **Simulace a rendering jsou oddělené.**
- Simulace běží s **pevným tickem 20 ticků/s** (fixed timestep s akumulátorem).
- Rendering běží tak rychle, jak to jde (V-Sync), a **interpoluje** pozice mezi posledními dvěma ticky.
- **Pojistka FPS:** některé ovladače (NVIDIA Optimus na notebooku) V-Sync ignorují. `GameLoop` proto má omezovač snímků, výchozí strop = obnovovací frekvence monitoru, změna přes `--fps-cap <n>` (0 = vypnuto). Když V-Sync funguje, omezovač prakticky nic nedělá.
- Rychlost hry: `pauza`, `1×`, `3×`, `10×` = počet simulačních ticků na reálný čas. Při 10× se simulace nesmí rozpadnout (žádné závislosti na delta času renderu).
- Ovládání rychlosti: mezerník = pauza (návrat na předchozí rychlost), `1` / `2` / `3` = 1× / 3× / 10×.
- Klávesy: F4 evoluční strom (jinak tlačítko Evoluce), ESC zavře otevřené okno / zruší výběr, bez otevřeného okna ukončí hru.
- Ukládání: F5 rychlé uložení, F9 rychlé načtení, menu *Hra* (uložit, načíst, smazat, nový svět, ukončit).
- Ladicí klávesy: F3 přehled + graf populace + popisek nad vybranou bytostí, F6 vyprázdnit jídlo, F7 +100 EP, F8 +100 Víry.
- Veškerá herní logika je deterministická vzhledem k seedu (jeden `Random` na svět se seedem), aby šly reprodukovat bugy.
- Seed se zadává `--seed <n>` (jinak náhodný); aktuální seed ukazuje F3 overlay a výpis v konzoli.

---

## 5. ECS architektura

Jednoduchý vlastní ECS, žádná knihovna.

- **Entity** = `int` ID. Recyklace ID po smazání.
- **Komponenta** = čistá data (record nebo třída bez logiky).
- **ComponentStore<T>** = úložiště komponent jednoho typu (na začátek `HashMap<Integer, T>` nebo sparse set; rozhraní navrhnout tak, aby šlo později vyměnit implementaci za rychlejší bez změn v systémech).
- **GameSystem** = logika; metoda `update(EcsWorld world, int tick)`.
- Systémy běží v **pevně daném pořadí** definovaném na jednom místě.
- Mazání entit během ticku je odložené (fronta, zpracuje se na konci ticku).

### Základní komponenty

| Komponenta | Data |
|---|---|
| `Transform` | pozice (Vector3f), rotace (yaw) |
| `PrevTransform` | pozice z minulého ticku (pro interpolaci) |
| `Velocity` | směr, rychlost |
| `Needs` | hlad, žízeň, energie, (později) sociální potřeba — 0..1 |
| `Health` | HP, max HP |
| `Age` | věk v tickách, dospělost, maximální věk |
| `Genome` | individuální odchylky vlastností (viz 7.3) |
| `SpeciesRef` | odkaz na druh (sdílený stav evoluce) |
| `AiState` | aktuální akce, cíl, cesta |
| `Carrying` | co nese (od fáze se zdroji) |
| `Selectable` | může být vybrán hráčem |
| `Genome` | geny velikost, rychlost, odstín (násobitele kolem 1) a generace |
| `Reproduction` | cooldown rozmnožování, počet potomků |
| `Believer` | značka: bytost věří v boha (fáze 7) |
| `Fear` | odkud a do kdy utíká před bleskem (fáze 7) |

### Pořadí systémů (orientační)

```
Input → GodPowers → Needs → Ai (rozhodování) → Pathfinding → Movement
→ Eating/Drinking → Reproduction → Aging/Death → Evolution (EP) → Cleanup
```

---

## 6. Svět

- **Plochá ohraničená mapa**, ne planeta. Výchozí velikost 256 × 256 dlaždic (konfigurovatelné).
- Terén = **heightmap** (ne voxely). Výška ze Simplex noise, několik oktáv.
- **Biomy** z kombinace teploty a vlhkosti (dva další noise kanály): tráva, les, poušť, tundra, bažina, voda. Definice v `biomes.json`.
- Každá dlaždice má: výšku, biom, průchodnost, úrodnost.
- **Zdroje ve světě** (`ResourceNode` entity): keře s bobulemi, stromy, voda, kámen. Jídlo **dorůstá** v čase podle úrodnosti biomu.
  - Voda = neomezený `ResourceNode` na každé dlaždici souše u vody (bytost pije ze břehu). Stromy a kámen přijdou se sběrem (fáze 9).
  - Zdroje jsou ve `SpatialGrid` zvlášť podle druhu (jídlo / voda), aby hledání jídla neprocházelo vodu.
- **SpatialGrid**: rozdělení mapy do buněk (např. 16 × 16) pro rychlé dotazy „co je v okolí X“. Žádné O(n²) hledání.
- Rendering terénu: jeden mesh rozdělený do chunků, barva podle biomu, per-vertex normály, jednoduché směrové světlo. Voda jako plochá průhledná rovina.

---

## 7. Bytosti a evoluce

### 7.1 Druh (Species)

Sdílený stav celého druhu:
- odemčené uzly evolučního stromu
- **základní statistiky** (spočítané z odemčených uzlů): velikost, rychlost, dohled, max HP, spotřeba jídla, délka života, rychlost rozmnožování, odolnost vůči chladu/horku, typ potravy
- odemčené **schopnosti** (plavání, lov, nástroje, stavění…)
- **vizuální díly** (počet nohou, ocas, rohy, srst, barva…)

Statistiky se **přepočítají jednou** při odemčení uzlu, ne každý tick.

### 7.2 Evoluční strom

Data-driven, definice v `resources/data/evolution/*.json`. Každý soubor = jedna větev.

**Větve (první verze):**
- **Tělo** – velikost, rychlost, končetiny, odolnost
- **Potrava** – býložravec / všežravec / masožravec (vzájemně se vylučují)
- **Adaptace** – chlad, horko, voda (plavání → obojživelník)
- **Mysl** – instinkty → paměť → sociální skupiny → nástroje → řeč → kmen

**Struktura uzlu:**
```json
{
  "id": "body_long_legs",
  "name": "Dlouhé nohy",
  "description": "Rychlejší pohyb, vyšší spotřeba energie.",
  "branch": "body",
  "cost": 40,
  "requires": ["body_legs_basic"],
  "exclusiveGroup": null,
  "requiresCondition": null,
  "effects": [
    { "type": "stat_mul", "stat": "speed", "value": 1.3 },
    { "type": "stat_mul", "stat": "energyDrain", "value": 1.15 },
    { "type": "visual", "part": "legs", "variant": "long" }
  ]
}
```

- `requires` – prerekvizity (všechny musí být odemčené)
- `exclusiveGroup` – uzly ve stejné skupině se vylučují (např. `"diet"`)
- `requiresCondition` – volitelná podmínka ze světa, např. `{ "type": "population_min", "value": 30 }` nebo `{ "type": "biome_presence", "biome": "tundra", "ratio": 0.2 }`
- **Typy efektů:** `stat_add`, `stat_mul`, `unlock_ability`, `visual`, `unlock_action`

Při načítání validovat: neexistující ID v `requires`, cykly, duplicitní ID. Chyba = pád s jasnou hláškou.

**Evoluční body (EP)** získává druh průběžně z:
- velikosti populace (logaritmicky, ať obří populace nevede k lavině bodů)
- počtu proběhlých generací
- přežívání v náročných podmínkách (jedinci v biomu, na který nejsou adaptovaní)

**Rozhodnutí k fázi 5:**
- **EP utrácí hráč** (§14). Ve fázi 5 přes ladicí panel, ve fázi 6 myší.
- Stav druhu = `species.json` (základ) + odemčené uzly. Přepočet jednou při odemčení: základ → všechny `stat_add` → všechny `stat_mul`. Genom jedince pak násobí výsledek (±10 %).
- Soubory větví: `data/evolution/*.json`, jejich seznam je v `data/evolution/branches.json` (z jaru nejde vypsat obsah složky).
- Statistiky pro `stat_add` / `stat_mul`: `size`, `speed`, `sight`, `maxHealth`, `hungerRate`, `thirstRate`, `energyDrain`, `lifespan`, `reproductionCooldown`, `litterSize`, `comfortMin`, `comfortMax`, `plantNutrition`, `meatNutrition`. Strava = výživnost (0 = nejí), takže býložravec / všežravec / masožravec jsou jen hodnoty. Schopnosti (`unlock_ability`): `swim`, `memory`.
- **Strava přes mršiny:** po každé uhynulé bytosti zůstane mršina (zdroj jídla typu `meat`), která postupně zmizí. Jídlo má typ (`plant` / `meat`) a výživnost; strava druhu určuje, co AI považuje za jídlo. Býložravec / všežravec / masožravec se vylučují. Lov (`Hunt`) až s divokou zvěří.
- **Klima jako tlak prostředí:** druh má teplotní rozsah pohodlí (`climate` v `species.json`). Mimo něj rychleji roste hlad (chlad) nebo žízeň (horko) a při velkém rozdílu ubývá zdraví. Adaptace na chlad/horko rozsah rozšiřují. Jedinci mimo rozsah dávají EP za „náročné podmínky“.
- **Plavání:** schopnost `swim` zpřístupní mělkou vodu (vlastní navigace, pro plavce spojuje ostrovy).
- **Mysl ve fázi 5:** jen první uzly, hlavně **Paměť** (bytost si pamatuje poslední místo s vodou a jídlem a vrátí se tam, když nic nevidí). Zbytek větve ve fázi 9+.

### 7.3 Individuální genom (drobná variace)

Každý jedinec má `Genome` = malé odchylky (±10 %) od statistik druhu. Při rozmnožení: průměr rodičů + malá náhodná mutace. Slouží pro rozmanitost a vizuální variaci (odstín barvy, velikost), **není** to hlavní evoluční mechanika — tou je strom.

- Geny: velikost (vykreslení + spotřeba jídla), rychlost chůze, odstín barvy. Mutace = Gaussovský šum `genome.mutation`, geny zůstávají v rozsahu `genome.variation`.
- **Rozmnožování** (akce `SeekMate`): dva dospělí jedinci (věk ≥ `reproduction.adultAgeSeconds`) po cooldownu, nasycení a napojení (< `maxNeed`), zdraví a bdělí. Oba rodiče zaplatí `hungerCost` hladu, takže růst populace omezuje jídlo a populace se ustálí kolem úživnosti prostředí. Mládě je menší a do dospělosti roste. Pohlaví zatím nejsou.
- Start: `population.starting` jedinců ve skupině v okruhu `spawnRadius` kolem místa s jídlem a vodou; `population.max` je pojistka výkonu (nad ní se nerodí).

### 7.4 Procedurální vzhled

`CreatureMeshBuilder` složí mesh z primitiv podle vizuálních dílů druhu. Mesh se generuje **jednou na druh** (+ při změně stromu), jedinci se kreslí **instancovaně** s per-instance daty (matice, barevný odstín, velikost).

**Rozhodnutí k fázi 6:**
- Díly jsou natočené kvádry (low-poly). Základ: tělo, hlava s čumákem, oči, uši, 4 nohy, ocas. Barva = barva druhu (`species.json`), odstín z genomu, spící tmavší, vybraná zvýrazněná.
- Vizuální díly (`visual` efekt: `part` → `variant`, první varianta = výchozí): `legs` normal/strong/long, `body` normal/large, `skin` normal/thick/sandy/moist/bare, `fur` none/thick/white, `feet` normal/webbed, `teeth` none/flat/mixed/sharp, `eyes` normal/big, `ears` normal/alert, `head` normal/large, `belly` normal/round. Při odemčení dalšího uzlu se stejným dílem vyhrává poslední odemčený. Nepodporovaný díl ve stromu = pád při startu s jasnou hláškou.
- Geometrie dílů je v kódu (`CreatureMeshBuilder`), ne v JSON: je to vzhled, ne balancování.
- Animace ve vertex shaderu: nohy (a chodidla, srst na nohou) se kývají kolem kyčle podle fáze chůze (per-instance fáze + amplituda), tělo se pohupuje. Rychlost kroku podle skutečné rychlosti, animace běží v simulačním čase (pauza ji zastaví).
- Mršiny mají vlastní tvar (ležící tělo), rozlišené podle `foodType: meat`.

Animace jen procedurální: pohupování těla a kmitání nohou podle rychlosti. Žádné kostry.

---

## 8. AI bytostí

**Utility AI:** každý tick (nebo každých N ticků kvůli výkonu, rozloženě mezi entity) bytost ohodnotí dostupné akce skóre 0..1 podle svých potřeb a okolí a vybere nejvyšší. Akce pak běží jako malý stavový automat, dokud neskončí nebo není přerušena výrazně silnější potřebou.

**Akce (první verze):** `Wander`, `SeekFood`, `Eat`, `SeekWater`, `Drink`, `Sleep`, `SeekMate`, `Flee` (od fáze 7: útěk před bleskem, přebije vše ostatní).

- Hodnocení každých `ai.evaluateEverySeconds` (rozloženo podle ID entity); akce se skóre 0 je nahrazena hned, jinak jen akcí s vyšším skóre o `ai.switchMargin`. Neúspěšná akce má krátký cooldown.
- Hladová / žíznivá bytost, která nic nevidí, bloudí dál (`ai.exploreRadiusFactor`); při žízni preferuje nižší terén. Při kritickém hladu/žízni nespí.
- Parametry potřeb, stravování a AI jsou v `data/species.json`.
Později: `Hunt`, `Gather`, `Deliver`, `Build`, `FollowLeader`.

**Pathfinding:** A* na mřížce dlaždic (8 směrů, bez řezání rohů), cesta se vyhlazuje přímou viditelností. Rozpočet na tick: max 40 hledání a ~20 000 prohledaných dlaždic, zbytek čeká ve frontě. Souvislé oblasti souše se spočítají předem, cesta mezi oblastmi selže bez hledání. Cesty se cachují, přepočítají se až při zablokování (max 3×).

---

## 9. Hráč a božské zásahy

- **Kamera:** RTS styl — WASD / okraje obrazovky posun, kolečko zoom, prostřední tlačítko rotace (+ Q/E pro trackpad bez prostředního tlačítka). Omezení na hranice mapy.
- **Výběr bytosti:** kliknutí (raycast na terén + nejbližší bytost) → panel s potřebami, věkem, akcí.
- **Panel druhu:** populace, EP, statistiky, otevření evolučního stromu.
- **Evoluční strom UI:** grafy uzlů s čarami prerekvizit; stavy odemčeno / dostupné / zamčeno / vyloučeno.
- **Víra** roste s počtem **věřících** bytostí. Zásahy (první verze): *Déšť* (zvýší úrodnost v oblasti), *Hojnost* (spawn jídla), *Zdvihni/sniž terén*, *Blesk* (zabije/vyděsí).
- Morální osa good/evil ve stylu Black & White: ve fázi 7 se jen **počítá** (hodnota dobro ↔ zlo, zobrazená v UI), herní dopady (strach, poslušnost, zásahy navíc pro dobrého / zlého boha) přijdou později.

**Rozhodnutí k fázi 7:**
- **Věřící** = příznak na bytosti (komponenta `Believer`). Bytost uvěří, když využije něco, co způsobil bůh: sní jídlo z božského keře (Hojnost, keře v dešti), napije se deště, nebo přežije blesk v okolí (uvěří ze strachu). Mládě věřícího rodiče je věřící.
- **Příjem Víry** = malý základ (aby hra nezamrzla bez věřících) + pevná částka za každého věřícího za minutu. Hráč začíná s počáteční Vírou na pár zásahů. Hodnoty v `data/powers.json`.
- **Zásahy se provádějí v simulaci:** kliknutí zařadí příkaz do fronty, provede se na začátku dalšího ticku (`GodPowerSystem` hned po `PrevTransform`), náhoda z `Random` světa → deterministické a připravené na save. Při pauze se fronta provede hned mimo tick (terén jde tvarovat i v pauze); déšť a útěk pak běží až po spuštění. Víra se přičítá jednou za herní sekundu (`FaithSystem`, poslední systém).
- **Déšť** (dočasný): po dobu trvání v kruhu rychleji dorůstá jídlo (keře jsou po dobu deště „božské“) a bytostem pod mrakem klesá žízeň.
- **Hojnost:** naplní keře v kruhu a přidá několik nových, které zůstanou. Keř z Hojnosti je božský, dokud ho nesnědí do dna.
- **Terén:** štětec zvedne / sníží rohy výšek v kruhu (měkký okraj); podržení tlačítka opakuje. Smí vznikat voda i pevnina. Po změně: přepočet biomů dotčených dlaždic (voda ↔ souš, z původní teploty a vlhkosti), oblastí pro hledání cest, napajedel na březích, zmizí keře pod vodou, bytosti ve vodě se přesunou na nejbližší průchodnou dlaždici, renderer přestaví dotčené chunky.
- **Blesk:** zabije bytosti v malém poloměru (zůstanou mršiny), okolní bytosti se vyděsí (akce `Flee`: utíkají pryč od místa úderu) a uvěří ze strachu. Posouvá morálku ke zlu.
- **Morálka:** každý zásah má v datech posun dobro/zlo (déšť, hojnost +, blesk −, terén 0); hodnota −1..1 plus počty laskavých a krutých činů.
- **Ovládání:** lišta zásahů dole (cena, tooltip, bez dost Víry zašedlé), vybraný zásah ukazuje kruh na terénu, LMB použije, RMB / ESC zruší.

---

## 10. Výkonnostní cíle

- **1 000 bytostí při 60 FPS** a 1× rychlosti na průměrném notebooku.
- Při 10× rychlosti smí FPS klesnout, simulace ale nesmí zaostávat víc než o sekundu.
- Jeden simulační tick < 20 ms při 1 000 bytostech.
- Debug overlay (F3) ukazuje FPS, čas ticku, počet entit, počet čekajících pathfinding požadavků.

---

## 11. Fáze vývoje

### Fáze 0 — Kostra
Maven projekt, LWJGL + JOML + Gson, okno s OpenGL 3.3 core, fixed-timestep loop, vstup, jednoduchý shader.
**DoD:** `java -jar` otevře okno na Windows i macOS, zobrazí barevný trojúhelník/kostku, F3 ukazuje FPS a ticky za sekundu.

### Fáze 1 — Terén a kamera
Heightmap ze Simplex noise, biomy, chunkový mesh, osvětlení, voda, RTS kamera.
**DoD:** vidím krajinu s rozpoznatelnými biomy a vodou, můžu po ní volně létat. Stejný seed = stejný svět.

### Fáze 2 — ECS a první bytosti
ECS jádro, Transform/Velocity, `Wander`, instancovaný rendering jednoduchého tvaru, interpolace, rychlost hry.
**DoD:** 1 000 bytostí se plynule náhodně pohybuje po terénu (drží se na výšce terénu, nelezou do vody) při cílovém FPS; pauza/1×/3×/10× funguje.

### Fáze 3 — Potřeby a AI
Needs, Health, Age, zdroje jídla a vody s dorůstáním, SpatialGrid, utility AI, A*, smrt hladem/stářím.
**DoD:** bytosti samy hledají jídlo a vodu, když je potřebují; když jídlo dojde, populace začne umírat; debug režim ukazuje aktuální akci nad hlavou vybrané bytosti. Unit testy pro NeedsSystem a A*.

### Fáze 4 — Rozmnožování a populace
Reprodukce, dospívání, genom + mutace, graf populace v čase.
**DoD:** populace se sama ustálí kolem úživnosti prostředí (roste, když je jídla dost, klesá, když ne). Nedochází k explozi ani okamžitému vyhynutí při výchozím nastavení.

### Fáze 5 — Evoluční strom (data + logika)
Načítání JSON, validace, EP, odemykání, přepočet statistik, efekty na AI (např. dieta mění, co je „jídlo“).
**DoD:** přes debug konzoli/klávesu odemknu uzel a chování populace se změní měřitelně (rychlost, co jí, kde přežije). Unit testy: validace stromu, výlučné skupiny, přepočet statistik.

### Fáze 6 — Procedurální vzhled + UI
CreatureMeshBuilder, text rendering, výběr bytosti, panel druhu, UI evolučního stromu.
Rozhodnutí: UI je vlastní immediate-mode (`Ui`), celé v jednom draw callu; rozvržení a kliknutí se zpracují při vstupu, vykreslí se po scéně. Jednotky UI = body obrazovky × měřítko systému (Retina, Windows škálování). Font Droid Sans přes `stb_truetype` (ASCII, Latin-1, Latin Extended-A, pomlčky, uvozovky, …) v jednom atlasu; F3 zůstává ladicí `stb_easy_font`. Rozložení stromu se počítá z dat (`TreeLayout`: řádek = hloubka prerekvizit, sloupec pod rodičem, větve se zalamují podle šířky okna), pozice uzlů nejsou v JSON. Panel bytosti umí kameru „Sledovat“; kamera startuje nad populací.
**DoD:** odemknutí uzlu viditelně změní vzhled bytostí; celý strom jde ovládat myší bez debug kláves.

### Fáze 7 — Božské zásahy
Víra, 4 zásahy z sekce 9, vizuální efekty (jednoduché).
**DoD:** zásahy mají viditelný dopad na svět a populaci, nedostatek Víry je zablokuje.

### Fáze 8 — Save/Load
Serializace světa, entit, stavu druhu a stromu do JSON (s polem `saveVersion`). Autosave.
Savy a nastavení se ukládají pod `GameDirs.root()` (např. `saves/`). Cesty se nesmí počítat ve statických konstantách tříd načtených před `Launch` — kořen dat nastavuje vstupní bod.
**DoD:** uložení a načtení vrátí hru do identického stavu (ověřit testem: save → load → porovnání).

Rozhodnutí:
- **Soubor:** `<GameDirs.root()>/saves/<název>.evsave` = JSON zabalený gzipem (terén má stovky kB), pole `saveVersion` (teď 1). Zápis do dočasného souboru a pak přesun, takže pád při ukládání nepoškodí starý save.
- **Co se ukládá:** vše, co ovlivní další vývoj simulace, aby načtená hra pokračovala *stejně* jako neuložená: tick, stav generátoru náhody (vlastní `SimRandom` se stejným algoritmem jako `java.util.Random`, stav jde přečíst a obnovit), přidělování ID entit (volná ID v pořadí), komponenty v pořadí úložišť (pořadí iterace systémů), fronta pathfindingu, statistiky a graf, terén (výšky, klima, biomy jako base64 pole), druh (body EP + odemčené uzly v pořadí; statistiky se přepočítají z aktuálních dat), Víra, morálka, aktivní déšť. Navíc pohled kamery a rychlost hry.
- **Nezávislost na vnitřním pořadí mřížek:** `SpatialGrid.nearest` při shodné vzdálenosti vybere nižší ID a zásahy zpracovávají entity seřazené podle ID. Mřížky se proto při načtení jen znovu postaví.
- **Kompatibilita:** save z novější verze (vyšší `saveVersion`) se odmítne s jasnou hláškou; neznámý biom / zdroj = chyba; neznámý evoluční uzel (strom se změnil) se přeskočí a hra to oznámí.
- **Ovládání:** F5 rychlé uložení, F9 rychlé načtení; tlačítko *Hra* v horní liště: uložit, seznam savů (načíst / smazat s potvrzením), nový svět, ukončit. Ladicí „nový svět“ z F5 se přesouvá do menu. Spuštění s `--load <název>` načte save.
- **Autosave:** každých 5 minut reálného času (když hra neběží v pauze) a při ukončení, pozice `autosave`. Snímek stavu se vytvoří mezi ticky na hlavním vlákně, JSON + gzip se zapisují na pozadí.
- Start hry zatím dál vytváří nový svět (hlavní menu s „Pokračovat“ může přijít později).

### Fáze 9+ — Mysl a kmen
Větev Mysl: sociální skupiny, sběr a nošení zdrojů, sklad, první stavby, joby.
Detailní návrh se doplní do tohoto dokumentu **před** začátkem fáze.

### Průřezová infrastruktura (mimo fáze)
Distribuce a launcher. Mění se jen se schválením a nesmí rozbít DoD žádné fáze.

- **Jeden fat jar, tři vstupní body:** `Main` (vývoj, data v `./run`), `Launch` (hra pro hráče), `LauncherMain` (launcher).
- **Datová složka (`GameDirs`):** Windows `%APPDATA%\Evolvia`, macOS `~/Library/Application Support/Evolvia`, Linux `$XDG_DATA_HOME/evolvia` (jinak `~/.local/share/evolvia`). Přebíjí `-Devolvia.home` a proměnná `EVOLVIA_HOME` (launcher ji předává hře). Všechny verze sdílí savy; nainstalované verze leží v `versions/<tag>/`, aktuální v `versions/current.txt`.
- **Release:** push tagu `v*` → `.github/workflows/release.yml` (matrix Windows/macOS/Linux, jpackage nemá cross-compile) → GitHub Release se 6 soubory `Evolvia-<os>` a `EvolviaLauncher-<os>` (`.zip`, Linux `.tar.gz`). macOS: ad-hoc `codesign`, `ditto`; hra s `-XstartOnFirstThread`.
- **Moduly JDK:** hra `java.base,java.sql,jdk.unsupported` (ověřit `jdeps --print-module-deps` při nové závislosti), launcher navíc `java.desktop,java.net.http,jdk.crypto.ec` (bez `jdk.crypto.ec` selže TLS s GitHubem). Seznam je v `release.yml` i `package.ps1` a musí být shodný.
- **Launcher:** GitHub API `releases/latest` bez tokenu (repo musí být veřejné, limit 60 req/h), porovnání verzí číselně po částech, rozbalení odmítá zip slip, převádí zpětná lomítka z PowerShell zipů, obnovuje unixová práva a symlinky. Offline spustí poslední nainstalovanou verzi.
- **Lokální test bez CI:** `packaging/package.ps1 -SkipTests -Launch` (lokální release v `dist/`, data v `./run`).
- **Známé limity:** launcher se sám neaktualizuje, chybí checksum stažení, macOS balíček je jen pro Apple Silicon (`macos-latest`), bez notarizace zůstává varování Gatekeeperu, staré verze v `versions/` se nemažou.

---

## 12. Konvence kódu

- Identifikátory, komentáře a commit zprávy **anglicky**; herní texty zatím česky (připravit na lokalizaci přes klíče není nutné v první verzi).
- Balíčky podle sekce 3, jedna odpovědnost na třídu.
- Žádná herní logika v renderingu a žádné OpenGL volání v simulaci.
- Konstanty balancování v JSON, ne v kódu.
- Veřejné metody jádra (ECS, evoluce) mají krátký Javadoc.

---

## 13. Mimo scope první verze

- Sférická planeta
- Více hráčem řízených druhů / soupeřící AI druhy (ale konkurenční divoká zvěř je v pozdějších fázích OK)
- Good/evil morální systém
- Multiplayer
- Ručně modelované assety a kosterní animace
- Zvuk (možná později)

---

## 14. Otevřené otázky (rozhodne Lucas)

- Finální název hry
- Velikost mapy pro první hratelnou verzi (256² vs 512²)
- Zda má být v první verzi divoká zvěř jako predátor/kořist
- ~~Ovládání evolučního stromu~~ → **rozhodnuto: EP utrácí hráč** (automatická evoluce případně později jako volitelný režim).
- Kmeny, války a boj proti jiným kmenům (soupeřící AI druhy jsou dnes v §13 mimo scope) — rozebrat později.
