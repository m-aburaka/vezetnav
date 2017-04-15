package com.vezet.vezetnav;


import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.util.ArrayList;

public class CustomConstraintLayout extends android.support.constraint.ConstraintLayout {

    public CustomConstraintLayout(Context context) {
        super(context);
    }

    public CustomConstraintLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {

        for (InterceptedTouchListener listener : listeners) {
            listener.onInterceptedTouch();
        }

        return false;
    }

    ArrayList<InterceptedTouchListener> listeners = new ArrayList<InterceptedTouchListener>();

    public void setOnInterceptedTouchListener (InterceptedTouchListener listener)
    {
        this.listeners.add(listener);
    }
}

