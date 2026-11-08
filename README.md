<div align="center">

# JOTV — Android

### Um Netflix caseiro, construído sobre o protocolo de addons do Stremio

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/minSdk-23-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Hilt](https://img.shields.io/badge/DI-Hilt-00C2A8)](https://dagger.dev/hilt/)
[![Media3](https://img.shields.io/badge/Player-Media3%20ExoPlayer-FF6D00)](https://developer.android.com/media/media3)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Um único app Kotlin/Compose para **celular, tablet e Android TV**, com busca de metadados no TMDB
e fontes de vídeo vindas de addons no padrão [Stremio](https://stremio.github.io/stremio-addon-guide/).

[Instalação](#instalação) · [Funcionalidades](#funcionalidades) · [Arquitetura](#arquitetura) · [Como compilar](#como-compilar) · [Configuração](#configuração) · [Estrutura](#estrutura-do-projeto)

</div>

---

## Sobre

JOTV é um app de streaming completo pensado pra quem já tem uma coleção de addons Stremio e
quer assistir numa interface de verdade — sem navegador, sem extensão, com layout adaptado pra
tela de toque **e** pra controle remoto na TV, no mesmo código-fonte.

Ele não hospeda nem distribui conteúdo nenhum: busca metadados (sinopse, elenco, pôster) no
**TMDB** e resolve links de vídeo através de addons Stremio que o próprio usuário instala. O app
é só o player e a interface.

## Instalação

Pegue o `.apk` pronto em **[Releases](../../releases)** — não precisa compilar nada. Se preferir
compilar você mesmo, veja [Como compilar](#como-compilar).

### Celular ou tablet Android
1. Baixe o `.apk` da Release direto no aparelho.
2. Abra o arquivo baixado. Na primeira vez o Android avisa que precisa liberar "instalar app de
   fonte desconhecida" para o app que você usou pra baixar (navegador, Arquivos...) — libere e
   confirme a instalação.

### Android TV / Google TV — qualquer marca
Vale pra **Sony, TCL, Philips, Hisense, Xiaomi, NVIDIA Shield, Chromecast com Google TV, Fire TV**
e qualquer outro aparelho que rode Android TV ou Google TV. Essas TVs não têm o JOTV na loja
oficial, então a instalação é por **sideload** (instalar um APK avulso). Dois jeitos:

- **Pelo computador, com ADB** (mais confiável): na TV, ative a depuração (em geral
  **Configurações → Aparelho → Sobre**, toque várias vezes na versão do sistema até liberar as
  "Opções do desenvolvedor", depois ligue "Depuração USB" ou "Depuração de rede"). No PC, com o
  [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
  instalado:
  ```sh
  adb connect <ip-da-tv>:5555
  adb install app-release.apk
  ```
- **Direto na TV, sem computador**: instale um app tipo "Send Files to TV" ou "Downloader"
  (disponíveis na própria loja de apps da TV), baixe o `.apk` por ele e instale.

### Samsung Smart TV (Tizen)
Samsung não roda Android, então o `.apk` não serve aqui. Existe uma versão nativa própria do
JOTV pra Tizen, empacotada como `.wgt`, pegue o arquivo `JOTV-Tizen-*.wgt` da mesma
[Release](../../releases).

1. Na TV, ligue o modo desenvolvedor (Smart Hub > Apps, selecione qualquer app instalado e
   digite **1 2 3 4 5** em sequência no controle). Anote o IP que aparece na tela.
2. No computador, instale o [Tizen Studio](https://samsungtizenos.com/tools-download/)
   (a opção "CLI installer" já basta, não precisa da IDE gráfica completa). Isso traz o `sdb`,
   usado pra instalar o pacote na TV.
3. Conecte e instale.
   ```sh
   sdb connect <ip-da-tv>:26101
   sdb install JOTV-Tizen-v1.3.4.wgt
   ```

O `.wgt` já sai assinado com um certificado de desenvolvedor, suficiente pra instalar em modo
desenvolvedor numa TV própria. Não serve pra publicar na Samsung Apps TV Store, só pra uso
pessoal via sideload.

### LG Smart TV (webOS)
Mesma ideia, versão nativa própria em `.ipk`. Pegue `JOTV-webOS-*.ipk` da mesma
[Release](../../releases) e siga o passo a passo de instalação pela própria TV (LG Content
Store > Developer Mode).

### Roku, Apple TV, ou qualquer outra TV sem Android, webOS ou Tizen
Essas não têm versão nativa do JOTV. O jeito de usar o app numa tela assim é plugar um aparelho
Android TV na entrada HDMI (um Chromecast com Google TV, um TV Box Android qualquer, um Fire TV
Stick) e instalar o app nele, do jeito da seção acima (a TV vira só o monitor, quem roda o app
é o aparelho plugado).

## Funcionalidades

### Catálogo e descoberta
- Início com destaque em tela cheia (com trailer tocando sozinho depois de alguns segundos parado), Continuar Assistindo, Novos Episódios, recomendados e fileiras de cada addon instalado
- Descobrir com filtro por gênero, ano, nota e popularidade — grade própria para TV
- Busca por título com histórico das últimas pesquisas
- Perfis múltiplos (como o seletor "Quem está assistindo?"), cada um com a própria Minha Lista, histórico e progresso

### Player
- Interface própria sobre o ExoPlayer (Media3): velocidade, qualidade, múltiplas faixas de áudio e legenda
- **Prévia em miniatura ao arrastar a barra**, tipo YouTube, extraída do próprio vídeo quando a fonte permite
- **Som de outra fonte sincronizado** com a imagem — assista a versão legendada ouvindo o áudio dublado de outra fonte, no mesmo player
- Aprendizado automático da abertura (pula sozinho nos episódios seguintes depois que você pula uma vez)
- Chromecast (transmitir pra TV) e Picture-in-Picture
- `MediaSession` — controle pela tela bloqueada, fone Bluetooth e Google Assistant — e foco de áudio do sistema (pausa em ligação, para ao tirar o fone)
- Legenda e idioma de áudio preferido lembrados entre episódios e sessões

### Addons (protocolo Stremio)
- Gerenciador de addons: instala por URL de manifest, reordena por prioridade, ativa/desativa
- Loja com catálogo da comunidade e captura automática do link de configuração de addons que exigem isso
- QR code pra configurar o addon no celular e levar o link pronto pra TV
- Instalação sempre manual — o app **não vem com addon nenhum pré-instalado**

### Perfil infantil
- Perfil infantil filtra conteúdo adulto automaticamente
- **PIN dos pais** trava sair do infantil e abrir Addons, a chave do TMDB e as opções de aparelho enquanto o infantil está ativo

### Multiplataforma de verdade
- Mesmo código reconhece **celular, tablet e TV** (ou deixa forçar o modo manualmente)
- Layout de TV com menu lateral que desliza por cima do conteúdo com opacidade, navegação 100% por D-pad e foco restaurado ao voltar de uma tela
- Primeira abertura pede só o nome do perfil e confirma a plataforma detectada (automática por padrão)

### Integrações
- **TMDB** — metadados, pôsteres, elenco e coleções (chave própria configurável nas Configurações)
- **Trakt.tv** (opcional) — sincroniza Minha Lista, histórico e scrobble de progresso
- **DNS-over-HTTPS (Cloudflare)** nas chamadas de vídeo, pra contornar bloqueio de DNS de operadora
- Download para assistir offline

## Arquitetura

```
UI (Jetpack Compose)  →  ViewModel (Hilt)  →  Repository  →  Room (local) / Retrofit (TMDB) / addons Stremio (OkHttp)
```

- **UI**: Jetpack Compose + Material 3, com `androidx.tv` (`tv-foundation`/`tv-material`) para o
  layout de TV. Uma única árvore de composables se adapta a celular, tablet e TV lendo o
  [`DeviceLayout`](app/src/main/java/com/joabtv/app/ui/common/DeviceLayout.kt) atual.
- **Injeção de dependência**: Hilt, módulos em [`di/`](app/src/main/java/com/joabtv/app/di).
- **Persistência local**: Room (perfis, Minha Lista, histórico, episódios vistos, downloads),
  com migrações explícitas a cada mudança de schema.
- **Preferências**: `SharedPreferences` encapsuladas em
  [`PlatformPrefs`](app/src/main/java/com/joabtv/app/data/local/PlatformPrefs.kt), com chaves
  escopadas por perfil quando faz sentido.
- **Rede**: Retrofit + OkHttp para o TMDB e o Trakt; um cliente OkHttp próprio com
  DNS-over-HTTPS para os links de vídeo dos addons.
- **Player**: Media3 (ExoPlayer) com `MediaSession`, suporte a Cast e extração de trailers do
  YouTube com embed bloqueado via NewPipeExtractor.

## Como compilar

Isso aqui é pra quem quer mexer no código. Pra só instalar e usar, veja [Instalação](#instalação).

### Pré-requisitos
- [Android Studio](https://developer.android.com/studio) (Ladybug ou mais recente) ou só o JDK 17 + Gradle, se preferir linha de comando
- **JDK 17** — é a versão que o Kotlin 2.1.20/AGP 8.7.0 deste projeto esperam. Com um JDK bem
  mais novo (21+ já funciona, mas 23+ pode não) como padrão do sistema, o Gradle falha ao
  avaliar os scripts Kotlin DSL com algo como `IllegalArgumentException: 26.0.1`. Se isso
  acontecer, aponte o Gradle pro JBR do Android Studio (ou pra um JDK 17/21 instalado à parte)
  num `gradle.properties` local (não versionado) com `org.gradle.java.home=<caminho-do-jdk>`,
  ou exporte `JAVA_HOME` antes de rodar `./gradlew`.
- Um dispositivo ou emulador Android 6.0+ (API 23) — ou um Android TV/Google TV pra testar o layout de TV

### Clonar e configurar

```sh
git clone <url-deste-repositório>
cd android
```

Crie um `local.properties` na raiz (esse arquivo não entra no controle de versão) com, no mínimo:

```properties
sdk.dir=/caminho/para/seu/Android/Sdk
```

Veja [Configuração](#configuração) para a chave do TMDB e o Trakt (opcionais, mas sem a chave do
TMDB o app te pede uma na primeira vez que abrir).

### Build

```sh
# Debug, instala direto num dispositivo conectado
./gradlew installDebug

# Release (minificado com R8, assinado com a chave de debug — troque por um keystore próprio
# antes de publicar de verdade, veja app/build.gradle.kts)
./gradlew assembleRelease

# Testes unitários
./gradlew testDebugUnitTest
```

O APK de release sai em `app/build/outputs/apk/release/`.

## Configuração

| Propriedade | Onde colocar | Obrigatória? |
|---|---|---|
| `TMDB_API_KEY` | `local.properties` ou `~/.gradle/gradle.properties` | Não — sem ela, o app pede a chave pessoal em **Configurações → TMDB** na primeira execução |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` | `local.properties` | Não — sem elas, a seção do Trakt fica escondida nas Configurações |

Chave do TMDB em [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api) (gratuita).
App do Trakt em [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications).

Os addons de vídeo (protocolo Stremio) são instalados **dentro do app**, em
**Configurações → Addons**, por URL de manifest — nenhum vem pré-configurado.

## Estrutura do projeto

```
app/src/main/java/com/joabtv/app/
├── data/
│   ├── local/        # Room (DAOs, entidades, migrações) e PlatformPrefs
│   ├── remote/        # TMDB, Trakt, addons Stremio, DNS-over-HTTPS
│   └── repository/     # Camada que a UI consome (biblioteca, downloads)
├── di/                # Módulos Hilt
├── domain/model/      # Modelos de domínio (filme, série, episódio, elenco)
└── ui/
    ├── common/         # Componentes e navegação compartilhados (foco de TV, layout adaptativo)
    ├── movies/          # Catálogo, detalhe, player
    ├── navigation/      # NavHost e menu lateral da TV
    ├── settings/        # Configurações, addons, perfis, PIN
    ├── theme/           # Cores e tipografia
    └── tv/               # Telas e componentes exclusivos da TV
```

## Roteiro

Coisas conhecidas que ainda não entraram:

- Progresso de reprodução por episódio (hoje é uma entrada de histórico por série)
- Exportar/importar backup de addons, perfis e Minha Lista
- Busca por voz e integração com o Watch Next do Google TV

## Aviso legal

Este app não hospeda, distribui nem armazena nenhum conteúdo de vídeo. Ele é um cliente para
addons de terceiros no protocolo Stremio, que o próprio usuário escolhe instalar. O uso e a
legalidade das fontes acessadas por esses addons são de responsabilidade de quem os instala.

Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.

## Licença

[Apache License 2.0](LICENSE).
