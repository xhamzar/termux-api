package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.services.AutoClickAccessibilityService;

public class AutoClickAPI {

    public static void onReceive(TermuxApiReceiver receiver,
                                 Context context,
                                 Intent intent) {

        int x = intent.getIntExtra("x", 500);
        int y = intent.getIntExtra("y", 800);

        AutoClickAccessibilityService service =
                AutoClickAccessibilityService.getInstance();

        if(service == null){
            Log.e("AutoClickAPI","Accessibility service not ready");
            return;
        }

        service.click(x,y);
    }
}
