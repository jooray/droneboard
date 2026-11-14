/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.util.LruCache;

/**
 * Factory for instantiating DictionaryFacilitator objects.
 */
public class DictionaryFacilitatorProvider {
    public static DictionaryFacilitator getDictionaryFacilitator(boolean isNeededForSpellChecking) {
        DictionaryFacilitatorImpl facilitator = new DictionaryFacilitatorImpl();
        // Droneboard: next-word prediction and completion from the language model, for the keyboard only
        facilitator.setUseLanguageModel(!isNeededForSpellChecking);
//        if (isNeededForSpellChecking)
//            facilitator.setValidSpellingWordReadCache(new LruCache<>(200));
        return facilitator;
    }
}
