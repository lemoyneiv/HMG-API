# When two mods want the same thing

HMG-API settles it, and the plan says how. Mods never overwrite each other.

| Both want | What happens |
|---|---|
| the same file change | combined when they fit together (for example two mods adding different video codecs); otherwise a conflict, and neither change is made until you remove or turn off one |
| a button press, both keeping the car's action | both get it |
| a button press, both replacing the car's action | one gets it (the plan says which) |
| Android Auto's density | one value wins (the plan says which) |
| the ambient light, the rear camera | one mod at a time; the other is told who has it |
| battery conditioning | one shared on/off; a stop you or a mod asks for wins over an automatic start |
| the same feature flag or Wi-Fi client | shared: it stays on while any mod wants it |
| not to run next to each other (a mod's `conflicts`, such as two builds of one mod) | the first in load order runs, the other is shown as a conflict until you turn one off |

## Picking which mod gets a button or the density

For now this is a setting in HMG-API's settings file, changed over adb (a picker
in the app is planned):

1. Copy the file out:
   ```
   adb shell "hmgapid sh 'cp /data/hmgapi/settings/hmgapi.json /data/local/tmp/ 2>/dev/null; chmod 644 /data/local/tmp/hmgapi.json'"
   ```
   ```
   adb pull /data/local/tmp/hmgapi.json
   ```
   (No file yet: start from `{}`.)
2. Add, inside `"overrides"` (create it if it isn't there):
   `"input": {"tune_enter hold": "<mod id>"}` for a button press, or
   `"projection": {"aa_density": "<mod id>"}` for Android Auto's density.
3. Copy it back and apply:
   ```
   adb push hmgapi.json /data/local/tmp/hmgapi.json
   ```
   ```
   adb shell "hmgapid sh 'cp /data/local/tmp/hmgapi.json /data/hmgapi/settings/hmgapi.json && rm /data/local/tmp/hmgapi.json'"
   ```
   Then **Apply changes** in the app.
