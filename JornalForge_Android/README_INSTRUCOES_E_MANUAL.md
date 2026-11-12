# JornalForge Android (APK) — Documentação Técnica e Manual

## 1. Como gerar o arquivo `.apk` instalável (2 Opções)
- **Opção A (Na Nuvem, sem instalar nada no PC):** Suba a pasta deste projeto para um repositório no GitHub. O workflow `.github/workflows/build_apk.yml` já está configurado para baixar automaticamente o modelo `pt_BR-faber-medium.onnx` oficial, compilar o projeto com Android SDK 34 e disponibilizar o `app-release.apk` assinado na aba **Actions -> Artifacts**.
- **Opção B (Local no Android Studio):**
  1. Abra a pasta `JornalForge_Android` no Android Studio Iguana ou superior.
  2. (Opcional para 100% ONNX offline embutido) Coloque o arquivo `pt_BR-faber-medium.onnx` dentro de `app/src/main/assets/piper/`.
  3. Vá em **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## 2. Instruções para trocar o modelo de voz
- Substitua os arquivos em `app/src/main/assets/piper/pt_BR-faber-medium.onnx` e `pt_BR-faber-medium.onnx.json`, ou copie um novo modelo `.onnx` para a pasta privada do app (`files/piper/`) mantendo a constante `MODEL_NAME` em `PiperTtsEngine.kt`.

## 3. Lista de Permissões Android (`AndroidManifest.xml`)
- `FOREGROUND_SERVICE` e `FOREGROUND_SERVICE_MEDIA_PROCESSING`: Mantém a renderização e codificação FFmpeg ativas mesmo com a tela bloqueada para vídeos de 4 a 10 minutos.
- `POST_NOTIFICATIONS`: Exibe a barra de progresso real por fase.
- `WAKE_LOCK`: Evita suspensão da CPU durante a codificação H.264.
- Sem permissões amplas invasivas: utiliza **Storage Access Framework (SAF)** para leitura de `.json`/`.txt` e **MediaStore** para salvar o MP4 em `Movies/JornalForge`.
