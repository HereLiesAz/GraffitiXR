# Patakaran sa Privacy para sa GraffitiXR

**Huling Na-update:** 2026-10-19

Binuo ng HereLiesAZ ang GraffitiXR app bilang source-available na app (tingnan ang LICENSE at docs/LICENSING.md ng repository para sa mga tuntunin). Ang SERBISYONG ito ay ibinibigay ng HereLiesAZ nang walang bayad at nilayon para sa paggamit kung ano man.

Ginagamit ang page na ito upang ipaalam sa mga bisita ang tungkol sa aming mga patakaran sa pangongolekta, paggamit, at pagsisiwalat ng Personal na Impormasyon kung may nagpasya na gamitin ang aming Serbisyo.

Kung pipiliin mong gamitin ang aming Serbisyo, sumasang-ayon ka sa pangongolekta at paggamit ng impormasyon kaugnay ng patakarang ito. Ang Personal na Impormasyon na aming kinokolekta ay ginagamit para sa pagbibigay at pagpapabuti ng Serbisyo. Hindi namin gagamitin o ibabahagi ang iyong impormasyon sa sinuman maliban sa inilarawan sa Patakaran sa Privacy na ito.

## Koleksyon at Paggamit ng Impormasyon

Para sa isang mas mahusay na karanasan, habang ginagamit ang aming Serbisyo, maaari naming hilingin sa iyo na magbigay sa amin ng ilang personal na nakakapagpakilalang impormasyon, kabilang ngunit hindi limitado sa:

* **Data ng Camera:** Nangangailangan ang app ng access sa camera ng iyong device upang ipakita ang view ng Augmented Reality (AR), Overlay mode, at para kumuha ng mga larawan para sa mga target ng proyekto. Lokal na pinoproseso ang data ng camera sa iyong device at hindi ipinapadala sa aming mga server maliban kung tahasan mong pipiliin na magbahagi ng project file.
* **Storage / Mga Larawan:** Walang hinihinging pahintulot sa storage ang app. Ang mga larawang idinadagdag mo ay dumarating sa pamamagitan ng photo picker o file picker ng Android system, na nagbibigay sa app ng access lamang sa file na pinili mo. Ang mga proyekto ay sine-save sa pribadong storage ng app, at ang mga na-export na larawan ay sine-save sa photo gallery ng iyong device.
* **Data ng Lokasyon (Opsyonal):** Humihingi lamang ang app ng pahintulot sa lokasyon kapag sinimulan mong kumuha ng AR target. Kung ibibigay mo ito, itinatala ng capture record ng target na iyon kung saan ito kinuha (latitude, longitude, altitude at katumpakan). Lokal itong sine-save sa project file at aalis lamang sa iyong device kung ikaw mismo ang magbabahagi o mag-e-export ng project file na iyon. Hindi ito kailanman ipinapadala ng Link (pagbabahagi ng session sa pangalawang device): natatanggap ng kabilang device ang proyekto nang walang anumang lokasyon.

## Mga Ulat ng Pag-crash (Opt-In)

Bilang default, walang umaalis sa iyong device dahil sa isang pag-crash. Kung mag-crash ang app o makabawi mula sa isang internal na error, isang ulat ang isusulat sa isang lokal, pansamantalang file sa iyong device para maipakita ng app ang isang "nag-crash ang huling run" na abiso sa susunod mong pagbukas nito — hindi kailanman umaalis sa device ang file na iyon sa sarili nito.

Naka-off bilang default ang pagpapadala ng ulat na iyon sa amin, maliban kung ikaw mismo ang magbubukas nito sa **Settings > Crash reports**. Kung — at kung lamang — na-opt-in ka na, ia-upload ng app ang ulat sa susunod itong magsimula, bilang isang **pampublikong isyu (public issue)** sa GitHub issue tracker ng proyektong ito (github.com/HereLiesAz/GraffitiXR). Kasama lamang sa ulat ang mga sumusunod:

*   kung ang pag-crash ay pumatay sa app o nabawi (nahuli at nagpatuloy ang app);
*   ang petsa at oras ng pag-crash;
*   ang manufacturer at modelo ng iyong device, at ang bersyon ng iyong Android;
*   ang pangalan ng bersyon ng app;
*   ang stack trace ng exception; at
*   hanggang sa huling 1,000 linya ng sariling logcat output ng app (limitado lamang sa proseso ng app na ito — hindi ang buong-sistemang log).

Dahil isinasampa ang ulat bilang isang pampublikong GitHub issue, ang mga nilalaman nito (kasama ang impormasyon ng device at log sa itaas) ay makikita ng sinumang makakakita sa issue tracker ng proyektong ito. I-on lamang ang crash reports kung komportable ka dito. Maaari mo itong i-off muli anumang oras, at hindi ito makakaapekto sa mga pag-crash na naganap na.

## Mga Tagabigay ng Serbisyo

Maaari kaming gumamit ng mga third-party na kumpanya at indibidwal dahil sa mga sumusunod na dahilan:

* Upang mapadali ang aming Serbisyo;
* Upang ibigay ang Serbisyo sa ngalan namin;
* Upang magsagawa ng mga serbisyong nauugnay sa Serbisyo;o
* Upang tulungan kami sa pagsusuri kung paano ginagamit ang aming Serbisyo.

Ang pag-isolate ng paksa (pag-alis ng background) ay gumagamit ng **U²-Net**, isang modelong kasama na sa app, nang buo sa iyong device. Walang dina-download para dito at walang larawang lumalabas sa device.

Sa mga device na sumusuporta rito, tumatakbo ang AR mode sa **Google Play Services for AR (ARCore)**, na ibinibigay ng Google LLC at saklaw ng [Patakaran sa Privacy ng Google](https://policies.google.com/privacy). Pinoproseso ng ARCore ang mga frame ng camera at ang data ng mga motion sensor para subaybayan ang device; tingnan ang [mga kinakailangan sa privacy ng ARCore](https://developers.google.com/ar/develop/privacy-requirements) ng Google para sa kinokolekta nito. Sa mga device na walang ARCore, sariling tracking ng app ang ginagamit at hindi kailanman kasali ang ARCore.

## Mga Update

Hindi kailanman kumokonekta ang app sa isang server para tumingin ng mga update. May "Buksan ang pahina ng mga release" sa Settings screen; ang pagpindot dito ay nagbubukas ng pahina ng mga release ng proyektong ito sa GitHub (github.com/HereLiesAz/GraffitiXR/releases) sa iyong browser, na para bang ikaw mismo ang sumunod sa link. Walang ipinapadala ang app mismo.

## Seguridad

Pinahahalagahan namin ang iyong tiwala sa pagbibigay sa amin ng iyong Personal na Impormasyon, kaya nagsusumikap kaming gumamit ng mga paraan na tinatanggap sa komersyo upang maprotektahan ito. Ngunit tandaan na walang paraan ng paghahatid sa internet, o paraan ng electronic storage na 100% secure at maaasahan, at hindi namin magagarantiya ang ganap na seguridad nito.

## Mga Link sa Iba Pang Mga Site

Ang Serbisyong ito ay maaaring maglaman ng mga link sa ibang mga site. Kung mag-click ka sa isang link ng third-party, ididirekta ka sa site na iyon. Tandaan na ang mga panlabas na site na ito ay hindi namin pinapatakbo. Samakatuwid, mariing ipinapayo namin sa iyo na suriin ang Patakaran sa Privacy ng mga website na ito. Wala kaming kontrol at walang pananagutan para sa nilalaman, mga patakaran sa privacy, o mga kasanayan ng anumang mga site o serbisyo ng third-party.

## Privacy ng mga Bata

Ang Mga Serbisyong ito ay hindi tumutugon sa sinumang wala pang 13 taong gulang. Hindi namin sinasadyang nangongolekta ng personal na nakakapagpakilalang impormasyon mula sa mga batang wala pang 13 taong gulang. Kung sakaling matuklasan namin na ang isang batang wala pang 13 taong gulang ay nagbigay sa amin ng personal na impormasyon, agad naming tinanggal ito sa aming mga server. Kung ikaw ay isang magulang o tagapag-alaga at alam mo na ang iyong anak ay nagbigay sa amin ng personal na impormasyon, mangyaring makipag-ugnayan sa amin upang magawa namin ang mga kinakailangang aksyon.

## Mga Pagbabago sa Patakaran sa Privacy na Ito

Maaari naming i-update ang aming Patakaran sa Privacy paminsan-minsan. Kaya, pinapayuhan kang suriin ang pahinang ito pana-panahon para sa anumang mga pagbabago. Aabisuhan ka namin ng anumang mga pagbabago sa pamamagitan ng pag-post ng bagong Patakaran sa Privacy sa pahinang ito.

Ang patakarang ito ay may bisa noong 2024-01-01

## Makipag-ugnayan sa Amin

Kung mayroon kang anumang mga tanong o mungkahi tungkol sa aming Patakaran sa Privacy, huwag mag-atubiling makipag-ugnayan sa amin sa https://github.com/HereLiesAz/GraffitiXR/issues.

---
*Na-update ang dokumentasyon noong 2026-09-04: itinama ang seksyong Data ng Log, na naglalarawan sa crash data bilang awtomatikong kinokolekta at walang pahintulot — sa totoo lang, nangangailangan ang app ng tahasang opt-in (Settings > Crash reports, naka-off bilang default) bago umalis sa device ang anumang crash report, at isinasampa ang ulat bilang isang pampublikong GitHub issue, hindi ipinapadala sa "aming mga server." Binigyan ng sariling seksyon ang dating hindi-nailathalang GitHub API update-check disclosure at nilinaw na user-initiated ito (ang "Suriin ang mga update" na button sa Settings), hindi awtomatiko. Pinalitan din ang lumang "Open Source" na claim ng tamang "source-available" na paglalarawan. Naunang update: 2026-03-17, sa panahon ng muling pagdidisenyo ng website at yugto ng pagsasama ng Stencil Mode.*