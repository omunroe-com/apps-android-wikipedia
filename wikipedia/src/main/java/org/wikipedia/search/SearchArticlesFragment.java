package org.wikipedia.search;

import org.wikipedia.PageTitle;
import org.wikipedia.R;
import org.wikipedia.Utils;
import org.wikipedia.WikipediaApp;
import org.wikipedia.concurrency.SaneAsyncTask;
import org.wikipedia.events.WikipediaZeroStateChangeEvent;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.page.PageActivity;
import com.squareup.otto.Subscribe;

import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

public class SearchArticlesFragment extends Fragment {
    private static final String ARG_LAST_SEARCHED_TEXT = "lastSearchedText";

    private static final int PANEL_RECENT_SEARCHES = 0;
    private static final int PANEL_TITLE_SEARCH = 1;
    private static final int PANEL_FULL_SEARCH = 2;

    private WikipediaApp app;
    private SearchView searchView;

    /**
     * Whether the Search fragment is currently showing.
     */
    private boolean isSearchActive = false;

    /**
     * The last search term that the user entered. This will be passed into
     * the TitleSearch and FullSearch sub-fragments.
     */
    private String lastSearchedText;

    /**
     * Whether the last search was forced. A search is forced if the user explicitly
     * clicks on the buttons to select Title or Full search. It's also forced if the user
     * is taken to Full search when a Title search produced no results.
     * A search is NOT forced when it comes from the user typing characters in the search field.
     */
    private boolean lastSearchForced = false;

    /**
     * View that contains the whole Search fragment. This is what should be shown/hidden when
     * the search is called for from the main activity.
     */
    private View searchContainerView;

    /**
     * View that contains the two types of search result fragments (Title and Full), as well
     * as the buttons to switch between the two.
     */
    private View searchTypesContainer;

    /**
     * Whether full-text search has been disabled via remote kill-switch.
     * TODO: remove this when we're comfortable that it won't melt down the servers.
     */
    private boolean fullSearchDisabled = false;

    private RecentSearchesFragment recentSearchesFragment;
    private TitleSearchFragment titleSearchFragment;
    private FullSearchFragment fullSearchFragment;

    private View buttonTitleSearch;
    private View buttonFullSearch;
    private int defaultTextColor;

    public SearchArticlesFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = WikipediaApp.getInstance();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        app = (WikipediaApp) getActivity().getApplicationContext();
        app.getBus().register(this);
        View parentLayout = inflater.inflate(R.layout.fragment_search, container, false);

        searchContainerView = parentLayout.findViewById(R.id.search_container);
        searchContainerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Give the root container view an empty click handler, so that click events won't
                // get passed down to any underlying views (e.g. a PageViewFragment on top of which
                // this fragment is shown)
            }
        });

        recentSearchesFragment = (RecentSearchesFragment)getActivity().getSupportFragmentManager().findFragmentById(R.id.search_panel_recent);
        searchTypesContainer = parentLayout.findViewById(R.id.search_panel_types);

        buttonTitleSearch = parentLayout.findViewById(R.id.button_search_title);
        buttonTitleSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getActivePanel() == PANEL_TITLE_SEARCH) {
                    return;
                }
                showPanel(PANEL_TITLE_SEARCH);
                startSearch(lastSearchedText, true);
            }
        });

        buttonFullSearch = parentLayout.findViewById(R.id.button_search_full);
        buttonFullSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getActivePanel() == PANEL_FULL_SEARCH) {
                    return;
                }
                showPanel(PANEL_FULL_SEARCH);
                startSearch(lastSearchedText, true);
            }
        });

        defaultTextColor = ((TextView)buttonFullSearch).getTextColors().getDefaultColor();

        titleSearchFragment = (TitleSearchFragment)getActivity().getSupportFragmentManager().findFragmentById(R.id.fragment_search_title);
        titleSearchFragment.setOnNoResultsListener(new TitleSearchFragment.OnNoResultsListener() {
            @Override
            public void onNoResults() {
                if (lastSearchForced) {
                    // don't automatically go to Full search if the previous search was forced.
                    // i.e. if the user had explicitly clicked on Title search.
                    return;
                }
                if (fullSearchDisabled) {
                    // full-text search disabled by kill-switch
                    return;
                }
                //automatically switch to full-text search!
                showPanel(PANEL_FULL_SEARCH);
                startSearch(lastSearchedText, true);
            }
        });

        fullSearchFragment = (FullSearchFragment)getActivity().getSupportFragmentManager().findFragmentById(R.id.fragment_search_full);

        //make sure we're hidden by default
        searchContainerView.setVisibility(View.GONE);

        if (savedInstanceState != null) {
            lastSearchedText = savedInstanceState.getString(ARG_LAST_SEARCHED_TEXT);
        }
        return parentLayout;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        app.getBus().unregister(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ARG_LAST_SEARCHED_TEXT, lastSearchedText);
    }

    public void switchToTitleSearch(String queryText) {
        if (getActivePanel() == PANEL_TITLE_SEARCH) {
            return;
        }
        showPanel(PANEL_TITLE_SEARCH);
        startSearch(queryText, true);
        searchView.setQuery(queryText, false);
    }

    /**
     * Changes the search text box to contain a different string.
     * @param text The text you want to make the search box display.
     */
    public void setSearchText(String text) {
        searchView.setQuery(text, false);
    }

    /**
     * Show a particular panel, which can be one of:
     * - PANEL_RECENT_SEARCHES
     * - PANEL_TITLE_SEARCH
     * - PANEL_FULL_SEARCH
     * Automatically hides the previous panel.
     * @param panel Which panel to show.
     */
    private void showPanel(int panel) {
        searchTypesContainer.setVisibility(View.GONE);
        recentSearchesFragment.hide();
        titleSearchFragment.hide();
        fullSearchFragment.hide();

        switch (panel) {
            case PANEL_RECENT_SEARCHES:
                recentSearchesFragment.show();
                break;
            case PANEL_TITLE_SEARCH:
                searchTypesContainer.setVisibility(View.VISIBLE);
                buttonTitleSearch.setBackgroundResource(R.drawable.button_shape_flat_highlight);
                ((TextView)buttonTitleSearch).setTextColor(Color.WHITE);
                buttonFullSearch.setBackgroundResource(R.drawable.button_shape_flat);
                ((TextView)buttonFullSearch).setTextColor(defaultTextColor);
                titleSearchFragment.show();
                break;
            case PANEL_FULL_SEARCH:
                searchTypesContainer.setVisibility(View.VISIBLE);
                buttonFullSearch.setBackgroundResource(R.drawable.button_shape_flat_highlight);
                ((TextView)buttonFullSearch).setTextColor(Color.WHITE);
                buttonTitleSearch.setBackgroundResource(R.drawable.button_shape_flat);
                ((TextView)buttonTitleSearch).setTextColor(defaultTextColor);
                fullSearchFragment.show();
                break;
            default:
                break;
        }
    }

    private int getActivePanel() {
        if (searchTypesContainer.getVisibility() == View.VISIBLE) {
            if (titleSearchFragment.isShowing()) {
                return PANEL_TITLE_SEARCH;
            } else if (fullSearchFragment.isShowing()) {
                return PANEL_FULL_SEARCH;
            }
        }
        //otherwise, the recent searches must be showing:
        return PANEL_RECENT_SEARCHES;
    }

    @Subscribe
    public void onWikipediaZeroStateChangeEvent(WikipediaZeroStateChangeEvent event) {
        updateZeroChrome();
    }

    /**
     * Kick off a search, based on a given search term. Will automatically pass the search to
     * Title search or Full search, based on which one is currently displayed.
     * If the search term is empty, the "recent searches" view will be shown.
     * @param term Phrase to search for.
     * @param force Whether to "force" starting this search. If the search is not forced, the
     *              search may be delayed by a small time, so that network requests are not sent
     *              too often.  If the search is forced, the network request is sent immediately.
     */
    public void startSearch(String term, boolean force) {
        lastSearchForced = force;
        if (!isSearchActive) {
            openSearch();
        }

        // TODO: remove this when ready for production
        if (app.getReleaseType() == WikipediaApp.RELEASE_PROD) {
            showPanel(PANEL_TITLE_SEARCH);
        } else {
            if (TextUtils.isEmpty(term)) {
                showPanel(PANEL_RECENT_SEARCHES);
            } else if (getActivePanel() == PANEL_RECENT_SEARCHES) {
                //start with title search...
                showPanel(PANEL_TITLE_SEARCH);
            }
        }

        if (getActivePanel() == PANEL_TITLE_SEARCH) {
            titleSearchFragment.startSearch(term, force);
        } else if (getActivePanel() == PANEL_FULL_SEARCH) {
            fullSearchFragment.startSearch(term, force);
        }
        lastSearchedText = term;
    }

    /**
     * Activate the Search fragment.
     */
    public void openSearch() {
        isSearchActive = true;
        // invalidate our activity's ActionBar, so that we'll have a chance to inject our
        // SearchView into it.
        getActivity().supportInvalidateOptionsMenu();
        ((PageActivity) getActivity()).getDrawerToggle().setDrawerIndicatorEnabled(false);
        // show ourselves
        searchContainerView.setVisibility(View.VISIBLE);

        // TODO: remove this when ready for production
        if (app.getReleaseType() == WikipediaApp.RELEASE_PROD) {
            fullSearchDisabled = true;
            //show title search by default...
            showPanel(PANEL_TITLE_SEARCH);

        } else {
            // find out whether full-text search has been disabled remotely, and
            // hide the title/full switcher buttons accordingly.
            fullSearchDisabled = app.getRemoteConfig().getConfig()
                    .optBoolean("disableFullTextSearch", false);

            //show recent searches by default...
            showPanel(PANEL_RECENT_SEARCHES);
        }
        getView().findViewById(R.id.search_type_button_container)
                .setVisibility(fullSearchDisabled ? View.GONE : View.VISIBLE);
    }

    public void closeSearch() {
        isSearchActive = false;
        // invalidate our activity's ActionBar, so that our SearchView is removed.
        getActivity().supportInvalidateOptionsMenu();
        ((PageActivity) getActivity()).getDrawerToggle().setDrawerIndicatorEnabled(true);
        // hide ourselves
        searchContainerView.setVisibility(View.GONE);
        Utils.hideSoftKeyboard(getActivity());

        // TODO: remove this when ready for production
        if (app.getReleaseType() != WikipediaApp.RELEASE_PROD) {
            addRecentSearch(lastSearchedText);
        }
    }

    /**
     * Determine whether the Search fragment is currently active.
     * @return Whether the Search fragment is active.
     */
    public boolean isSearchActive() {
        return isSearchActive;
    }

    public boolean onBackPressed() {
        if (isSearchActive) {
            closeSearch();
            return true;
        }
        return false;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (isSearchActive) {
            addSearchView(menu);
            // TODO: remove this when ready for production
            if (app.getReleaseType() != WikipediaApp.RELEASE_PROD) {
                addDeleteRecentSearchesMenu(menu);
            }
        }
    }

    private void addSearchView(Menu menu) {
        MenuItem searchAction = menu.add(0, Menu.NONE, Menu.NONE, getString(R.string.search_hint));
        MenuItemCompat.setShowAsAction(searchAction, MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
        searchView = new SearchView(getActivity());
        searchView.setFocusable(true);
        searchView.requestFocusFromTouch();
        searchView.setOnQueryTextListener(searchQueryListener);
        searchView.setOnCloseListener(searchCloseListener);
        searchView.setIconified(false);
        searchView.setInputType(EditorInfo.TYPE_CLASS_TEXT);
        searchView.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchView.setSubmitButtonEnabled(true);
        updateZeroChrome();
        // if we already have a previous search query, then put it into the SearchView, and it will
        // automatically trigger the showing of the corresponding search results.
        if (isValidQuery(lastSearchedText)) {
            searchView.setQuery(lastSearchedText, false);

            // automatically select all text in the search field, so that typing a new character
            // will clear it by default
            EditText editText = getSearchViewEditText(searchView);
            if (editText != null) {
                editText.selectAll();
            }
        }
        MenuItemCompat.setActionView(searchAction, searchView);
    }

    private void addDeleteRecentSearchesMenu(Menu menu) {
        MenuItem deleteAction = menu.add(0, R.id.menu_clear_all_recent_searches, Menu.NONE, getString(R.string.menu_clear_all_recent_searches));
        MenuItemCompat.setShowAsAction(deleteAction, MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        deleteAction.setIcon(R.drawable.ic_delete);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_clear_all_recent_searches:
                new DeleteAllRecentSearchesTask(app).execute();
                return true;
            default:
                return false;
        }
    }

    /**
     * Retrieve the EditText component from inside a SearchView widget, so that operations
     * may be performed on the EditText, such as selecting text.
     * @param parent SearchView from which to retrieve the EditText view.
     * @return EditText view, or null if not found.
     */
    private EditText getSearchViewEditText(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof EditText) {
                return (EditText)parent.getChildAt(i);
            } else if (parent.getChildAt(i) instanceof ViewGroup) {
                EditText et = getSearchViewEditText((ViewGroup)parent.getChildAt(i));
                if (et != null) {
                    return et;
                }
            }
        }
        return null;
    }

    /*
    Update any UI elements related to WP Zero
     */
    private void updateZeroChrome() {
        if (searchView != null) {
            searchView.setQueryHint(
                    app.getWikipediaZeroHandler().isZeroEnabled() ? getString(R.string.zero_search_hint) : getString(R.string.search_hint));
        }
    }

    private boolean isValidQuery(String queryText) {
        return queryText != null && TextUtils.getTrimmedLength(queryText) > 0;
    }

    private final SearchView.OnQueryTextListener searchQueryListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String queryText) {
            if (isValidQuery(queryText)) {
                navigateToTitle(queryText);
            }
            closeSearch();
            return true;
        }

        @Override
        public boolean onQueryTextChange(String queryText) {
            startSearch(queryText.trim(), false);
            return true;
        }
    };

    private final SearchView.OnCloseListener searchCloseListener = new SearchView.OnCloseListener() {
        @Override
        public boolean onClose() {
            closeSearch();
            return false;
        }
    };

    private void navigateToTitle(String queryText) {
        navigateToTitle(new PageTitle(queryText, app.getPrimarySite(), null));
    }

    public void navigateToTitle(PageTitle title) {
        HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_SEARCH);
        Utils.hideSoftKeyboard(getActivity());
        closeSearch();
        ((PageActivity)getActivity()).displayNewPage(title, historyEntry);
    }

    private void addRecentSearch(String title) {
        if (isValidQuery(title)) {
            new SaveRecentSearchTask(new RecentSearch(title)).execute();
        }
    }

    private final class SaveRecentSearchTask extends SaneAsyncTask<Void> {
        private final RecentSearch entry;
        public SaveRecentSearchTask(RecentSearch entry) {
            super(SINGLE_THREAD);
            this.entry = entry;
        }

        @Override
        public Void performTask() throws Throwable {
            app.getPersister(RecentSearch.class).upsert(entry);
            return null;
        }

        @Override
        public void onFinish(Void result) {
            super.onFinish(result);
            recentSearchesFragment.updateList();
        }

        @Override
        public void onCatch(Throwable caught) {
            Log.w("SaveRecentSearchTask", "Caught " + caught.getMessage(), caught);
        }
    }
}
