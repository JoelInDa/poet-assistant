/*
 * Copyright (c) 2016 - present Carmen Alvarez
 *
 * This file is part of Poet Assistant.
 *
 * Poet Assistant is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Poet Assistant is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Poet Assistant.  If not, see <http://www.gnu.org/licenses/>.
 */

package ca.rmen.android.poetassistant.shared.main;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import static ca.rmen.android.poetassistant.main.CustomViewMatchers.isActivated;
import static ca.rmen.android.poetassistant.main.TestAppUtils.search;

import androidx.test.espresso.ViewInteraction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import ca.rmen.android.poetassistant.R;
import ca.rmen.android.poetassistant.main.MainActivity;
import ca.rmen.android.poetassistant.main.rules.PoetAssistantActivityTestRule;
import dagger.hilt.android.testing.HiltAndroidRule;
import dagger.hilt.android.testing.HiltAndroidTest;
import dagger.hilt.android.testing.HiltTestApplication;

/**
 * The M2 redesign made favoriting a word a long-press on its pill (a plain tap navigates). A
 * favorited word is shown with a gold border, driven by the view's {@code activated} state.
 * Favorites no longer appear as a section inside the rhymer/thesaurus lists (only on the dedicated
 * favorites tab), so a rhyme appears exactly once here and its activated state is an unambiguous
 * signal that the long-press toggled the favorite.
 */
@LargeTest
@HiltAndroidTest
@Config(application = HiltTestApplication.class)
@RunWith(AndroidJUnit4.class)
public class FavoriteWordTest {

    @Rule(order = 0)
    public HiltAndroidRule hiltTestRule = new HiltAndroidRule(this);

    @Rule(order = 1)
    public PoetAssistantActivityTestRule<MainActivity> mActivityTestRule = new PoetAssistantActivityTestRule<>(MainActivity.class, true);

    private ViewInteraction rhyme(String word) {
        return onView(allOf(withId(R.id.text1), withText(word),
                isDescendantOfA(withId(R.id.rhymer_recycler_view)), isDisplayed()));
    }

    @Test
    public void longPressFavoritesWord() {
        search("donkey");
        rhyme("swanky").check(matches(not(isActivated())));
        rhyme("swanky").perform(longClick());
        rhyme("swanky").check(matches(isActivated()));
    }

    @Test
    public void longPressAgainRemovesFavorite() {
        search("donkey");
        rhyme("swanky").perform(longClick());
        rhyme("swanky").check(matches(isActivated()));
        rhyme("swanky").perform(longClick());
        rhyme("swanky").check(matches(not(isActivated())));
    }
}
