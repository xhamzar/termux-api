package com.termux.api.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "AutoClickService";

    private static AutoClickAccessibilityService instance;

    public static AutoClickAccessibilityService getInstance(){
        return instance;
    }

    @Override
    protected void onServiceConnected(){
        super.onServiceConnected();

        instance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;

        info.flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

        setServiceInfo(info);

        Log.d(LOG_TAG,"Service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event){
        // tidak digunakan
    }

    @Override
    public void onInterrupt(){
        Log.e(LOG_TAG,"Service interrupted");
    }

    public void click(int x,int y){

        if(instance == null){
            Log.e(LOG_TAG,"Service instance NULL");
            return;
        }

        Log.d(LOG_TAG,"Click "+x+" "+y);

        Path path = new Path();
        path.moveTo(x,y);

        GestureDescription.Builder builder =
                new GestureDescription.Builder();

        builder.addStroke(
                new GestureDescription.StrokeDescription(path,0,50)
        );

        dispatchGesture(
                builder.build(),
                new GestureResultCallback(){

                    @Override
                    public void onCompleted(GestureDescription gestureDescription){
                        Log.d(LOG_TAG,"Gesture completed");
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription){
                        Log.e(LOG_TAG,"Gesture cancelled");
                    }

                },
                null
        );
    }
}
