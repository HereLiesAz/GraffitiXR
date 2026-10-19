# Adatvédelmi Irányelvek a GraffitiXR-hez

**Utolsó frissítés:** 2026-10-19

A HereLiesAZ a GraffitiXR alkalmazást forráskód-elérhető alkalmazásként készítette (lásd a tároló LICENSE és docs/LICENSING.md fájljait a feltételekért). Ezt a SZOLGÁLTATÁST a HereLiesAZ ingyenesen nyújtja, és "ahogy van" (as is) használatra szánták.

Ez az oldal arra szolgál, hogy tájékoztassa a látogatókat a Személyes Adatok gyűjtésével, használatával és közzétételével kapcsolatos irányelveinkről, ha valaki úgy dönt, hogy használja a Szolgáltatásunkat.

Ha úgy döntesz, hogy használod a Szolgáltatásunkat, akkor beleegyezel az információk gyűjtésébe és használatába ezen irányelvekkel kapcsolatban. Az általunk gyűjtött Személyes Adatokat a Szolgáltatás nyújtására és javítására használjuk. Adataidat senkivel nem használjuk fel és nem osztjuk meg, kivéve az ebben az Adatvédelmi Irányelvben leírtak szerint.

## Információgyűjtés és Használat

A jobb élmény érdekében a Szolgáltatásunk használata során megkövetelhetjük, hogy adj meg nekünk bizonyos személyazonosításra alkalmas információkat, beleértve, de nem kizárólagosan:

*   **Kamera Adatok:** Az alkalmazásnak hozzá kell férnie az eszközöd kamerájához a Kiterjesztett Valóság (AR) nézet, a Rétegzés mód megjelenítéséhez, és a projekt célpontjaihoz szükséges képek rögzítéséhez. A kamera adatai helyileg, az eszközödön kerülnek feldolgozásra, és nem továbbítódnak a szervereinkre, hacsak nem döntesz kifejezetten egy projektfájl megosztása mellett.
*   **Tárhely / Fotók:** Az alkalmazás semmilyen tárhely-engedélyt nem kér. A hozzáadott képek az Android rendszer fotó- vagy fájlválasztóján keresztül érkeznek, amelyek csak az általad kiválasztott fájlhoz adnak hozzáférést. A projektek az alkalmazás privát tárhelyére, az exportált képek pedig az eszközöd fotógalériájába kerülnek.
*   **Helyadatok (Opcionális):** Az alkalmazás csak akkor kér helymeghatározási engedélyt, amikor elkezdesz egy AR-célpontot rögzíteni. Ha megadod, a célpont rögzítési adatai eltárolják, hol készült (szélesség, hosszúság, magasság és pontosság). Ez helyileg, a projektfájlban kerül mentésre, és csak akkor hagyja el az eszközödet, ha te magad osztod meg vagy exportálod a projektfájlt. A Link (munkamenet megosztása egy második eszközzel) soha nem küldi el: a másik eszköz minden helyadat nélkül kapja meg a projektet.

## Hibajelentések (Opt-In)

Alapértelmezetten semmi nem hagyja el az eszközödet egy összeomlás miatt. Ha az alkalmazás összeomlik, vagy kilábal egy belső hibából, egy jelentés íródik egy helyi, ideiglenes fájlba az eszközödön, hogy az alkalmazás meg tudjon mutatni egy "az előző futás összeomlott" értesítést a következő megnyitáskor — ez a fájl önmagától soha nem hagyja el az eszközt.

A jelentés hozzánk küldése alapértelmezetten ki van kapcsolva, hacsak te magad be nem kapcsolod a **Beállítások > Hibajelentések** menüben. Ha — és csak ha — bekapcsoltad, az alkalmazás a következő induláskor feltölti a jelentést, mint **nyilvános issue-t** ennek a projektnek a GitHub issue-trackerében (github.com/HereLiesAz/GraffitiXR). A jelentés csak a következőket tartalmazza:

*   hogy az összeomlás végzetes volt-e (az alkalmazást leállították) vagy helyreállt (elkapva, és az alkalmazás tovább futott);
*   az összeomlás dátumát és időpontját;
*   az eszközöd gyártóját és típusát, valamint az Android verziódat;
*   az alkalmazás verziónevét;
*   a kivétel (exception) stack trace-ét; és
*   az alkalmazás saját logcat-kimenetének utolsó legfeljebb 1000 sorát (csak erre az alkalmazásra korlátozva — nem a rendszerszintű naplókra).

Mivel a jelentés nyilvános GitHub issue-ként kerül benyújtásra, annak tartalma (beleértve a fenti eszköz- és naplóinformációkat) mindenki számára látható, aki hozzáfér ennek a projektnek az issue-trackeréhez. Csak akkor kapcsold be a hibajelentéseket, ha ezzel egyetértesz. Bármikor kikapcsolhatod ezt a beállítást újra, ez nem érinti a már megtörtént összeomlásokat.

## Szolgáltatók

Az alábbi okokból alkalmazhatunk harmadik fél cégeket és magánszemélyeket:

*   A Szolgáltatásunk megkönnyítése érdekében;
*   A Szolgáltatás nyújtása a nevünkben;
*   A Szolgáltatáshoz kapcsolódó szolgáltatások elvégzése; vagy
*   Annak elemzése, hogy hogyan használják a Szolgáltatásunkat.

Az alany kiemelése (háttér eltávolítása) a **U²-Net** nevű, az alkalmazásba épített modellt futtatja, teljesen az eszközödön. Ehhez semmi sem töltődik le, és egyetlen kép sem hagyja el az eszközt.

A támogatott eszközökön az AR mód a **Google Play-szolgáltatások AR-hez (ARCore)** segítségével fut, amelyet a Google LLC biztosít, és a [Google adatvédelmi irányelvei](https://policies.google.com/privacy) szabályoznak. Az ARCore a kamera képkockáit és a mozgásérzékelők adatait dolgozza fel az eszköz követéséhez; hogy mit gyűjt, azt a Google [ARCore adatvédelmi követelményei](https://developers.google.com/ar/develop/privacy-requirements) írják le. ARCore nélküli eszközökön az alkalmazás saját követést használ, és az ARCore sosem vesz részt.

## Frissítések

Az alkalmazás soha nem lép kapcsolatba szerverrel frissítések keresése céljából. A Beállítások képernyőn található egy "Kiadások oldalának megnyitása" bejegyzés; megérintésével a böngésződben megnyílik a projekt GitHub kiadási oldala (github.com/HereLiesAz/GraffitiXR/releases), pontosan úgy, mintha magad nyitottad volna meg a hivatkozást. Maga az alkalmazás semmit sem küld.

## Biztonság

Értékeljük a Személyes Adataid megadásával belénk vetett bizalmadat, ezért arra törekszünk, hogy kereskedelmileg elfogadható eszközöket használjunk azok védelmére. De ne feledd, hogy az interneten keresztüli továbbítás egyetlen módszere, illetve az elektronikus tárolás egyetlen módszere sem 100%-ig biztonságos és megbízható, és nem tudjuk garantálni az abszolút biztonságát.

## Hivatkozások Más Oldalakra

Ez a Szolgáltatás hivatkozásokat tartalmazhat más oldalakra. Ha egy harmadik fél linkjére kattintasz, arra az oldalra leszel irányítva. Ne feledd, hogy ezeket a külső oldalakat nem mi üzemeltetjük. Ezért nyomatékosan javasoljuk, hogy tekintsd át ezen weboldalak Adatvédelmi Irányelveit. Nincs befolyásunk, és nem vállalunk felelősséget semmilyen harmadik fél webhelyének vagy szolgáltatásának tartalmáért, adatvédelmi irányelveiért vagy gyakorlatáért.

## Gyermekek Adatvédelme

Ezek a Szolgáltatások nem céloznak meg 13 év alatti személyeket. Tudatosan nem gyűjtünk személyazonosításra alkalmas információkat 13 év alatti gyermekektől. Abban az esetben, ha felfedezzük, hogy egy 13 év alatti gyermek személyes adatokat adott meg nekünk, azonnal töröljük azokat a szervereinkről. Ha szülő vagy gyám vagy, és tudomásod van arról, hogy a gyermeked személyes adatokat adott meg nekünk, kérjük, lépj kapcsolatba velünk, hogy megtehessük a szükséges intézkedéseket.

## Változások Ebben az Adatvédelmi Irányelvben

Időről időre frissíthetjük az Adatvédelmi Irányelveinket. Ezért javasoljuk, hogy rendszeresen tekintsd át ezt az oldalt az esetleges változások miatt. Bármilyen változásról értesítünk az új Adatvédelmi Irányelv ezen az oldalon történő közzétételével.

Ez az irányelv 2024-01-01-től érvényes.

## Lépj Kapcsolatba Velünk

Ha bármilyen kérdésed vagy javaslatod van az Adatvédelmi Irányelveinkkel kapcsolatban, ne habozz kapcsolatba lépni velünk itt: https://github.com/HereLiesAz/GraffitiXR/issues.

---
*A dokumentációt 2026-09-04-én frissítettük: kijavítottuk a Naplóadatok szakaszt, amely úgy írta le az összeomlási adatokat, mintha azokat automatikusan és beleegyezés nélkül gyűjtenénk — valójában az alkalmazás kifejezett opt-in hozzájárulást igényel (Beállítások > Hibajelentések, alapértelmezetten kikapcsolva), mielőtt bármilyen hibajelentés elhagyná az eszközt, és a jelentés nyilvános GitHub issue-ként kerül benyújtásra, nem "a szervereinkre" küldve. Hozzáadtuk a GitHub API frissítés-ellenőrzés közzétételét, amely hiányzott ebből a dokumentumból, pedig az alkalmazás már küldött ilyen kéréseket (felhasználó által indítva, a Beállítások "Frissítések ellenőrzése" gombjával). Korábbi frissítés: 2026-03-17, a weboldal újratervezése és a Sablon Mód integrációs szakasza során.*
