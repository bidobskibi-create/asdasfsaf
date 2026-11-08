./build.sh -> out/SwipeNav-flash.zip. Flash in TWRP over the installed ROM (no need to reflash the ROM). Reboot after.
(GitHub: push this folder and download the "SwipeNav-flash" artifact from the Actions run.)

v4 - editable gestures + force close
  * Settings > Gestures: tap Swipe left / Swipe right / Swipe up / Swipe up and hold (or far)
    and choose: Nothing, Back, Home, Recent apps, Force close app, Control panel.
    Defaults are unchanged (Back, Back, Home, Recent apps), so existing installs behave as before.
  * "Force close app" force-stops the app you are in (never the launcher, system UI or lock screen).
    It needs FORCE_STOP_PACKAGES, which is granted because the app is installed in /system/priv-app.
    If that is ever refused it falls back to Home + killBackgroundProcesses.
  * The pill turns red when a gesture is set to Force close app.
  * New files: Actions.java. Changed: GestureView, SwipeNavService, SettingsActivity, AndroidManifest
    (added GET_TASKS, FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES).

v16 - recents screen
  * The "Recent apps" gesture now opens SwipeNav's own recents screen instead of Android's.
    One custom view, spring (bouncy) animations, redraws only while moving, freed completely when closed.
  * Every card has a close button (x). Swipe a card up to close it too. "Clear all" closes everything.
    Tap a card to open it, tap outside the strip to dismiss. Scroll sideways with a rubber-band bounce.
  * Settings > Gestures: "SwipeNav recents screen" (off = Android's own recents) and "Apps shown in recents" (4-12).
  * New file: RecentsView.java. Changed: Actions, SwipeNavService, SettingsActivity, AndroidManifest
    (added REORDER_TASKS, REMOVE_TASKS). Closing uses removeTask, then force-stop, then killBackgroundProcesses.

v17 - recents polish
  * Free RAM + bar in the recents header (updates after Clear all). Setting: "Show free RAM in recents".
  * Cards snap to the nearest card after a fling (with a small bounce).
  * Depth effect: cards towards the screen edges are slightly smaller and dimmer.
  * Long-press a card = that app's info page (force stop, uninstall, permissions).
  * The app you were just in has an accent outline.

v18 - customisable recents
  * Settings > Recents look: Animation (Smooth / Bouncy / Extra bouncy), Card colour (Dark / Black / Light / Glass),
    Card size, Corner roundness, Icon size, Position from top, Background dim, and switches for app names,
    close button, accent bar, depth effect and snap-to-cards. "Preview recents now" opens it with the current look.
    The accent colour is shared with the control panel. "Reset recents look" restores the defaults.
  * All settings are read once when recents opens, so they cost nothing while it is closed.

v19 - recents styles
  * Settings > Recents look > Recents style: Classic (the v16-v18 strip), MIUI, iOS, Windows Phone.
    - MIUI: orange accent, 2-column grid that scrolls vertically, flat gray cards, plain x, solid "Clear all" button.
      Swipe a card sideways to close it.
    - iOS: large centred cards, app icon + name above each card, soft springs, swipe a card up to close it.
    - Windows Phone: flat accent-blue tiles, lowercase labels, circle-outline x, black background,
      tiles flip in and out (turnstile), swipe a tile sideways to close it.
  * Every style has its own defaults (animation, card colour, dim, close button, depth) and every control in
    "Recents look" still overrides them. New card colours: Accent, Gray.
  * RecentsView.java rewritten to support grid and strip layouts; behaviour of Classic is unchanged.
