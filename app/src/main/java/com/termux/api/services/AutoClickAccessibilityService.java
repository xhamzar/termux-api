package com.termux.api.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickAccessibilityService extends AccessibilityService {

    private static AutoClickAccessibilityService instance;

    public static AutoClickAccessibilityService getInstance(){
        return instance;
    }

    @Override
    protected void onServiceConnected(){
        super.onServiceConnected();

        instance = this;

        Log.d("AutoClickService","Service connected");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event){}

    @Override
    public void onInterrupt(){}

    public void click(int x,int y){

        Log.d("AutoClickService","Click "+x+" "+y);

        Path path = new Path();
        path.moveTo(x,y);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
            new GestureDescription.StrokeDescription(path,0,50)
        );

        dispatchGesture(builder.build(),null,null);
    }
}
