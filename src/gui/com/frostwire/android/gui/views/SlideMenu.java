/*
 * A sliding menu for Android, very much like the Google+ and Facebook apps have.
 * 
 * Copyright (C) 2012 CoboltForge
 * 
 * Based upon the great work done by stackoverflow user Scirocco (http://stackoverflow.com/a/11367825/361413), thanks a lot!
 * The XML parsing code comes from https://github.com/darvds/RibbonMenu, thanks!
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Changes by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011, 2012, FrostWire(TM). All rights reserved.
 *
 * Support for styles and visual improvements.
 */

package com.frostwire.android.gui.views;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.TranslateAnimation;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.frostwire.android.R;
import com.frostwire.android.gui.activities.MediaPlayerActivity;
import com.frostwire.android.gui.services.Engine;

public class SlideMenu extends LinearLayout {

    private static final String TAG = "FW.SlideMenu";

    // keys for saving/restoring instance states
    private final static String KEY_MENUSHOWN = "menuWasShown";
    private final static String KEY_STATUSBARHEIGHT = "statusBarHeight";
    private final static String KEY_SUPERSTATE = "superState";

    public static class SlideMenuItem {
        public int id;
        public Drawable icon;
        public String label;
        public boolean selected;
    }

    // a simple adapter
    private class SlideMenuAdapter extends ArrayAdapter<SlideMenuItem> {
        Activity act;
        SlideMenuItem[] items;

        class MenuItemHolder {
            public TextView label;
            public ImageView icon;
        }

        public SlideMenuAdapter(Activity act, SlideMenuItem[] items) {
            super(act, R.id.slidemenu_listitem_label, items);
            this.act = act;
            this.items = items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View rowView = convertView;
            if (rowView == null) {
                LayoutInflater inflater = act.getLayoutInflater();
                rowView = inflater.inflate(R.layout.slidemenu_listitem, null);
                MenuItemHolder viewHolder = new MenuItemHolder();
                viewHolder.label = (TextView) rowView.findViewById(R.id.slidemenu_listitem_label);
                viewHolder.icon = (ImageView) rowView.findViewById(R.id.slidemenu_listitem_icon);
                rowView.setTag(viewHolder);
            }

            MenuItemHolder holder = (MenuItemHolder) rowView.getTag();
            SlideMenuItem item = items[position];

            holder.label.setText(item.label);
            holder.icon.setImageDrawable(item.icon);

            rowView.setBackgroundResource(item.selected ? R.drawable.slidemenu_listitem_background_selected : android.R.color.transparent);

            return rowView;
        }
    }

    private static boolean menuShown = false;
    private int statusHeight;
    private static View menu;
    private static ViewGroup content;
    private static FrameLayout parent;
    private static int menuSize;
    private Activity act;
    private int headerImageRes;
    private TranslateAnimation slideRightAnim;
    private TranslateAnimation slideMenuLeftAnim;
    private TranslateAnimation slideContentLeftAnim;

    private ArrayList<SlideMenuItem> menuItemList;
    private SlideMenuInterface.OnSlideMenuItemClickListener callback;

    private Interpolator smoothInterpolator;
    private int deltaX;
    private int delta;
    private boolean dragging;

    /**
     * Constructor used by the inflation apparatus.
     * To be able to use the SlideMenu, call the {@link #init init()} method.
     * @param context
     */
    public SlideMenu(Context context) {
        super(context);
    }

    /**
     * Constructor used by the inflation apparatus.
     * To be able to use the SlideMenu, call the {@link #init init()} method.
     * @param attrs
     */
    public SlideMenu(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 
     * Constructs a SlideMenu with the given menu XML. 
     * @param act The calling activity.
     * @param menuResource Menu resource identifier.
     * @param cb Callback to be invoked on menu item click.
     * @param slideDuration Slide in/out duration in milliseconds.
     */
    public SlideMenu(Activity act, int menuResource, SlideMenuInterface.OnSlideMenuItemClickListener cb, int slideDuration) {
        super(act);
        init(act, menuResource, cb, slideDuration);
    }

    /** 
     * Constructs an empty SlideMenu.
     * @param act The calling activity.
     * @param cb Callback to be invoked on menu item click.
     * @param slideDuration Slide in/out duration in milliseconds.
     */
    public SlideMenu(Activity act, SlideMenuInterface.OnSlideMenuItemClickListener cb, int slideDuration) {
        this(act, 0, cb, slideDuration);
    }

    /** 
     * If inflated from XML, initializes the SlideMenu.
     * @param act The calling activity.
     * @param menuResource Menu resource identifier, can be 0 for an empty SlideMenu.
     * @param cb Callback to be invoked on menu item click.
     * @param slideDuration Slide in/out duration in milliseconds.
     */
    public void init(Activity act, int menuResource, SlideMenuInterface.OnSlideMenuItemClickListener cb, int slideDuration) {

        this.act = act;
        this.callback = cb;

        // set size
        menuSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 250, act.getResources().getDisplayMetrics());

        /*
         * idea from http://android.cyrilmottier.com/?p=658
         * The making of Prixing #1: Fly-in app menu
         * Cyril Mottier
         * 
         * aldenml: I tried the formula interpolator(t) = (t-1)^5 + 1 without good results
         * thinking in implementing Hermite interpolation in the future.
         */
        smoothInterpolator = new DecelerateInterpolator(1.02f);

        // create animations accordingly
        slideRightAnim = new TranslateAnimation(-menuSize, 0, 0, 0);
        slideRightAnim.setDuration(slideDuration);
        slideRightAnim.setFillAfter(true);
        slideRightAnim.setInterpolator(smoothInterpolator);
        slideMenuLeftAnim = new TranslateAnimation(0, -menuSize, 0, 0);
        slideMenuLeftAnim.setDuration(slideDuration);
        slideMenuLeftAnim.setFillAfter(true);
        slideMenuLeftAnim.setInterpolator(smoothInterpolator);
        slideContentLeftAnim = new TranslateAnimation(menuSize, 0, 0, 0);
        slideContentLeftAnim.setDuration(slideDuration);
        slideContentLeftAnim.setFillAfter(true);
        slideContentLeftAnim.setInterpolator(smoothInterpolator);

        // and get our menu
        parseXml(menuResource);
    }

    /**
     * Sets an optional image to be displayed on top of the menu.
     * @param imageResource
     */
    public void setHeaderImage(int imageResource) {
        headerImageRes = imageResource;
    }

    /**
     * Dynamically adds a menu item.
     * @param item
     */
    public void addMenuItem(SlideMenuItem item) {
        menuItemList.add(item);
    }

    /**
     * Empties the SlideMenu.
     */
    public void clearMenuItems() {
        menuItemList.clear();
    }

    /**
     * Slide the menu in.
     */
    public void show() {
        /*
         *  We have to adopt to status bar height in most cases,
         *  but not if there is a support actionbar!
         */
        try {
            Method getSupportActionBar = act.getClass().getMethod("getSupportActionBar", (Class[]) null);
            Object sab = getSupportActionBar.invoke(act, (Object[]) null);
            sab.toString(); // check for null

            if (android.os.Build.VERSION.SDK_INT >= 11) {
                // over api level 11? add the margin
                applyStatusbarOffset();
            }
        } catch (Exception es) {
            // there is no support action bar!
            applyStatusbarOffset();
        }

        /*
         * phew, finally!
         */
        this.show(true);
    }

    private void show(boolean animate) {
        try {
            // modify content layout params
            try {
                content = ((LinearLayout) act.findViewById(android.R.id.content).getParent());
            } catch (ClassCastException e) {
                /*
                 * When there is no title bar (android:theme="@android:style/Theme.NoTitleBar"),
                 * the android.R.id.content FrameLayout is directly attached to the DecorView,
                 * without the intermediate LinearLayout that holds the titlebar plus content.
                 */
                content = (FrameLayout) act.findViewById(android.R.id.content);
            }
            FrameLayout.LayoutParams parm = new FrameLayout.LayoutParams(-1, -1, 3);
            parm.setMargins(menuSize, 0, -menuSize, 0);
            content.setLayoutParams(parm);
    
            // animation for smooth slide-out
            if (animate)
                content.startAnimation(slideRightAnim);
    
            // add the slide menu to parent
            parent = (FrameLayout) content.getParent();
            LayoutInflater inflater = (LayoutInflater) act.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if (menu != null) {
                parent.removeView(menu);
            }
            menu = inflater.inflate(R.layout.slidemenu, null);
            FrameLayout.LayoutParams lays = new FrameLayout.LayoutParams(-1, -1, 3);
            lays.setMargins(0, statusHeight, 0, 0);
            menu.setLayoutParams(lays);
            parent.addView(menu);
    
            // set header
            try {
                ImageView header = (ImageView) act.findViewById(R.id.slidemenu_header);
                header.setImageDrawable(act.getResources().getDrawable(headerImageRes));
            } catch (Exception e) {
                // not found
            }
    
            View playerItem = (View) act.findViewById(R.id.slidemenu_player_menuitem);
            playerItem.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                    launchPlayerActivity();
                }
            });
    
            // connect the menu's listview
            final ListView list = (ListView) act.findViewById(R.id.slidemenu_listview);
            SlideMenuItem[] items = menuItemList.toArray(new SlideMenuItem[menuItemList.size()]);
            SlideMenuAdapter adap = new SlideMenuAdapter(act, items);
            list.setAdapter(adap);
    
            list.setOnItemClickListener(new OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    hide();
                    if (callback != null) {
                        callback.onSlideMenuItemClick(menuItemList.get(position).id);
                    }
                }
            });
    
            // slide menu in
            if (animate) {
                menu.startAnimation(slideRightAnim);
            }
    
            menu.findViewById(R.id.slidemenu_overlay).setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    SlideMenu.this.hide();
                }
            });
            /*
             * idea from http://android.cyrilmottier.com/?p=701
             * The making of Prixing #2: Swiping the fly-in app menu
             * Cyril Mottier 
             */
            menu.findViewById(R.id.slidemenu_overlay).setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return dragOverlay(event);
                }
            });
            enableDisableViewGroup(content, false);
    
            menuShown = true;
        } catch (Exception catcher) {

        }
    }

    public void setSelectedItem(int id) {
        for (SlideMenuItem item : menuItemList) {
            item.selected = item.id == id;
        }
    }

    public void hide() {
        hide(true);
    }

    /**
     * Slide the menu out.
     */
    private void hide(boolean animate) {
        if (animate) {
            menu.startAnimation(slideMenuLeftAnim);
            parent.removeView(menu);
        }

        if (animate) {
            content.startAnimation(slideContentLeftAnim);
        }

        FrameLayout.LayoutParams parm = (FrameLayout.LayoutParams) content.getLayoutParams();
        parm.setMargins(0, 0, 0, 0);
        content.setLayoutParams(parm);
        enableDisableViewGroup(content, true);

        menuShown = false;
    }

    private void applyStatusbarOffset() {
        Rect r = new Rect();
        Window window = act.getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(r);
        statusHeight = r.top;
    }

    //originally: http://stackoverflow.com/questions/5418510/disable-the-touch-events-for-all-the-views
    //modified for the needs here
    private void enableDisableViewGroup(ViewGroup viewGroup, boolean enabled) {
        int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View view = viewGroup.getChildAt(i);
            if (view.isFocusable())
                view.setEnabled(enabled);
            if (view instanceof ViewGroup) {
                enableDisableViewGroup((ViewGroup) view, enabled);
            } else if (view instanceof ListView) {
                if (view.isFocusable())
                    view.setEnabled(enabled);
                ListView listView = (ListView) view;
                int listChildCount = listView.getChildCount();
                for (int j = 0; j < listChildCount; j++) {
                    if (view.isFocusable())
                        listView.getChildAt(j).setEnabled(false);
                }
            }
        }
    }

    // originally: https://github.com/darvds/RibbonMenu
    // credit where credits due!
    private void parseXml(int menu) {

        menuItemList = new ArrayList<SlideMenuItem>();

        try {
            XmlResourceParser xpp = act.getResources().getXml(menu);

            xpp.next();
            int eventType = xpp.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {

                if (eventType == XmlPullParser.START_TAG) {

                    String elemName = xpp.getName();

                    if (elemName.equals("item")) {

                        String textId = xpp.getAttributeValue("http://schemas.android.com/apk/res/android", "title");
                        String iconId = xpp.getAttributeValue("http://schemas.android.com/apk/res/android", "icon");
                        String resId = xpp.getAttributeValue("http://schemas.android.com/apk/res/android", "id");

                        SlideMenuItem item = new SlideMenuItem();
                        item.id = Integer.valueOf(resId.replace("@", ""));
                        item.icon = act.getResources().getDrawable(Integer.valueOf(iconId.replace("@", "")));
                        item.label = resourceIdToString(textId);

                        menuItemList.add(item);
                    }

                }

                eventType = xpp.next();

            }

        } catch (Throwable e) {
            Log.e(TAG, "Error loading menu items from resource", e);
        }
    }

    private String resourceIdToString(String text) {
        if (!text.contains("@")) {
            return text;
        } else {
            String id = text.replace("@", "");
            return act.getResources().getString(Integer.valueOf(id));

        }
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        try {

            if (state instanceof Bundle) {
                Bundle bundle = (Bundle) state;

                statusHeight = bundle.getInt(KEY_STATUSBARHEIGHT);

                if (bundle.getBoolean(KEY_MENUSHOWN))
                    show(false); // show without animation

                super.onRestoreInstanceState(bundle.getParcelable(KEY_SUPERSTATE));

                return;
            }

            super.onRestoreInstanceState(state);

        } catch (NullPointerException e) {
            // in case the menu was not declared via XML but added from code
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_SUPERSTATE, super.onSaveInstanceState());
        bundle.putBoolean(KEY_MENUSHOWN, menuShown);
        bundle.putInt(KEY_STATUSBARHEIGHT, statusHeight);

        return bundle;
    }

    private boolean dragOverlay(MotionEvent event) {
        FrameLayout.LayoutParams parm = null;
        int x = (int) event.getRawX();
        switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            parm = (FrameLayout.LayoutParams) content.getLayoutParams();
            deltaX = x - parm.leftMargin;
            dragging = false;
            break;
        case MotionEvent.ACTION_MOVE:
            int lm = x - deltaX;
            delta = 0;
            dragging = true;
            if (0 <= lm && lm < menuSize) {// avoid over scroll
                // move content
                parm = (FrameLayout.LayoutParams) content.getLayoutParams();

                delta = parm.leftMargin - lm;

                parm.leftMargin = lm;
                parm.rightMargin = -lm;
                content.setLayoutParams(parm);

                // move menu
                parm = (FrameLayout.LayoutParams) menu.getLayoutParams();
                parm.leftMargin = lm - menuSize;
                parm.width = menuSize;
                menu.setLayoutParams(parm);

                if (Math.abs(delta) <= 5) {
                    parent.invalidate();
                } else {
                    moveMenu(delta);
                }
            }
            break;
        case MotionEvent.ACTION_UP:
            if (dragging) {
                dragging = false;

                if (delta > 0) {
                    // move content
                    parm = (FrameLayout.LayoutParams) content.getLayoutParams();

                    delta = parm.leftMargin;

                    parm.leftMargin = 0;
                    parm.rightMargin = 0;
                    content.setLayoutParams(parm);

                    // move menu
                    parm = (FrameLayout.LayoutParams) menu.getLayoutParams();
                    parm.leftMargin = -menuSize;
                    parm.width = menuSize;
                    menu.setLayoutParams(parm);

                    moveMenu(delta);
                    hide(false);
                } else if (delta <= 0) {
                    // move content
                    parm = (FrameLayout.LayoutParams) content.getLayoutParams();

                    delta = parm.leftMargin - menuSize;

                    parm.leftMargin = menuSize;
                    parm.rightMargin = -menuSize;
                    content.setLayoutParams(parm);

                    // move menu
                    parm = new FrameLayout.LayoutParams(-1, -1, 3);
                    parm.setMargins(0, statusHeight, 0, 0);
                    menu.setLayoutParams(parm);

                    moveMenu(delta);
                }
            }
            break;
        }

        return false;
    }

    private void moveMenu(int delta) {
        TranslateAnimation anim = new TranslateAnimation(delta, 0, 0, 0);
        anim.setDuration(500);
        anim.setFillAfter(true);
        anim.setInterpolator(smoothInterpolator);
        content.startAnimation(anim);
        menu.startAnimation(anim);
    }

    public boolean isMenuShown() {
        return menuShown;
    }

    private void launchPlayerActivity() {
        if (Engine.instance().getMediaPlayer().getCurrentFD() != null) {
            Intent i = new Intent(getContext(), MediaPlayerActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
    }
}
