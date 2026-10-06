# CabFusion – AI Powered Shared Mobility Platform

Android app (Kotlin, XML layouts) that pools riders going the same way into one cab and splits the fare.
Mobile Application Development (CS4504) PBL project, Chennai Institute of Technology.
Team: Hariss Kumar K (24CS0309), Divesh Kumar S (24CS0225). Mentor: Mrs. W. Lydia Shammi.

## How matching works
A shared request is compared with every open request:

1. **Hard gates** – departures within 30 min, pickups within 2.5 km, directions within 60°, and for the best
   of the four pickup/drop orders nobody rides more than 1.45× their solo distance.
2. **Score (0–100)** – 0.45 × route overlap (400 m corridor, 100 m sampling) + 0.20 × direction
   + 0.15 × pickup proximity + 0.20 × detour. Candidates scoring ≥ 40 are ranked.
3. **Group** – greedy, up to 3 riders, every pair compatible; stops ordered pickups-first.
4. **Fare** – ₹50 + ₹14/km + ₹1.5/min (min ₹80). Pooled fare = shared-route fare × 0.85, split by solo
   distance, never above a rider's solo fare.

Group formation and driver acceptance run as Firestore transactions, so a rider can't be in two cabs
and a ride can't get two drivers.

## Stack
Kotlin · coroutines/Flow · View Binding · Material 3 · Firebase Auth + Cloud Firestore · osmdroid (OpenStreetMap)
· OSRM routing · Photon geocoding · Retrofit/OkHttp · Fused Location Provider.

## Project layout
```
app/src/main/java/com/cabfusion/app/
  core/     Geo, RouteMatcher, FareCalculator, StopPlanner   (pure Kotlin, unit-tested)
  domain/   MatchingService
  data/     Backend, FirebaseBackend, LocalBackend (demo), MapsRepository, ServiceLocator, model/, net/
  ui/       Splash, Login, Register, Dashboard, LocationSelection, RideStatus, MyRides, Profile, Driver
firestore.rules                 Firestore security rules
.github/workflows/android.yml   build + unit tests + emulator UI test, results pushed to the ci-results branch
```

## Run it
1. Open in Android Studio (JDK 17, Android SDK 34) and run, or `./gradlew assembleDebug`.
2. With `app/google-services.json` present the app uses Firebase; without it, it runs on the built-in
   demo backend (sample Chennai riders and a simulated driver).
3. Firebase setup: enable **Email/Password** auth, create **Cloud Firestore**, publish `firestore.rules`.

Prebuilt APK: `CabFusion-debug.apk` on the `ci-results` branch.

## Tests
- `./gradlew testDebugUnitTest` – EngineTest (11), StopPlannerTest, FallbackTest, MatchingEvaluationTest
  (10 Chennai trip pairs with real OSRM routes, group-of-three run, latency up to 1,000 requests).
- `./gradlew connectedDebugAndroidTest` – BackendRulesTest (transaction rules) and ScreenshotFlowTest
  (full rider + driver flow with a screenshot at every step).
