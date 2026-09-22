# Politique de Confidentialité de GraffitiXR

**Dernière Mise à Jour :** 2026-09-04

HereLiesAZ a conçu l'application GraffitiXR comme une application à code source disponible (source-available) — voir le fichier LICENSE et docs/LICENSING.md du dépôt pour les conditions exactes. Ce SERVICE est fourni par HereLiesAZ gratuitement et est destiné à être utilisé tel quel.

Cette page a pour but d'informer les visiteurs concernant nos politiques relatives à la collecte, l'utilisation et la divulgation des Informations Personnelles si quiconque décide d'utiliser notre Service.

Si vous choisissez d'utiliser notre Service, vous acceptez la collecte et l'utilisation d'informations en relation avec cette politique. Les Informations Personnelles que nous collectons sont utilisées pour fournir et améliorer le Service. Nous n'utiliserons ni ne partagerons vos informations avec quiconque, sauf tel que décrit dans cette Politique de Confidentialité.

## Collecte et Utilisation des Informations

Pour une meilleure expérience lors de l'utilisation de notre Service, nous pouvons vous demander de nous fournir certaines informations personnellement identifiables, y compris, mais sans s'y limiter :

*   **Données de la Caméra :** L'application nécessite l'accès à la caméra de votre appareil pour afficher la vue de Réalité Augmentée (AR), le mode Superposition, et pour capturer des images pour les cibles de projet. Les données de la caméra sont traitées localement sur votre appareil et ne sont pas transmises à nos serveurs, sauf si vous choisissez explicitement de partager un fichier de projet.
*   **Stockage / Photos :** Nous avons besoin d'accéder au stockage de votre appareil (Lecture/Écriture sur le Stockage Externe ou la Photothèque) pour charger des images pour les superpositions, sauvegarder vos projets et exporter les images capturées.
*   **Données de Localisation (Optionnel) :** Si vous accordez les autorisations de localisation, l'application peut collecter des données GPS (latitude, longitude, altitude) pour géolocaliser vos projets. Ces données sont enregistrées localement dans vos fichiers de projet.

## Rapports de Plantage (Opt-In)

Par défaut, rien concernant un plantage ne quitte votre appareil. Si l'application plante ou se remet d'une erreur interne, un rapport est écrit dans un fichier local et temporaire sur votre appareil, afin que l'application puisse vous afficher un avis « la dernière session a planté » la prochaine fois que vous l'ouvrez — ce fichier ne quitte jamais l'appareil de lui-même.

L'envoi de ce rapport est désactivé à moins que vous ne l'activiez vous-même, dans **Paramètres > Rapports de plantage**. Si — et seulement si — vous avez activé cette option, l'application télécharge le rapport au prochain démarrage, en tant qu'**issue publique** sur le suivi des issues GitHub de ce projet (github.com/HereLiesAZ/GraffitiXR). Le rapport contient uniquement :

*   si le plantage était fatal (l'application a été arrêtée) ou récupéré (intercepté, l'application a continué à fonctionner) ;
*   la date et l'heure du plantage ;
*   le fabricant et le modèle de votre appareil, ainsi que votre version d'Android ;
*   le nom de version de l'application ;
*   la pile d'appels (stack trace) de l'exception ; et
*   jusqu'aux 1 000 dernières lignes de la sortie logcat propre à l'application (limitée au processus de cette application — pas les journaux système entiers).

Comme le rapport est déposé en tant qu'issue publique GitHub, son contenu (y compris les informations sur l'appareil et les journaux ci-dessus) est visible par quiconque peut consulter le suivi des issues de ce projet. N'activez les rapports de plantage que si vous êtes à l'aise avec cela. Vous pouvez désactiver ce paramètre à tout moment, sans que cela n'affecte les plantages déjà survenus.

## Fournisseurs de Services

Nous pouvons employer des sociétés tierces et des personnes pour les raisons suivantes :

*   Pour faciliter notre Service ;
*   Pour fournir le Service en notre nom ;
*   Pour effectuer des services liés au Service ; ou
*   Pour nous aider à analyser comment notre Service est utilisé.

Nous utilisons **Google ML Kit** pour la segmentation des sujets (suppression de l'arrière-plan). Ce traitement s'effectue localement sur votre appareil.

## Vérifications de Mise à Jour

L'écran Paramètres comporte un bouton « Vérifier les mises à jour ». Rien n'est vérifié automatiquement — uniquement lorsque vous appuyez dessus. Appuyer dessus effectue une requête vers l'**API GitHub** (api.github.com) pour consulter la dernière version de ce projet. GitHub est un tiers hors de notre contrôle, et votre adresse IP est visible par GitHub pendant la durée de cette unique requête, tout comme elle le serait pour n'importe quelle requête web que vous adressez à github.com. Aucune autre information n'est envoyée dans le cadre de cette requête.

## Sécurité

Nous apprécions votre confiance en nous fournissant vos Informations Personnelles, c'est pourquoi nous nous efforçons d'utiliser des moyens commercialement acceptables pour les protéger. Mais rappelez-vous qu'aucune méthode de transmission sur Internet, ou méthode de stockage électronique n'est sûre et fiable à 100 %, et nous ne pouvons garantir sa sécurité absolue.

## Liens vers d'Autres Sites

Ce Service peut contenir des liens vers d'autres sites. Si vous cliquez sur un lien tiers, vous serez dirigé vers ce site. Notez que ces sites externes ne sont pas exploités par nous. Par conséquent, nous vous conseillons vivement de consulter la Politique de Confidentialité de ces sites web. Nous n'avons aucun contrôle sur le contenu, les politiques de confidentialité ou les pratiques de tout site ou service tiers et n'en assumons aucune responsabilité.

## Confidentialité des Enfants

Ces Services ne s'adressent à personne de moins de 13 ans. Nous ne collectons pas sciemment d'informations personnellement identifiables auprès d'enfants de moins de 13 ans. Dans le cas où nous découvrons qu'un enfant de moins de 13 ans nous a fourni des informations personnelles, nous les supprimons immédiatement de nos serveurs. Si vous êtes un parent ou un tuteur et que vous savez que votre enfant nous a fourni des informations personnelles, veuillez nous contacter afin que nous puissions prendre les mesures nécessaires.

## Modifications de Cette Politique de Confidentialité

Nous pouvons mettre à jour notre Politique de Confidentialité de temps à autre. Ainsi, il vous est conseillé de consulter cette page périodiquement pour tout changement. Nous vous informerons de tout changement en publiant la nouvelle Politique de Confidentialité sur cette page.

Cette politique est en vigueur à compter du 2024-01-01.

## Nous Contacter

Si vous avez des questions ou des suggestions concernant notre Politique de Confidentialité, n'hésitez pas à nous contacter sur notre dépôt GitHub : https://github.com/HereLiesAZ/GraffitiXR.


---
*Documentation mise à jour le 2026-09-04 : correction de « Open Source » en « à code source disponible » (voir LICENSE / docs/LICENSING.md). Mise à jour précédente : 2026-03-17.*

*Documentation mise à jour le 2026-09-22 : correction de la section « Données de Journal », qui décrivait les données de plantage comme collectées automatiquement et sans consentement — en réalité, l'application nécessite une activation explicite (Paramètres > Rapports de plantage, désactivée par défaut) avant qu'un rapport de plantage ne quitte l'appareil, et le rapport est déposé en tant qu'issue publique GitHub, et non envoyé à « nos serveurs ». Une section dédiée a été ajoutée pour la vérification des mises à jour via l'API GitHub, précisant qu'elle est déclenchée par l'utilisateur (bouton « Vérifier les mises à jour » dans les Paramètres), et non automatique.*
