# Third-Party Open Source Notices

Opedrgent uses or references the following open source software projects.
Each project is listed with its name, copyright holder, and applicable license.

This file is organised into three parts:

- **A. Bundled / linked open-source dependencies** — Gradle artifacts declared in
  `gradle/libs.versions.toml` and `app/build.gradle.kts` that are packaged into the APK.
- **B. Architecture & design references** — upstream projects whose design, patterns or
  UX the implementation draws inspiration from; their source code is *not* redistributed.
- **C. Model & data assets** — model weights and corpora downloaded at runtime. They are
  *not* bundled in this repository and are *not* covered by the MIT license of Opedrgent's
  own source code.

---

## A. Bundled / Linked Open Source Dependencies

Versions below are pinned by `gradle/libs.versions.toml` (Opedrgent v1.2.1, versionCode 4).

### AndroidX / Jetpack — Apache License 2.0

Copyright (c) The Android Open Source Project.
Source: https://developer.android.com/jetpack

- `androidx.core:core-ktx:1.15.0` — Kotlin extensions for core platform APIs.
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` — lifecycle-aware coroutine integration.
- `androidx.lifecycle:lifecycle-viewmodel-ktx` — ViewModel for screens.
  (No explicit `version.ref` in `libs.versions.toml`; resolved alongside the 2.8.7 lifecycle family — verify against the effective Gradle resolution result if a precise pin is required.)
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` — ViewModel ↔ Compose integration.
- `androidx.lifecycle:lifecycle-runtime-compose:2.8.7` — lifecycle awareness inside Compose.
- `androidx.activity:activity-compose:1.9.3` — `ComponentActivity` ↔ Compose integration.
- Jetpack Compose via `androidx.compose:compose-bom:2026.04.01`:
  `androidx.compose.ui:ui`, `androidx.compose.ui:ui-graphics`,
  `androidx.compose.ui:ui-tooling-preview`, `androidx.compose.material3:material3`,
  `androidx.compose.material3:material3-window-size-class`,
  `androidx.compose.material:material-icons-extended`,
  `androidx.compose.runtime:runtime-livedata` — declarative UI toolkit.
- `androidx.navigation:navigation-compose:2.8.4` — Compose navigation graph.
- `androidx.work:work-runtime-ktx:2.10.0` — WorkManager for deferrable background jobs.
- `androidx.security:security-crypto:1.1.0-alpha06` — Keystore-backed encrypted SharedPreferences.
- `androidx.datastore:datastore-preferences:1.1.2` — typed, async key-value storage.
- `androidx.media3:media3-exoplayer:1.6.1` — audio/media playback engine.
- `androidx.health.connect:connect-client:1.1.0` — Health Connect client for fitness/health records.

### Kotlin / kotlinx — Apache License 2.0

Copyright (c) JetBrains s.r.o. and the Kotlin Programming Language contributors.
Sources:
https://github.com/JetBrains/kotlin ,
https://github.com/Kotlin/kotlinx.coroutines ,
https://github.com/Kotlin/kotlinx.serialization

- `org.jetbrains.kotlin:kotlin-reflect:2.3.0` — Kotlin reflection runtime.
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` — `Dispatchers.Main` for Android.
- `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0` — bridge between Google Play services `Task<T>` and coroutines.
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3` — Kotlin multiplatform JSON serialization.

### Networking & HTML parsing

- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP client.
  Copyright (c) Square, Inc. **License:** Apache License 2.0.
  Source: https://github.com/square/okhttp
- `org.jsoup:jsoup:1.18.1` — HTML parsing / DOM / select.
  **License:** MIT License.
  Source: https://github.com/jhy/jsoup

### Google ML Kit & on-device inference

- `com.google.mlkit:text-recognition:16.0.1` and `com.google.mlkit:text-recognition-chinese:16.0.1`
  — on-device OCR (Latin script and Chinese script). The Maven artifacts themselves are
  **Apache License 2.0**; use of the ML Kit APIs is additionally governed by the
  **Google APIs Terms of Service** (https://developers.google.com/ml-kit/terms).
  Source: https://github.com/googlesamples/mlkit
- `com.google.android.gms:play-services-tasks:18.1.0` — asynchronous `Task` API used to
  bridge Google Play services calls. **License:** Apache License 2.0 (SDK); runtime
  behaviour is provided by Google Play services and is subject to the Google APIs Terms
  of Service (https://developers.google.com/android/terms).
- `com.google.ai.edge.litertlm:litertlm-android:0.12.0` — LiteRT-LM on-device LLM
  inference engine (prompt / LoRA loading for Gemma-class models).
  **License:** Apache License 2.0.
  Source: https://github.com/google-ai-edge/LiteRT
- `com.google.android.gms:play-services-tflite-java:16.4.0`,
  `com.google.android.gms:play-services-tflite-gpu:16.4.0`,
  `com.google.android.gms:play-services-tflite-support:16.4.0` — TensorFlow Lite runtime
  delivered through Google Play services (Java bindings, GPU delegate, support library).
  **License:** Apache License 2.0 (SDK artifacts); the on-device runtime itself is
  provided by Google Play services under the Google APIs Terms of Service.
  Source: https://www.tensorflow.org/lite/android

### Speech recognition & ONNX runtime

#### Sherpa-ONNX 1.13.1

Copyright (c) k2-fsa. Coordinates: `com.github.k2-fsa:sherpa-onnx:1.13.1` (resolved via JitPack).

**License:** Apache License 2.0

> Used for: Offline speech recognition engine (SenseVoice / Paraformer model inference).
> Source: https://github.com/k2-fsa/sherpa-onnx

#### ONNX Runtime Android 1.21.0

Copyright (c) Microsoft Corporation. Coordinates: `com.microsoft.onnxruntime:onnxruntime-android:1.21.0`.

**License:** MIT License

> Used for: ONNX model inference on device (PP-OCRv6 OCR; also supplies the native
> runtime that Sherpa-ONNX links against — both AARs ship `libonnxruntime.so`, the build
> picks the first occurrence).
> Source: https://github.com/microsoft/onnxruntime

### Utilities

- `org.apache.commons:commons-compress:1.27.0` — archive (tar / zip) extraction used to
  unpack downloaded model bundles. **License:** Apache License 2.0.
  Source: https://github.com/apache/commons-compress

> Test-only dependencies (`junit:junit:4.13.2`,
> `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0`, `org.json:json:20240303`,
> `com.squareup.okhttp3:mockwebserver:4.12.0`, AndroidX test ext / Espresso / Compose
> `ui-test-junit4`, debug-only `androidx.compose.ui:ui-tooling` and `ui-test-manifest`)
> are NOT packaged into the release APK and are listed here for traceability only; each
> remains under its own upstream license.
>
> `com.google.mlkit:genai:0.3.0` is currently commented out in `app/build.gradle.kts`
> (no public Maven publication as of this writing) and is therefore NOT on the runtime
> classpath — no notice is required yet.

---

## B. Architecture & Design References

The following upstream projects were used as design / UX / architecture references only.
No source code from these projects is redistributed in Opedrgent; they are listed here
for attribution and provenance.

## MiMo Code

Copyright (c) 2026 MiMo Code, Xiaomi Corporation
Copyright (c) 2025 opencode

**License:** MIT License

> Used for: Reference implementation for ASR/TTS API integration patterns.
> Source: https://github.com/MiMoCode/MiMo-Code

## GElab-Zero (stepfun-ai)

Copyright (c) 2025 stepfun-ai

**License:** MIT License (base model); Apache 2.0 (Qwen3-VL-4B-Instruct foundation); LGPL v3 (YADB tooling)

> Used for: Vision-language model reference; multimodal AI interaction design reference.
> Source: https://huggingface.co/stepfun-ai/GElab-Zero-4B-preview

## Koog AI Agent Framework

Copyright (c) Koog contributors

**License:** Apache License 2.0

> Used for: Kotlin multiplatform agent framework architecture reference (graph-based workflows, ToolRegistry, MCP integration).
> Source: https://github.com/louis-sun/koog

## GPT Mobile

Copyright (c) GPT Mobile contributors

**License:** GNU General Public License v3 (GPLv3)

> Used for: Android Compose UI patterns, MVVM architecture, multi-provider LLM client reference.
> Source: https://github.com/chungjungsoo/gpt-mobile

## Kilo Code / opencode

Copyright (c) 2026 Kilo Code
Copyright (c) 2025 opencode

**License:** MIT

> Used for: Terminal-based AI agent UI patterns, ink rendering engine reference.
> Source: https://github.com/kilocode/kilocode

## Qdrant

Copyright (c) Qdrant contributors

**License:** Apache License 2.0

> Used for: Vector database architecture reference (embedding storage, similarity search).
> Source: https://github.com/qdrant/qdrant

## ML Intern (smolagents)

Copyright (c) Hugging Face / smolagents contributors

**License:** Apache License 2.0

> Used for: Multi-agent orchestration patterns, MCP integration reference.
> Source: https://github.com/smolagents/ml-intern

## Meilisearch

Copyright (c) 2019-2025 Meili SAS

**License:** Business Source License 1.1 (Enterprise Edition) / MIT (Community Edition)

> Used for: Full-text search engine architecture reference.
> Source: https://github.com/meilisearch/meilisearch

## SearXNG

Copyright (c) SearXNG contributors

**License:** GNU Affero General Public License v3 (AGPLv3)

> Used for: Meta-search engine architecture, privacy-focused web search patterns reference.
> Source: https://github.com/searxng/searxng

## Open WebUI

Copyright (c) 2023- Open WebUI Inc. [Created by Timothy Jaeryang Baek]

**License:** Open WebUI License (custom proprietary license)

> Used for: LLM chat UI/UX design reference (chat interface, RAG pipeline, document processing).
> Source: https://github.com/open-webui/open-webui

---

## C. Model & Data Assets (Downloaded at Runtime)

The following assets are **not bundled in this repository** and are **not covered by the
MIT license** of Opedrgent's own source code. They are fetched on demand by the app at
runtime (typically over Wi-Fi) and remain under their own upstream terms.

- **Gemma models** (e.g. Gemma 2 / Gemma 3, pulled through the LiteRT-LM / LiteRT model
  downloader). Governed by the **Gemma Terms of Use**.
  Copyright Google LLC. Terms: https://ai.google.dev/gemma/terms
- **Sherpa-ONNX speech models** (e.g. SenseVoice, Paraformer, streaming zipformer
  variants) downloaded at runtime for offline ASR. Each model artifact carries its own
  license from its upstream publisher — e.g. SenseVoice © Alibaba / FunAudioLLM
  (Apache-2.0); Paraformer / Whisper / other zipformer model cards on Hugging Face list
  their individual terms. Always read the model card before redistribution.
  Index: https://k2-fsa.github.io/sherpa/onnx/models/
- **ONNX OCR models** (e.g. PP-OCRv6 / PaddleOCR family) downloaded on demand; see the
  upstream model card for the exact license.

By downloading or using any of the above, the user accepts the corresponding upstream
terms. The MIT grant in `LICENSE` applies only to Opedrgent's own source code.

---

## Full License Texts

### Apache License 2.0

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by the
      copyright owner that is granting the License.

      "Legal Entity" shall mean the union of all acting entities involved in the
      Agreement, such as an individual, corporation, or nonprofit
      organization.

      "You" (or "Your") shall mean an individual or Legal Entity exercising
      permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation source,
      and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but not limited
      to compiled object code, generated documentation, and conversions to other
      media types.

      "Work" shall mean the work of authorship, whether in Source or Object
      form, made available under the License, as indicated by a copyright notice
      that is included in or attached to the work.

      "Derivative Works" shall mean any work, whether in Source or Object form,
      that is based on (or derived from) the Work and for which the editorial
      revisions, annotations, elaborations, or other modifications represent,
      as a whole, an original work of authorship. For the purposes of this
      License, Derivative Works shall not include works that remain separable
      from, or merely link (or bind by name) to the interfaces of, the Work and
      Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including the original
      version of the Work and any modifications or additions to that Work or
      Derivative Works, that is intentionally submitted to the Licensor for
      inclusion in the Work by the copyright owner or by an individual or
      Legal Entity authorized to submit on behalf of the copyright owner. For
      the purposes of this definition, "submitted" means any form of electronic,
      verbal, or written communication sent to the Licensor or its representatives,
      including but not limited to communication on mailing lists, source code
      control systems, and issue tracking systems that are managed by, or on
      behalf of, the Licensor for the purpose of discussing and improving the
      Work, but excluding communications that are conspicuously marked or
      otherwise designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity on
      behalf of whom a Contribution has been received by the Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of this
      License, each Contributor hereby grants You a perpetual, worldwide,
      non-exclusive, no-charge, royalty-free, irrevocable copyright license to
      reproduce, prepare Derivative Works of, publicly display, publicly perform,
      sublicense, and distribute the Work and such Derivative Works in Source
      or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of this
      License, each Contributor hereby grants You a perpetual, worldwide,
      non-exclusive, no-charge, royalty-free, irrevocable (except as stated in
      this section) patent license to make, have made, use, offer to sell, sell,
      import, and otherwise transfer the Work, where such license applies only
      to those patent claims licensable by such Contributor that are necessarily
      infringed by their Contribution(s) alone or by combination of their
      Contribution(s) with the Work to which such Contribution(s) was submitted.
      If You institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work or a
      Contribution incorporated within the Work constitutes direct or contributory
      patent infringement, then any patent licenses granted by You under this
      License for that Work shall terminate as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the Work or
      Derivative Works thereof in any medium, with or without modifications,
      and in Source or Object form, provided that You meet the following conditions:

      (a) You must give any other recipients of the Work or Derivative Works a
          copy of this License; and

      (b) You must cause any modified files to carry prominent notices stating
          that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works that You
          distribute, all copyright, patent, trademark, and attribution notices
          from the Source form of the Work, excluding those notices that do not
          pertain to any part of the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its distribution,
          then any Derivative Works that You distribute must include a readable
          copy of the attribution notices contained within such NOTICE file,
          excluding any notices that do not pertain to any part of the Derivative
          Works, in at least one of the following places: within a NOTICE text
          file distributed as part of the Derivative Works; within the Source
          form or documentation, if provided along with the Derivative Works;
          or within a display generated by the Derivative Works, if and wherever
          such third-party notices normally appear. The contents of the NOTICE
          file are for informational purposes only and do not modify the License.

   5. Submission of Contributions. Unless You explicitly state otherwise, any
      Contribution intentionally submitted for inclusion in the Work by You to the
      Licensor shall be under the terms of this License, without any additional
      terms or conditions. Notwithstanding the above, nothing herein shall supersede
      or modify the terms of any separate license agreement you may have executed
      regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade names,
      trademarks, service marks, or product names of the Licensor, except as
      required for reasonable and customary use in describing the origin of the
      Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or agreed to in
      writing, Licensor provides the Work (and each Contributor provides its
      Contributions) on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
      KIND, either express or implied, including, but not limited to, warranties
      or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any risks
      associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory, whether in
      tort (including negligence), contract, or otherwise, unless required by
      applicable law (such as deliberate and grossly negligent acts) or agreed to
      in writing, shall any Contributor be liable to You for damages, including
      any direct, indirect, special, incidental, or consequential damages of any
      character arising as a result of this License or out of the use or
      inability to use the Work (including but not limited to damages for loss of
      goodwill, work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor has been advised
      of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing the Work
      or Derivative Works thereof, You may choose to offer, and charge a fee for,
      acceptance of support, warranty, indemnity, or other liability obligations
      and/or rights consistent with this License. However, in accepting such
      obligations, You may act only on Your own behalf and on Your sole
      responsibility, not on behalf of any other Contributor, and only if You
      agree to indemnify, defend, and hold each Contributor harmless for any
      liability incurred by, or claims asserted against, any Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS


   Copyright 2004 Apache Software Foundation

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### MIT License

```
MIT License

Copyright (c) 2026 HaohanHe (Opedrgent project)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### GNU General Public License v3

Full text available at: https://www.gnu.org/licenses/gpl-3.0.html

### GNU Affero General Public License v3

Full text available at: https://www.gnu.org/licenses/agpl-3.0.html

### GNU Lesser General Public License v3

Full text available at: https://www.gnu.org/licenses/lgpl-3.0.html

---

*This file was last reviewed on October 3, 2026. Versions are taken from
`gradle/libs.versions.toml` for Opedrgent v1.2.1 (versionCode 4).*
