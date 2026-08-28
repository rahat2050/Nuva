#!/usr/bin/env python3
"""Deterministic Android manifest/security/version contract audit for NUVA.

This is intentionally dependency-free so it can run in constrained CI/sandboxes
before Gradle is available. It does not replace assembleDebug, lint, JVM tests or
real-device QA; it catches high-impact wiring and policy regressions early.

Usage:
    cd android && python3 tools/android_contract_check.py
"""

from __future__ import annotations

import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ANDROID_DIR = Path(__file__).resolve().parents[1]
REPO = ANDROID_DIR.parent
APP = ANDROID_DIR / "app"
MAIN = APP / "src" / "main"
MANIFEST = MAIN / "AndroidManifest.xml"

checks = 0


def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


def attr(node: ET.Element, name: str) -> str | None:
    return node.get(ANDROID + name)


def child_with_name(parent: ET.Element, tag: str, name: str) -> ET.Element | None:
    return next((node for node in parent.findall(tag) if attr(node, "name") == name), None)


def source_contains_component(simple_name: str, kotlin_source: str) -> bool:
    return re.search(rf"\b(?:class|object)\s+{re.escape(simple_name)}\b", kotlin_source) is not None


def main() -> None:
    manifest_text = MANIFEST.read_text(encoding="utf-8")
    root = ET.fromstring(manifest_text)
    application = root.find("application")
    require(application is not None, "manifest must contain <application>")
    assert application is not None

    # Every resource XML must be well formed.
    xml_files = list(MAIN.rglob("*.xml"))
    for path in xml_files:
        ET.parse(path)
    require(len(xml_files) >= 5, "expected Android XML resources")

    permissions = [attr(node, "name") for node in root.findall("uses-permission")]
    require(len(permissions) == len(set(permissions)), "manifest permissions must be unique")
    required_permissions = {
        "android.permission.RECORD_AUDIO",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    }
    require(required_permissions.issubset(set(permissions)), "voice foreground permissions are incomplete")

    forbidden_permissions = {
        "android.permission.CAPTURE_AUDIO_HOTWORD",  # system/signature only
        "android.permission.RECORD_BACKGROUND_AUDIO",
        "android.permission.MANAGE_SPEECH_RECOGNITION",
        "android.permission.WRITE_CALENDAR",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    }
    present_forbidden = sorted(set(permissions) & forbidden_permissions)
    require(not present_forbidden, f"forbidden/excess permissions declared: {present_forbidden}")

    # Android 11+ package visibility is mandatory for SpeechRecognizer discovery.
    queries = root.find("queries")
    query_actions = {
        attr(action, "name")
        for action in (queries.findall("./intent/action") if queries is not None else [])
    }
    require(
        "android.speech.RecognitionService" in query_actions,
        "RecognitionService package-visibility query missing",
    )

    kotlin_files = list((MAIN / "java").rglob("*.kt"))
    kotlin_source = "\n".join(path.read_text(encoding="utf-8") for path in kotlin_files)
    for component_tag in ("activity", "service", "receiver"):
        for component in application.findall(component_tag):
            component_name = attr(component, "name") or ""
            simple_name = component_name.rsplit(".", 1)[-1]
            require(
                source_contains_component(simple_name, kotlin_source),
                f"manifest {component_tag} implementation not found: {component_name}",
            )

    main_activity = child_with_name(application, "activity", ".MainActivity")
    require(main_activity is not None, "MainActivity declaration missing")
    assert main_activity is not None
    main_actions = {attr(node, "name") for node in main_activity.findall("./intent-filter/action")}
    require("android.intent.action.MAIN" in main_actions, "launcher action missing")
    require("android.intent.action.ASSIST" in main_actions, "assistant Activity fallback missing")
    require("android.intent.action.SEND" in main_actions, "explicit shared-text handoff missing")
    require("android.intent.action.PROCESS_TEXT" in main_actions, "selected-text handoff missing")
    require(attr(main_activity, "launchMode") == "singleTop", "assistant Activity must consume repeat invocations")
    shortcuts_metadata = child_with_name(main_activity, "meta-data", "android.app.shortcuts")
    require(attr(shortcuts_metadata, "resource") == "@xml/shortcuts", "launcher shortcut metadata missing")
    shortcuts_root = ET.parse(MAIN / "res" / "xml" / "shortcuts.xml").getroot()
    require(shortcuts_root.find("shortcut") is not None, "Talk to NUVA launcher shortcut missing")

    quick_tile = child_with_name(application, "service", ".service.NuvaQuickSettingsTileService")
    require(quick_tile is not None, "Quick Settings tile service missing")
    assert quick_tile is not None
    require(attr(quick_tile, "exported") == "true", "system must be able to bind the Quick Settings tile")
    require(
        attr(quick_tile, "permission") == "android.permission.BIND_QUICK_SETTINGS_TILE",
        "Quick Settings tile must require the system bind permission",
    )
    tile_actions = {attr(node, "name") for node in quick_tile.findall("./intent-filter/action")}
    require("android.service.quicksettings.action.QS_TILE" in tile_actions, "Quick Settings tile filter missing")

    # Complete VoiceInteractionService registration.
    voice_service = child_with_name(
        application,
        "service",
        ".systemassistant.NuvaVoiceInteractionService",
    )
    require(voice_service is not None, "VoiceInteractionService declaration missing")
    assert voice_service is not None
    require(attr(voice_service, "exported") == "true", "VoiceInteractionService must be system-bindable")
    require(
        attr(voice_service, "permission") == "android.permission.BIND_VOICE_INTERACTION",
        "VoiceInteractionService must require BIND_VOICE_INTERACTION",
    )
    voice_actions = {attr(node, "name") for node in voice_service.findall("./intent-filter/action")}
    require(
        "android.service.voice.VoiceInteractionService" in voice_actions,
        "VoiceInteractionService intent filter missing",
    )
    voice_metadata = child_with_name(voice_service, "meta-data", "android.voice_interaction")
    require(attr(voice_metadata, "resource") == "@xml/voice_interaction_service", "voice metadata wiring missing")

    voice_xml = ET.parse(MAIN / "res" / "xml" / "voice_interaction_service.xml").getroot()
    require(voice_xml.tag == "voice-interaction-service", "voice metadata root is invalid")
    session_class = attr(voice_xml, "sessionService") or ""
    recognition_class = attr(voice_xml, "recognitionService") or ""
    require(session_class.endswith(".NuvaVoiceInteractionSessionService"), "session service metadata mismatch")
    require(recognition_class.endswith(".NuvaRecognitionService"), "recognition service metadata mismatch")
    require(attr(voice_xml, "supportsAssist") == "true", "assistant role qualification requires assist support")
    require(attr(voice_xml, "supportsLaunchVoiceAssistFromKeyguard") == "false", "lockscreen launch must stay off")
    require(attr(voice_xml, "supportsLocalInteraction") == "false", "implicit local interaction must stay off")

    session_service = child_with_name(
        application,
        "service",
        ".systemassistant.NuvaVoiceInteractionSessionService",
    )
    require(session_service is not None, "session service declaration missing")
    assert session_service is not None
    require(
        attr(session_service, "permission") == "android.permission.BIND_VOICE_INTERACTION",
        "session service must require BIND_VOICE_INTERACTION",
    )

    recognition_service = child_with_name(
        application,
        "service",
        ".systemassistant.NuvaRecognitionService",
    )
    require(recognition_service is not None, "recognition service declaration missing")
    assert recognition_service is not None
    recognition_actions = {
        attr(node, "name") for node in recognition_service.findall("./intent-filter/action")
    }
    require("android.speech.RecognitionService" in recognition_actions, "recognition service filter missing")
    require(
        attr(recognition_service, "permission") is None,
        "do not invent a bind permission; RecognitionService performs caller RECORD_AUDIO checks",
    )
    recognition_metadata = child_with_name(recognition_service, "meta-data", "android.speech")
    require(attr(recognition_metadata, "resource") == "@xml/recognition_service", "recognition metadata missing")

    # Microphone services must remain visible foreground services.
    for service_name in (".service.NuvaForegroundService", ".service.WakeWordService"):
        service = child_with_name(application, "service", service_name)
        require(service is not None, f"{service_name} missing")
        assert service is not None
        require(attr(service, "foregroundServiceType") == "microphone", f"{service_name} must declare mic FGS")
        require(attr(service, "exported") == "false", f"{service_name} must not be externally startable")

    # Source-level privacy and recursion boundaries around the assistant stack.
    voice_source = (MAIN / "java/com/nuva/assistant/systemassistant/NuvaVoiceInteractionService.kt").read_text()
    recognizer_source = (MAIN / "java/com/nuva/assistant/systemassistant/NuvaRecognitionService.kt").read_text()
    wake_source = (MAIN / "java/com/nuva/assistant/service/WakeWordService.kt").read_text()
    require("setDisabledShowContext" in voice_source, "AssistStructure/screenshot disabling missing")
    require("SHOW_WITH_ASSIST" in voice_source and "SHOW_WITH_SCREENSHOT" in voice_source, "assist privacy flags incomplete")
    require("filterNot { it.packageName == ownPackage }" in recognizer_source, "recognizer recursion guard missing")
    require("callingAttributionSource" in recognizer_source, "Android 12+ microphone attribution forwarding missing")
    require("startForeground(" in wake_source, "wake listener must visibly enter foreground")
    require("isScreenInteractive()" in wake_source, "screen-off wake pause missing")
    require("withTimeoutOrNull(WAKE_LISTEN_TIMEOUT_MS)" in wake_source, "stuck wake-cycle recovery missing")
    require('speakIfEnabled("Listening")' not in wake_source, "wake listener must not recognize its own TTS")

    # v4.3 visual system: depth must not trade away navigation, touch targets or reduced-motion safety.
    app_ui_source = (MAIN / "java/com/nuva/assistant/ui/NuvaApp.kt").read_text()
    ui_3d_source = (MAIN / "java/com/nuva/assistant/ui/theme/Nuva3D.kt").read_text()
    require("NuvaBackdrop" in app_ui_source, "global 3D backdrop missing")
    require("NuvaGlassPanel" in app_ui_source, "floating navigation glass panel missing")
    require("NavigationBarItem" in app_ui_source, "four-route navigation must remain explicit")
    require("heightIn(min = 50.dp)" in ui_3d_source, "custom primary action touch target is too small")
    require("Role.Button" in ui_3d_source, "3D voice control needs button semantics")
    require("rememberInfiniteTransition" not in ui_3d_source, "decorative infinite motion is not allowed")
    three_d_screens: dict[str, str] = {}
    for screen_name in ("home/HomeScreen.kt", "history/HistoryScreen.kt", "memory/MemoryScreen.kt"):
        screen_source = (MAIN / f"java/com/nuva/assistant/ui/{screen_name}").read_text()
        three_d_screens[screen_name] = screen_source
        require(screen_source.count("LazyColumn(") == 1, f"{screen_name} must use one bounded lazy list")
        require("NuvaGlassPanel" in screen_source, f"{screen_name} is outside the 3D component system")
    require("showClearConfirmation" in three_d_screens["history/HistoryScreen.kt"], "history clear needs confirmation")
    require("PendingMemoryDeletion" in three_d_screens["memory/MemoryScreen.kt"], "memory deletion needs confirmation")
    overlay_source = (MAIN / "java/com/nuva/assistant/ui/floating/FloatingAssistantOverlay.kt").read_text()
    require("Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL" in overlay_source, "assistant voice plate should stay reachable")

    # Lifecycle/resource hardening: screen-owned work must stop with its ViewModel/composition.
    ui_source = "\n".join(path.read_text() for path in (MAIN / "java/com/nuva/assistant/ui").rglob("*.kt"))
    require("stateIn(CoroutineScope(" not in ui_source, "unmanaged stateIn scope found in UI")
    require("CoroutineScope(Dispatchers.IO).launch" not in ui_source, "unmanaged ViewModel IO scope found")
    voice_controller_source = (MAIN / "java/com/nuva/assistant/voice/VoiceController.kt").read_text()
    tts_source = (MAIN / "java/com/nuva/assistant/voice/TTSManager.kt").read_text()
    require("mainScope.cancel()" in voice_controller_source, "VoiceController scope is not cancelled")
    require("voiceEnabledBlocking" not in voice_controller_source, "VoiceController blocks the main thread on DataStore")
    require("pendingSpeech" in tts_source, "first TTS reply can be lost during async initialization")
    require("ttsManagerDelegate.isInitialized()" in ui_source, "Settings TTS engine cleanup missing")

    endpoint_source = (MAIN / "java/com/nuva/assistant/core/security/SecureEndpointPolicy.kt").read_text()
    preferences_source = (MAIN / "java/com/nuva/assistant/memory/UserPreferences.kt").read_text()
    settings_source = (MAIN / "java/com/nuva/assistant/ui/settings/SettingsScreen.kt").read_text()
    require('parsed.scheme?.lowercase() != "https"' in endpoint_source, "auth endpoint HTTPS gate missing")
    require("parsed.userInfo" in endpoint_source and "parsed.query" in endpoint_source, "endpoint credential/query guard missing")
    require("AndroidTokenCipher" in preferences_source, "Supabase session tokens are not Keystore encrypted")
    home_config_source = (MAIN / "java/com/nuva/assistant/homeassistant/HomeAssistantConfigStore.kt").read_text()
    require("previousUrl != normalized" in home_config_source, "Home Assistant token can be reused across endpoint changes")
    require('SESSION_PREFIX = "enc-v1:"' in preferences_source, "encrypted session format marker missing")
    require("clearSession()" in preferences_source, "invalid legacy sessions are not cleared")
    require(preferences_source.count("if (endpointChanged)") >= 2, "endpoint changes can retain an old JWT")
    require(settings_source.count("visualTransformation = PasswordVisualTransformation()") >= 2, "password/token field masking regressed")
    require('password = ""' in settings_source, "submitted password is not cleared from UI state")
    application_source = (MAIN / "java/com/nuva/assistant/NuvaApplication.kt").read_text()
    main_activity_source = (MAIN / "java/com/nuva/assistant/MainActivity.kt").read_text()
    require("WakeWordService.start" not in application_source, "background process creation can start the wake microphone")
    require("ScheduledComposeScheduler.restorePending" not in application_source, "Application and BootReceiver can double-restore drafts")
    require("normalVisibleLaunch" in main_activity_source, "visible-launch wake restoration missing")
    require("stillVisible" in main_activity_source, "wake restore can outlive the visible Activity state")
    require("visibleRestoreStarted" in main_activity_source, "visible draft restore is not idempotent")
    notification_source = (MAIN / "java/com/nuva/assistant/service/NuvaNotificationListener.kt").read_text()
    require(
        notification_source.index("SensitiveAppPolicy.isSensitivePackage(sbn.packageName)")
        < notification_source.index("sbn.notification?.extras"),
        "financial notification extras are touched before the package denylist",
    )
    require(".take(MAX_STORED)" in notification_source, "initial notification refresh is not bounded")
    calendar_source = (MAIN / "java/com/nuva/assistant/automation/CalendarProviderController.kt").read_text()
    require("safeLocationForDisplay(event.location)" in calendar_source, "calendar location bypasses credential/code redaction")
    settings_opener_source = (MAIN / "java/com/nuva/assistant/automation/SettingsOpener.kt").read_text()
    require("torchCallbackRegistered" in settings_opener_source, "torch callback is registered repeatedly and leaked")

    # v4.4 user-present entry points must never turn external text into an automatic command.
    main_activity_source = (MAIN / "java/com/nuva/assistant/MainActivity.kt").read_text()
    handoff_source = (MAIN / "java/com/nuva/assistant/automation/ExternalTextHandoffPolicy.kt").read_text()
    tile_source = (MAIN / "java/com/nuva/assistant/service/NuvaQuickSettingsTileService.kt").read_text()
    require("draftText" in main_activity_source, "external text must be represented as a draft")
    require("viewModel.submitTyped(invocation.draftText" not in three_d_screens["home/HomeScreen.kt"], "external draft auto-submit detected")
    require("mentionsCredentials" in handoff_source, "external text credential guard missing")
    require("isTransactionRequest" in handoff_source, "external text transaction guard missing")
    require("MAX_DRAFT_CHARS = 1_000" in handoff_source, "external text bound changed")
    require("ACTION_QUICK_SPEAK" in tile_source, "Quick Settings tile must open the explicit speak route")
    require("startActivityAndCollapse(pendingIntent)" in tile_source, "Android 14 tile PendingIntent path missing")

    # Server/AI still cannot resolve local provider/device actions.
    intent_source = (MAIN / "java/com/nuva/assistant/command/Intent.kt").read_text()
    require(
        "entries.firstOrNull { !it.localOnly && it.wireName == value }" in intent_source,
        "LOCAL_ONLY wire rejection changed",
    )
    for local_intent in ("HOME_ASSISTANT", "CALENDAR_PROVIDER"):
        require(
            re.search(rf"\b{local_intent}\([^\n]+localOnly\s*=\s*true", intent_source) is not None,
            f"{local_intent} must remain local-only",
        )

    # A fresh clone must be able to bootstrap the exact Gradle release.
    wrapper_jar = ANDROID_DIR / "gradle/wrapper/gradle-wrapper.jar"
    require(wrapper_jar.is_file(), "Gradle wrapper JAR missing from fresh clone")
    wrapper_sha = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
    require(
        wrapper_sha == "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17",
        "Gradle 8.9 wrapper JAR checksum mismatch",
    )
    wrapper_properties = (ANDROID_DIR / "gradle/wrapper/gradle-wrapper.properties").read_text()
    require("gradle-8.9-bin.zip" in wrapper_properties, "Gradle wrapper version drifted from 8.9")

    # Version is one source of release truth across Android build + client header + docs.
    gradle = (APP / "build.gradle.kts").read_text()
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    version_code = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    require(version_name is not None and version_code is not None, "Android version fields missing")
    assert version_name is not None and version_code is not None
    ai_source = (MAIN / "java/com/nuva/assistant/ai/AIRepository.kt").read_text()
    require(
        f'APP_VERSION = "{version_name.group(1)}"' in ai_source,
        "Android versionName and API client header diverged",
    )
    major_minor = ".".join(version_name.group(1).split(".")[:2])
    supported = (REPO / "docs/supported-features.md").read_text()
    require(f"(v{major_minor})" in supported.splitlines()[0], "supported-features version heading is stale")

    print(f"PASS: {checks} Android contracts · {len(kotlin_files)} Kotlin files · {len(xml_files)} XML files")
    print(f"Version: {version_name.group(1)} (code {version_code.group(1)})")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ET.ParseError) as error:
        print(f"FAIL after {checks} checks: {error}", file=sys.stderr)
        raise SystemExit(1)
