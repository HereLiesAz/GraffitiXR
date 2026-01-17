### 8. `fauxpas.md`
*What NOT to do.*

```markdown
# Development Faux Pas (Do Not Do This)

## 1. Do NOT add Cloud Dependencies
* Adding Firebase Database, AWS Amplify, or similar "backend-as-a-service" SDKs will result in immediate rejection. We are off-grid.

## 2. Do NOT break the Rail
* Do not add a `BottomNavigationView`.
* Do not add a Hamburger Menu (`DrawerLayout`).
* The `AzNavRail` is the **only** navigation paradigm.

## 3. Do NOT leak Native Memory
* In `MobileGS.cpp`, every `new` must have a `delete`.
* Be extremely careful with JNI `GetPrimitiveArrayCritical`. Do not perform blocking operations while holding a primitive lock.

## 4. Do NOT block the UI Thread
* Never call `MobileGS::saveModel()` on the Main Thread. It writes megabytes to disk. Use a Coroutine with `Dispatchers.IO`.