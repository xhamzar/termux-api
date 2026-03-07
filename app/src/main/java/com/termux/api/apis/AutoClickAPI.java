public class AutoClickAPI {

    public static void onReceive(TermuxApiReceiver receiver,
                                 Context context,
                                 Intent intent) {

        int x = intent.getIntExtra("x", 500);
        int y = intent.getIntExtra("y", 800);

        AccessibilityService service = MyAccessibilityService.getInstance();

        if(service != null){
            service.click(x, y);
        }
    }
}
