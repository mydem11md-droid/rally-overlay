# Rally Overlay — Native Android

Standalone Kotlin overlay. No WebView, website CSS, SVG, or bundled website JavaScript is used. The rally calculator is implemented with native Android Views and a foreground service.

Build: `gradle testDebugUnitTest assembleDebug` from this directory. GitHub Actions uploads `app-debug.apk` as `mythic-rally-overlay-debug-apk`.

Physical-device validation is still required for overlay window geometry, OEM keyboard/IME behavior, permission screens, touch handling and background-service restrictions.
