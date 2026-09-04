# GraffitiXR

GraffitiXR é um aplicativo Android para artistas de rua. Existem muitos aplicativos que sobrepõem uma imagem na visão da sua câmera para que você possa traçá-la virtualmente, mas quando estou pintando um mural baseado em um esboço que salvei no meu telefone, usar um tripé pode realmente atrapalhar o fluxo. Nós estamos por toda parte. Eu, por exemplo, coloco meu telefone no bolso. Até mesmo os aplicativos que usam AR para manter a imagem estável e em um só lugar não conseguem lidar com a escuridão abismal do bolso.

Então, estou criando algo melhor, adaptando (o que os especialistas chamam de) o método de grade. Eu sempre pensava: "Por que esses rabiscos específicos não podem ser salvos, como uma âncora persistente, para que a sobreposição esteja sempre simplesmente no lugar certo?"

Então, agora, é isso que esses rabiscos fazem.

Eu tive que inventar um **relocalizador com impressão digital** ("fingerprinted relocalizer") personalizado que funciona no Android sem a ajuda da nuvem — porque graffiti é, sabe, ilegal. Quando você trava em uma parede, o app captura uma **impressão digital** de recursos do OpenCV das suas marcas — descritores mais um punhado de pontos 3D triangulados. Mesmo após a perda de rastreamento ou a tela ser desligada, o motor compara a câmera ao vivo com essa impressão digital e resolve a pose (PnP) para **realinhar** ("snap back") o seu mural à parede em milissegundos — sem nuvem, e sem pré-varredura de todo o ambiente.

E eu segui isso com o que chamo de **Teleological SLAM** — já que sabemos como o resultado deve parecer, uso o OpenCV para observar o seu progresso, o que significa que quanto mais você avança, mais firmemente a sobreposição se prende à parede. Sem isso, você cobriria essas marcas com a própria pintura, tornando o aplicativo menos preciso à medida que você avança. É exatamente aí que outros aplicativos semelhantes falham de verdade.

Ambos os recursos — o realinhamento e a impressão digital autoexpansível — atualmente são entregues como um botão opt-in (Configurações > sobreposição de diagnóstico, desativado por padrão) enquanto eu os valido em hardware real, então não espere nenhum dos dois pronto de fábrica ainda.

Só por diversão, também incluí a funcionalidade de sobreposição de imagem sem AR para traçado de imagem, igual ao que você encontra nesses outros aplicativos, caso você prefira assim. Ou, se você gosta de algo mais rápido, há o modo **Maquete (Mockup)**. Tire uma foto da parede, e eu tenho algumas ferramentas rápidas para uma maquete ágil. E se você não tem nada a provar e apenas quer algo copiado para o papel com perfeição, o modo **Traçar (Trace)** permite que você use seu telefone como uma mesa de luz, mantendo a tela ligada com o brilho no máximo, travando a imagem no lugar e bloqueando todos os toques até você terminar.

Além disso, há um bom conjunto de ferramentas de design pertinentes para preparar a única imagem que você está posicionando — tom, balanço de cores, extração de contorno, isolamento de assunto. Compor várias imagens em uma só é trabalho do aplicativo de design complementar, não deste. Eu poderia continuar, mas sinto que já falei bastante.

## Principais Recursos
*   **Primeiro Offline (Offline-First):** Sem dependências da nuvem para nada que o aplicativo faça — rastreamento, renderização e trabalho de design são todos locais. A única coisa que já sai do dispositivo é um relatório de falha, e isso é opt-in e desativado por padrão (Configurações > Relatórios de falha); veja [`docs/en/PRIVACY_POLICY.md`](../en/PRIVACY_POLICY.md).
*   **Fingerprint Relocalization:** um pipeline nativo em C++17 do OpenCV (descritores ORB/SuperPoint + PnP/RANSAC) captura a impressão digital das marcas que você desenha na parede e realinha a sobreposição após a perda de rastreamento — totalmente offline, sem pré-varredura do ambiente.
*   **Pronto para o bolso (experimental, desativado por padrão):** correção de deriva e uma impressão digital autoexpansível (para que o realinhamento sobreviva mesmo que a referência original seja pintada por cima) existem e podem ser ativadas em Configurações > sobreposição de diagnóstico, mas nenhuma das duas foi validada em hardware real ainda — veja [Teleological SLAM](../TELEOLOGICAL_SLAM.md).
*   **Dual-Lens Aware:** seleciona automaticamente a profundidade estéreo de hardware em dispositivos que a oferecem; outros dispositivos rastreiam via a pose monocular do ARCore, sem uma estimativa de profundidade separada.
*   **Modo Co-op:** compartilhamento de sessão criptografado e pareado por QR code, para que um colaborador possa acompanhar o canvas ao vivo do anfitrião em AR. Atualmente apenas anfitrião → convidado — as próprias edições de um convidado ainda não são sincronizadas de volta.
*   **Interface AzNavRail:** navegação acionada pelo polegar, com uma mão só, projetada para artistas segurando uma lata de spray.

## Modos
*   **Mural AR:** O instrumento central de precisão para ancorar conceitos digitais a superfícies físicas, rastreado pelo relocalizador com impressão digital descrito acima.
*   **Modo Maquete (Mockup):** Ferramentas rápidas para visualizar camadas e modos de mesclagem sobre fotos estáticas da parede.
*   **Traçar (Mesa de luz):** Superfície com brilho máximo para copiar no papel, com bloqueio de toque, retração automática da barra de navegação e saída por botão físico de volume (Cima, Baixo, Cima, Baixo).
*   **Sobreposição (Overlay):** Traçado de imagem sem AR — sua imagem de referência é sobreposta à câmera ao vivo (CameraX) com opacidade ajustável. No pequeno número de dispositivos sem ARCore, esse modo pode em vez disso rastrear uma forma que você marca na parede, usando o mesmo pipeline do OpenCV, apenas de forma planar.
*   **Design:** Ferramentas de posicionamento e legibilidade para a única imagem de design sendo traçada — opacidade/brilho/contraste/saturação/balanço de cores, inverter, extração de contorno e isolamento de assunto. Existe exatamente uma imagem de design; compor várias imagens em uma só é trabalho do aplicativo de design complementar, não deste aplicativo.

## Licenciamento
O GraffitiXR é **de código disponível (source-available), não é código aberto (open source).** O aplicativo, os módulos `core:*` e o motor de AR/SLAM/teleológico são licenciados sob **PolyForm Noncommercial 1.0.0** ([`/LICENSE`](../../LICENSE)); a superfície de API de extensão declarada e os importadores de assets são **MIT** ([`docs/licenses/MIT.txt`](../licenses/MIT.txt)). O **aplicativo compilado é de uso gratuito para qualquer pessoa, incluindo comissões pagas** — o termo não comercial se aplica à reutilização do *código-fonte*, não a muralistas realizando trabalho remunerado. Veja [`docs/LICENSING.md`](../LICENSING.md) para a estrutura autoritativa, caminho por caminho, e a ordem de precedência. Componentes de terceiros incluídos (OpenCV, ML Kit, …) mantêm suas próprias licenças de origem.

## Arquitetura
Arquitetura multi-módulo estritamente desacoplada:
*   `:app` — Navegação, orquestração de câmera e injeção de dependência Hilt.
*   `:feature:ar` — Gerenciamento de sessão do ARCore, `ArRenderer` e processamento de dados SLAM.
*   `:feature:editor` — Manipulação e ajustes de imagem para uma única imagem.
*   `:feature:dashboard` — Biblioteca de projetos, integração inicial (onboarding) e configurações.
*   `:core:nativebridge` — Motor nativo em C++ (`MobileGS`), ponte JNI e threads de relocalização. O próprio OpenCV é uma dependência do Maven Central (`org.opencv:opencv`), não um módulo empacotado.
*   `:android_collaboration_module` — rede ponto a ponto para o Modo Co-op (anfitrião → convidado; veja Principais Recursos acima).
*   `:core:data` / `:core:domain` / `:core:common` — Camada de dados unificada e abstração de wearables.
*   `:core:design` — Sistema de design Compose compartilhado (controles e sobreposições reutilizáveis).

## Documentação
- [Visão Geral da Arquitetura](../ARCHITECTURE.md)
- [Detalhes do Motor Nativo](../NATIVE_ENGINE.md)
- [Configuração e Relocalização do SLAM](../SLAM_SETUP.md)
- [Teleological SLAM](../TELEOLOGICAL_SLAM.md)
- [Guia de Desempenho](../performance.md)
- [Estratégia de Testes](../testing.md)
- [Formatos de Dados](../data_formats.md)
- [Como Contribuir](../contributing.md)
- [Lançamento e Distribuição no Google Play](../RELEASE.md)

---
*Documentação atualizada em 2026-09-04: alinhada com o README raiz em inglês, já corrigido — o suposto motor de "Persistent Voxel Memory", o "Single GL Render Path" com `slamManager.draw()`, e o fallback para fluxo óptico no suporte a múltiplas lentes não existem no código e foram substituídos pela real relocalização por impressão digital, o comportamento correto do Dual-Lens Aware, o Modo Co-op, o modo Traçar e uma seção de Licenciamento. Links quebrados para `docs/PIPELINE_3D.md` (não existe) e `docs/screens.md` (caminho incorreto) foram removidos ou corrigidos. Atualização anterior: 2026-03-17, durante a fase de redesign do site e integração do Modo Estêncil (o Modo Estêncil foi removido do aplicativo desde então).*
