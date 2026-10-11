# Política de Privacidade do GraffitiXR

**Última Atualização:** 2026-09-04

A HereLiesAZ criou o aplicativo GraffitiXR como um aplicativo de código disponível (source-available) (consulte o LICENSE do repositório e docs/LICENSING.md para os termos). Este SERVIÇO é fornecido pela HereLiesAZ sem nenhum custo e destina-se a ser usado no estado em que se encontra.

Esta página é usada para informar os visitantes sobre nossas políticas com relação à coleta, uso e divulgação de Informações Pessoais, caso alguém decida usar nosso Serviço.

Se você optar por usar nosso Serviço, você concorda com a coleta e uso de informações em relação a esta política. As Informações Pessoais que coletamos são usadas para fornecer e melhorar o Serviço. Não usaremos nem compartilharemos suas informações com ninguém, exceto conforme descrito nesta Política de Privacidade.

## Coleta e Uso de Informações

Para uma melhor experiência, ao usar nosso Serviço, podemos exigir que você nos forneça certas informações de identificação pessoal, incluindo, mas não se limitando a:

*   **Dados da Câmera:** O aplicativo requer acesso à câmera do seu dispositivo para exibir a visualização de Realidade Aumentada (AR), o modo de Sobreposição e para capturar imagens para alvos do projeto. Os dados da câmera são processados localmente em seu dispositivo e não são transmitidos aos nossos servidores, a menos que você escolha explicitamente compartilhar um arquivo de projeto.
*   **Armazenamento / Fotos:** O aplicativo não solicita nenhuma permissão de armazenamento. As imagens que você adiciona chegam pelo seletor de fotos ou de arquivos do sistema Android, que dão ao aplicativo acesso apenas ao arquivo que você escolhe. Os projetos são salvos no armazenamento privado do aplicativo, e as imagens exportadas são salvas na galeria de fotos do seu dispositivo.
*   **Dados de Localização (Opcional):** O aplicativo só pede a permissão de localização quando você começa a capturar um alvo de AR. Se você a conceder, o registro de captura desse alvo armazena onde ele foi capturado (latitude, longitude, altitude e precisão). Ele é salvo localmente no arquivo do projeto e só sai do seu dispositivo se você mesmo compartilhar ou exportar esse arquivo de projeto.

## Relatórios de Falha (Opt-In)

Nada sobre uma falha sai do seu dispositivo por padrão. Se o aplicativo travar ou se recuperar de um erro interno, um relatório é gravado em um arquivo local e temporário no seu dispositivo, para que o aplicativo possa exibir um aviso de "a última execução travou" na próxima vez que você o abrir — esse arquivo nunca sai do dispositivo por conta própria.

Enviar esse relatório para nós está desativado, a menos que você mesmo o ative, em **Configurações > Relatórios de falha**. Somente se — e apenas se — você tiver optado por participar, o aplicativo envia o relatório na próxima vez que iniciar, como uma **issue pública** no rastreador de issues do GitHub deste projeto (github.com/HereLiesAz/GraffitiXR). O relatório contém apenas:

*   se a falha foi fatal (o aplicativo foi encerrado) ou recuperada (capturada, com o aplicativo continuando em execução);
*   a data e a hora da falha;
*   o fabricante e o modelo do seu dispositivo, e sua versão do Android;
*   o nome da versão do aplicativo;
*   o stack trace da exceção; e
*   até as últimas 1.000 linhas da saída do logcat do próprio aplicativo (limitada ao processo deste aplicativo — não a logs de todo o sistema).

Como o relatório é registrado como uma issue pública no GitHub, seu conteúdo (incluindo as informações de dispositivo e log acima) fica visível para qualquer pessoa que possa ver o rastreador de issues deste projeto. Ative os relatórios de falha somente se você estiver confortável com isso. Você pode desativar essa opção novamente a qualquer momento, e isso não afetará falhas que já ocorreram.

## Provedores de Serviço

Podemos empregar empresas de terceiros e indivíduos pelos seguintes motivos:

*   Para facilitar nosso Serviço;
*   Para fornecer o Serviço em nosso nome;
*   Para realizar serviços relacionados ao Serviço; ou
*   Para nos auxiliar na análise de como nosso Serviço é usado.

Nós usamos o **Google ML Kit** para segmentação de objetos (remoção de fundo). Esse processamento ocorre localmente em seu dispositivo.

## Atualizações

O aplicativo nunca contata um servidor para verificar atualizações. A tela de Configurações tem uma entrada "Abrir a página de versões"; tocá-la abre no seu navegador a página de versões do GitHub deste projeto (github.com/HereLiesAz/GraffitiXR/releases), exatamente como se você tivesse seguido o link. O aplicativo em si não envia nada.

## Segurança

Valorizamos sua confiança em nos fornecer suas Informações Pessoais, portanto, estamos nos esforçando para usar meios comercialmente aceitáveis de protegê-las. Mas lembre-se que nenhum método de transmissão pela internet ou método de armazenamento eletrônico é 100% seguro e confiável, e não podemos garantir sua segurança absoluta.

## Links para Outros Sites

Este Serviço pode conter links para outros sites. Se você clicar em um link de terceiros, você será direcionado para esse site. Observe que esses sites externos não são operados por nós. Portanto, aconselhamos fortemente que você reveja a Política de Privacidade desses sites. Não temos controle e não assumimos qualquer responsabilidade pelo conteúdo, políticas de privacidade ou práticas de quaisquer sites ou serviços de terceiros.

## Privacidade Infantil

Estes Serviços não se dirigem a menores de 13 anos. Não coletamos intencionalmente informações de identificação pessoal de crianças menores de 13 anos. Caso descubramos que uma criança menor de 13 anos nos forneceu informações pessoais, excluímos isso imediatamente de nossos servidores. Se você é um pai ou responsável e tem conhecimento de que seu filho nos forneceu informações pessoais, entre em contato conosco para que possamos tomar as ações necessárias.

## Mudanças nesta Política de Privacidade

Podemos atualizar nossa Política de Privacidade de tempos em tempos. Portanto, é aconselhável rever esta página periodicamente para quaisquer alterações. Iremos notificá-lo sobre quaisquer alterações publicando a nova Política de Privacidade nesta página.

Esta política é efetiva a partir de 2024-01-01.

## Fale Conosco

Se você tiver alguma dúvida ou sugestão sobre nossa Política de Privacidade, não hesite em nos contatar em https://github.com/HereLiesAz/GraffitiXR/issues.


---
*Documentação atualizada em 2026-09-04: corrigida a seção de Dados de Registro, que descrevia a coleta de dados de falha como automática e sem consentimento — o aplicativo, na verdade, exige um opt-in explícito (Configurações > Relatórios de falha, desativado por padrão) antes que qualquer relatório de falha saia do dispositivo, e o relatório é registrado como uma issue pública no GitHub, não enviado a "nossos servidores". A divulgação existente da verificação de atualizações via API do GitHub ganhou sua própria seção e foi esclarecido que ela é iniciada pelo usuário (botão "Verificar atualizações" em Configurações), não automática. Corrigida a descrição de licenciamento (o aplicativo é de código disponível, não de código aberto) e preenchidos os placeholders de data e contato. Atualização anterior: 2026-03-17, durante a fase de redesign do site e integração do Modo Estêncil (o Modo Estêncil foi removido do aplicativo desde então).*