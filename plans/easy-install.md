# Plano: instalar e fazer o debloat sem IA

Status: para o futuro (anotado em 2026-10-21). Nada disso foi feito ainda.

## Onde está o trabalho hoje

O debloat já é feito pelo próprio app, na TV: quando a pessoa troca para *Free My TV* em *Início*, o app usa a
depuração da própria TV (ADB em 127.0.0.1) para desligar os apps. O que ainda exige computador ou IA:

1. **Compilar:** JDK, Android SDK e o `config.properties`.
2. **Instalar:** `adb install` a partir de um computador.
3. **TCL:** `appops set io.freemytv AUTO_START allow` (ou `APP_AUTO_START`) pelo adb, sem o qual a troca não roda.

## Passos

### 1. APK pronto (GitHub Actions + Releases)

- Workflow que roda `./build.sh` a cada tag `v*` e publica o `FreeMyTV.apk` em *Releases*.
- `freemytv.keystore` e as senhas dele vão nos *Secrets* do repositório (quem cola é o dono do repositório, nunca no
  código), para que as atualizações sejam assinadas com a mesma chave e instalem por cima.
- O APK não pode depender de `config.properties`. Quase tudo já se ajusta na TV (chave do TMDB, cidade, idioma, moeda).
  Falta:
  - `tmdb_region`: vir do país da TV (`Locale.getDefault().getCountry()`), com opção em *Mais ajustes*;
  - `disable_assistant`: virar uma opção em *Mais ajustes*;
  - `temperature` e `show_crypto`: padrão pelo país, ajustável na TV.

### 2. Instalar pela própria TV

- App **Downloader** (Play Store) e um link curto, por exemplo `freemytv.io/apk`, que redireciona para o APK da última
  release.
- A TV pede para permitir "fontes desconhecidas" para o Downloader na primeira vez.

### 3. O passo da TCL feito pelo app

- O app já fala com o ADB da TV (`Adb.java`). Antes de agendar a troca (`ModeJob`), rodar pelo próprio app o mesmo
  `appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow`, só em TVs TCL.
- Tem que rodar em primeiro plano (na tela de ajustes), porque o `TclAppBoot` bloqueia o job em segundo plano.

### 4. Guia no freemytv.io (pt e en)

Passo a passo só com o controle:

1. Ativar as Opções do desenvolvedor (Configurações → Sistema → Sobre → tocar 7 vezes em "Build").
2. Ligar a Depuração USB.
3. Instalar pelo Downloader com o link curto.
4. Abrir o Free My TV → *Início* → *Free My TV* e aceitar *Permitir depuração* (marcar *Sempre permitir*).
5. Opcional: digitar a chave do TMDB em *Mais ajustes* → *Chave do TMDB*.

`docs/index.html` e `docs/pt/index.html` mudam juntos.

### 5. Opcional: script de um clique

`install.ps1` (Windows) e `install.sh` (macOS/Linux): baixa o platform-tools, pergunta o IP da TV, `adb connect`,
baixa o APK da última release, `adb install -r` e, em TCL, o `appops`.

## Ordem recomendada

1 + 3 + 4 primeiro (a instalação fica toda na TV); o script depois.

## Decisões em aberto

- Publicar as releases no fork (`Arthur997/free-my-tv-nuvio`) ou propor ao projeto original.
- Quem cuida dos *Secrets* com a chave de assinatura.
