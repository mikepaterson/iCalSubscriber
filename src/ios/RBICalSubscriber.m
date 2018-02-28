#import "RBICalSubscriber.h"

@implementation RBICalSubscriber

- (void)subscribe:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];

    NSURL *webCalUrl = [NSURL URLWithString:[command.arguments objectAtIndex:0]];
    if(!webCalUrl)
    {
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    else
    {
        if([[UIApplication sharedApplication] respondsToSelector:@selector(openURL:options:completionHandler:)])
        {
            [[UIApplication sharedApplication] openURL:webCalUrl options:[NSDictionary dictionary] completionHandler:^(BOOL success) {
                if(success)
                {
                    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK] callbackId:command.callbackId];
                }
                else
                {
                    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:command.callbackId];
                }
            }];
        }
        else
        {
            [[UIApplication sharedApplication] openURL:webCalUrl];
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK] callbackId:command.callbackId];
        }
    }
}

@end
