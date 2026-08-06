# Gumroad Listing Copy — PocketMOBAlert Lite

## Title
PocketMOBAlert Lite — Phone-to-Phone Man Overboard Alert

## Short description (one line, shows in search/cards)
Turns your crew's own phones into a man-overboard alarm — no hardware, no internet, free.

## Price
$0

## Long description

**A man-overboard alert system that costs nothing extra, because everyone already has a phone.**

PocketMOBAlert gives small crews the same core idea as commercial AIS personal locator beacons
($250-600 per person), shrunk down to something that runs on phones already in your pockets. One
phone (Crew mode) quietly advertises a Bluetooth beacon in the background; another (Watch mode)
scans for it and sounds a loud alarm the instant it goes out of range or disconnects
unexpectedly — which is what happens fast when someone goes in the water. Every phone can run
both modes at once, so a small crew can watch each other's backs mutually, not just one specific
"captain's phone."

**Important: this is a personal, unofficial safety aid, not a substitute for certified AIS
personal locator beacons.** A phone is bulkier and less water-resistant than a dedicated tag, and
only works if the crew member is actually carrying it.

**Zero internet dependency for the alarm, full stop.** Detection and alerting run entirely over
local Bluetooth Low Energy — the app doesn't even request the `INTERNET` permission. The deck of
a boat underway typically has zero signal; that's completely irrelevant to whether this works.

**What's free:**
- Full Crew-mode BLE beacon and Watch-mode scanning/alarm — the entire core safety loop, unlimited
  time, forever, never gated
- Two-phase separation detection (about 8 seconds worst case) that tells a real MOB event apart
  from routine BLE dropout without ever delaying a real emergency
- Loud sound + vibration on the alarm stream (bypasses silent/DND), relayed to every other paired
  Watch device on the boat, not just whichever phone noticed first
- GPS position + timestamp captured automatically the moment an alarm fires
- Name paired crew by who they actually are — "Chief Engineer," "Skipper," whoever — not an
  anonymous numbered list
- Capped at 2 paired crew devices per voyage, and the alert log shows only the most recent event.
  See PocketMOBAlert Pro for unlimited crew and full history, permanently.

**Requirements:** Android 8.0 (Oreo) or newer, with Bluetooth Low Energy. Installs via sideload
(this isn't distributed through Google Play) — your phone will need "install from unknown
sources" enabled, which is expected for anything not from a store.

**Source code:** fully open, MIT licensed —
[github.com/Trozovka/pocketmobalert-lite](https://github.com/Trozovka/pocketmobalert-lite)

## Screenshots to upload (in `gumroad-assets/`, in this order)
1. `01_free_crew_mode.png` — Crew mode screen
2. `02_free_watch_mode.png` — Watch mode screen

## File to upload
The signed release APK once built — `app-release.apk`, renamed to something like
`PocketMOBAlert-Lite-v1.0.0.apk` for clarity in the downloads folder.
