# FastShare
Send/receive big files (videos) between Android phones on the same Wi-Fi. Resumable. Android 9+.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo, upload all files of this folder (keep the .github folder).
2. Open the repo -> Actions tab -> "Build APK" (runs automatically, or press Run workflow).
3. When finished, open the run -> Artifacts -> download FastShare-apk -> unzip -> app-debug.apk.
4. Copy to both phones and install (allow "install unknown apps").

## Or build with Android Studio
Open this folder in Android Studio (Hedgehog or newer), let it sync, Build > Build APK(s).

## Use
Receiver phone: Choose save folder -> Start receiving (shows its IP).
Sender phone: tap "Find receiver automatically" (or type the IP) -> Choose files & send.
If the connection drops or the app is killed, send the same file again: it continues from where it stopped.
