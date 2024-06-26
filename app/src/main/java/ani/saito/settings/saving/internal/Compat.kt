package ani.saito.settings.saving.internal

import android.content.Context
import ani.saito.settings.saving.PrefManager
import ani.saito.settings.saving.PrefName

class Compat {
    companion object {
        fun importOldPrefs(context: Context) {
            if (PrefManager.getVal(PrefName.HasUpdatedPrefs)) return
            val oldPrefs = context.getSharedPreferences("downloads_pref", Context.MODE_PRIVATE)
            val jsonString = oldPrefs.getString("downloads_key", null)
            PrefManager.setVal(PrefName.DownloadsKeys, jsonString)
            oldPrefs.edit().clear().apply()
            PrefManager.setVal(PrefName.HasUpdatedPrefs, true)
        }
    }
}