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
- Cíl první verze: sandbox bez výhry; game over = vyhyne hráčův lid (věřící, od fáze 9c), hrozbu přinášejí soupeřící stáda, divoká zvěř a soupeřící kmeny (9c, 9f, 10+).

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
- Božská ruka: klik na bytost → tlačítka v panelu; Přenést / Zaútoč čekají na klik do krajiny (pravé tlačítko nebo ESC zruší).
- Ladicí klávesy: F3 přehled + graf populace + popisek nad vybranou bytostí, F6 vyprázdnit jídlo, F7 +100 EP, F8 +100 Víry, F10 všichni hned vyvinutí.
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

**Generační evoluce (rozhodnutí Lucase, po fázi 9b):** odemčení uzlu nezmění žijící bytosti, jen druh. Každá bytost má *vývojový stupeň* z narození (`SpeciesRef.stage`, stupeň k = prvních k odemčených uzlů) a celý život má jeho statistiky, schopnosti i vzhled. Mládě dostane stupeň vyspělejšího rodiče + `evolution.traitStepsPerBirth` (výchozí 1, strop = všechny odemčené), takže při odemčení několika uzlů naráz se znaky projevují postupně po generacích a populace se mění plynule, jak staří umírají a rodí se noví. Panel druhu ukazuje hodnoty nejnovější generace a podíl populace s nejnovějšími znaky, strom u odemčeného uzlu podíl populace, která znak nese, panel bytosti její stupeň. Ladicí F10 vyvine všechny hned. Save verze 3 ukládá stupeň každé bytosti (starší savy: všichni nejnovější stupeň).

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
Rozděleno do podfází; každá má vlastní DoD, commit a vydání a začíná se až po schválení předchozí. Detail každé podfáze se doplní sem **před** jejím začátkem (9a je rozepsaná).

| Podfáze | Obsah | DoD |
|---|---|---|
| **9a Skupiny** | uzel *Sociální skupiny*, stáda s vůdcem, `FollowLeader`, sdílená paměť skupiny | po odemčení se populace rozpadne do skupin, které se pohybují spolu (viditelné a měřitelné) |
| **9b Vzpřímená chůze** | větev Tělo: vzpřímení → chůze po dvou → šikovné ruce; humanoidní model (trup nahoře, 2 nohy, 2 ruce, animace rukou) | odemčení postupně mění čtyřnožce v dvounožce; ruce jsou podmínkou nástrojů |
| **9c Tvůj lid a soupeři** | malý start, pomalejší růst, věřící stádo hráče vs. divoká stáda, území a boj, božská ruka, milníky, game over | prvních 10–15 minut má napětí: populace v desítkách, stáda o území bojují, hráč vůdce ovládá rukou, divoká stáda lze získat |
| **9d Den, noc a útočiště** | cyklus dne a noci, útočiště (jeskyně, převisy, háje), posvátné místo | stáda v noci hledají útočiště, požehnané místo je domovem |
| **9e Příroda** | roční období, počasí, nemoci ze zkaženého jídla, přírodní katastrofy | svět se v čase mění a zpomaluje hráče, katastrofy jsou vidět |
| **9f Divoká zvěř** | kořist a predátoři jako druhy bez hráče, akce `Hunt` | predátoři ohrožují stáda, lov dává maso |
| **9g Nástroje a sběr** | stromy a kámen ve světě, uzel *Nástroje*, `Gather` / `Carry` / `Deliver`, sběrné místo skupiny | suroviny se sbírají a hromadí, je vidět, kdo co nese |
| **9h Řeč a kmen** | uzel *Kmen*: z největší skupiny vznikne kmen s tábořištěm, zásoby, role (sběrač, stavitel), stavby sklad / přístřešek / ohniště, `Build` | kmen sám postaví ≥ 3 druhy staveb a ty mají měřitelný efekt |
| **9i Spodní lišta** | (po testu fáze 9) spodní lišta ve stylu The Universim: lid, suroviny, záložky Zásahy / Stavby, Víra; štíhlejší horní lišta; milník vítězství i za predátory a lov | UI se nepřekrývá na 1280×720, vše z horní lišty a lišty zásahů je dostupné, „První vítězství“ jde získat na každé mapě |

Rozhodnutí k fázi 9 (celé):
- **Stavby (9h):** kmen staví sám podle potřeb; hráč navíc může za Víru umístit *božský plán* stavby, který má přednost (kombinace Black & White a Universim).
- **Kmeny (9h):** kmen vznikne z největší skupiny, ostatní skupiny se k němu časem přidají, nebo později založí vlastní kmen (příprava na soupeření). Ve fázi 9 jen jeden kmen hráčova druhu.
- **Morálka (9h):** víra se promítne do kmene. Dobrý bůh = spokojenější kmen, rychlejší rozmnožování. Zlý bůh = strach, rychlejší práce, ale méně mláďat a občas útěk z kmene.
- **Pořadí:** vzpřímená chůze a humanoidní model (9b) předchází nošení surovin a stavbám. Po zpětné vazbě na prázdnou ranou hru přišly před nástroje podfáze 9c–9f (rané hraní, noc, příroda, zvěř).
- **Zpětná vazba po testu fáze 9 (Lucas):** vše funguje, ale zatím je to spíš simulátor začátků. Rozhodnuto: (1) UI staveb, zásahů a surovin do jedné spodní lišty (9i, hned); (2) „První vítězství“ počítá i predátory a lov (9i); (3) rozdělit evoluci a vědu: evoluce (EP) = tělo a mysl, rychlý levný začátek a pak stále dražší stupně; věda = nový strom od kmene, body tvoří sami lidé (fáze 10); (4) přístřešek pojme jen omezený počet bytostí, víc staveb se tak vyplatí (domy ve fázi 14); stavby a jejich UI se budou dál předělávat.
- **Fáze 10+ — Soupeřící kmeny a války:** AI kmeny jiného druhu, území, nájezdy, souboje, vliv dobrého / zlého boha; spolu s vyhynutím tvoří game over. Detail před začátkem fáze.

#### 9a Skupiny (detail)
- **Uzel** `mind_social_groups` „Sociální skupiny“ (větev Mysl, vyžaduje Paměť, podmínka populace ≥ 60, cena ~80 EP), efekt `unlock_ability: groups`.
- **Komponenta** `GroupMember` (ID skupiny); **registr skupin** ve světě (`Groups`: ID, vůdce, počet členů, sdílená paměť vody a jídla).
- **`GroupSystem`** (každých `groups.updateSeconds`, deterministicky podle ID):
  - bez skupiny: přidá se k nejbližší skupině do `joinRadius`, má-li místo (`maxSize`); jinak ≥ `minFounders` volných bytostí v okolí založí novou skupinu, vůdcem je nejstarší dospělý,
  - skupina větší než `maxSize` se rozdělí (nový vůdce = nejstarší z odcházející poloviny),
  - vůdce zemře → vůdcem se stane nejstarší dospělý člen; skupina pod `minSize` se rozpadne,
  - člen, který je dlouho dál než `leaveDistance` od vůdce, ze skupiny odejde,
  - mládě se narodí do skupiny rodiče.
- **Akce `FollowLeader`:** člen dál než `followDistance` od vůdce jde k němu; skóre roste se vzdáleností, ale zůstává pod potřebami (hlad, žízeň, spánek mají přednost) a nad bloumáním. Vůdce se chová normálně (bloudí, hledá jídlo), skupina ho následuje.
- **Sdílená paměť:** kde se člen napil / najedl, zapíše se i do paměti skupiny; `SeekFood` / `SeekWater` bez vlastní vzpomínky použije paměť skupiny (jen s Pamětí).
- **Soudržnost (upřesněno při implementaci):** člen hledá jídlo a vodu jen v okruhu `forageRadius` kolem vůdce a z paměti používá jen paměť stáda, dokud potřeba nedosáhne `urgentNeed` (pak hledá sám a daleko). Bloumá kolem vůdce. Partnera hledá jen ve svém stádu. Vůdce se chová jako dřív, takže stádo vede k jídlu a vodě on.
- **Data:** blok `groups` v `species.json` (updateSeconds, joinRadius, minFounders, minSize, maxSize, followDistance, leaveDistance, leaveSeconds, followScore, forageRadius, urgentNeed).
- **UI:** klávesa G / tlačítko *Stáda* zapne zobrazení skupin (barevný kroužek pod členy, značka nad vůdcem); panel bytosti ukazuje skupinu a roli; panel druhu počet a průměrnou velikost skupin.
- **Save:** `saveVersion` 2 (skupiny a členství); save verze 1 se načte bez skupin (vytvoří se znovu).
- **Testy:** skupiny vzniknou jen po odemčení, velikosti v mezích, členové jsou u vůdce výrazně blíž než bez skupin, výměna vůdce po smrti, dělení velké skupiny, mládě ve skupině rodiče, save → load → identický běh i se skupinami.

#### 9c Tvůj lid a soupeři (detail)
- **Start a tempo:** hráčovo stádo ~12 bytostí + `population.wildHerds` (3) divokých stád po `wildHerdSize` (~10), vzdálených aspoň `herdSpacing`, u jídla a vody. Pomalejší růst (delší dospívání a pauza mezi mláďaty, méně jídla); cíl 40–80 bytostí po 15 minutách, ladí se měřením.
- **Stáda od začátku:** stádní život nevyžaduje uzel. *Sociální skupiny* dávají bonusy: sdílená paměť vody a jídla, větší stáda (×1.5), členové brání napadeného druha. Podmínky uzlů na velikost populace se snižují podle menšího startu.
- **Tvůj lid = věřící.** Stádo má vlastníka (hráč / divoké). Členové hráčova stáda jsou věřící (`Believer`), jen oni dávají Víru. Divoké stádo přejde k hráči, když v něm věří aspoň polovina (zázraky, požehnání), nebo když se jeho bytosti v boji vzdají hráčovu stádu.
- **Území:** stádo má domov (sleduje vůdce, nebo ho hráč pevně nastaví „Usaď se“, pak vůdce bloumá kolem domova) a území `combat.territoryRadius` kolem něj.
- **Boj (`Attack`):** sám, když cizí stádo vstoupí na území a stádo má hlad nad `combat.aggroNeed` (hráčova stáda spolu nebojují); nebo na rozkaz. Útočník dojde k cíli a ubírá zdraví (`combat.damagePerSecond`); napadený se brání, pod `fleeHealth` utíká, pod `surrenderHealth` se vzdá a přidá se ke stádu vítěze. Nová příčina smrti „v boji“.
- **Božská ruka** (panel vybrané bytosti, ceny ve Víře v `powers.json`): vlastní vůdce: *Přenést* (klik do krajiny), *Zaútoč* (klik na bytost cizího stáda; krutý čin), *Usaď se tady*, *Uzdrav*, *Požehnej*; ostatní vlastní: *Přenést*, *Uzdrav*, *Požehnej*; divoké: *Požehnej* (uvěří). Uzdrav = plné zdraví (laskavý čin). Požehnej vlastní = může mít mládě hned a uleví se jí od hladu a žízně (laskavý čin). Příkazy ruky se jako zásahy provedou na začátku dalšího ticku.
- **Milníky** (`data/milestones.json`: typ, hodnota, odměna EP / Víra): 20 bytostí lidu, první odemčený uzel, první vyhraný střet, druhé stádo, 40 bytostí lidu. Panel „Cíle“ vlevo, oznámení a odměna při splnění.
- **Game over:** když vyhyne lid (žádný věřící), hra se zastaví a ukáže „Tvůj lid zanikl“ s volbami Načíst / Nový svět.
- **UI:** horní lišta ukazuje lid a divoké zvlášť; zobrazení stád odliší vlastní a divoká stáda (praporek) a ukáže území vybraného stáda.
- **Save verze 4:** vlastník, domov a rozkazy stád, stav boje, splněné milníky, fronta příkazů ruky.
- **Testy:** start (1 + 3 stáda), tempo růstu, boj a vzdání se, rozkaz útoku, přenesení vůdce a následování stáda, usazení, uzdravení a požehnání, převzetí divokého stáda, milníky s odměnou, game over, save → load → identický běh.
- **Upřesněno při implementaci (měřeno na 4 seedech):** divoká stáda brání území vždy, hráčova jen při hladu nebo na rozkaz; divoká stáda vznikají 1–1,6× `herdSpacing` (38) od hráče na stejné pevnině, takže první střety přijdou v prvních minutách. Méně jídla (hustota 0.018, dorůstání 0.02) a delší dospívání / pauza mezi mláďaty (300 / 240 s): po 10 minutách ~50–100 bytostí celkem. Víra za věřícího 0.5 / min. Podmínky uzlů: Masožravec ≥ 40, Sociální skupiny ≥ 25 lidu. EP za populaci se počítají z lidu. Kamera startuje nad lidem, zobrazení stád je zapnuté od začátku.

#### 9d Den, noc a útočiště (detail)
- **Čas:** `world.json` → `time` (délka dne `dayLengthSeconds` 480, začátek `startTimeOfDay`, noční ochlazení `nightCooling`). Čas dne se počítá z ticku (nic se neukládá navíc); noc je od 0.78 do 0.22 dne. Horní lišta ukazuje den a denní dobu.
- **Světlo:** slunce obíhá, v noci je krajina modrá a tmavá, obloha a mlha tmavnou, ráno a večer do oranžova (uniform `uLightTint` ve všech světových shaderech).
- **Útočiště** (`data/refuges.json`): jeskyně (vysoko), hustý háj (les), skalní převis (tráva, poušť, tundra); rozmístí se při generování s rozestupem. Mají poloměr; kdo v něm spí, je *v úkrytu*: neochlazuje ho noc, rychleji odpočívá a léčí se.
- **Noc:** večer si stádo vybere útočiště (nejbližší k vůdci v `searchRadius`, lid preferuje posvátné). Útočiště, které už zabralo jiné stádo, bere jen, když jiné není (společný nocleh znamená ráno boj); divoká stáda se posvátným místům vyhýbají. Útočiště mají vlastní generátor ze semínka, takže nemění zbytek světa a staré savy dostanou stejná. Nová akce `SeekShelter` (jdi do útočiště) a spánek: v noci se spí déle (do rána), v úkrytu nebo tam, kde bytost je, když útočiště nezná.
- **Posvátné místo:** nový zásah *Posvátné místo* (klik na útočiště, Víra): útočiště se stane posvátným, nejbližší stádo lidu se tam usadí (domov). Věřící, kteří tam spí, dávají Víru navíc. Laskavý čin.
- **Milníky:** Útočiště (5 bytostí lidu spí v úkrytu), Přečkej noc (1. noc), Posvátné místo.
- **Save verze 5:** útočiště (s posvátností) a útočiště stád.
- **Testy:** cyklus dne, noční ochlazení a úkryt, stáda v noci v útočišti (většina lidu o půlnoci), spánek do rána, posvátné místo (domov + Víra), milníky, save → load.

#### 9e Příroda (detail)
- **Data:** `data/nature.json` (období, počasí, nemoc, katastrofy); vše deterministické ze simulačního generátoru, stav se ukládá.
- **Roční období:** každé trvá `seasonDays` (2) dny, rok = 8 dní, hra začíná na jaře. Období mění dorůstání jídla (jaro ×1.3, léto ×1, podzim ×0.6, zima ×0.15), teplotu (léto +, zima −) a žízeň (léto ×1.25). V zimě je krajina zasněžená (bílá místa podle `uSnow` v terrain shaderu). Horní lišta: období a počasí.
- **Počasí:** celý svět má jedno počasí, mění se po 90–180 s podle vah daného období: *jasno*, *déšť* (dorůstání ×1.5, uleví od žízně, ochladí, tlumí oheň), *bouřka* (déšť + přírodní blesky každých ~20 s: zabíjejí a v trávě a lese zapalují požár), *sněžení* (jen zima, chladno). Vidět jsou kapky / vločky kolem kamery a tmavší světlo.
- **Nemoci ze zkaženého jídla:** mršina se po `spoilSeconds` zkazí (zezelená). Kdo ji jí, s šancí onemocní (`Sick`): ubírá zdraví, víc unavuje, bytost je nazelenalá. Nemoc se šíří na blízké členy stáda; po `durationSeconds` přejde a bytost je čas imunní. Nová příčina smrti *nemoc*. Božská ruka *Uzdrav* nemoc vyléčí.
- **Přírodní katastrofy** (ne v prvních `graceDays` = 2 dnech; jednou za den s šancí podle období; oznámí se zprávou):
  - *Požár* (hlavně léto a podzim, nebo úder blesku za bouřky): hoří dlaždice trávy / lesa / savany, oheň se šíří na sousední hořlavé dlaždice, spálí keře, zraňuje bytosti v plamenech (smrt *oheň*), bytosti v okolí utíkají. Déšť ho tlumí, zásah *Déšť* ho v oblasti uhasí. Spálená zem je chvíli tmavá a keře na ní začínají od nuly. Počet hořících dlaždic má strop.
  - *Záplava* (jaro, bouřky): hladina moře stoupne o `rise`, chvíli drží a opadne. Bytosti na zatopených dlaždicích utíkají výš a topí se (smrt *utonutí*), keře pod vodou ztratí plody. Hledání cest zatopení nebere v úvahu (známý limit).
  - *Vánice* (zima): 1–2 minuty silný mráz a husté sněžení; bytosti mimo útočiště mrznou.
- **Save verze 6:** počasí, katastrofy (hořící a spálené dlaždice, záplava, vánice), nemoci, stáří mršin.
- **Upřesněno při implementaci:** oheň se šíří těsně pod hranicí, kdy by prošel celým lesem (šance za sekundu les 0.05, tráva 0.03, bažina 0.01, hoření 15 s, spálená zem 120 s, nejvýš 300 dlaždic), jinak na malé mapě spálil vše a lid vyhladověl. Za deště hoří 3× kratší dobu a šíří se 5× méně. Požár katastrofy začíná 3×3 dlaždic 15–35 polí od lidu. Nemoc: šíření 0.05 / s v okruhu 3. Spálená zem je tmavá přes texturu v terrain shaderu. Den, období a počasí ukazuje panel vpravo pod lištou. Ladicí klávesy: F11 další počasí, F12 další katastrofa (požár, záplava, vánice).
- **Testy:** koloběh období a jejich efekty, počasí ze semínka a jeho střídání, déšť zmírní žízeň, zkažená mršina → nemoc → šíření ve stádu → uzdravení rukou, požár se šíří jen po hořlavé zemi, pálí keře a zraňuje, zásah Déšť ho uhasí, záplava zatopí nízké pobřeží a opadne, vánice ochladí, v ochranné době žádné katastrofy, save → load → identický běh s hořícím požárem a nemocí.

#### 9f Divoká zvěř (detail)
- **Data** `data/animals.json`: druh zvěře = základní `species.json` s přepsanými hodnotami, role (kořist / predátor), vzhled, počet a velikost stád, biomy, koho loví, noční; společný blok `hunting` (práh hladu, délka honu, skóre, noční bonus, dosah plašení).
  - *Jelen* (kořist): rychlý býložravec, stáda 5–8 v lese a trávě, dožije se méně než lid.
  - *Vlk* (predátor): masožravec, smečky 3–4, loví jeleny i tvůj lid, přes den spí a loví hlavně v noci.
- **Zvěř je druh bez evolučního stromu a bez víry:** nevěří (déšť, blesk ani požehnání to nezmění), nedává Víru ani EP, nepočítá se do lidu ani divokých; horní lišta ji ukazuje zvlášť. Rozmnožuje se s vlastním stropem `population.max`.
- **Stáda podle druhu:** stádo má druh; bytosti se přidávají jen ke stádu svého druhu a pravidla stáda (velikost, vzdálenosti) jsou z jeho definice. O území se perou jen stáda stejného druhu.
- **Lov (`Hunt`):** masožravec s hladem nad prahem si vybere nejbližší kořist v dohledu (predátor druhy ze svého seznamu; tvůj lid s Masožravcem zvěř s rolí kořist), žene se za ní nejvýš `chaseSeconds` a útočí jako v boji. Zabitá kořist zůstane jako mršina a lovec ji sní. Predátor loví v noci ochotněji. Kdo spí v útočišti, toho predátor neloví (útočiště chrání).
- **Útěk a obrana:** kořist, za kterou se lovec žene, i její stádo v okolí utíkají; rychlá kořist často unikne. Tvůj lid se vlkům brání (bojuje s útočníkem a pomáhá druhům ve stádě). Kořist sama neútočí a nevzdává se; mezi druhy se nikdo nepřidává k vítězi.
- **Božská ruka:** *Zaútoč* funguje i na zvěř (hon na rozkaz).
- **Upřesněno při implementaci (měřeno na 4 seedech, 30 min):** lovec na krátkou vzdálenost (14 polí) běží přímo za kořistí bez hledání cesty (jinak ho každé přeplánování zastavilo); prvních 15 s honu sprintuje (×1.3), kousne z o 0,5 pole větší vzdálenosti než bojovník (těla se nepřekrývají) a kousnutí dělá 3× víc než úder v boji, takže přepad zblízka vyjde a dlouhá honička ne. Vlk dává přednost kořisti v pořadí seznamu (jelen před lidem). Zvěř se ze zkažených mršin nenakazí (mrchožrouti). Vlci: život 25–33 min, vrh 2; zvěř snese širší rozsah teplot. Výsledek: jelenů 33–67, vlků 13–17, lid 13–41 (vlci ho znatelně tlačí). Horní lišta ukazuje „Zvěř“, panel zvířete roli a smečku / stádo, u zvěře chybí Požehnej.
- **Save verze 7:** druh každé bytosti a každého stáda; starší save dostane zvěř rozmístěnou ze semínka.
- **Testy:** zvěř rozmístěná podle dat, nevěří a nedává Víru, stáda jen jednoho druhu, vlk uloví kořist a sní mršinu, kořist utíká, vlci loví hlavně v noci, útočiště chrání spící, lid s Masožravcem loví, rozkaz útoku na zvěř, save → load identický.

#### 9g Nástroje a sběr (detail)
- **Suroviny ve světě** (`resources.json`, druh `material`): *strom* (dřevo; hustě v lese, řídce v trávě a bažině; pomalu dorůstá) a *kámen* (tundra, poušť, řídce jinde; nedorůstá). Hustota je po biomech (`biomeDensity`). Rozmístí je vlastní generátor ze semínka, takže nemění zbytek světa. Požár spálí stromy na hořících dlaždicích.
- **Uzel Nástroje** `mind_tools` (větev Mysl, vyžaduje Šikovné ruce, ~70 EP): schopnost `tools`. Jen dospělí se schopností sbírají.
- **Tábor stáda:** stádo lidu (i divoké stádo tvého druhu) má tábor s zásobami (dřevo, kámen). Tábor vznikne při první donášce v místě domova a stádo se tam usadí; *Usaď se* ho přesune. Zásoby mají strop (`gathering.stockCap`), pak se daná surovina nesbírá.
- **Akce `Gather`:** nasycený dospělý se schopností a táborem jde k nejbližší surovině (té, které je v táboře méně) v okruhu tábora, chvíli pracuje a vezme jednu jednotku (komponenta `Carrying`). **`Deliver`:** kdo nese, jde do tábora a složí náklad do zásob. Potřeby mají přednost; sběr je nad bloumáním.
- **Vidět:** stromy (kmen + koruna, vykácený = pařez) a kameny v krajině; nesená surovina na zádech bytosti; hromady dřeva a kamení v táboře podle zásob; panel bytosti ukazuje, co nese, a zásoby tábora.
- **Milník** Zásoby (20 jednotek v táboře).
- **Upřesněno při implementaci:** pravidla sběru jsou v `data/tribe.json` (strop 40 každé suroviny, práce 3 s, okruh 30 polí od tábora, skóre 0.22, donáška 0.5, sbírá jen kdo má hlad i žízeň pod 0.5). Nástroje leží ve větvi Mysl, požadavek Šikovné ruce z Těla ukazuje tooltip. Požár pálí stromy, kameny ne; *Usaď se* a Posvátné místo přesunou i tábor. Po odemčení na seedu 11 byl milník Zásoby splněný během první noci.
- **Save verze 8:** nesené suroviny, tábory a zásoby; starší save dostane suroviny rozmístěné ze semínka.
- **Testy:** suroviny podle biomů, bez Nástrojů se nesbírá, s nimi zásoby rostou a je vidět nošení, první donáška založí tábor, strop zásob, požár spálí stromy, save → load identický.

#### 9h Řeč a kmen (detail)
- **Uzly** (větev Mysl): *Řeč* `mind_speech` (vyžaduje Sociální skupiny, ~60 EP): schopnost `speech`, stádo si dává poplach: když jeden člen utíká před lovcem, utíká celé stádo v okolí. *Kmen* `mind_tribe` (vyžaduje Řeč a Nástroje, podmínka lid ≥ 25, ~100 EP): schopnost `tribe`.
- **Kmen:** po odemčení se největší stádo tvého lidu stane kmenem (jeden kmen v celé fázi 9). Kmen nemá strop velikosti stáda; jiná stáda tvého lidu, jejichž vůdce přijde do okruhu `tribe.joinRadius` tábora, se ke kmeni přidají. Kmen má tábor (jako v 9g), zásoby a stavby. Horní lišta ukazuje kmen a jeho náladu.
- **Role:** každých pár sekund kmen rozdělí dospělé: *stavitelé* (když je rozestavěná stavba, `builderShare` dospělých, nejvýš `maxBuilders`), ostatní *sběrači*. Stavitel jde ke stavbě a staví (`Build`), sběrač sbírá (9g). Role je v panelu bytosti.
- **Stavby** (`data/buildings.json`: cena v surovinách, doba stavby, efekt, cena plánu ve Víře):
  - *Ohniště*: bytosti v okruhu nemrznou (noc, zima, vánice) a necítí chlad.
  - *Přístřešek*: nové útočiště u tábora (spánek v něm jako v jeskyni, predátoři tam spící neloví).
  - *Sklad*: strop zásob tábora ×2,5.
  - *Svatyně*: věřící kmene dávají o `faithBonus` víc Víry.
  - Kmen si sám vybírá stavbu podle potřeby (bez ohniště nejdřív ohniště, pak přístřešek na každých ~10 lidí, sklad při plných zásobách, svatyně), stavbu založí na volném místě kolem tábora, když má suroviny (odečtou se hned).
- **Božský plán:** tlačítka staveb nad lištou zásahů (jen když kmen existuje); hráč za Víru umístí plán do okruhu tábora, kmen ho staví přednostně (suroviny platí kmen, čeká na ně).
- **Morálka** (sklon víry): dobrý bůh (> 0.3) = spokojený kmen, pauza mezi mláďaty ×0.8; zlý bůh (< −0.3) = strach: práce (sběr, stavba) ×1.3 rychleji, pauza mezi mláďaty ×1.3 a občas někdo z kmene uteče (opustí kmen i víru). Mezi tím neutrální.
- **Milníky:** Kmen (založen), Stavitelé (3 různé druhy staveb).
- **Upřesněno při implementaci:** pravidla kmene a stavby jsou v `data/tribe.json`. Pořadí vlastních staveb: ohniště, první přístřešek, sklad (až jsou zásoby skoro plné), svatyně, pak další přístřešky (1 na 10 lidí); jinak kmen stavěl jen přístřešky. Přístřešek je útočiště typu `hut` v `refuges.json`. Uprchlík z kmene (zlý bůh) ztratí víru a na 20 s utíká od tábora. Plán musí být na volné zemi do 2× `siteRadius` od tábora, jinak se nic nestane a Víra se nevrací ani nebere. Údaj o kmeni a jeho náladě je v panelu hodin. Opraveno: posunutý evoluční strom přetékal přes horní lištu; savy starší než verze 4 se načtou s celým hráčovým druhem jako lidem (dřív hlásily hned „Tvůj lid zanikl“).
- **Save verze 9:** kmen, role, stavby (rozestavěné i hotové, plány), fronta plánů.
- **Testy:** kmen vznikne z největšího stáda jen s uzlem, stáda se přidávají, role, kmen sám postaví ≥ 3 druhy staveb, efekt každé stavby (ohniště: chlad, přístřešek: útočiště, sklad: strop, svatyně: Víra), plán má přednost a stojí Víru, poplach s Řečí, morálka (dobrý: rychlejší mláďata, zlý: rychlejší práce a útěky), save → load identický.

#### 9i Spodní lišta (detail)
Mezifáze po testu celé fáze 9, před fází 10. Jen UI a milník, simulace se nemění (save verze zůstává 9).
- **Spodní lišta** (přes celou šířku, dole, ~64 px, blokuje myš), zleva doprava:
  - *Lid*: počet tvého lidu, kmen a jeho nálada (barevně), divocí lidé a zvěř (dva řádky).
  - *Suroviny*: zásoby tábora tvého lidu (kmene, jinak stáda s největším táborem) jako „Dřevo 20 / 40“; bez tábora šedé „Tábor zatím není“. Tooltip: kde tábor je a strop zásob.
  - *Záložky*: svislé přepínače **Zásahy** a **Stavby**. Stavby jsou šedé, dokud není kmen (tooltip „Stavby odemkne kmen“); když kmen vznikne, záložka krátce svítí.
  - *Obsah záložky*: karty (název, cena, barevný proužek). Zásahy = dnešní božské zásahy, Stavby = božské plány staveb (cena ve Víře, tooltip s cenou v surovinách pro kmen). Šířka karet se přizpůsobí oknu (min. ~72 px).
  - *Víra*: body, přírůstek za minutu, ukazatel dobrý / zlý bůh (tooltip s morálkou jako dnes).
- **Horní lišta** se zúží: Hra, druh, Gen., EP (+/min), Druh, Evoluce, Stáda, čas (den, část dne, období, počasí; nebezpečí červeně), rychlost. Panel hodin vpravo nahoře zmizí (překrýval ho panel bytosti), údaje o kmeni jsou dole.
- **Nápovědy a tooltipy** (vybraný zásah, plán, rozkaz ruky, hlášky „nedostatek Víry“) se kreslí nad spodní lištu.
- **Klávesy:** Tab přepíná záložky Zásahy / Stavby (číslice zůstávají rychlosti hry).
- **Milník „První vítězství“:** počítá se i zabití predátora (vlk) a úlovek zvěře tvým lidem, nejen porážka divokého stáda lidí. Popis: „Tvůj lid vyhraje boj: nad divokým stádem, predátorem nebo na lovu.“ Počítadlo v panelu Druh „vítězství“ zahrnuje obojí.
- **Upřesněno při implementaci:** `PowerBar` se stal `BottomBar` (zásahy i plány v něm, logika beze změny). Karty mají pevné sekce vlevo (lid 150 px, suroviny 124 px, záložky 84 px) a vpravo (Víra 158 px), karty mezi nimi jsou 64–120 px široké a vystředěné; dlouhý název se zmenší nebo zkrátí „…“. Zásoby ukazuje `World.playerCamp()` a strop `World.stockCap()`. Čas v horní liště vynechá období, když se nevejde. Záložka Stavby svítí 10 s, jen když kmen vznikne během hry (ne po načtení). Opraveno: klik, kterým se použil zásah nebo umístil plán, zároveň nevybere bytost.
- **Testy:** vítězství za zabitého vlka a uloveného jelena, ne za bytost zabitou divokým stádem; rozvržení lišty na 1280×720 a 1024×600 se nepřekrývá (layout spočítaný bez GL); screenshoty: start (bez kmene, Stavby šedé), kmen se zásobami, vybraný plán, panel bytosti vpravo.

#### 9b Vzpřímená chůze (detail)
- **Uzly** (větev Tělo, řetěz):
  - `body_upright` „Vzpřímený postoj“ (vyžaduje Silné nohy, ~60 EP): dohled ×1.15 (vidí přes trávu), rychlost ×0.9 (zatím nemotorný), vzhled `posture: semi`.
  - `body_bipedal` „Chůze po dvou“ (vyžaduje Vzpřímený postoj, ~80 EP): únava ×0.85 (úsporná chůze), rychlost ×1.1, vzhled `posture: upright`.
  - `body_hands` „Šikovné ruce“ (vyžaduje Chůzi po dvou, ~70 EP): schopnost `hands` (podmínka nástrojů v 9c), vzhled `hands: nimble` (prsty a palec).
  - `body_human_face` „Lidská tvář“ (vyžaduje Šikovné ruce, ~60 EP): čumák zmizí, plochý obličej s nosem, menší uši; první krok ke ztrátě zvířecích rysů (další přijdou s dalšími fázemi).
- **Srst nebo kůže (volitelné, větev Adaptace):** `adapt_hairless` „Holá kůže“ (vyžaduje Chůzi po dvou, ~40 EP): bez srsti, lépe snáší horko, hůř chlad. Vylučuje se se Srstí (skupina `coat`), takže vedle „lidí“ mohou vzniknout i „zvířecí lidé“ se srstí (hodí se i pro jiné kolonie později). Jen volba, žádná povinnost.
- **Směr:** po Šikovných rukách budou další uzly postupně ubírat zvířecí rysy, až z tvorů budou lidé; tempo a podoba se doplní u dalších podfází.
- **UI stromu (upřesněno při implementaci):** strom vyšší než obrazovka se posouvá kolečkem myši; požadavek z jiné větve (Holá kůže ← Chůze po dvou) se nekreslí čarou ani neposouvá uzel níž, jmenuje ho tooltip.
- **Model podle postoje** (`posture`: quadruped / semi / upright), `CreatureMeshBuilder` staví kostru podle postoje a ostatní díly (srst, kůže, zuby, oči, uši, chodidla, břicho, velká hlava, dlouhé / silné nohy) se k ní připojují:
  - *semi*: trup nakloněný ~35°, zadní nohy, dlouhé přední končetiny opřené o zem (chůze po kloubech), kratší ocas,
  - *upright*: svislý trup, 2 nohy, 2 ruce volně podél těla, hlava nahoře, bez ocasu, kratší čumák (víc obličej než tlama).
- **Animace:** nohy se kývají jako dnes; ruce se kývají proti noze na stejné straně (pivot v rameni). Vzpřímená postava je vyšší, popisky a značka výběru se řídí výškou modelu.
- **Testy:** každý postoj se postaví a stojí na zemi, vzpřímený má 2 nohy + 2 ruce v pohybu, je vyšší než čtyřnožec, každý uzel řetězu viditelně změní model, `hands` odemkne až poslední uzel.

### Fáze 10–20 — plán do verze 1.0
Každá fáze se před začátkem rozepíše do detailu (podfáze, data, testy) a začne se až po schválení. Po testu fáze 9 přibyla fáze 10 *Evoluce a věda*; dřívější fáze *Lidé* se rozpustila do fáze 10 (vzhled, oheň, oděv, vaření jako věda) a 14 (domy a rodiny).

| Fáze | Obsah | DoD |
|---|---|---|
| **10 Evoluce a věda** | rozdělení postupu: *evoluce* (EP) = tělo a mysl, levný rychlý začátek, pak stupňované vlastnosti (Síla, Výdrž, Rychlost, Smysly, Odolnost I–V) se stále vyšší cenou, poslední kroky ke vzhledu člověka; *věda* = nový strom od kmene, body tvoří lidé (pozorování, práce, později učenec/šaman), odemyká nástroje, stavby, oheň, oděv, vaření; přesun Nástrojů z evoluce do vědy; záložka Věda ve spodní liště | prvních ~15 minut přinese rychlý sled evolučních uzlů, pak evoluce zpomalí a tempo převezme věda; každý vědecký objev má viditelný efekt |
| **11 Soupeřící kmeny** | AI kmen jiného druhu („zvířecí lidé“, druh s vlastní automatickou evolucí a vzhledem), vlastní tábor, stavby a území; nájezdy na zásoby a stáda; zajatci a obrácení na víru; zničení kmene; game over = zánik lidu nebo kmene | soupeřící kmen sám roste, staví a útočí; hráč ho může odrazit, obrátit na svou víru nebo zničit |
| **12 Válka a obrana** | věda *Zbraně* (kopí, kamenné sekery), role válečník a hlídka, palisáda a strážní věž, morálka v boji (útěk, vzdání), božské zásahy do bitvy (požehnání bojovníků, blesk, strach) | bitva dvou kmenů má viditelný průběh a výsledek; obrana z opevněného tábora je měřitelně silnější |
| **13 Víra a kult** | šaman / kněz, modlitby lidu (kmen žádá déšť, jídlo, ochranu; splnění zvyšuje víru, nesplnění ji snižuje), rituály a oběti (zlá cesta), zlé zásahy (mor, zemětřesení, strach) a dobré (uzdravení kmene, úroda); větší rozdíl dobrého a zlého boha | kmen se modlí o konkrétní věci a reaguje na odpověď; dobrá a zlá cesta mají každá vlastní zásahy a důsledky |
| **14 Zemědělství a vesnice** | pole a sklizeň podle ročních období, domestikace zvěře (ohrada), sýpka a příprava na zimu; rodiny a domy s omezenou kapacitou (místo jednoho přístřešku pro všechny), kmen se mění ve vesnici; nové UI staveb (kategorie ve spodní liště) | vesnice díky zásobám přežije zimu a roste i bez divoké potravy; počet domů měřitelně omezuje růst |
| **15 Řemesla a věky** | nové suroviny (hlína, ruda), dílny, věda Keramika, Tkaní, Kovářství; věky (kámen → bronz) mění vzhled staveb, nástrojů a zbraní | kmen projde aspoň dvěma věky s viditelnou a měřitelnou změnou |
| **16 Osady a diplomacie** | více osad hráčova lidu (kolonie, pěšiny mezi nimi), obchod mezi osadami a s AI kmeny, spojenectví a nepřátelství, vliv boha na vztahy | hráčův lid má 2+ osady, obchoduje a udrží mír nebo vede válku s AI kmenem |
| **17 Velký svět a výkon** | mapa 512², zjednodušená simulace vzdálených oblastí, LOD a ořez vykreslování, minimapa, nastavení nového světa (velikost, ostrovy / kontinent, množství zvěře) | 512² svět s 3 000+ bytostmi běží na cílovém FPS; minimapa a nastavení světa fungují |
| **18 Menu, nastavení a první kroky** | hlavní menu (Pokračovat, Nová hra, Načíst, Nastavení), nastavení grafiky a ovládání, úvodní průvodce přes milníky, encyklopedie druhů, staveb a zásahů | nový hráč se bez návodu dostane k prvnímu kmeni |
| **19 Zvuk a atmosféra** | zvuky prostředí (les, déšť, bouřka, noc), hudba podle denní doby a nebezpečí, zvuky zásahů, staveb a boje (OpenAL z LWJGL), hlasitost v nastavení | hra má zvuk, jde ztlumit po kategoriích a nesnižuje výkon |
| **20 Vyvážení a verze 1.0** | automatické dlouhé simulace pro vyvážení, volitelné cíle (např. „Jediný kmen“, „Bůh všech“), statistiky hry, opravy, anglická lokalizace, vydání 1.0 přes launcher | dvouhodinová hra bez zaseknutí a propadů výkonu; vydání v1.0 |

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
- Více hráčem řízených druhů (soupeřící AI kmeny a divoká zvěř naopak do první verze patří, fáze 9f a 10+)
- Multiplayer
- Ručně modelované assety a kosterní animace
- Zvuk až ve fázi 19

---

## 14. Otevřené otázky (rozhodne Lucas)

- Finální název hry
- Velikost mapy pro první hratelnou verzi (256² vs 512²)
- ~~Zda má být v první verzi divoká zvěř jako predátor/kořist~~ → **rozhodnuto: ano, podfáze 9f** (před nástroji a soupeřícími kmeny).
- ~~Ovládání evolučního stromu~~ → **rozhodnuto: EP utrácí hráč** (automatická evoluce případně později jako volitelný režim).
- ~~Kmeny, války a boj proti jiným kmenům~~ → **rozhodnuto: patří do první verze jako Fáze 10+** (bez soupeřů by nebyl pořádný game over). Detail před začátkem fáze.
