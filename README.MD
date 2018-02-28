# iCal Subscriber



This plugin handles launching the appropriate handler on mobile operating systems to allow users to subscribe to our iCal feeds.  On iOS this merely passes the `webcal://` url to the operating system to open, while on Android it will obtain permission from the user to access their Google Calendar account and programmatically sync their RosterBot feeds into Google Calendar.