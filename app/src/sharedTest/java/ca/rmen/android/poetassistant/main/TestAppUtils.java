/*
 * Copyright (c) 2017 Carmen Alvarez
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

package ca.rmen.android.poetassistant.main;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.IdRes;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;

import androidx.test.espresso.ViewInteraction;
import android.text.TextUtils;

import ca.rmen.android.poetassistant.R;
import ca.rmen.android.poetassistant.main.dictionaries.ResultListFactory;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.pressImeActionButton;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isFocusable;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static ca.rmen.android.poetassistant.main.TestUiUtils.checkTitleStripOrTab;
import static ca.rmen.android.poetassistant.main.TestUiUtils.clickPreference;
import static ca.rmen.android.poetassistant.main.TestUiUtils.openMenuItem;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Utility functions specific to the functionality of this app.
 */
public class TestAppUtils {
    private TestAppUtils() {
        // prevent instantiation
    }


    public static void openSearchView() {
        // Tap on the search icon in the action bar
        onView(allOf(withId(R.id.open_search_bar_text_view), isDisplayed())).perform(click());
    }

    public static ViewInteraction typeQuery(String query) {
        // Type the query term and search
        getInstrumentation().waitForIdleSync();
        ViewInteraction searchAutoComplete = onView(allOf(withId(R.id.open_search_view_edit_text), isDisplayed()));
        searchAutoComplete.check(matches(isDisplayed()));
        searchAutoComplete.perform(typeText(query));
        getInstrumentation().waitForIdleSync();
        return searchAutoComplete;
    }

    public static void search(String query) {
        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        SystemClock.sleep(1000);
        openSearchView();

        // Type the query term and search
        ViewInteraction vi = typeQuery(query);
        getInstrumentation().waitForIdleSync();
        vi.perform(pressImeActionButton());
        getInstrumentation().waitForIdleSync();
    }

    // The per-row R/T/D icons are gone (M2 pill redesign). Cross-mode lookup is now: tap the
    // word to search it in the current mode, then move to the target mode. Both helpers therefore
    // behave like their former "clean layout" counterparts.
    public static void openThesaurus(Context context, String entry, String expectedFirstSynonym) {
        openThesaurusCleanLayout(context, entry, expectedFirstSynonym);
    }

    public static void openThesaurusCleanLayout(Context context, String entry, String expectedFirstSynonym) {
        onView(withText(entry)).perform(click());
        onView(withText(R.string.tab_thesaurus)).perform(click());
        CustomChecks.checkFirstSynonym(expectedFirstSynonym);
        checkTitleStripOrTab(context, R.string.tab_thesaurus);
    }

    public static void openDictionary(Context context, String entry, String expectedFirstDefinition) {
        openDictionaryCleanLayout(context, entry, expectedFirstDefinition);
    }

    public static void openDictionaryCleanLayout(Context context, String entry, String expectedFirstDefinition) {
        onView(withText(entry)).perform(click());
        onView(withText(R.string.tab_dictionary)).perform(click());
        checkTitleStripOrTab(context, R.string.tab_dictionary);
        CustomChecks.checkFirstDefinition(expectedFirstDefinition);
    }

    public static void starQueryWord() {
        ViewInteraction starIcon = onView(
                allOf(withId(R.id.btn_star_query), isDisplayed()));
        starIcon.check(matches(isNotChecked()));
        starIcon.perform(click());
        starIcon.check(matches(isChecked()));
    }

    public static void unStarQueryWord() {
        ViewInteraction starIcon = onView(
                allOf(withId(R.id.btn_star_query), isDisplayed()));
        starIcon.check(matches(isChecked()));
        starIcon.perform(click());
        onView(allOf(withId(R.id.btn_star_query), isDisplayed()))
                .check(matches(isNotChecked()));
    }

    public static ViewInteraction openFilter(String expectedPrefilledFilter) {
        getInstrumentation().waitForIdleSync();
        ViewInteraction vi = onView(allOf(withId(R.id.btn_filter), withContentDescription(R.string.filter_title), isDisplayed()));
        vi.check(matches(isDisplayed()));
        vi.perform(click());
        SystemClock.sleep(200);
        ViewInteraction result = onView(allOf(
                withId(R.id.edit),
                isDisplayed()))
                .inRoot(isFocusable());
        result.check(matches(withText(expectedPrefilledFilter)));
        return result;
    }

    public static void addFilter(Tab tab, String filter, String firstExpectedFilteredMatch) {
        @IdRes int recyclerViewId = ResultListFactory.INSTANCE.getRecyclerViewId(tab);
        ViewInteraction filterView = openFilter("");
        filterView.perform(typeText(filter), closeSoftKeyboard());
        clickDialogPositiveButton(android.R.string.ok);

        if (TextUtils.isEmpty(firstExpectedFilteredMatch)) {
            onView(allOf(withId(R.id.empty), hasSibling(withId(recyclerViewId)), isDisplayed()))
                    .check(matches(isDisplayed()));
        } else {
            onView(allOf(withId(R.id.empty), hasSibling(allOf(withId(recyclerViewId), isDisplayed()))))
                    .check(matches(not(isDisplayed())));
            // Pills are direct children of the recycler view now (was text -> row -> recycler).
            onView(allOf(withId(R.id.text1),
                    withText(firstExpectedFilteredMatch),
                    withParent(withId(recyclerViewId)),
                    isDisplayed()))
                    .check(matches(withText(firstExpectedFilteredMatch)));
        }

    }

    public static void clearFilter(Tab tab, String firstExpectedNonFilteredMatch) {
        @IdRes int recyclerViewId = ResultListFactory.INSTANCE.getRecyclerViewId(tab);
        onView(allOf(withId(R.id.btn_clear), withContentDescription(R.string.filter_clear), isDisplayed()))
                .perform(click());

        onView(allOf(withId(R.id.text1),
                withText(firstExpectedNonFilteredMatch),
                withParent(withParent(withId(recyclerViewId))),
                isDisplayed()))
                .check(matches(withText(firstExpectedNonFilteredMatch)));
    }

    public static void clearStarredWords() {
        onView(allOf(withId(R.id.btn_delete), withContentDescription(R.string.action_clear_favorites), isDisplayed())).perform(click());
        clickDialogPositiveButton(R.string.action_clear);
    }

    public static void clickDialogPositiveButton(@StringRes int labelRes) {
        // Top ok on the confirmation dialog
        SystemClock.sleep(200);
        onView(allOf(withId(android.R.id.button1), withText(labelRes))).perform(scrollTo(), click());
    }





    public static void onNewIntent(MainActivity activity, Intent intent) {
        activity.onNewIntent(intent);
    }
}
