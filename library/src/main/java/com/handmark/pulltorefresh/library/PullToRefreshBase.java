/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.handmark.pulltorefresh.library;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.handmark.pulltorefresh.library.internal.LoadingLayout;

import static com.handmark.pulltorefresh.library.PullToRefreshBase.Mode.PULL_FROM_END;
import static com.handmark.pulltorefresh.library.PullToRefreshBase.Mode.PULL_FROM_START;

public abstract class PullToRefreshBase<T extends View> extends LinearLayout {

    private final boolean USE_HW_LAYERS = false;

    private final float FRICTION = 2.0f;

    private final int SMOOTH_SCROLL_DURATION_MS = 200;
    private final int SMOOTH_SCROLL_LONG_DURATION_MS = 325;
    private final int DEMO_SCROLL_INTERVAL = 225;

    private final String STATE_STATE = "ptr_state";
    private final String STATE_MODE = "ptr_mode";
    private final String STATE_CURRENT_MODE = "ptr_current_mode";
    private final String STATE_SCROLLING_REFRESHING_ENABLED = "ptr_disable_scrolling";
    private final String STATE_SHOW_REFRESHING_VIEW = "ptr_show_refreshing_view";
    private final String STATE_SUPER = "ptr_super";

    private int mTouchSlop;
    private float mLastMotionY;
    private float mInitialMotionY;

    private boolean mIsBeingDragged = false;
    private State mState = State.RESET;
    private Mode mMode = Mode.getDefault();

    private Mode mCurrentMode;
    T mRefreshableView;
    private FrameLayout mRefreshableViewWrapper;

    private boolean mShowViewWhileRefreshing = true;
    private boolean mScrollingWhileRefreshingEnabled = false;
    private boolean mFilterTouchEvents = true;
    private boolean mOverScrollEnabled = true;
    private boolean mLayoutVisibilityChangesEnabled = true;
    private boolean mShowLoading = true;

    private Interpolator mScrollAnimationInterpolator;

    private ILoadingLayout mHeaderLayout;
    private ILoadingLayout mFooterLayout;

    private OnRefreshListener<T> mOnRefreshListener;
    private OnRefreshListener2<T> mOnRefreshListener2;
    private OnPullEventListener<T> mOnPullEventListener;

    private SmoothScrollRunnable mCurrentSmoothScrollRunnable;

    public PullToRefreshBase(Context context) {
        super(context);
        init(context, null);
    }

    public PullToRefreshBase(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public PullToRefreshBase(Context context, Mode mode) {
        super(context);
        mMode = mode;
        init(context, null);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {

        final T refreshableView = getRefreshableView();

        if (refreshableView instanceof ViewGroup) {
            ((ViewGroup) refreshableView).addView(child, index, params);
        } else {
            throw new UnsupportedOperationException("Refreshable View is not a ViewGroup so can't addView");
        }
    }

    public final Mode getCurrentMode() {
        return mCurrentMode;
    }

    public final boolean getFilterTouchEvents() {
        return mFilterTouchEvents;
    }

    public final Mode getMode() {
        return mMode;
    }

    public final T getRefreshableView() {
        return mRefreshableView;
    }

    public final boolean getShowViewWhileRefreshing() {
        return mShowViewWhileRefreshing;
    }

    public final State getState() {
        return mState;
    }

    /**
     * @deprecated See {@link #isScrollingWhileRefreshingEnabled()}.
     */
    public final boolean isDisableScrollingWhileRefreshing() {
        return !isScrollingWhileRefreshingEnabled();
    }

    public final boolean isPullToRefreshEnabled() {
        return mMode.permitsPullToRefresh();
    }

    public final boolean isPullToRefreshOverScrollEnabled() {
        return mOverScrollEnabled && OverScrollHelper.isAndroidOverScrollEnabled(mRefreshableView);
    }

    public final boolean isRefreshing() {
        return mState == State.REFRESHING || mState == State.MANUAL_REFRESHING;
    }

    public final boolean isScrollingWhileRefreshingEnabled() {
        return mScrollingWhileRefreshingEnabled;
    }

    public final void onRefreshComplete() {
        if (isRefreshing()) {
            setState(State.RESET);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isPullToRefreshEnabled()) {
            return false;
        }
        final int action = event.getAction();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mIsBeingDragged = false;
            return false;
        }
        if (action != MotionEvent.ACTION_DOWN && mIsBeingDragged) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                //若正在刷新中且设置了刷新中不可滑动
                if (!mScrollingWhileRefreshingEnabled && isRefreshing()) {
                    return true;
                }
                if (isReadyForPull()) {
                    final float y = event.getY();
                    final float diff;
                    final float absDiff;
                    diff = y - mLastMotionY;
                    absDiff = Math.abs(diff);
                    if (absDiff > mTouchSlop) {
                        if (mMode.showHeaderLoadingLayout() && diff >= 1f && isReadyForPullStart()) {
                            mLastMotionY = y;
                            mIsBeingDragged = true;
                            if (mMode == Mode.BOTH) {
                                mCurrentMode = Mode.PULL_FROM_START;
                            }
                        } else if (mMode.showFooterLoadingLayout() && diff <= -1f && isReadyForPullEnd()) {
                            mLastMotionY = y;
                            mIsBeingDragged = true;
                            if (mMode == Mode.BOTH) {
                                mCurrentMode = PULL_FROM_END;
                            }
                        }
                    }
                }
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                if (isReadyForPull()) {
                    mLastMotionY = mInitialMotionY = event.getY();
                    mIsBeingDragged = false;
                }
                break;
            }
        }

        return mIsBeingDragged;
    }

    @Override
    public final boolean onTouchEvent(MotionEvent event) {
        if (!isPullToRefreshEnabled()) {
            return false;
        }
        //若正在刷新中且设置了刷新中不可滑动
        if (!mScrollingWhileRefreshingEnabled && isRefreshing()) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN && event.getEdgeFlags() != 0) {
            return false;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                if (mIsBeingDragged) {
                    mLastMotionY = event.getY();
                    pullEvent();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                if (isReadyForPull()) {
                    mLastMotionY = mInitialMotionY = event.getY();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                if (mIsBeingDragged) {
                    mIsBeingDragged = false;

                    if (mState == State.RELEASE_TO_REFRESH
                            && (null != mOnRefreshListener || null != mOnRefreshListener2)) {
                        setState(State.REFRESHING, true);
                        return true;
                    }
                    // If we're already refreshing, just scroll back to the top
                    if (isRefreshing()) {
                        smoothScrollTo(0);
                        return true;
                    }
                    // If we haven't returned by here, then we're not in a state
                    // to pull, so just reset
                    setState(State.RESET);
                    return true;
                }
                break;
            }
        }

        return false;
    }

    public final void setScrollingWhileRefreshingEnabled(boolean allowScrollingWhileRefreshing) {
        mScrollingWhileRefreshingEnabled = allowScrollingWhileRefreshing;
    }

    /**
     * @deprecated See {@link #setScrollingWhileRefreshingEnabled(boolean)}
     */
    public void setDisableScrollingWhileRefreshing(boolean disableScrollingWhileRefreshing) {
        setScrollingWhileRefreshingEnabled(!disableScrollingWhileRefreshing);
    }

    public final void setFilterTouchEvents(boolean filterEvents) {
        mFilterTouchEvents = filterEvents;
    }

    @Override
    public void setLongClickable(boolean longClickable) {
        getRefreshableView().setLongClickable(longClickable);
    }

    public final void setMode(Mode mode) {
        if (mode != mMode) {
            mMode = mode;
            updateUIForMode();
        }
    }

    public void setOnPullEventListener(OnPullEventListener<T> listener) {
        mOnPullEventListener = listener;
    }

    public final void setOnRefreshListener(OnRefreshListener<T> listener) {
        mOnRefreshListener = listener;
        mOnRefreshListener2 = null;
    }

    public final void setOnRefreshListener(OnRefreshListener2<T> listener) {
        mOnRefreshListener2 = listener;
        mOnRefreshListener = null;
    }

    /**
     * @param enable Whether Pull-To-Refresh should be used
     * @deprecated This simple calls setMode with an appropriate mode based on
     * the passed value.
     */
    public final void setPullToRefreshEnabled(boolean enable) {
        setMode(enable ? Mode.getDefault() : Mode.DISABLED);
    }

    public final void setPullToRefreshOverScrollEnabled(boolean enabled) {
        mOverScrollEnabled = enabled;
    }

    public final void setRefreshing() {
        setRefreshing(true);
    }

    public final void setRefreshing(boolean doScroll) {
        if (!isRefreshing()) {
            setState(State.MANUAL_REFRESHING, doScroll);
        }
    }

    public void setScrollAnimationInterpolator(Interpolator interpolator) {
        mScrollAnimationInterpolator = interpolator;
    }

    public final void setShowViewWhileRefreshing(boolean showView) {
        mShowViewWhileRefreshing = showView;
    }

    final void setState(State state, final boolean... params) {
        mState = state;
        switch (mState) {
            case RESET:
                onReset();
                break;
            case PULL_TO_REFRESH:
                onPullToRefresh();
                break;
            case RELEASE_TO_REFRESH:
                onReleaseToRefresh();
                break;
            case REFRESHING:
            case MANUAL_REFRESHING:
                onRefreshing(params[0]);
                break;
            case OVERSCROLLING:
                // NO-OP
                break;
        }

        // Call OnPullEventListener
        if (null != mOnPullEventListener) {
            mOnPullEventListener.onPullEvent(this, mState, mCurrentMode);
        }
    }

    /**
     * Used internally for adding view. Need because we override addView to
     * pass-through to the Refreshable View
     */
    protected final void addViewInternal(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
    }

    /**
     * Used internally for adding view. Need because we override addView to
     * pass-through to the Refreshable View
     */
    protected final void addViewInternal(View child, ViewGroup.LayoutParams params) {
        super.addView(child, -1, params);
    }

    /**
     * 创建顶部下拉刷新View
     *
     * @param context
     * @param attrs
     * @return
     */
    public ILoadingLayout createHeaderLoadingLayout(Context context, TypedArray attrs) {
        return new LoadingLayout(context, Mode.PULL_FROM_START, attrs);
    }

    /**
     * 创建底部上拉加载刷新View
     *
     * @param context
     * @param attrs
     * @return
     */
    public ILoadingLayout createFooterLoadingLayout(Context context, TypedArray attrs) {
        return new LoadingLayout(context, PULL_FROM_END, attrs);
    }

    /**
     * This is implemented by derived classes to return the created View. If you
     * need to use a custom View (such as a custom ListView), override this
     * method and return an instance of your custom class.
     * <p/>
     * Be sure to set the ID of the view in this method, especially if you're
     * using a ListActivity or ListFragment.
     *
     * @param context Context to create view with
     * @param attrs   AttributeSet from wrapped class. Means that anything you
     *                include in the XML layout declaration will be routed to the
     *                created View
     * @return New instance of the Refreshable View
     */
    protected abstract T createRefreshableView(Context context, AttributeSet attrs);

    protected final void disableLoadingLayoutVisibilityChanges() {
        mLayoutVisibilityChangesEnabled = false;
    }

    protected final ILoadingLayout getFooterLayout() {
        return mFooterLayout;
    }

    protected final int getFooterSize() {
        return mFooterLayout.getContentSize();
    }

    protected final ILoadingLayout getHeaderLayout() {
        return mHeaderLayout;
    }

    protected final int getHeaderSize() {
        return mHeaderLayout.getContentSize();
    }

    protected int getPullToRefreshScrollDuration() {
        return SMOOTH_SCROLL_DURATION_MS;
    }

    protected int getPullToRefreshScrollDurationLonger() {
        return SMOOTH_SCROLL_LONG_DURATION_MS;
    }

    protected FrameLayout getRefreshableViewWrapper() {
        return mRefreshableViewWrapper;
    }

    /**
     * Allows Derivative classes to handle the XML Attrs without creating a
     * TypedArray themsevles
     *
     * @param a - TypedArray of PullToRefresh Attributes
     */
    protected void handleStyledAttributes(TypedArray a) {
    }

    /**
     * Implemented by derived class to return whether the View is in a state
     * where the user can Pull to Refresh by scrolling from the end.
     *
     * @return true if the View is currently in the correct state (for example,
     * bottom of a ListView)
     */
    protected abstract boolean isReadyForPullEnd();

    /**
     * Implemented by derived class to return whether the View is in a state
     * where the user can Pull to Refresh by scrolling from the start.
     *
     * @return true if the View is currently the correct state (for example, top
     * of a ListView)
     */
    protected abstract boolean isReadyForPullStart();

    /**
     * Called by {@link #onRestoreInstanceState(Parcelable)} so that derivative
     * classes can handle their saved instance state.
     *
     * @param savedInstanceState - Bundle which contains saved instance state.
     */
    protected void onPtrRestoreInstanceState(Bundle savedInstanceState) {
    }

    /**
     * Called by {@link #onSaveInstanceState()} so that derivative classes can
     * save their instance state.
     *
     * @param saveState - Bundle to be updated with saved state.
     */
    protected void onPtrSaveInstanceState(Bundle saveState) {
    }

    /**
     * Called when the UI has been to be updated to be in the
     * {@link State#PULL_TO_REFRESH} state.
     */
    protected void onPullToRefresh() {
        switch (mCurrentMode) {
            case PULL_FROM_END:
                mFooterLayout.pullToRefresh();
                break;
            case PULL_FROM_START:
                mHeaderLayout.pullToRefresh();
                break;
            default:
                // NO-OP
                break;
        }
    }

    /**
     * Called when the UI has been to be updated to be in the
     * {@link State#REFRESHING} or {@link State#MANUAL_REFRESHING} state.
     *
     * @param doScroll - Whether the UI should scroll for this event.
     */
    protected void onRefreshing(final boolean doScroll) {
        if (mCurrentMode == PULL_FROM_START && mMode.showHeaderLoadingLayout()) {
            mHeaderLayout.refreshing();
        }
        if (mCurrentMode == PULL_FROM_END && mMode.showFooterLoadingLayout()) {
            mFooterLayout.refreshing();
        }

        if (doScroll) {
            if (mShowViewWhileRefreshing) {

                // Call Refresh Listener when the Scroll has finished
                OnSmoothScrollFinishedListener listener = new OnSmoothScrollFinishedListener() {
                    @Override
                    public void onSmoothScrollFinished() {
                        callRefreshListener();
                    }
                };

                switch (mCurrentMode) {
                    case MANUAL_REFRESH_ONLY:
                    case PULL_FROM_END:
                        smoothScrollTo(getFooterSize(), listener);
                        break;
                    default:
                    case PULL_FROM_START:
                        smoothScrollTo(-getHeaderSize(), listener);
                        break;
                }
            } else {
                smoothScrollTo(0);
            }
        } else {
            // We're not scrolling, so just call Refresh Listener now
            callRefreshListener();
        }
    }

    /**
     * Called when the UI has been to be updated to be in the
     * {@link State#RELEASE_TO_REFRESH} state.
     */
    protected void onReleaseToRefresh() {
        switch (mCurrentMode) {
            case PULL_FROM_END:
                mFooterLayout.releaseToRefresh();
                break;
            case PULL_FROM_START:
                mHeaderLayout.releaseToRefresh();
                break;
            default:
                // NO-OP
                break;
        }
    }

    /**
     * Called when the UI has been to be updated to be in the
     * {@link State#RESET} state.
     */
    protected void onReset() {
        mIsBeingDragged = false;
        mLayoutVisibilityChangesEnabled = true;

        // Always reset both layouts, just in case...
        if (mCurrentMode == PULL_FROM_END) {
            mFooterLayout.reset();
        }
        if (mCurrentMode == PULL_FROM_START) {
            mHeaderLayout.reset();
        }

        smoothScrollTo(0);
    }

    @Override
    protected final void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;

            setMode(Mode.mapIntToValue(bundle.getInt(STATE_MODE, 0)));
            mCurrentMode = Mode.mapIntToValue(bundle.getInt(STATE_CURRENT_MODE, 0));

            mScrollingWhileRefreshingEnabled = bundle.getBoolean(STATE_SCROLLING_REFRESHING_ENABLED, false);
            mShowViewWhileRefreshing = bundle.getBoolean(STATE_SHOW_REFRESHING_VIEW, true);

            // Let super Restore Itself
            super.onRestoreInstanceState(bundle.getParcelable(STATE_SUPER));

            State viewState = State.mapIntToValue(bundle.getInt(STATE_STATE, 0));
            if (viewState == State.REFRESHING || viewState == State.MANUAL_REFRESHING) {
                setState(viewState, true);
            }

            // Now let derivative classes restore their state
            onPtrRestoreInstanceState(bundle);
            return;
        }

        super.onRestoreInstanceState(state);
    }

    @Override
    protected final Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();

        // Let derivative classes get a chance to save state first, that way we
        // can make sure they don't overrite any of our values
        onPtrSaveInstanceState(bundle);

        bundle.putInt(STATE_STATE, mState.getIntValue());
        bundle.putInt(STATE_MODE, mMode.getIntValue());
        bundle.putInt(STATE_CURRENT_MODE, mCurrentMode.getIntValue());
        bundle.putBoolean(STATE_SCROLLING_REFRESHING_ENABLED, mScrollingWhileRefreshingEnabled);
        bundle.putBoolean(STATE_SHOW_REFRESHING_VIEW, mShowViewWhileRefreshing);
        bundle.putParcelable(STATE_SUPER, super.onSaveInstanceState());

        return bundle;
    }

    @Override
    protected final void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // We need to update the header/footer when our size changes
        refreshLoadingViewsSize();

        // Update the Refreshable View layout
        refreshRefreshableViewSize(w, h);

        /**
         * As we're currently in a Layout Pass, we need to schedule another one
         * to layout any changes we've made here
         */
        post(new Runnable() {
            @Override
            public void run() {
                requestLayout();
            }
        });
    }

    /**
     * Re-measure the Loading Views height, and adjust internal padding as
     * necessary
     */
    protected final void refreshLoadingViewsSize() {
        final int maximumPullScroll = (int) (getMaximumPullScroll() * 1.2f);

        int pLeft = getPaddingLeft();
        int pTop = getPaddingTop();
        int pRight = getPaddingRight();
        int pBottom = getPaddingBottom();

        if (mMode.showHeaderLoadingLayout()) {
            mHeaderLayout.setHeight(maximumPullScroll);
            pTop = -maximumPullScroll;
        } else {
            pTop = 0;
        }

        if (mMode.showFooterLoadingLayout()) {
            mFooterLayout.setHeight(maximumPullScroll);
            pBottom = -maximumPullScroll;
        } else {
            pBottom = 0;
        }
        setPadding(pLeft, pTop, pRight, pBottom);
    }

    protected final void refreshRefreshableViewSize(int width, int height) {
        // We need to set the Height of the Refreshable View to the same as
        // this layout
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mRefreshableViewWrapper.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            mRefreshableViewWrapper.requestLayout();
        }
    }

    /**
     * Helper method which just calls scrollTo() in the correct scrolling
     * direction.
     *
     * @param value - New Scroll value
     */
    protected final void setHeaderScroll(int value) {
        // Clamp value to with pull scroll range
        final int maximumPullScroll = getMaximumPullScroll();
        value = Math.min(maximumPullScroll, Math.max(-maximumPullScroll, value));

        if (mLayoutVisibilityChangesEnabled) {
            if (value < 0) {
                mHeaderLayout.setVisibility(View.VISIBLE);
            } else if (value > 0) {
                mFooterLayout.setVisibility(View.VISIBLE);
            } else {
                mHeaderLayout.setVisibility(View.INVISIBLE);
                mFooterLayout.setVisibility(View.INVISIBLE);
            }
        }

        if (USE_HW_LAYERS) {
            mRefreshableViewWrapper.setLayerType(value != 0 ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        }
        scrollTo(0, value);
    }

    /**
     * Smooth Scroll to position using the default duration of ms.
     *
     * @param scrollValue - Position to scroll to
     */
    protected final void smoothScrollTo(int scrollValue) {
        smoothScrollTo(scrollValue, getPullToRefreshScrollDuration());
    }

    /**
     * Smooth Scroll to position using the default duration of ms.
     *
     * @param scrollValue - Position to scroll to
     * @param listener    - Listener for scroll
     */
    protected final void smoothScrollTo(int scrollValue, OnSmoothScrollFinishedListener listener) {
        smoothScrollTo(scrollValue, getPullToRefreshScrollDuration(), 0, listener);
    }

    /**
     * Smooth Scroll to position using the longer default duration of ms.
     *
     * @param scrollValue - Position to scroll to
     */
    protected final void smoothScrollToLonger(int scrollValue) {
        smoothScrollTo(scrollValue, getPullToRefreshScrollDurationLonger());
    }

    /**
     * Updates the View State when the mode has been set. This does not do any
     * checking that the mode is different to current state so always updates.
     */
    protected void updateUIForMode() {
        // We need to use the correct LayoutParam values, based on scroll
        // direction
        final LinearLayout.LayoutParams lp = getLoadingLayoutLayoutParams();

        // Remove Header, and then add Header Loading View again if needed
        if (this == mHeaderLayout.getParent()) {
            removeView(mHeaderLayout);
        }
        if (mMode.showHeaderLoadingLayout()) {
            addViewInternal(mHeaderLayout, 0, lp);
        }

        // Remove Footer, and then add Footer Loading View again if needed
        if (this == mFooterLayout.getParent()) {
            removeView(mFooterLayout);
        }
        if (mMode.showFooterLoadingLayout()) {
            addViewInternal(mFooterLayout, lp);
        }

        // Hide Loading Views
        refreshLoadingViewsSize();

        // If we're not using Mode.BOTH, set mCurrentMode to mMode, otherwise
        // set it to pull down
        mCurrentMode = (mMode != Mode.BOTH) ? mMode : Mode.PULL_FROM_START;
    }

    private void addRefreshableView(Context context, T refreshableView) {
        mRefreshableViewWrapper = new FrameLayout(context);
        mRefreshableViewWrapper.addView(refreshableView, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        addViewInternal(mRefreshableViewWrapper, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
    }

    private void callRefreshListener() {
        if (!mShowLoading) {
            onRefreshComplete();
            return;
        }
        if (null != mOnRefreshListener) {
            mOnRefreshListener.onRefresh(this);
        } else if (null != mOnRefreshListener2) {
            if (mCurrentMode == Mode.PULL_FROM_START) {
                mOnRefreshListener2.onPullDownToRefresh(this);
            } else if (mCurrentMode == PULL_FROM_END) {
                mOnRefreshListener2.onPullUpToRefresh(this);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void init(Context context, AttributeSet attrs) {
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER);

        ViewConfiguration config = ViewConfiguration.get(context);
        mTouchSlop = config.getScaledTouchSlop();

        // Styleables from XML
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PullToRefresh);

        if (a.hasValue(R.styleable.PullToRefresh_ptrMode)) {
            mMode = Mode.mapIntToValue(a.getInteger(R.styleable.PullToRefresh_ptrMode, 0));
        }

        // Refreshable View
        // By passing the attrs, we can add ListView/GridView params via XML
        mRefreshableView = createRefreshableView(context, attrs);
        addRefreshableView(context, mRefreshableView);

        // We need to create now layouts now
        mHeaderLayout = createHeaderLoadingLayout(context, a);
        mFooterLayout = createFooterLoadingLayout(context, a);

        /**
         * Styleables from XML
         */
        if (a.hasValue(R.styleable.PullToRefresh_ptrRefreshableViewBackground)) {
            Drawable background = a.getDrawable(R.styleable.PullToRefresh_ptrRefreshableViewBackground);
            if (null != background) {
                mRefreshableView.setBackgroundDrawable(background);
            }
        } else if (a.hasValue(R.styleable.PullToRefresh_ptrAdapterViewBackground)) {
            Drawable background = a.getDrawable(R.styleable.PullToRefresh_ptrAdapterViewBackground);
            if (null != background) {
                mRefreshableView.setBackgroundDrawable(background);
            }
        }

        if (a.hasValue(R.styleable.PullToRefresh_ptrShowLoading)) {
            mShowLoading = a.getBoolean(R.styleable.PullToRefresh_ptrShowLoading, true);
        }

        if (a.hasValue(R.styleable.PullToRefresh_ptrOverScroll)) {
            mOverScrollEnabled = a.getBoolean(R.styleable.PullToRefresh_ptrOverScroll, true);
        }

        if (a.hasValue(R.styleable.PullToRefresh_ptrScrollingWhileRefreshingEnabled)) {
            mScrollingWhileRefreshingEnabled = a.getBoolean(
                    R.styleable.PullToRefresh_ptrScrollingWhileRefreshingEnabled, false);
        }

        // Let the derivative classes have a go at handling attributes, then
        // recycle them...
        handleStyledAttributes(a);
        a.recycle();

        // Finally update the UI for the modes
        updateUIForMode();
    }

    private boolean isReadyForPull() {
        switch (mMode) {
            case PULL_FROM_START:
                return isReadyForPullStart();
            case PULL_FROM_END:
                return isReadyForPullEnd();
            case BOTH:
                return isReadyForPullEnd() || isReadyForPullStart();
            default:
                return false;
        }
    }

    /**
     * Actions a Pull Event
     *
     * @return true if the Event has been handled, false if there has been no
     * change
     */
    private void pullEvent() {
        final int newScrollValue;
        final int itemDimension;
        final float initialMotionValue;
        final float lastMotionValue;
        initialMotionValue = mInitialMotionY;
        lastMotionValue = mLastMotionY;

        switch (mCurrentMode) {
            case PULL_FROM_END:
                newScrollValue = Math.round(Math.max(initialMotionValue - lastMotionValue, 0) / FRICTION);
                itemDimension = getFooterSize();
                break;
            case PULL_FROM_START:
            default:
                newScrollValue = Math.round(Math.min(initialMotionValue - lastMotionValue, 0) / FRICTION);
                itemDimension = getHeaderSize();
                break;
        }

        setHeaderScroll(newScrollValue);

        if (newScrollValue != 0 && !isRefreshing()) {
            float scale = Math.abs(newScrollValue) / (float) itemDimension;
            switch (mCurrentMode) {
                case PULL_FROM_END:
                    mFooterLayout.onPull(scale);
                    break;
                case PULL_FROM_START:
                default:
                    mHeaderLayout.onPull(scale);
                    break;
            }

            if (mState != State.PULL_TO_REFRESH && itemDimension >= Math.abs(newScrollValue)) {
                setState(State.PULL_TO_REFRESH);
            } else if (mState == State.PULL_TO_REFRESH && itemDimension < Math.abs(newScrollValue)) {
                setState(State.RELEASE_TO_REFRESH);
            }
        }
    }

    private LinearLayout.LayoutParams getLoadingLayoutLayoutParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int getMaximumPullScroll() {
        return Math.round(getHeight() / FRICTION);
    }

    /**
     * Smooth Scroll to position using the specific duration
     *
     * @param scrollValue - Position to scroll to
     * @param duration    - Duration of animation in milliseconds
     */
    private void smoothScrollTo(int scrollValue, long duration) {
        smoothScrollTo(scrollValue, duration, 0, null);
    }

    private void smoothScrollTo(int newScrollValue, long duration, long delayMillis,
                                OnSmoothScrollFinishedListener listener) {
        if (null != mCurrentSmoothScrollRunnable) {
            mCurrentSmoothScrollRunnable.stop();
        }

        final int oldScrollValue;
        oldScrollValue = getScrollY();

        if (oldScrollValue != newScrollValue) {
            if (null == mScrollAnimationInterpolator) {
                // Default interpolator is a Decelerate Interpolator
                mScrollAnimationInterpolator = new DecelerateInterpolator();
            }
            mCurrentSmoothScrollRunnable = new SmoothScrollRunnable(oldScrollValue, newScrollValue, duration, listener);

            if (delayMillis > 0) {
                postDelayed(mCurrentSmoothScrollRunnable, delayMillis);
            } else {
                post(mCurrentSmoothScrollRunnable);
            }
        }
    }

    private void smoothScrollToAndBack(int y) {
        smoothScrollTo(y, SMOOTH_SCROLL_DURATION_MS, 0, new OnSmoothScrollFinishedListener() {

            @Override
            public void onSmoothScrollFinished() {
                smoothScrollTo(0, SMOOTH_SCROLL_DURATION_MS, DEMO_SCROLL_INTERVAL, null);
            }
        });
    }

    public enum Mode {

        /**
         * Disable all Pull-to-Refresh gesture and Refreshing handling
         */
        DISABLED(0x0),

        /**
         * Only allow the user to Pull from the start of the Refreshable View to
         * refresh. The start is either the Top or Left, depending on the
         * scrolling direction.
         */
        PULL_FROM_START(0x1),

        /**
         * Only allow the user to Pull from the end of the Refreshable View to
         * refresh. The start is either the Bottom or Right, depending on the
         * scrolling direction.
         */
        PULL_FROM_END(0x2),

        /**
         * Allow the user to both Pull from the start, from the end to refresh.
         */
        BOTH(0x3),

        /**
         * Disables Pull-to-Refresh gesture handling, but allows manually
         * setting the Refresh state via
         * {@link PullToRefreshBase#setRefreshing() setRefreshing()}.
         */
        MANUAL_REFRESH_ONLY(0x4);

        /**
         * @deprecated Use {@link #PULL_FROM_START} from now on.
         */
        public static Mode PULL_DOWN_TO_REFRESH = Mode.PULL_FROM_START;

        /**
         * @deprecated Use {@link #PULL_FROM_END} from now on.
         */
        public static Mode PULL_UP_TO_REFRESH = PULL_FROM_END;

        /**
         * Maps an int to a specific mode. This is needed when saving state, or
         * inflating the view from XML where the mode is given through a attr
         * int.
         *
         * @param modeInt - int to map a Mode to
         * @return Mode that modeInt maps to, or PULL_FROM_START by default.
         */
        static Mode mapIntToValue(final int modeInt) {
            for (Mode value : Mode.values()) {
                if (modeInt == value.getIntValue()) {
                    return value;
                }
            }

            // If not, return default
            return getDefault();
        }

        static Mode getDefault() {
            return DISABLED;
        }

        private int mIntValue;

        // The modeInt values need to match those from attrs.xml
        Mode(int modeInt) {
            mIntValue = modeInt;
        }

        /**
         * @return true if the mode permits Pull-to-Refresh
         */
        boolean permitsPullToRefresh() {
            return !(this == DISABLED || this == MANUAL_REFRESH_ONLY);
        }

        /**
         * @return true if this mode wants the Loading Layout Header to be shown
         */
        public boolean showHeaderLoadingLayout() {
            return this == PULL_FROM_START || this == BOTH;
        }

        /**
         * @return true if this mode wants the Loading Layout Footer to be shown
         */
        public boolean showFooterLoadingLayout() {
            return this == PULL_FROM_END || this == BOTH || this == MANUAL_REFRESH_ONLY;
        }

        int getIntValue() {
            return mIntValue;
        }

    }

    /**
     * Listener that allows you to be notified when the user has started or
     * finished a touch event. Useful when you want to append extra UI events
     * (such as sounds). See (
     *
     * @author Chris Banes
     */
    public interface OnPullEventListener<V extends View> {

        /**
         * Called when the internal state has been changed, usually by the user
         * pulling.
         *
         * @param refreshView - View which has had it's state change.
         * @param state       - The new state of View.
         * @param direction   - One of {@link Mode#PULL_FROM_START} or
         *                    {@link Mode#PULL_FROM_END} depending on which direction
         *                    the user is pulling. Only useful when <var>state</var> is
         *                    {@link State#PULL_TO_REFRESH} or
         *                    {@link State#RELEASE_TO_REFRESH}.
         */
        void onPullEvent(final PullToRefreshBase<V> refreshView, State state, Mode direction);

    }

    /**
     * Simple Listener to listen for any callbacks to Refresh.
     *
     * @author Chris Banes
     */
    public interface OnRefreshListener<V extends View> {

        /**
         * onRefresh will be called for both a Pull from start, and Pull from
         * end
         */
        void onRefresh(final PullToRefreshBase<V> refreshView);

    }

    /**
     * An advanced version of the Listener to listen for callbacks to Refresh.
     * This listener is different as it allows you to differentiate between Pull
     * Ups, and Pull Downs.
     *
     * @author Chris Banes
     */
    public interface OnRefreshListener2<V extends View> {
        // TODO These methods need renaming to START/END rather than DOWN/UP

        /**
         * onPullDownToRefresh will be called only when the user has Pulled from
         * the start, and released.
         */
        void onPullDownToRefresh(final PullToRefreshBase<V> refreshView);

        /**
         * onPullUpToRefresh will be called only when the user has Pulled from
         * the end, and released.
         */
        void onPullUpToRefresh(final PullToRefreshBase<V> refreshView);

    }

    public enum State {

        /**
         * When the UI is in a state which means that user is not interacting
         * with the Pull-to-Refresh function.
         */
        RESET(0x0),

        /**
         * When the UI is being pulled by the user, but has not been pulled far
         * enough so that it refreshes when released.
         */
        PULL_TO_REFRESH(0x1),

        /**
         * When the UI is being pulled by the user, and <strong>has</strong>
         * been pulled far enough so that it will refresh when released.
         */
        RELEASE_TO_REFRESH(0x2),

        /**
         * When the UI is currently refreshing, caused by a pull gesture.
         */
        REFRESHING(0x8),

        /**
         * When the UI is currently refreshing, caused by a call to
         * {@link PullToRefreshBase#setRefreshing() setRefreshing()}.
         */
        MANUAL_REFRESHING(0x9),

        /**
         * When the UI is currently overscrolling, caused by a fling on the
         * Refreshable View.
         */
        OVERSCROLLING(0x10);

        /**
         * Maps an int to a specific state. This is needed when saving state.
         *
         * @param stateInt - int to map a State to
         * @return State that stateInt maps to
         */
        static State mapIntToValue(final int stateInt) {
            for (State value : State.values()) {
                if (stateInt == value.getIntValue()) {
                    return value;
                }
            }

            // If not, return default
            return RESET;
        }

        private int mIntValue;

        State(int intValue) {
            mIntValue = intValue;
        }

        int getIntValue() {
            return mIntValue;
        }
    }

    final class SmoothScrollRunnable implements Runnable {
        private final Interpolator mInterpolator;
        private final int mScrollToY;
        private final int mScrollFromY;
        private final long mDuration;
        private OnSmoothScrollFinishedListener mListener;

        private boolean mContinueRunning = true;
        private long mStartTime = -1;
        private int mCurrentY = -1;

        public SmoothScrollRunnable(int fromY, int toY, long duration, OnSmoothScrollFinishedListener listener) {
            mScrollFromY = fromY;
            mScrollToY = toY;
            mInterpolator = mScrollAnimationInterpolator;
            mDuration = duration;
            mListener = listener;
        }

        @Override
        public void run() {

            /**
             * Only set mStartTime if this is the first time we're starting,
             * else actually calculate the Y delta
             */
            if (mStartTime == -1) {
                mStartTime = System.currentTimeMillis();
            } else {

                /**
                 * We do do all calculations in long to reduce software float
                 * calculations. We use 1000 as it gives us good accuracy and
                 * small rounding errors
                 */
                long normalizedTime = (1000 * (System.currentTimeMillis() - mStartTime)) / mDuration;
                normalizedTime = Math.max(Math.min(normalizedTime, 1000), 0);

                final int deltaY = Math.round((mScrollFromY - mScrollToY)
                        * mInterpolator.getInterpolation(normalizedTime / 1000f));
                mCurrentY = mScrollFromY - deltaY;
                setHeaderScroll(mCurrentY);
            }

            // If we're not at the target Y, keep going...
            if (mContinueRunning && mScrollToY != mCurrentY) {
                postOnAnimation(this);
            } else {
                if (null != mListener) {
                    mListener.onSmoothScrollFinished();
                }
            }
        }

        public void stop() {
            mContinueRunning = false;
            removeCallbacks(this);
        }
    }

    interface OnSmoothScrollFinishedListener {
        void onSmoothScrollFinished();
    }

}
