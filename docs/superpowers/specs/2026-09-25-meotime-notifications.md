# Meotime — Notifications (Deferred Plan)

Date: 2026-09-25
Status: Deferred — NOT part of MVP. Documented per request so the design is captured
separately from the MVP build.

## Why deferred

The core product is the intervention overlay. Notifications are heads-up nudges that
complement the overlay but are not required for the MVP success scenario. Building them
now would inflate MVP scope and delay the accessibility/usage spike.

## Design intent (future)

When the user approaches their daily limit, Meotime sends a notification before the overlay
triggers.

Example content:

> Instagram — You've used 25 of your 30 minute limit.

## Requirements (for later)

1. Notification posted once, shortly before limit is reached (e.g. at 80% of limit, or at
   limit minus 5 minutes).
2. Only for monitored, enabled apps.
3. Must not spam — one notification per app per day, or re-postable only on state change
   (limit increased, day reset).
4. Uses `POST_NOTIFICATIONS` runtime permission (Android 13+); degrade gracefully if
   denied (overlay still works).
5. No foreground service: notifications can be triggered from MonitoringCore via the
   existing access service callback path, or via WorkManager if a fire-and-forget
   reminder suffices.
6. Tap → opens Meotime for the relevant app (deep link to `appsettings/{package}`).
7. Follows the monochrome minimal theme.

## Open decisions for when implemented

- Trigger point: percentage-based vs fixed-minutes-before.
- Channel/naming.
- Whether history (P1) ships with it.