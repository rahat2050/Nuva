#!/usr/bin/env python3
"""Focused Kotlin/API semantic compile when a full Android SDK/Gradle is unavailable.

This checks the provider and assistant code added in v4.0-v4.2 against a real
public android.jar. Tiny project-only stubs isolate Android API signatures from
Compose/Room/network dependencies. It is a supplement, never a replacement, for
`:app:testDebugUnitTest`, lint and `:app:assembleDebug`.

Example:
    python3 tools/focused_android_api_compile_check.py \
      --kotlinc /path/to/kotlinc-jvm \
      --android-jar /path/to/platforms/android-35/android.jar
"""

from __future__ import annotations

import argparse
import os
import subprocess
import tempfile
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parents[1]
SOURCE = ANDROID_DIR / "app/src/main/java/com/nuva/assistant"


def write(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def compile_group(
    name: str,
    kotlinc: Path,
    android_jar: Path,
    root: Path,
    sources: list[Path],
    extra_classpath: list[Path] | None = None,
) -> None:
    output = root / f"out-{name}"
    output.mkdir()
    classpath = [android_jar, *(extra_classpath or [])]
    command = [
        str(kotlinc),
        "-jvm-target",
        "17",
        "-classpath",
        os.pathsep.join(str(path) for path in classpath),
        "-d",
        str(output),
        *(str(path) for path in sources),
    ]
    completed = subprocess.run(command, text=True, capture_output=True)
    if completed.returncode != 0:
        print(completed.stdout, end="")
        print(completed.stderr, end="")
        raise SystemExit(f"FAIL: focused compile group {name}")
    print(f"PASS: {name} ({len(sources)} sources)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kotlinc", required=True, type=Path)
    parser.add_argument("--android-jar", required=True, type=Path)
    args = parser.parse_args()
    require_files = [args.kotlinc, args.android_jar]
    for path in require_files:
        if not path.is_file():
            raise SystemExit(f"missing required file: {path}")

    kotlin_home = args.kotlinc.resolve().parent.parent
    coroutines = kotlin_home / "lib/kotlinx-coroutines-core-jvm.jar"
    if not coroutines.is_file():
        raise SystemExit(f"Kotlin distribution is missing coroutines jar: {coroutines}")

    with tempfile.TemporaryDirectory(prefix="nuva-api-compile-") as temporary:
        root = Path(temporary)

        main_activity_stub = write(
            root / "stubs/com/nuva/assistant/MainActivity.kt",
            """package com.nuva.assistant
class MainActivity {
    companion object {
        const val ACTION_SYSTEM_ASSISTANT = "test"
        const val EXTRA_LISTEN_IN_APP = "listen"
        const val EXTRA_INLINE_COMMAND = "command"
    }
}
""",
        )
        compile_group(
            "system-assistant",
            args.kotlinc,
            args.android_jar,
            root,
            [main_activity_stub, *sorted((SOURCE / "systemassistant").glob("*.kt"))],
        )

        permission_stub = write(
            root / "stubs-calendar/com/nuva/assistant/core/permissions/NuvaPermissions.kt",
            """package com.nuva.assistant.core.permissions
import android.content.Context
object NuvaPermissions { fun hasReadCalendar(context: Context): Boolean = true }
""",
        )
        security_stub = write(
            root / "stubs-calendar/com/nuva/assistant/core/security/SensitiveAppPolicy.kt",
            """package com.nuva.assistant.core.security
object SensitiveAppPolicy {
    fun mentionsCredentials(text: String): Boolean = false
    fun redactCodes(text: String): String = text
}
""",
        )
        calendar_action_stub = write(
            root / "stubs-calendar/com/nuva/assistant/command/Calendar.kt",
            """package com.nuva.assistant.command
enum class CalendarProviderOperation { READ_AGENDA, OPEN_EVENT, EDIT_EVENT }
sealed class NuvaAction {
    data class CalendarProvider(
        val operation: CalendarProviderOperation,
        val rangeStart: Long,
        val rangeEnd: Long,
        val eventQuery: String?,
    ) : NuvaAction()
}
""",
        )
        compile_group(
            "calendar-provider",
            args.kotlinc,
            args.android_jar,
            root,
            [
                permission_stub,
                security_stub,
                calendar_action_stub,
                SOURCE / "automation/CalendarProviderController.kt",
            ],
        )

        compile_group(
            "home-assistant-keystore",
            args.kotlinc,
            args.android_jar,
            root,
            [SOURCE / "homeassistant/HomeAssistantConfigStore.kt"],
        )

        compile_group(
            "speech-recognizer",
            args.kotlinc,
            args.android_jar,
            root,
            [SOURCE / "voice/SpeechRecognizerController.kt"],
            extra_classpath=[coroutines],
        )

        compile_group(
            "pure-wake-state",
            args.kotlinc,
            args.android_jar,
            root,
            [
                SOURCE / "voice/WakePhraseDetector.kt",
                SOURCE / "service/WakeSessionState.kt",
            ],
        )

    print("PASS: 5 focused Kotlin/API compile groups")
    print("NOTE: full Gradle compile, resource linking, lint and device QA are still required")


if __name__ == "__main__":
    main()
