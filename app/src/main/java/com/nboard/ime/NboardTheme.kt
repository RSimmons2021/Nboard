package com.nboard.ime

internal fun themeStyleFor(mode: AppThemeMode): Int =
    if (mode == AppThemeMode.AMOLED) R.style.Theme_Nboard_Amoled else R.style.Theme_Nboard
