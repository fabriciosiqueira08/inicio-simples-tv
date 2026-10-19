# Início Simples

Tela inicial leve para Android TV / Google TV: sem anúncios, sem recomendações, sem serviço em segundo plano. Só um relógio, uma saudação, uma vitrine do app em foco e a grade com todos os apps.

Feita para uma Philips 2K com Google TV (Android 11), mas roda em qualquer Android TV com Android 8 ou mais novo.

## Como é

![Tela inicial rodando na TV](docs/tela.png)

- **Vitrine:** o app em foco aparece grande no topo (nome + banner), e o fundo ganha um brilho com a cor dominante do banner.
- **Relógio de mostrador:** hora, dois-pontos em ember, minuto leve, dia da semana e data.
- **Saudação** que muda com a hora (bom dia / boa tarde / boa noite), com nomes opcionais.
- **Grade 16:9** em ordem alfabética, com "Mais usados" no topo assim que você começa a abrir apps. O cartão em foco cresce e ganha um anel ember.
- **Rolagem por fileira:** a grade só para em fileira inteira, nunca corta um cartão embaixo da vitrine.
- Fonte Ubuntu, movimento curto (150–320 ms, expo-out) e só quando o foco muda.

Tudo em Java puro, um punhado de arquivos, sem Gradle, sem AndroidX, sem dependências.

## Compilar

Precisa do Android SDK (build-tools + uma plataforma ≥ 30) e de um JDK 17.

```bash
cd launcher
ANDROID_HOME=~/Android/Sdk JAVA_HOME=/caminho/do/jdk-17 ./build.sh
```

Na primeira vez o `build.sh` cria uma chave de assinatura `chave.jks`. **Guarde essa chave**: sem ela, uma versão nova não instala por cima da antiga. Também dá para apontar para uma chave existente com `KEYSTORE=/caminho/chave.jks`.

## Instalar e definir como tela inicial

Na TV: Configurações → Sistema → Sobre → clique 7× no item de build do sistema → em Opções do desenvolvedor, ative a depuração USB (e a depuração por rede, se houver).

```bash
adb connect IP_DA_TV:5555            # aceite o aviso na TV
./scripts/backup.sh IP_DA_TV:5555    # ANTES de mexer em qualquer coisa
adb install -r launcher/tela-inicial.apk
adb shell cmd package set-home-activity --user 0 br.simples.home/.Home
```

### Google TV: por que só isso não basta

O launcher do Google TV (`com.google.android.apps.tv.launcherx`) declara prioridade 2 no filtro HOME. O Android só consulta a tela inicial preferida quando as candidatas têm a mesma prioridade, então ele sempre vence. O segundo da fila é o assistente de configuração (`com.google.android.tungsten.setupwraith`, prioridade 1), cuja tela de "recuperação" religa o launcher do Google.

Para o Início Simples assumir, desative os dois, nessa ordem:

```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell input keyevent KEYCODE_HOME
```

Nada é desinstalado: `pm disable-user` só desativa, e o `restaurar.sh` desfaz.

## Nomes na saudação

Os nomes ficam salvos no aparelho, não no código:

```bash
adb shell am start -n br.simples.home/.Home --es nomes "'Ana e Beto'"   # "Boa noite, Ana e Beto"
adb shell am start -n br.simples.home/.Home --es nomes "''"             # volta a só "Boa noite"
```

## Limpeza opcional

O que foi desativado na Philips/KTC com Google TV, um `pm disable-user --user 0 <pacote>` por vez. Nunca desinstale app de sistema.

| Categoria | Pacotes |
|---|---|
| Telemetria | `com.google.android.feedback`, `com.google.android.partnersetup` |
| Anúncios / recomendações | `com.realtek.leanbacklauncher.partnercustomizer` |
| Serviços pouco usados | `com.realtek.storemode` (modo loja), `com.vewd.core.service`, `com.hk.gingaplayer.huangs` (Ginga), `com.google.android.play.games`, `com.android.printspooler`, `com.kgzn.serialport` (teste de fábrica), `com.electronic.manual` |
| Depende de você | `com.google.android.apps.mediashell` (Chromecast), `com.google.android.katniss` (Assistente), `com.realtek.miracast`, `com.rtk.mediabrowser` (player de pendrive), apps de streaming |

Não mexa em `com.google.android.gms` (atualização do sistema vem por ele) nem nas entradas de TV da Realtek (`com.realtek.tv.*`, `com.realtek.dtv`). No Brasil a antena usa `com.realtek.tv.isdb`.

## Desfazer tudo

```bash
./scripts/restaurar.sh backup-AAAA-MM-DD IP_DA_TV:5555
```

Reativa todo app que estava ativo no backup e devolve a tela inicial do Google TV. Para reativar um app só:

```bash
adb shell pm enable --user 0 <pacote>
adb shell pm list packages -d    # lista os desativados
```

## Licenças

A fonte Ubuntu é distribuída sob a [Ubuntu Font Licence 1.0](launcher/assets/fonts/UFL.txt).
