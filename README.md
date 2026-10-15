# PUNK Companion v2.1 ⚡
> **The Sovereign Autonomous Android Bridge & Neural Telemetry Suite**
> Desenvolvido para transformar qualquer dispositivo Android moderno (testado e otimizado para Samsung Galaxy S23 Ultra - Android 16) em uma extensão autônoma, sensorial e executiva para Agentes de IA (Hermes Agent / JARVIS).

[![Android](https://img.shields.io/badge/Platform-Android%2014--16%20(One%20UI%206--8)-green.svg)](https://developer.android.com/)
[![Shizuku](https://img.shields.io/badge/Privilege-Shizuku%20UID%202000-blue.svg)](https://shizuku.rikka.app/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%201.9-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-black.svg)](LICENSE)

---

## 🎯 O que é o PUNK Companion e Para Que Serve?

O **PUNK Companion** não é um aplicativo comum de controle remoto. Ele foi desenhado como o **"corpo físico e sensorial"** de um assistente de inteligência artificial autônomo (JARVIS / Hermes Agent).

Enquanto LLMs e agentes de IA geralmente ficam confinados a servidores, navegadores e terminais, o **PUNK Companion** estabelece uma ponte bidirecional contínua de baixíssima latência (WebSocket + REST) entre o cérebro da IA e o hardware do smartphone.

Com ele, o agente adquire:
* **Olhos:** Visão remota em tempo real via captura de tela em alta definição e fotos silenciosas através das câmeras física frontal e traseira (`Camera2`).
* **Voz e Audição:** Síntese de fala imediata (`TTS`), reconhecimento de voz e escuta em streaming de alta fidelidade.
* **Tato e Coordenação Motora:** Capacidade de navegar em qualquer aplicativo através de cliques (`tap`), gestos (`swipe`), rolagem (`scroll`) e digitação (`input_text`), combinando acessibilidade nativa com permissões de nível Shell ADB via Shizuku.
* **Senso Espacial e Contextual:** Coordenadas GPS em tempo real com resolução reversa de endereço (rua, bairro, cidade), telemetria de bateria, Wi-Fi, sensores e notificações.

---

## 🚀 Arsenal de Funcionalidades Implementadas

### 1. 👁️ Visão Computacional Remota (Multimodal Vision)
- **`screen_capture_b64`**: Captura a tela atual do dispositivo em resolução nativa (com pipeline otimizado para telas QHD+ de 1440p) e retorna diretamente em Base64 para inspeção visual imediata por modelos multimodais (Gemini Flash, Claude, GPT-4o).
- **`camera_capture`**: Dispara capturas em segundo plano com seleção de lente (`facing: "front"` ou `"back"`), resolução de até 1080p, foco e exposição automáticos sem necessidade de interromper o usuário.

### 2. 👆 Computer-Use Tátil & Ações do Sistema
- **`input_tap`**: Realiza toques precisos por coordenadas `(x, y)` na tela.
- **`input_swipe`**: Executa arrastos direcionais suaves (scroll em feeds, swipe de notificações, navegação por carrossel).
- **`input_text`**: Injeta texto formatado ou strings complexas diretamente em campos focados.
- **`global_action`**: Aciona botões do sistema operacional Android (`home`, `back`, `recents`, `notifications`, `lock_screen`) com dupla camada de segurança: via *AccessibilityService* ou via *Shizuku ADB Keyevent*.

### 3. 📍 Geolocalização & Telemetria
- **`get_location`**: Fusão dos provedores GPS e Network em tempo real com geocodificação reversa nativa em português (`pt-BR`), informando latitude, longitude, precisão, altitude, logradouro, município e estado.
- **`dumpsys` & `shell_exec`**: Acesso completo ao subsistema Android via Shizuku (`UID 2000`), permitindo monitorar o estado da bateria, tráfego de rede, processos ativos, memória e logs do sistema.

### 4. 🗣️ Áudio, Voz e Interação Humana
- **`tts_speak`**: Motor de síntese de fala local e imediata na língua portuguesa com controle de volume, tom e velocidade.
- **`audio_record`**: Gravação de áudio do microfone do dispositivo comprimido em AAC/Base64 para transcrição e análise acústica remota.
- **`flashlight`**: Controle da lanterna LED traseira (ligar, desligar, pulso SOS).
- **`vibrate`**: Padrões táteis de vibração háptica para alertas do assistente.
- **`toast` & `send_notification`**: Exibição de avisos flutuantes e notificações ricas na barra de status do sistema.

### 5. 📱 Telecomunicações & Área de Transferência
- **`dial_phone`**: Discagem rápida e chamadas telefônicas diretas (`ACTION_CALL`).
- **`send_sms`**: Envio de mensagens de texto SMS diretamente pelo modem do smartphone.
- **`clipboard_get` & `clipboard_set`**: Sincronização em tempo real da área de transferência entre a máquina do agente e o smartphone.

---

## 🏗️ Arquitetura do Sistema

```
┌────────────────────────────────────────────────────────┐
│                   Hermes Agent / JARVIS                │
│                 (PC / Servidor / Nuvem)                │
└──────────────────────────┬─────────────────────────────┘
                           │ HTTP REST / WebSocket (Portas 8765 / 8766)
                           ▼
┌────────────────────────────────────────────────────────┐
│             punk_server.py (Bridge Daemon)             │
│        Buffer expandido para 32MB (QuadHD Vision)      │
└──────────────────────────┬─────────────────────────────┘
                           │ Protocolo PUNK WebSocket
                           ▼
┌────────────────────────────────────────────────────────┐
│              PUNK Companion Android App                │
│                                                        │
│  ┌──────────────────┐            ┌──────────────────┐  │
│  │   core-bridge    │◄───────────┤   core-tools     │  │
│  │ (WS / KeepAlive) │            │  (ToolRegistry)  │  │
│  └────────┬─────────┘            └────────┬─────────┘  │
│           │                               │            │
│  ┌────────▼─────────┐            ┌────────▼─────────┐  │
│  │ core-accessibility│           │ core-axmanager   │  │
│  │  (System Gestures)│           │ (Shizuku UID2000)│  │
│  └──────────────────┘            └──────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## 🛠️ Fluxo de Desenvolvimento e Engenharia

1. **Modularização Android (Gradle Kotlin Multi-Module):**
   * Estruturado em módulos coesos: `app`, `core-tools`, `core-bridge`, `core-accessibility`, `core-axmanager`, `core-voice`, `core-media`, `core-security`, `feature-hud`, `feature-chat`.
2. **Integração Shizuku Privileged API:**
   * Utilização da biblioteca `dev.rikka.shizuku:api:13.1.5` para invocar comandos e obter permissões de sistema sem necessidade de root tradicional, aproveitando o daemon ADB em porta sem fio.
3. **Resolução de Desafios de Alta Resolução (Hotfix 1440p):**
   * Ao transmitir capturas de tela nativas da tela do Galaxy S23 Ultra em Base64 bruto (~2.2MB por imagem), o servidor WebSocket enfrentou o erro RFC 6455 `Code 1009 (Message size exceeds limit)`.
   * Solução: Implementado buffer dinâmico de 32MB tanto no `websockets.serve` quanto no `aiohttp.web.Application` no bridge Python (`punk_server.py`), possibilitando streaming e OCR/Visão instantâneos.
4. **Captura Silenciosa com Camera2 API:**
   * Criação do componente `CameraCapture.kt` utilizando corrotinas suspensas (`suspendCancellableCoroutine`) com ciclo de vida isolado em `HandlerThread`, garantindo captura assíncrona limpa sem travar a interface.

---

## 📦 Como Instalar e Rodar

### Pré-requisitos
* Android 10 ou superior (Recomendado: Android 14+ / One UI 6+).
* [Shizuku](https://shizuku.rikka.app/) instalado e ativado (via Wireless Debugging).

### Instalação Direta
1. Baixe o APK compilado na pasta [`/apk/PUNK-Companion-v2.1-autonomous.apk`](./apk/PUNK-Companion-v2.1-autonomous.apk) ou nas [Releases](../../releases).
2. Instale no seu dispositivo Android.
3. Abra o app e conceda a permissão ao Shizuku e as permissões de Câmera, Localização e Acessibilidade.
4. Configure o IP do seu servidor bridge nas configurações do Companion.

### Compilação a partir do Código-Fonte
```bash
# Clone o repositório
git clone https://github.com/felipegatoloko10/punk-app.git
cd punk-app

# Compile o APK de depuração
./gradlew assembleDebug
```

---

## 📄 Licença
Distribuído sob a licença MIT. Consulte `LICENSE` para mais detalhes.

---
*Construído com lealdade, precisão e autonomia total por Mr. Punk (JARVIS) para Sir Felipe.*
