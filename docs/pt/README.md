# GraffitiXR

GraffitiXR é um aplicativo Android para artistas de rua. Existem muitos aplicativos que sobrepõem uma imagem na visão da sua câmera para que você possa traçá-la virtualmente, mas quando estou pintando um mural baseado em um esboço que salvei no meu telefone, usar um tripé pode realmente atrapalhar o fluxo. Nós estamos por toda parte. Eu, por exemplo, coloco meu telefone no bolso. Até mesmo os aplicativos que usam AR para manter a imagem estável e em um só lugar não conseguem lidar com a escuridão abismal do bolso.

Então, estou criando algo melhor, adaptando (o que os especialistas chamam de) o método de grade. Eu sempre pensava: "Por que esses rabiscos específicos não podem ser salvos, como uma âncora persistente, para que a sobreposição esteja sempre simplesmente no lugar certo?"

Então, agora, é isso que esses rabiscos fazem.

Eu tive que inventar um motor de Gaussian Splatting personalizado que funciona no Android sem a ajuda da nuvem — porque graffiti é, sabe, ilegal.

E segui com o que chamo de Teleological Slam (SLAM Teleológico) — já que sabemos como deve ser o resultado, uso o OpenCV para observar o seu progresso, o que significa que quanto mais você avança, mais firmemente a sobreposição se prende à parede. Sem isso, você cobriria essas marcas com a própria pintura, tornando o aplicativo menos preciso à medida que você avança. É exatamente aí que outros aplicativos semelhantes falham de verdade.

Só por diversão, incluí a funcionalidade de sobreposição de imagem sem AR para traçado de imagem, igual ao que você encontra nesses outros aplicativos, caso você prefira assim. Ou, se você gosta de algo rápido, há o modo Maquete (Mockup). Tire uma foto da parede, e eu tenho algumas ferramentas rápidas para uma maquete ágil. E se você não tem nada a provar e apenas quer algo copiado para o papel com perfeição, o modo Traçar (Trace) permite que você use seu telefone como uma mesa de luz, mantendo a tela ligada com o brilho no máximo, travando a imagem no lugar e bloqueando todos os toques até você terminar.

Além disso, há um conjunto decente de ferramentas de design pertinentes, com suporte para criação gráfica em várias camadas. Eu poderia continuar, mas sinto que já falei bastante.

**GraffitiXR** é um aplicativo Android offline para artistas de rua. Ele usa AR para projetar imagens em paredes utilizando um sistema de mapeamento de voxels baseado em confiança.

## Principais Recursos
*   **Primeiro Offline:** Sem dependências da nuvem; zero dados coletados.
*   **Motor Personalizado (MobileGS):** Motor nativo em C++17 para Gaussian Splatting 3D e mapeamento espacial.
*   **Pipeline Completo do ARCore:** Feed da câmera ao vivo via `BackgroundRenderer`, relocalização de quadros de cor e ARCore Depth API — todos alimentando o motor SLAM com dados reais.
*   **Interface AzNavRail:** Navegação acionada pelo polegar para uso com uma mão em campo.
*   **Caminho Único de Renderização GL:** `ArRenderer` lida tanto com o fundo da câmera (`BackgroundRenderer`) quanto com os splats de voxel SLAM (`slamManager.draw()`) em uma única `GLSurfaceView`.
*   **Suporte a Múltiplas Lentes:** Usa automaticamente profundidade estéreo de câmera dupla em dispositivos suportados; reverte para fluxo óptico quando necessário.
*   **Correção Teleológica:** Alinhamento automático do mapa para o mundo usando identificação do OpenCV.

## Arquitetura
Arquitetura multi-módulo estritamente desacoplada:
*   `:app` — Navegação, combinável `ArViewport`, orquestração de propriedade da câmera.
*   `:feature:ar` — Sessão do ARCore, `ArRenderer`, `BackgroundRenderer`, fusão de sensores, alimentação de dados SLAM.
*   `:feature:editor` — Manipulação de imagem, gerenciamento de camadas.
*   `:feature:dashboard` — Biblioteca de projetos, configurações.
*   `:core:nativebridge` — Ponte JNI do `SlamManager`, motor de voxel `MobileGS`, renderização OpenGL ES.
*   `:core:data` / `:core:domain` / `:core:common` — Camada de dados Clean Architecture.

## Documentação
- [Visão Geral da Arquitetura](docs/ARCHITECTURE.md)
- [Detalhes do Motor Nativo](docs/NATIVE_ENGINE.md)
- [Configuração e Ajuste do SLAM](docs/SLAM_SETUP.md)
- [Especificação do Pipeline 3D](docs/PIPELINE_3D.md)
- [Estratégia de Testes](docs/testing.md)
- [Referência de Telas e Modos](docs/screens.md)

---
*Documentação atualizada em 2026-03-17 durante a fase de redesign do site e integração do Modo Estêncil.*