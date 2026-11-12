# BaixaFotos

App Android para baixar fotos da internet direto para a galeria do celular.

## O que ele faz
- Você cola um ou vários links de imagens (um por linha) e toca em **Baixar fotos**.
- As fotos vão para a galeria, no álbum **BaixaFotos** (pasta `Pictures/BaixaFotos`).
- Botão **Colar link** pega os links da área de transferência.
- No navegador (ou em outro app), use **Compartilhar → BaixaFotos** para mandar o link direto para o app.
- Mostra a prévia da última foto baixada e avisa quais links falharam (ex.: link que não é imagem).
- Aceita JPG, PNG, GIF, WebP, BMP, HEIC e AVIF.
- Funciona no Android 8.0 ou mais novo. Não usa bibliotecas externas.

## Como gerar o APK (sem computador, pelo GitHub)
1. Crie um repositório no GitHub e envie todos os arquivos desta pasta (inclusive a pasta `.github`).
2. Abra a aba **Actions** do repositório. A compilação "Gerar APK" começa sozinha (leva uns 3–5 minutos).
3. Quando terminar (✔ verde), abra a execução e baixe o arquivo **BaixaFotos-apk** em *Artifacts*.
4. Descompacte o .zip no celular e toque no `app-release.apk`. O Android vai pedir para permitir
   "instalar apps desta fonte" — permita e instale.

## Como gerar o APK no computador (Android Studio)
1. Abra a pasta `BaixaFotos` no Android Studio (File → Open).
2. Espere a sincronização do Gradle terminar.
3. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, ou conecte o celular e clique em ▶ Run.

## Estrutura
- `app/src/main/java/com/eraldo/baixafotos/MainActivity.kt` — toda a lógica (download e gravação na galeria)
- `app/src/main/res/layout/activity_main.xml` — a tela
- `app/src/main/res/values/strings.xml` — os textos (em português)
