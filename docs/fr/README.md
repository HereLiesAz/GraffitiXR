# GraffitiXR

GraffitiXR est une application Android pour les artistes de rue. Il existe déjà pas mal d'applications qui superposent une image à la vue de la caméra pour la décalquer virtuellement, mais quand je peins une fresque à partir d'un croquis enregistré sur mon téléphone, utiliser un trépied casse vraiment le rythme. On est tout le temps en mouvement. Moi, je mets mon téléphone dans ma poche. Même les applications qui utilisent la RA pour garder l'image stable et bien en place ne s'en sortent pas face à l'obscurité totale d'une poche.

Alors je fais quelque chose de mieux en réutilisant (ce que les initiés appellent) la méthode du quadrillage. Je me suis toujours dit : « Pourquoi ces gribouillis précis ne pourraient-ils pas être enregistrés, comme une ancre persistante, pour que la superposition soit toujours exactement au bon endroit ? »

Alors voilà, maintenant, c'est ce que font ces gribouillis.

J'ai dû inventer un **relocalisateur par empreinte** sur mesure qui fonctionne sur Android sans l'aide du cloud — parce que le graffiti, c'est illégal, comme chacun sait. Quand vous vous ancrez sur un mur, l'application capture une **empreinte** de vos traits sous forme de descripteurs OpenCV — des descripteurs plus une poignée de points 3D triangulés. Même après une perte de suivi ou une extinction d'écran, le moteur compare la caméra en direct à cette empreinte et résout la pose (PnP) pour **se recaler** et réaligner votre fresque sur le mur en quelques millisecondes — sans cloud, et sans pré-scan de toute la pièce.

Et j'ai complété ça avec ce que j'appelle un **SLAM téléologique** — puisqu'on sait à quoi le résultat est censé ressembler, j'utilise OpenCV pour observer votre progression, ce qui fait que plus vous avancez, plus la superposition colle fermement au mur. Sans ça, vous recouvririez ces traits avec la peinture elle-même, rendant l'application de moins en moins précise au fil du travail. C'est exactement là que les autres applications de ce genre échouent vraiment.

Ces deux fonctions — le recalage automatique et l'empreinte auto-extensible — ne sont pour l'instant qu'un interrupteur optionnel (Réglages > superposition de diagnostic, désactivé par défaut), le temps que je les valide sur du matériel réel. Ne vous attendez donc pas encore à les voir actives par défaut.

Juste pour le plaisir, j'ai intégré la fonction de superposition d'image hors RA pour le décalque, comme dans les autres applications, au cas où ça vous parle. Ou si vous êtes du genre à voir grand, il y a le **mode Maquette**. Prenez une photo du mur, et j'ai quelques outils rapides pour une maquette express. Et si vous n'avez rien à prouver et que vous voulez juste copier quelque chose parfaitement sur papier, le **mode Trace** transforme votre téléphone en table lumineuse, en gardant l'écran allumé à luminosité maximale, en verrouillant votre image en place et en bloquant tous les gestes tactiles jusqu'à ce que vous ayez terminé.

Et puis, il y a une bonne suite d'outils de conception pertinents pour préparer l'unique image que vous placez — tons, balance des couleurs, extraction de contours, isolement du sujet. Composer plusieurs images en une seule, c'est le travail de l'application de conception compagnon, pas de celle-ci. Je pourrais continuer, mais j'ai l'impression d'en avoir déjà assez dit.

## Fonctionnalités clés
*   **Hors ligne d'abord :** Aucune dépendance au cloud pour quoi que ce soit que fait l'application — le suivi, le rendu et le travail de conception se font tous en local. La seule chose qui quitte jamais l'appareil, c'est un rapport de plantage, et c'est facultatif et désactivé par défaut (Réglages > Rapports de plantage) ; voir [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Relocalisation par empreinte :** un pipeline OpenCV natif en C++17 (descripteurs ORB/SuperPoint + PnP/RANSAC) capture une empreinte des traits que vous dessinez sur le mur et recale la superposition après une perte de suivi — entièrement hors ligne, sans pré-scan de la pièce.
*   **Prêt pour la poche (expérimental, désactivé par défaut) :** la correction de dérive et une empreinte auto-extensible (pour que le recalage survive même si la référence d'origine est recouverte de peinture) existent et peuvent être activées depuis Réglages > superposition de diagnostic, mais aucune des deux n'a encore été validée sur du matériel réel — voir [SLAM téléologique](../TELEOLOGICAL_SLAM.md).
*   **Sensible au double objectif :** sélectionne automatiquement la profondeur stéréo matérielle sur les appareils qui l'exposent ; les autres appareils suivent via la pose monoculaire d'ARCore, sans estimation de profondeur séparée.
*   **Mode Coop :** partage de session chiffré et couplé par QR code, permettant à un collaborateur de suivre en direct le canevas de l'hôte en RA. Pour l'instant, uniquement de l'hôte vers l'invité — les modifications propres à un invité ne sont pas encore resynchronisées.
*   **Interface AzNavRail :** navigation au pouce, à une main, conçue pour les artistes qui tiennent une bombe de peinture.

## Modes
*   **Fresque RA :** L'instrument de précision central pour ancrer des concepts numériques sur des surfaces physiques, suivi grâce au relocalisateur par empreinte décrit ci-dessus.
*   **Mode Maquette :** Outils rapides pour visualiser des calques et des modes de fusion par-dessus des photos statiques de murs.
*   **Trace (Table lumineuse) :** Surface pleine luminosité pour décalquer sur papier, avec verrouillage tactile, rétractation automatique du rail, et sortie via les boutons physiques de volume (Haut, Bas, Haut, Bas).
*   **Superposition :** Décalque d'image hors RA — votre image de référence superposée à la caméra en direct (CameraX) avec une opacité réglable. Sur le petit nombre d'appareils sans ARCore, ce mode peut à la place suivre une forme que vous marquez sur le mur en utilisant le même pipeline OpenCV, en planaire uniquement.
*   **Conception :** Outils de placement et de lisibilité pour l'unique image de conception en cours de décalque — opacité/luminosité/contraste/saturation/balance des couleurs, inversion, extraction de contours et isolement du sujet. Il n'y a qu'une seule image de conception ; composer plusieurs images en une seule, c'est le travail de l'application de conception compagnon, pas de cette application.

## Licence
GraffitiXR est **à code source disponible, pas open source.** L'application, les modules `core:*`, ainsi que le moteur RA / SLAM / téléologique sont sous licence **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)) ; la surface d'API d'extension déclarée et les importateurs d'assets sont sous licence **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). **L'application compilée est libre d'utilisation pour tous, y compris pour des commandes rémunérées** — la clause non commerciale concerne la réutilisation du *code source*, pas les muralistes qui font un travail payant. Voir [`docs/LICENSING.md`](../LICENSING.md) pour la répartition faisant foi, chemin par chemin. Les bibliothèques tierces intégrées (OpenCV, ML Kit, …) conservent leurs propres licences d'origine.

## Architecture
Architecture Clean multi-modules strictement découplée :
*   `:app` — Navigation, orchestration de la caméra et injection de dépendances Hilt.
*   `:feature:ar` — Gestion de la session ARCore, `ArRenderer`, et traitement des données SLAM.
*   `:feature:editor` — Manipulation et ajustements de l'unique image de conception.
*   `:feature:dashboard` — Bibliothèque de projets, intégration et réglages.
*   `:core:nativebridge` — Moteur natif en C++ (`MobileGS`), pont JNI et threads de relocalisation. OpenCV lui-même est une dépendance Maven Central (`org.opencv:opencv`), pas un module intégré.
*   `:android_collaboration_module` — Réseau pair-à-pair pour le Mode Coop (hôte → invité ; voir Fonctionnalités clés ci-dessus).
*   `:core:data` / `:core:domain` / `:core:common` — Couche de données unifiée et abstraction pour montres connectées.
*   `:core:design` — Système de conception Compose partagé (contrôles et superpositions réutilisables).

## Documentation
- [Aperçu de l'architecture](../ARCHITECTURE.md)
- [Détails du moteur natif](../NATIVE_ENGINE.md)
- [Configuration & relocalisation SLAM](../SLAM_SETUP.md)
- [SLAM téléologique](../TELEOLOGICAL_SLAM.md)
- [Guide de performance](../performance.md)
- [Stratégie de test](../testing.md)
- [Formats de données](../data_formats.md)
- [Contribuer](../contributing.md)
- [Publication & distribution Google Play](../RELEASE.md)
- [Référence des écrans (en anglais)](../en/screens.md)

---
*Documentation mise à jour le 2026-09-04 : alignement avec le README anglais corrigé. Suppression des références au « Mode Pochoir » (fonctionnalité retirée, sans code d'implémentation), à la gestion complète des calques et à la génération de pochoirs multicouches — l'application gère une seule image de conception, la composition de plusieurs images étant réservée à l'application de conception compagnon. Ajout des sections Relocalisation par empreinte, Mode Coop, mode Trace et Licence, absentes de la version précédente. Correction des liens de documentation cassés (`docs/PRIVACY_POLICY_fr.md` et `docs/SECURITY_fr.md` n'existent pas à ces chemins) vers les fichiers réels du dépôt. Mise à jour précédente : 2026-03-17.*
