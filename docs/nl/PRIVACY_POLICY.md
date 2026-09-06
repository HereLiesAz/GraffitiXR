# Privacybeleid voor GraffitiXR

**Laatst bijgewerkt:** [Huidige datum]

HereLiesAZ heeft de GraffitiXR-app gebouwd als een source-available app (zie de LICENSE en docs/LICENSING.md van de repository voor de voorwaarden). Deze DIENST wordt kosteloos door HereLiesAZ geleverd en is bedoeld voor gebruik zoals deze is.

Deze pagina wordt gebruikt om bezoekers te informeren over ons beleid met betrekking tot het verzamelen, gebruiken en openbaar maken van persoonlijke informatie als iemand besluit onze service te gebruiken.

Als u ervoor kiest om onze Dienst te gebruiken, gaat u akkoord met het verzamelen en gebruiken van informatie met betrekking tot dit beleid. De Persoonlijke Informatie die wij verzamelen wordt gebruikt voor het leveren en verbeteren van de Dienst. We zullen uw informatie met niemand gebruiken of delen, behalve zoals beschreven in dit privacybeleid.

## Informatieverzameling en -gebruik

Voor een betere ervaring tijdens het gebruik van onze Service kunnen we van u verlangen dat u ons bepaalde persoonlijk identificeerbare informatie verstrekt, inclusief maar niet beperkt tot:

*   **Cameragegevens:** De app heeft toegang tot de camera van uw apparaat nodig om de Augmented Reality (AR)-weergave en de overlay-modus weer te geven en om afbeeldingen vast te leggen voor projectdoelen. Cameragegevens worden lokaal op uw apparaat verwerkt en worden niet naar onze servers verzonden, tenzij u er expliciet voor kiest om een ​​projectbestand te delen.
*   **Opslag / Foto's:** We hebben toegang nodig tot de opslag van uw apparaat (externe lees-/schrijfopslag of fotobibliotheek) om afbeeldingen voor overlays te laden, uw projecten op te slaan en vastgelegde afbeeldingen te exporteren.
*   **Locatiegegevens (optioneel):** Als u locatierechten verleent, kan de app GPS-gegevens verzamelen (breedtegraad, lengtegraad, hoogte) om uw projecten te geotaggen. Deze gegevens worden lokaal opgeslagen in uw projectbestanden.

## Crashrapporten (Opt-In)

Standaard verlaat er niets over een crash uw apparaat. Als de app crasht of herstelt van een interne fout, wordt er een rapport weggeschreven naar een lokaal, tijdelijk bestand op uw apparaat, zodat de app u bij de volgende keer opstarten een melding "vorige sessie is gecrasht" kan tonen — dat bestand verlaat op zichzelf nooit het apparaat.

Het versturen van dat rapport naar ons staat uit, tenzij u dit zelf inschakelt, via **Instellingen > Crashrapporten**. Alleen als u zich hiervoor heeft aangemeld, uploadt de app het rapport bij de volgende start, als een **openbaar issue** in de GitHub-issue-tracker van dit project (github.com/HereLiesAz/GraffitiXR). Het rapport bevat alleen:

*   of de crash fataal was (de app werd afgesloten) of hersteld (opgevangen en de app bleef draaien);
*   de datum en tijd van de crash;
*   de fabrikant en het model van uw apparaat, en uw Android-versie;
*   de versienaam van de app;
*   de stack trace van de uitzondering; en
*   maximaal de laatste 1.000 regels van de eigen logcat-uitvoer van de app (beperkt tot het proces van deze app — geen systeembrede logs).

Omdat het rapport wordt ingediend als een openbaar GitHub-issue, is de inhoud ervan (inclusief de bovenstaande apparaat- en loginformatie) zichtbaar voor iedereen die de issue-tracker van dit project kan bekijken. Zet crashrapporten alleen aan als u daar geen probleem mee heeft. U kunt de instelling op elk moment weer uitzetten; dit heeft geen invloed op crashes die al hebben plaatsgevonden.

## Dienstverleners

We kunnen om de volgende redenen externe bedrijven en personen in dienst nemen:

*   Om onze Dienst te vergemakkelijken;
*   Om de Dienst namens ons te verlenen;
*   Om Servicegerelateerde diensten uit te voeren; of
*   Om ons te helpen analyseren hoe onze Dienst wordt gebruikt.

We gebruiken **Google ML Kit** voor onderwerpsegmentatie (achtergrondverwijdering). Deze verwerking gebeurt lokaal op uw apparaat.

## Updatecontroles

Op het instellingenscherm staat een knop "Controleren op updates". Er wordt niets automatisch gecontroleerd — alleen wanneer u erop drukt. Door erop te drukken doet de app een verzoek aan de **GitHub API** (api.github.com) om de laatste release van dit project op te zoeken. GitHub is een derde partij buiten onze controle, en uw IP-adres is voor de duur van dat ene verzoek zichtbaar voor GitHub, net zoals bij elk webverzoek dat u naar github.com doet. Er wordt geen andere informatie met dit verzoek meegestuurd.

## Beveiliging

Wij waarderen uw vertrouwen bij het verstrekken van uw persoonlijke gegevens aan ons en daarom streven wij ernaar commercieel aanvaardbare middelen te gebruiken om deze te beschermen. Maar onthoud dat geen enkele methode van verzending via internet of elektronische opslag 100% veilig en betrouwbaar is, en we kunnen de absolute veiligheid ervan niet garanderen.

## Links naar andere sites

Deze Dienst kan links naar andere sites bevatten. Als u op een link van een derde partij klikt, wordt u naar die site geleid. Houd er rekening mee dat deze externe sites niet door ons worden beheerd. Daarom raden wij u ten zeerste aan om het privacybeleid van deze websites te raadplegen. Wij hebben geen controle over en aanvaarden geen verantwoordelijkheid voor de inhoud, het privacybeleid of de praktijken van sites of diensten van derden.

## Privacy van kinderen

Deze Services richten zich niet tot personen jonger dan 13 jaar. We verzamelen niet bewust persoonlijk identificeerbare informatie van kinderen jonger dan 13 jaar. Als we ontdekken dat een kind jonger dan 13 jaar ons persoonlijke informatie heeft verstrekt, verwijderen we deze onmiddellijk van onze servers. Als u een ouder of voogd bent en u weet dat uw kind ons persoonlijke gegevens heeft verstrekt, neem dan contact met ons op zodat wij de nodige acties kunnen ondernemen.

## Wijzigingen in dit privacybeleid

We kunnen ons privacybeleid van tijd tot tijd bijwerken. Daarom wordt u geadviseerd deze pagina regelmatig te controleren op eventuele wijzigingen. Wij zullen u op de hoogte stellen van eventuele wijzigingen door het nieuwe privacybeleid op deze pagina te plaatsen.

Dit beleid is van kracht vanaf 01-01-2024

## Neem contact met ons op

Als u vragen of suggesties heeft over ons privacybeleid, aarzel dan niet om contact met ons op te nemen via [Contact-e-mail invoegen of GitHub Repository Link].


---
*Documentatie bijgewerkt op 2026-09-04: het onderdeel Loggegevens, dat crashgegevens beschreef als automatisch en zonder toestemming verzameld, is gecorrigeerd — de app vereist in werkelijkheid een expliciete opt-in (Instellingen > Crashrapporten, standaard uit) voordat er een crashrapport het apparaat verlaat, en het rapport wordt ingediend als een openbaar GitHub-issue, niet verzonden naar "onze servers". De bestaande melding over de GitHub-API-updatecontrole heeft nu een eigen sectie gekregen en is verduidelijkt als door de gebruiker geïnitieerd (de knop "Controleren op updates" in Instellingen), niet automatisch. Vorige update: 17-03-2026, tijdens het herontwerp van de website en de integratiefase van de stencilmodus.*
